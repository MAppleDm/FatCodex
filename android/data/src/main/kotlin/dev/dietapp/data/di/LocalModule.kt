package dev.dietapp.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.local.AssetCatalogSource
import dev.dietapp.data.local.CatalogSource
import dev.dietapp.data.local.DiagLog
import dev.dietapp.data.local.FoodBase
import dev.dietapp.data.local.FoodTools
import dev.dietapp.data.local.KeyCipher
import dev.dietapp.data.local.KeystoreCipher
import dev.dietapp.data.local.WebSearch
import dev.dietapp.data.local.FoodResolver
import dev.dietapp.data.local.LocalMessageProcessor
import dev.dietapp.data.local.LocalSettings
import dev.dietapp.data.local.LocalSettingsImpl
import dev.dietapp.data.local.ModeStore
import dev.dietapp.data.local.ParserChooser
import dev.dietapp.data.local.RecordMode
import dev.dietapp.data.local.SecretStore
import dev.dietapp.data.local.parse.Lexicon
import dev.dietapp.data.local.parse.ModelParser
import dev.dietapp.data.local.parse.LiveOfflineParser
import dev.dietapp.data.local.parse.PromptAssets
import dev.dietapp.data.repo.SyncTrigger
import dev.dietapp.data.sync.AppSyncTrigger
import dev.dietapp.data.sync.OutboxFiles
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** Everything local mode needs: the bundled catalog and dictionary, the two parsers and the processor. */
@Module
@InstallIn(SingletonComponent::class)
object LocalModule {
    @Provides @Singleton
    fun lexicon(@ApplicationContext context: Context): Lexicon = context.assets.open("local/ru_foods.json").use { Lexicon.fromStream(it) }

    @Provides @Singleton
    fun promptAssets(@ApplicationContext context: Context): PromptAssets = PromptAssets.load { context.assets.open(it) }

    @Provides @Singleton
    fun resolver(catalog: CatalogSource, lexicon: Lexicon, foods: FoodBase): FoodResolver = FoodResolver(catalog, lexicon, foods)

    /** The offline parser knows the user's own foods by name too, so it is rebuilt from the current food base. */
    @Provides @Singleton
    fun offlineParser(foods: FoodBase): LiveOfflineParser = LiveOfflineParser { foods.lexicon() }

    /** Its own client: the server client adds the server's access token to every request, which must never reach DeepSeek. */
    @Provides @Singleton @Named("model")
    fun modelHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The model works as a small agent over the food base: it may search it and add the foods it lacks. */
    @Provides @Singleton
    fun modelParser(
        @Named("model") http: OkHttpClient,
        @Named("modelBaseUrl") baseUrl: String,
        prompts: PromptAssets,
        secrets: SecretStore,
        foods: FoodBase,
        log: DiagLog,
        mode: ModeStore,
    ): ModelParser = ModelParser(
        http, prompts, keyProvider = { secrets.deepseekKey }, baseUrl = baseUrl,
        // web search is always on; in the precise mode every change to an existing food waits for the user's yes
        tools = FoodTools(foods, WebSearch(http, log), strict = { mode.recordMode.value == RecordMode.Precise }), trace = log,
    )

    @Provides @Singleton
    fun parserChooser(offline: LiveOfflineParser, model: ModelParser, secrets: SecretStore, log: DiagLog): ParserChooser =
        // web pages and a few tool rounds take longer than a plain parse
        ParserChooser(offline, model, hasKey = { secrets.hasKey.value }, trace = log, budgetMs = 120_000)

    @Provides @Singleton
    fun processor(
        db: AppDatabase,
        chooser: ParserChooser,
        resolver: FoodResolver,
        lexicon: Lexicon,
        files: OutboxFiles,
        clock: Clock,
        mode: ModeStore,
    ): LocalMessageProcessor = LocalMessageProcessor(db, chooser, resolver, lexicon, files, clock, recordMode = { mode.recordMode.value })
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalBindsModule {
    @Binds abstract fun keyCipher(impl: KeystoreCipher): KeyCipher
    @Binds abstract fun catalogSource(impl: AssetCatalogSource): CatalogSource
    @Binds abstract fun localSettings(impl: LocalSettingsImpl): LocalSettings
    @Binds abstract fun journal(impl: DiagLog): dev.dietapp.data.local.Journal
    @Binds abstract fun syncTrigger(impl: AppSyncTrigger): SyncTrigger
    @Binds abstract fun foodRepository(impl: dev.dietapp.data.repo.FoodRepositoryImpl): dev.dietapp.data.repo.FoodRepository
}
