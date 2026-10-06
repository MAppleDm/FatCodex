package dev.dietapp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.TestEnv
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.local.parse.ModelFailure
import dev.dietapp.data.local.parse.MessageParser
import dev.dietapp.data.local.parse.ParseRequest
import dev.dietapp.data.local.parse.ParseResult
import dev.dietapp.data.net.AppError
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val DAY = LocalDate.parse("2026-09-30")
private val NOW = ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.ofHours(3))
private const val KEY = "sk-journal-test-key-98765"

/** The diagnostics journal: enough to tell from the phone alone why the model did not work. */
@RunWith(AndroidJUnit4::class)
class JournalTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        env = TestEnv()
        env.mode.set(AppMode.Local)
        env.secrets.saveKey(KEY)
    }

    @After fun tearDown() = env.close()

    private suspend fun say(text: String) = env.localDiary.sendMessage(text, null, DAY, NOW, MessageSource.Text)
    private suspend fun notes() = env.db.notes().observeDay(DAY.toString()).first().map { it.text }

    /** What the person sees under the message that the agent could not read. */
    private suspend fun failure() = env.db.outbox().observeAll().first().single().also { assertEquals("failed", it.state) }.error!!

    private fun turn(name: String, args: String) =
        """{"choices": [{"finish_reason": "tool_calls", "message": {"role": "assistant", "content": null, "tool_calls": [{"id": "c1", "type": "function",
            "function": {"name": "$name", "arguments": ${Json.encodeToString(String.serializer(), args)}}}]}}], "usage": {"prompt_tokens": 900, "completion_tokens": 30}}"""

    @Test fun `a refused request shows DeepSeek's own reason in the feed and in the journal`() = runTest {
        env.enqueue(400, """{"error": {"message": "Model Not Exist", "type": "invalid_request_error"}}""")
        say("гречка 200 г")

        assertEquals(
            "Агент ответил непонятно. Подробности — в журнале (О приложении → Журнал).",
            failure(),
        )
        assertTrue("nothing is recorded in the agent's place", env.db.entries().forDay(DAY.toString()).isEmpty())
        val log = env.journal.read()
        assertTrue(log, log.contains("[message] processing \"гречка 200 г\""))
        assertTrue(log, log.contains("→ POST ${env.server.url("/")}chat/completions · round 1, attempt 1"))
        assertTrue(log, log.contains("model=deepseek-flash tool_choice=auto tools=[record_food, search_foods, save_food, delete_food, reply, propose_food, web_search, open_page]"))
        assertTrue(log, log.contains("user: context: {\"local_time\":\"12:00\"}\n    message: гречка 200 г") || log.contains("message: гречка 200 г"))
        assertTrue(log, log.contains("← HTTP 400 in"))
        assertTrue(log, log.contains("Model Not Exist"))
        assertTrue(log, log.contains("the message stays, to be tried again"))
        assertFalse("the system prompt is not copied into the journal", log.contains("Never estimate or mention calories"))
    }

    @Test fun `the key never reaches the journal`() = runTest {
        env.enqueue(401, "Authentication Fails (governor) for key $KEY")
        say("банан")
        val log = env.journal.read()
        assertTrue(log, log.contains("Authentication Fails (governor)"))
        assertFalse(log.contains(KEY))
        assertTrue(log, log.contains("sk-…8765"))
        assertTrue(failure().contains("HTTP 401: Authentication Fails (governor)"))
        assertFalse("nor does it reach the screen", failure().contains(KEY))
    }

    @Test fun `a successful agent run is readable - rounds, tool calls, tool results and the outcome`() = runTest {
        env.enqueue(200, turn("search_foods", """{"query": "гречка"}"""))
        env.enqueue(200, turn("record_food", """{"items": [{"action": "add", "name": "гречка", "query_en": "buckwheat groats, roasted, cooked", "grams": 200, "confidence": 0.9}]}"""))
        say("гречка 200 г")
        val log = env.journal.read()
        assertTrue(log, log.contains("← HTTP 200 in"))
        assertTrue(log, log.contains("finish=tool_calls tokens=900+30"))
        assertTrue(log, log.contains("calls: search_foods({\"query\": \"гречка\"})"))
        assertTrue(log, log.contains("round 2, attempt 1"))
        assertTrue(log, log.contains("tool[c1]: {\"results\":"))
        assertTrue(log, log.contains("done in 2 round(s): add гречка 200.0 g"))
        assertTrue(log, log.contains("[message] done in"))
    }

    @Test fun `a photo is logged by size, never as bytes`() = runTest {
        env.enqueue(200, turn("record_food", """{"items": [{"action": "add", "name": "омлет", "grams": 150, "confidence": 0.8}]}"""))
        env.localDiary.sendMessage(null, ByteArray(3000) { 7 }, DAY, NOW, MessageSource.Photo)
        val log = env.journal.read()
        assertTrue(log, log.contains("<image, "))
        assertFalse(log, log.contains("BwcHBwcH")) // base64 of the 7s
    }

    @Test fun `no answer at all is logged with the exception`() = runTest {
        env.server.shutdown()
        say("гречка 200 г")
        val log = env.journal.read()
        assertTrue(log, log.contains("✕ no answer after"))
        assertEquals("a connection that did not work is one short line", "Нет связи с агентом.", failure())
        assertTrue("the exception is in the journal, not in the chat", log.contains("Exception"))
    }

    @Test fun `a bug on our side never loses the message, and is logged with its stack`() = runTest {
        val broken = object : MessageParser {
            override suspend fun parse(request: ParseRequest): ParseResult = throw IllegalStateException("boom")
        }
        val chooser = ParserChooser(broken, { true }, env.journal)
        val error = try { chooser.parse(ParseRequest("гречка 200 г")); null } catch (e: AppError) { e }
        assertTrue("the message is kept, with a reason: ${error?.message}", error!!.message!!.startsWith("Агент ответил непонятно."))
        val log = env.journal.read()
        assertTrue(log, log.contains("java.lang.IllegalStateException: boom"))
        assertTrue(log, log.contains("    at "))
    }

    @Test fun `a model that never finishes does not hold the message for more than the budget`() = runTest {
        val stuck = object : MessageParser {
            override suspend fun parse(request: ParseRequest): ParseResult = kotlinx.coroutines.awaitCancellation()
        }
        val chooser = ParserChooser(stuck, { true }, env.journal, budgetMs = 30_000)
        val error = try { chooser.parse(ParseRequest("гречка 200 г")); null } catch (e: AppError) { e }
        assertEquals("Нет связи с агентом.", error!!.message)
        assertTrue(env.journal.read().contains("no result within 30 s"))
    }

    @Test fun `the journal keeps only the latest part and can be cleared`() {
        repeat(4000) { env.journal.log("test", "line $it " + "x".repeat(200)) }
        val text = env.journal.read()
        assertTrue(text.length < 600_000)
        assertTrue(text.contains("line 3999"))
        assertFalse(text.contains("line 0 "))
        env.journal.clear()
        assertEquals("", env.journal.read())
    }

    @Test fun `failure details are short and concrete`() {
        assertEquals("HTTP 400: x", ModelFailure.Rejected("HTTP 400: x").detail)
        assertEquals(
            "Ключ DeepSeek не подошёл (HTTP 402: Insufficient Balance). Проверь его в «О приложении → Агент».",
            modelError(ModelFailure.Auth("HTTP 402: Insufficient Balance")).message,
        )
    }
}
