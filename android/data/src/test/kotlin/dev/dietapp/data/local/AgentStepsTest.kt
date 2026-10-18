package dev.dietapp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.TestEnv
import dev.dietapp.data.domain.AgentStatus
import dev.dietapp.data.domain.AgentStep
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.local.parse.ParseRequest
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val DAY = LocalDate.parse("2026-09-30")
private val NOW = ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.ofHours(3))

/** The line under a message while the agent works: what it is doing, never what it was asked or what it read. */
@RunWith(AndroidJUnit4::class)
class AgentStepsTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        env = TestEnv()
        env.mode.set(AppMode.Local)
        env.secrets.saveKey("sk-local-test-key-123456")
    }

    @After fun tearDown() = env.close()

    private fun turn(vararg calls: Pair<String, String>): String {
        val list = calls.mapIndexed { i, (name, args) ->
            """{"id": "call_$i", "type": "function", "function": {"name": "$name", "arguments": ${Json.encodeToString(String.serializer(), args)}}}"""
        }
        return """{"choices": [{"message": {"role": "assistant", "content": null, "tool_calls": [${list.joinToString(",")}]}}]}"""
    }

    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private val oatmeal = """{"items": [{"action": "add", "name": "овсянка", "grams": 60, "confidence": 0.9}]}"""

    @Test fun `each tool the agent uses is a step, and a page is named by its site only`() = runTest {
        val page = env.webServer.url("/food/232505.php?secret=1")
        env.enqueue(200, turn("search_foods" to """{"query": "овсянка"}"""))
        env.enqueue(200, turn("web_search" to """{"query": "овсянка кбжу на 100 г"}"""))
        env.enqueue(200, turn("open_page" to """{"url": "$page"}"""))
        env.enqueue(200, turn("record_food" to oatmeal))
        env.webServer.enqueue(html("<html></html>"))
        env.webServer.enqueue(html("<html><body>Овсянка: 342 ккал</body></html>"))

        val steps = mutableListOf<AgentStatus>()
        env.model.parse(ParseRequest("овсянка 60 г", onStep = { steps += it }))

        assertEquals(
            listOf(
                AgentStep.Reading, AgentStep.SearchBase,
                AgentStep.Thinking, AgentStep.SearchWeb,
                AgentStep.Thinking, AgentStep.ReadPage,
                AgentStep.Thinking, AgentStep.Recording,
            ),
            steps.map { it.step },
        )
        assertEquals("the site, not the path or the query string", page.host, steps.single { it.step == AgentStep.ReadPage }.detail)
        assertTrue("nothing the person asked or the agent searched for is in a step", steps.filter { it.step != AgentStep.ReadPage }.all { it.detail == null })
    }

    @Test fun `a photo is looked at first`() = runTest {
        env.enqueue(200, turn("record_food" to oatmeal))
        val steps = mutableListOf<AgentStatus>()
        env.model.parse(ParseRequest(null, imageBase64 = "AAAA", onStep = { steps += it }))
        assertEquals(listOf(AgentStep.Looking, AgentStep.Recording), steps.map { it.step })
    }

    @Test fun `changes to the base and answers are steps too`() = runTest {
        env.enqueue(200, turn("save_food" to """{"name": "овсянка", "kcal": 342, "protein": 12, "fat": 6, "carbs": 60, "estimated": false}"""))
        env.enqueue(200, turn("reply" to """{"text": "готово"}"""))
        env.enqueue(200, turn("record_food" to """{"items": []}"""))
        val steps = mutableListOf<AgentStatus>()
        env.model.parse(ParseRequest("добавь овсянку в базу", onStep = { steps += it }))
        assertEquals(
            listOf(AgentStep.Reading, AgentStep.Adding, AgentStep.Thinking, AgentStep.Replying, AgentStep.Thinking, AgentStep.Recording),
            steps.map { it.step },
        )
    }

    @Test fun `when the message is done nothing is left under it, and neither when it failed`() = runTest {
        env.enqueue(200, turn("record_food" to oatmeal))
        env.localDiary.sendMessage("овсянка 60 г", null, DAY, NOW, MessageSource.Text)
        assertTrue(env.activity.statuses.value.isEmpty())

        repeat(3) { env.enqueue(503, "{}") }
        env.localDiary.sendMessage("кофе", null, DAY, NOW, MessageSource.Text)
        assertEquals("failed", env.db.outbox().observeAll().first().single().state)
        assertTrue(env.activity.statuses.value.isEmpty())
    }

    @Test fun `the step shows on the message the agent is reading, and only on it`() = runTest {
        env.diary.sendMessage("гречка", null, DAY, NOW, MessageSource.Text)
        env.diary.sendMessage("кофе", null, DAY, NOW, MessageSource.Text)
        val rows = env.db.outbox().observeAll().first()
        val reading = rows.first { it.text == "гречка" }.id

        assertNull("waiting its turn: no step yet", env.diary.observeDay(DAY).first().outbox.first { it.text == "гречка" }.agent)

        env.activity.report(reading, AgentStatus(AgentStep.SearchWeb))
        val outbox = env.diary.observeDay(DAY).first().outbox
        assertEquals(AgentStatus(AgentStep.SearchWeb), outbox.first { it.text == "гречка" }.agent)
        assertNull(outbox.first { it.text == "кофе" }.agent)

        env.activity.clear(reading)
        assertNull(env.diary.observeDay(DAY).first().outbox.first { it.text == "гречка" }.agent)
    }
}
