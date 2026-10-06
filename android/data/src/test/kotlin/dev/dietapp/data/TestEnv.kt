package dev.dietapp.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.di.DataProvidesModule
import dev.dietapp.data.local.DiagLog
import dev.dietapp.data.local.FoodBase
import dev.dietapp.data.local.FoodTools
import dev.dietapp.data.local.WebSearch
import dev.dietapp.data.local.FoodResolver
import dev.dietapp.data.local.LocalEngine
import dev.dietapp.data.local.LocalMessageProcessor
import dev.dietapp.data.local.LocalSettingsImpl
import dev.dietapp.data.local.ModeStore
import dev.dietapp.data.local.ParserChooser
import dev.dietapp.data.local.SecretStore
import dev.dietapp.data.local.TestFiles
import dev.dietapp.data.local.parse.ModelParser
import dev.dietapp.data.local.parse.PromptAssets
import dev.dietapp.data.net.DietApi
import dev.dietapp.data.net.SessionStore
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import dev.dietapp.data.repo.AuthRepositoryImpl
import dev.dietapp.data.repo.DiaryRepositoryImpl
import dev.dietapp.data.repo.SyncTrigger
import dev.dietapp.data.sync.OutboxFiles
import dev.dietapp.data.sync.SyncEngine
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class MutableClock(var now: Instant = Instant.parse("2026-09-30T05:15:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
}

/** Robolectric has no Android Keystore: a reversible stand-in whose tokens are recognisably not the plain text. */
object ReversibleCipher : dev.dietapp.data.local.KeyCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(token: String) = token.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")?.reversed()
}

class RecordingTrigger : SyncTrigger {
    val requests = mutableListOf<Boolean>()
    override fun requestSync(pull: Boolean) { requests += pull }
}

/** Real Room (in memory), real Retrofit/OkHttp against MockWebServer, real repositories and sync engine. */
class TestEnv {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val server = MockWebServer().apply { start() }
    val json = DataProvidesModule.json()
    val session = SessionStore(context).also { it.clear() }
    val files = OutboxFiles(Files.createTempDirectory("outbox").toFile())
    val thumbs = dev.dietapp.data.media.PhotoThumbs(Files.createTempDirectory("thumbs").toFile())
    val bodyStore = dev.dietapp.data.local.BodyStore(context).also { it.clear() }
    val clock = MutableClock()
    val trigger = RecordingTrigger()
    val api: DietApi = DataProvidesModule.api(
        DataProvidesModule.retrofit(DataProvidesModule.okHttp(session), json, server.url("/").toString()),
    )
    val mode = ModeStore(context, session).also { it.set(null); it.setLanguage(dev.dietapp.data.domain.Language.Ru) }
    val secrets = SecretStore(context, ReversibleCipher).also { it.clear() }
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // local mode: the real prompts from the repo, the user's own food base, the model is whatever MockWebServer plays
    val prompts = PromptAssets.load { FileInputStream(TestFiles.repoFile("android/data/src/main/assets/$it")) }
    val foods = FoodBase(db, clock)
    val journal = DiagLog(context, clock, secrets).also { it.clear() }
    /** Plays DuckDuckGo and the pages the model opens. Separate from [server], which plays DeepSeek. */
    val webServer = MockWebServer().apply { start() }
    val web = WebSearch(OkHttpClient(), journal, searchUrl = webServer.url("/html/").toString())
    val model = ModelParser(OkHttpClient(), prompts, { secrets.deepseekKey }, baseUrl = server.url("/").toString(),
        pause = { }, tools = FoodTools(foods, web), trace = journal)
    val resolver = FoodResolver(foods)
    val chooser = ParserChooser(model, { secrets.hasKey.value }, journal)
    val processor = LocalMessageProcessor(db, chooser, resolver, files, clock)
    val localEngine = LocalEngine(db, processor, files, journal)
    val localSettings = LocalSettingsImpl(mode, secrets, prompts)

    val bodyModel = dev.dietapp.data.repo.BodyModel(bodyStore, db, clock)
    val engine = SyncEngine(db, api, session, files, json, clock)
    val diary = DiaryRepositoryImpl(db, files, session, trigger, mode, engine, localEngine, clock, foods, thumbs, bodyModel)

