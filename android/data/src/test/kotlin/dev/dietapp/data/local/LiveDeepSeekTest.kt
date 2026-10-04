package dev.dietapp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.TestEnv
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.local.parse.LiveOfflineParser
import dev.dietapp.data.local.parse.ModelParser
import dev.dietapp.data.local.parse.OfflineParserTest
import dev.dietapp.data.repo.DiaryRepositoryImpl
import dev.dietapp.data.repo.SyncTrigger
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Against the real DeepSeek API and the real web: runs only when DEEPSEEK_API_KEY is set, never in a normal build.
 *
 *     DEEPSEEK_API_KEY=sk-… ./gradlew :data:testDebugUnitTest --tests "*LiveDeepSeekTest"
 *
 * The model's answers vary, so the checks are about the shape of the outcome, not exact numbers. The journal of the
 * run is written to data/build/live-journal.txt for reading.
 */
@RunWith(AndroidJUnit4::class)
class LiveDeepSeekTest {
    private val key: String? = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
    private lateinit var env: TestEnv
    private lateinit var diary: DiaryRepositoryImpl
    private val day = LocalDate.now()

    @Before fun setUp() {
        assumeTrue("set DEEPSEEK_API_KEY to run against the real API", key != null)
        env = TestEnv()
        env.mode.set(AppMode.Local)
        env.secrets.saveKey(key!!)
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val model = ModelParser(
            http, env.prompts, { env.secrets.deepseekKey },
            tools = FoodTools(env.foods, WebSearch(http, env.journal), strict = { env.mode.recordMode.value == RecordMode.Precise }),
            trace = env.journal,
        )
        val chooser = ParserChooser(LiveOfflineParser { env.foods.lexicon() }, model, { true }, env.journal, budgetMs = 150_000)
        val processor = LocalMessageProcessor(env.db, chooser, env.resolver, OfflineParserTest.lexicon, env.files, env.clock)
        val engine = LocalEngine(env.db, processor, env.files, env.journal)
        val now = { runBlocking { engine.run() } }
        diary = DiaryRepositoryImpl(
            env.db, env.files, env.session, object : SyncTrigger { override fun requestSync(pull: Boolean) = now() },
            env.mode, env.engine, engine, env.clock, env.foods,
        )
    }

    @After fun tearDown() {
        if (key == null) return
        File("build/live-journal.txt").writeText(env.journal.read())
        env.close()
    }

    private fun say(text: String) = runBlocking {
        diary.sendMessage(text, null, day, ZonedDateTime.now(ZoneOffset.ofHours(3)), MessageSource.Text)
    }

    private fun entries() = runBlocking { env.db.entries().forDay(day.toString()) }
    private fun notes() = runBlocking { env.db.notes().observeDay(day.toString()).first() }

    @Test fun `a plain food, then adding it to the base without naming it`() {
        say("Макароны вареные 200 грамм")
        val pasta = entries().single()
        assertNotNull("numbers from the catalog: ${notes().map { it.text }}", pasta.kcal)
        assertTrue("about 300 kcal: ${pasta.kcal}", pasta.kcal!! in 250.0..360.0)
        assertTrue("plain pasta needs no question in the standard mode", !pasta.pending)

        say("Добавь в базу данных")
        val foods = runBlocking { env.db.foods().all() }
        assertEquals("the latest entry went into the base: ${notes().map { it.text }}", 1, foods.size)
        assertTrue(foods.single().name, foods.single().name.contains("макарон", ignoreCase = true))
    }

    @Test fun `a branded product is looked up on the web and offered for confirmation`() {
        say("сосиски ремит рубленые 100 грамм")
        val proposal = notes().firstOrNull { it.kind == "proposal" }
        assertNotNull("a proposal: ${notes().map { it.kind + ": " + it.text }}", proposal)
        val choices = ProposalCodec.decode(proposal!!.payload)
        assertTrue("options: $choices", choices.isNotEmpty())
        assertTrue("nothing in the base before the user confirms", runBlocking { env.db.foods().all() }.isEmpty())
        val log = env.journal.read()
        assertTrue("the web was used", log.contains("[web] search"))

        assertTrue("waits for the user until then", entries().first().pending)
        assertTrue(runBlocking { diary.acceptProposal(proposal.id, choices.first()) }.isSuccess)
        assertNotNull(entries().first().kcal)
        assertTrue("the pick records it", !entries().first().pending)
    }
}
