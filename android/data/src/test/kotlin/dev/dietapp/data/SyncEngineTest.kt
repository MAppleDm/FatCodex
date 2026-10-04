package dev.dietapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.dietapp.data.db.OutboxRow
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.sync.SyncResult
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val DAY = LocalDate.parse("2026-09-30")
private val NOW = ZonedDateTime.of(2026, 9, 30, 8, 15, 0, 0, ZoneOffset.ofHours(3))
private const val E1 = "11111111-1111-4111-8111-111111111111"

private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv().also { it.loginAs() } }
    @After fun tearDown() = env.close()

    private suspend fun send(text: String = "гречка 200 г") =
        env.diary.sendMessage(text, null, DAY, NOW, MessageSource.Text)

    // ---------- messages ----------

    @Test fun `a queued message is posted with the token and becomes entries`() = runTest {
        send()
        env.enqueue(200, messageResultJson(listOf(entryJson())))

        env.engine.confirmations.test {
            assertEquals(SyncResult.Done, env.engine.sync(pull = false))
            awaitItem()
        }

        val req = env.server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/messages", req.path)
        assertEquals("Bearer tok-123", req.getHeader("Authorization"))
        val body = req.bodyJson()
        assertEquals("гречка 200 г", body["text"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30", body["day"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30T08:15:00+03:00", body["eaten_at"]!!.jsonPrimitive.content)
        assertEquals("text", body["source"]!!.jsonPrimitive.content)
        assertFalse("null fields must not be sent", body.containsKey("image_base64"))
        assertFalse(body.containsKey("pending_question"))

        val stored = env.db.entries().get(E1)!!
        assertEquals(184.0, stored.kcal!!, 0.0)
        assertEquals(92.0, stored.kcal100!!, 0.0)
        assertFalse(stored.dirty)
        assertTrue(env.db.outbox().queued().isEmpty())
    }

    @Test fun `the clarifying question is kept and sent back with the next message`() = runTest {
        send("суп")
        env.enqueue(200, messageResultJson(listOf(entryJson(id = E1, name = "суп", kcal = null, status = "unmatched")),
            question = "Какой суп?", target = E1))
        env.engine.sync(pull = false)

        val question = env.db.notes().latestOpenQuestion()!!
        assertEquals("Какой суп?", question.text)
        assertEquals(E1, question.targetEntryId)

        send("борщ")
        assertNull("answering closes the question", env.db.notes().latestOpenQuestion())
        env.enqueue(200, messageResultJson(listOf(entryJson(id = E1, name = "борщ"))))
        env.engine.sync(pull = false)

        env.server.takeRequest() // the first message
        val second = env.server.takeRequest().bodyJson()
        val pending = second["pending_question"]!!.jsonObject
        assertEquals("Какой суп?", pending["question"]!!.jsonPrimitive.content)
        assertEquals(E1, pending["target_id"]!!.jsonPrimitive.content)
    }

    @Test fun `a message with no food leaves a note instead of silence`() = runTest {
        send("привет")
        env.enqueue(200, messageResultJson())
        env.engine.sync(pull = false)
        assertNull(env.db.notes().latestOpenQuestion())
        env.diary.observeDay(DAY).test {
            val notes = awaitItem().notes
            assertEquals(listOf("Не нашёл в сообщении еды."), notes.map { it.text })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `removed ids become tombstones`() = runTest {
        env.db.entries().upsert(entryRow(id = E1, name = "хлеб", grams = 60.0))
        send("убери хлеб")
        env.enqueue(200, messageResultJson(removed = listOf(E1)))
        env.engine.sync(pull = false)
        assertTrue(env.db.entries().get(E1)!!.deleted)
    }

    @Test fun `a temporary failure keeps every message, in order, and stops the queue`() = runTest {
        send("первое")
        env.clock.advanceSeconds(1)
        send("второе")
        env.enqueue(503, errorJson("llm_unavailable", "Сервис разбора сейчас недоступен."))

        assertEquals(SyncResult.Retry, env.engine.sync(pull = false))
        assertEquals(1, env.server.requestCount) // the second message was not even tried
        val rows = env.db.outbox().queued()
        assertEquals(listOf("первое", "второе"), rows.map { it.text })
        assertEquals(1, rows[0].attempts)
        assertEquals("Сервис разбора сейчас недоступен.", rows[0].error)

        env.enqueue(200, messageResultJson(listOf(entryJson(id = E1, name = "первое"))))
        env.enqueue(200, messageResultJson(listOf(entryJson(id = "33333333-3333-4333-8333-333333333333", name = "второе"))))
        assertEquals(SyncResult.Done, env.engine.sync(pull = false))
        env.server.takeRequest()
        assertEquals("первое", env.server.takeRequest().bodyJson()["text"]!!.jsonPrimitive.content)
        assertEquals("второе", env.server.takeRequest().bodyJson()["text"]!!.jsonPrimitive.content)
        assertTrue(env.db.outbox().queued().isEmpty())
    }

    @Test fun `no connection is a quiet retry that loses nothing`() = runTest {
        send()
        env.server.shutdown()
        assertEquals(SyncResult.Retry, env.engine.sync(pull = false))
        val row = env.db.outbox().get(env.db.outbox().queued().single().id)!!
        assertEquals("queued", row.state)
        assertEquals("Нет связи с сервером.", row.error)
    }

    @Test fun `a rejected message is marked failed and does not block the next one`() = runTest {
        send("плохое")
        env.clock.advanceSeconds(1)
        send("хорошее")
        env.enqueue(422, errorJson("llm_rejected", "Не удалось обработать это сообщение."))
        env.enqueue(200, messageResultJson(listOf(entryJson(id = E1, name = "хорошее"))))

        assertEquals(SyncResult.Done, env.engine.sync(pull = false))

        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals("Не удалось обработать это сообщение.", failed.error)
        assertNotNull(env.db.entries().get(E1))

        // it is not retried by itself, only on request
        assertEquals(SyncResult.Done, env.engine.sync(pull = false))
        assertEquals(2, env.server.requestCount)
        env.diary.retryOutbox(failed.id)
        assertEquals("queued", env.db.outbox().get(failed.id)!!.state)
    }

    @Test fun `an expired token signs the user out`() = runTest {
        send()
        env.enqueue(401, errorJson("unauthorized", "Нужно войти заново."))
        assertEquals(SyncResult.SignedOut, env.engine.sync(pull = false))
        assertFalse(env.session.loggedIn.value)
        assertEquals(1, env.db.outbox().queued().size) // still there for after the next login
    }

    @Test fun `without a session nothing is sent`() = runTest {
        env.session.clear()
        send()
        assertEquals(SyncResult.SignedOut, env.engine.sync(pull = true))
        assertEquals(0, env.server.requestCount)
    }

    @Test fun `a photo is sent as base64 and its file is removed afterwards`() = runTest {
        env.diary.sendMessage("с сыром", byteArrayOf(1, 2, 3), DAY, NOW, MessageSource.Text)
        val id = env.db.outbox().queued().single().id
        assertNotNull(env.files.read(id))
        env.enqueue(200, messageResultJson(listOf(entryJson())))
        env.engine.sync(pull = false)

        val body = env.server.takeRequest().bodyJson()
        assertEquals("AQID", body["image_base64"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", body["image_mime"]!!.jsonPrimitive.content)
        assertEquals("photo", body["source"]!!.jsonPrimitive.content)
        assertNull(env.files.read(id))
    }

    @Test fun `a missing photo file fails the message instead of sending it without the photo`() = runTest {
        env.db.outbox().upsert(OutboxRow("lost", null, true, "image/jpeg", "2026-09-30", "2026-09-30T08:15:00+03:00", "photo",
            null, null, "queued", null, 0, 1))
        assertEquals(SyncResult.Done, env.engine.sync(pull = false))
        assertEquals("failed", env.db.outbox().get("lost")!!.state)
        assertEquals(0, env.server.requestCount)
    }

    // ---------- local edits ----------

    private suspend fun seedEntry(grams: Double = 200.0) {
        env.enqueue(200, messageResultJson(listOf(entryJson(grams = grams))))
        send()
        env.engine.sync(pull = false)
        env.server.takeRequest()
    }

    @Test fun `an edited entry is patched and then clean`() = runTest {
        seedEntry()
        env.diary.updateEntry(E1, grams = 100.0, name = null)
        assertEquals(92.0, env.db.entries().get(E1)!!.kcal!!, 0.0) // offline result is already right
        assertTrue(env.db.entries().get(E1)!!.dirty)

        env.enqueue(200, entryJson(grams = 100.0, kcal = 92.0, updatedAt = "2026-09-30T06:00:00Z"))
        env.engine.sync(pull = false)

        val req = env.server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/v1/entries/$E1", req.path)
        val body = req.bodyJson()
        assertEquals(100.0, body["grams"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("гречка", body["name"]!!.jsonPrimitive.content)
        assertFalse(env.db.entries().get(E1)!!.dirty)
    }

    @Test fun `a deleted entry is deleted on the server, and already gone is fine`() = runTest {
        seedEntry()
        env.diary.deleteEntry(E1)
        env.enqueue(404, errorJson("not_found", "Запись не найдена."))
        assertEquals(SyncResult.Done, env.engine.sync(pull = false))
        val req = env.server.takeRequest()
        assertEquals("DELETE", req.method)
        val row = env.db.entries().get(E1)!!
        assertTrue(row.deleted)
        assertFalse(row.dirty)
    }

    @Test fun `an edit made while the request is in flight is not overwritten`() = runTest {
        seedEntry()
        env.diary.updateEntry(E1, grams = 100.0, name = null)
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { env.diary.updateEntry(E1, grams = 77.0, name = null) } // the user edits again meanwhile
                return MockResponse().setResponseCode(200).setBody(entryJson(grams = 100.0, kcal = 92.0, updatedAt = "2026-09-30T06:00:00Z"))
            }
        }
        env.clock.advanceSeconds(5)
        env.engine.sync(pull = false)
        val row = env.db.entries().get(E1)!!
        assertEquals(77.0, row.grams, 0.0)
        assertTrue("the newer edit still has to be sent", row.dirty)
    }

    @Test fun `weights are put and deleted`() = runTest {
        env.diary.addWeight(DAY, 82.4, env.clock.instant())
        val id = env.db.weights().dirty().single().id
        env.enqueue(200, """{"id": "$id", "day": "2026-09-30", "kg": 82.4, "updated_at": "2026-09-30T05:15:05Z", "deleted": false}""")
        env.engine.sync(pull = false)
        val put = env.server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/weights/$id", put.path)
        assertEquals(82.4, put.bodyJson()["kg"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertTrue(env.db.weights().dirty().isEmpty())

        env.diary.deleteWeight(id)
        env.enqueueNoContent()
        env.engine.sync(pull = false)
        assertEquals("DELETE", env.server.takeRequest().method)
        assertTrue(env.db.weights().dirty().isEmpty())
    }

    // ---------- pull ----------

    @Test fun `pull stores rows, the goal and the cursor, and follows has_more`() = runTest {
        val e2 = "44444444-4444-4444-8444-444444444444"
        env.enqueue(200, syncJson(entries = listOf(entryJson(id = E1)), nextSince = "2026-09-30T05:10:00Z", hasMore = true))
        env.enqueue(200, syncJson(entries = listOf(entryJson(id = e2, name = "масло")),
            weights = listOf("""{"id": "w1", "day": "2026-09-30", "kg": 82.4, "updated_at": "2026-09-30T05:00:00Z", "deleted": false}"""),
            nextSince = "2026-09-30T05:20:00Z", goal = 1900))

        assertEquals(SyncResult.Done, env.engine.sync(pull = true))

        assertEquals("/v1/sync", env.server.takeRequest().path)
        assertEquals("/v1/sync?since=2026-09-30T05%3A10%3A00Z", env.server.takeRequest().path)
        assertNotNull(env.db.entries().get(E1))
        assertNotNull(env.db.entries().get(e2))
        assertNotNull(env.db.weights().get("w1"))
        assertEquals(1900, env.db.profile().get()!!.calorieGoal)
        assertEquals("2026-09-30T05:20:00Z", env.session.since)
    }

    @Test fun `pull never overwrites a row the user edited while syncing`() = runTest {
        seedEntry()
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { env.diary.updateEntry(E1, grams = 50.0, name = null) } // edit lands before the pull is applied
                return MockResponse().setResponseCode(200).setBody(syncJson(entries = listOf(entryJson(id = E1, grams = 999.0))))
            }
        }
        env.engine.sync(pull = true)
        val row = env.db.entries().get(E1)!!
        assertEquals(50.0, row.grams, 0.0)
        assertTrue(row.dirty)
    }

    @Test fun `a clean row is replaced by the server version`() = runTest {
        seedEntry()
        env.enqueue(200, syncJson(entries = listOf(entryJson(id = E1, grams = 300.0, kcal = 276.0))))
        env.engine.sync(pull = true)
        assertEquals(300.0, env.db.entries().get(E1)!!.grams, 0.0)
    }

    @Test fun `pull is skipped when not asked for`() = runTest {
        assertEquals(SyncResult.Done, env.engine.sync(pull = false))
        assertEquals(0, env.server.requestCount)
    }

    @Test fun `a failed pull is a retry`() = runTest {
        env.enqueue(503, errorJson("x", "busy"))
        assertEquals(SyncResult.Retry, env.engine.sync(pull = true))
    }
}