    /** The same diary, but every queued message is processed on the spot, as the app does in local mode. */
    val localDiary = DiaryRepositoryImpl(
        db, files, session,
        object : dev.dietapp.data.repo.SyncTrigger {
            override fun requestSync(pull: Boolean) { runBlocking { localEngine.run() } }
        },
        mode, engine, localEngine, clock, foods, thumbs, bodyModel,
    )
    val exports = dev.dietapp.data.repo.ExportRepositoryImpl(db, clock, bodyModel)
    val auth = AuthRepositoryImpl(db, api, session, files, trigger, json, mode, secrets, thumbs, bodyStore, scope)
    val body = dev.dietapp.data.repo.BodyRepositoryImpl(bodyStore, bodyModel, diary, clock)

    fun loginAs(email: String = "me@example.com", token: String = "tok-123") = session.save(token, email)

    fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    fun enqueueNoContent() {
        server.enqueue(MockResponse().setResponseCode(204))
    }

    fun close() {
        scope.cancel()
        runCatching { server.shutdown() }
        runCatching { webServer.shutdown() }
        db.close()
    }
}

fun entryRow(
    id: String = "11111111-1111-4111-8111-111111111111",
    name: String = "гречка",
    grams: Double = 200.0,
    status: String = "ok",
    day: String = "2026-09-30",
    dirty: Boolean = false,
    deleted: Boolean = false,
    matched: Boolean = true,
) = dev.dietapp.data.db.EntryRow(
    id = id, mealId = null, day = day, eatenAtMs = 0, position = 0, name = name, grams = grams,
    kcal = if (matched) grams * 0.92 else null, protein = if (matched) grams * 0.0338 else null,
    fat = if (matched) grams * 0.0062 else null, carbs = if (matched) grams * 0.1994 else null,
    kcal100 = if (matched) 92.0 else null, protein100 = if (matched) 3.38 else null,
    fat100 = if (matched) 0.62 else null, carbs100 = if (matched) 19.94 else null,
    foodName = null, status = status, confidence = 0.9, source = "text", updatedAtMs = 1, deleted = deleted, dirty = dirty,
)

// ---------- JSON the gateway would send ----------

fun entryJson(
    id: String = "11111111-1111-4111-8111-111111111111",
    name: String = "гречка",
    grams: Double = 200.0,
    kcal: Double? = 184.0,
    status: String = "ok",
    day: String = "2026-09-30",
    updatedAt: String = "2026-09-30T05:15:10.123456Z",
    deleted: Boolean = false,
    position: Int = 0,
): String {
    val numbers = if (kcal == null) {
        """"kcal": null, "protein": null, "fat": null, "carbs": null, "per100": null, "food_name": null"""
    } else {
        """"kcal": $kcal, "protein": 6.8, "fat": 1.2, "carbs": 39.9,
           "per100": {"kcal": 92.0, "protein": 3.38, "fat": 0.62, "carbs": 19.94},
           "food_name": "Buckwheat groats, roasted, cooked""""
    }
    return """{"id": "$id", "meal_id": "22222222-2222-4222-8222-222222222222", "day": "$day",
      "eaten_at": "2026-09-30T05:15:00Z", "position": $position, "name": "$name", "grams": $grams, $numbers,
      "status": "$status", "confidence": 0.9, "source": "text", "updated_at": "$updatedAt", "deleted": $deleted}"""
}

fun messageResultJson(entries: List<String> = emptyList(), question: String? = null, target: String? = null, removed: List<String> = emptyList()) =
    """{"entries": [${entries.joinToString(",")}], "removed_ids": [${removed.joinToString(",") { "\"$it\"" }}],
        "clarify_question": ${question?.let { "\"$it\"" }}, "clarify_target_id": ${target?.let { "\"$it\"" }}}"""

fun errorJson(code: String, message: String) = """{"error": {"code": "$code", "message": "$message"}}"""

fun syncJson(
    entries: List<String> = emptyList(),
    weights: List<String> = emptyList(),
    nextSince: String = "2026-09-30T05:20:00Z",
    hasMore: Boolean = false,
    goal: Int? = 1900,
) = """{"server_time": "2026-09-30T05:20:05Z", "next_since": "$nextSince", "has_more": $hasMore, "calorie_goal": $goal,
    "entries": [${entries.joinToString(",")}], "weights": [${weights.joinToString(",")}]}"""
