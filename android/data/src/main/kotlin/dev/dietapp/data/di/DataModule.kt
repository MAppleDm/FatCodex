package dev.dietapp.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.net.DietApi
import dev.dietapp.data.net.SessionStore
import dev.dietapp.data.repo.AuthRepository
import dev.dietapp.data.repo.AuthRepositoryImpl
import dev.dietapp.data.repo.DiaryRepository
import dev.dietapp.data.repo.DiaryRepositoryImpl
import dev.dietapp.data.repo.SpeechRepository
import dev.dietapp.data.repo.SpeechRepositoryImpl
import dev.dietapp.data.local.ModeStore
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Lives as long as the process: sync attempts started by the UI must outlive any one screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * The app module must provide `@Named("apiBaseUrl") String` (from BuildConfig).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {
    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false // null fields are left out of request bodies
        encodeDefaults = true
    }

    @Provides @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, mode: ModeStore): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "diet.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .apply {
                // With a server, everything but unsent messages can be re-downloaded, so a schema change may rebuild.
                // In local mode this database is the only copy of the diary: a release that changes the schema must
                // ship a real Migration, and a missing one fails loudly instead of silently erasing the data.
                if (!mode.isLocal) fallbackToDestructiveMigration(dropAllTables = true)
            }
            .build()

    @Provides fun entryDao(db: AppDatabase) = db.entries()
    @Provides fun weightDao(db: AppDatabase) = db.weights()
    @Provides fun outboxDao(db: AppDatabase) = db.outbox()
    @Provides fun noteDao(db: AppDatabase) = db.notes()
    @Provides fun profileDao(db: AppDatabase) = db.profile()

    @Provides @Singleton
    fun okHttp(session: SessionStore): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // The gateway may wait up to ~100 s for the language model (3 attempts) before it gives up.
        .readTimeout(110, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = session.token
            val request = if (token != null) {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .build()

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json, @Named("apiBaseUrl") baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton
    fun api(retrofit: Retrofit): DietApi = retrofit.create(DietApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds abstract fun diary(impl: DiaryRepositoryImpl): DiaryRepository
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun speech(impl: SpeechRepositoryImpl): SpeechRepository
    @Binds abstract fun body(impl: dev.dietapp.data.repo.BodyRepositoryImpl): dev.dietapp.data.repo.BodyRepository
    @Binds abstract fun export(impl: dev.dietapp.data.repo.ExportRepositoryImpl): dev.dietapp.data.repo.ExportRepository
}
