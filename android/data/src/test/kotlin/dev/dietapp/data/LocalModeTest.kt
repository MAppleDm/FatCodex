package dev.dietapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.AppMode
import dev.dietapp.data.local.FoodInput
import dev.dietapp.data.local.NEEDS_KEY
import dev.dietapp.data.net.AppError
import dev.dietapp.data.repo.LOCAL_SPEECH_MESSAGE
import dev.dietapp.data.repo.SpeechRepositoryImpl
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
private val NOW = ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.ofHours(3))
private const val MODEL_KEY = "sk-local-test-key-123456"

/**
 * Local mode end to end: real Room, the user's own food database, MockWebServer standing in for DeepSeek. The agent is the
 * only thing that reads a message: without it (no key, no connection, a refused key) nothing is recorded, and the
 * message waits, failed, for another try.
 */
@RunWith(AndroidJUnit4::class)
class LocalModeTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        env = TestEnv()
        env.mode.set(AppMode.Local)
    }

    @After fun tearDown() = env.close()

    private suspend fun say(text: String, day: LocalDate = DAY, source: MessageSource = MessageSource.Text) =
        env.localDiary.sendMessage(text, null, day, NOW, source)

    private suspend fun entries(day: LocalDate = DAY) = env.db.entries().forDay(day.toString())

    private fun toolCall(items: String) =
        """{"choices": [{"message": {"role": "assistant", "content": null, "tool_calls": [{"id": "c1", "type": "function",
            "function": {"name": "record_food", "arguments": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), """{"items": $items}""")}}}]}}]}"""

    /** What the agent answers: one record_food call with these items. */
    private fun agentSays(vararg items: String) = env.enqueue(200, toolCall("[${items.joinToString(",")}]"))

    /** One item of record_food. [food] is the food of the user's base the agent picked, if it picked one. */
    private fun item(
        name: String, grams: Double, food: Food? = null, confidence: Double = 0.9, ask: Boolean = false, question: String? = null,
    ) = """{"action": "add", "name": "$name", "grams": $grams, "confidence": $confidence, "needs_confirmation": $ask""" +
        (food?.let { ""","food_id": "my:${it.id}"""" } ?: "") +
        (question?.let { ""","clarify_question": "$it"""" } ?: "") + "}"

    private suspend fun base(name: String, kcal: Double, protein: Double, fat: Double, carbs: Double, estimated: Boolean = false): Food =
        env.foods.save(FoodInput(name, Per100(kcal, protein, fat, carbs), estimated = estimated))

    private fun needAgent() = env.secrets.saveKey(MODEL_KEY)

    // ---------- starting without a server ----------

    @Test fun `choosing local mode signs in without any server`() = runTest {
        env.mode.set(null)
        assertFalse(env.auth.loggedIn.value)
        env.auth.useWithoutServer()
        assertEquals(AppMode.Local, env.mode.mode.value)
        assertTrue(env.auth.loggedIn.value)
        assertEquals(0, env.server.requestCount)
        assertNull(env.db.profile().get()!!.email)
    }

    @Test fun `an existing server session is a server user, not a new local one`() {
        env.session.save("tok", "me@example.com")
        env.mode.set(null)
        val reopened = dev.dietapp.data.local.ModeStore(env.context, env.session)
        assertEquals(AppMode.Server, reopened.mode.value)
        env.mode.set(AppMode.Local)
        assertEquals(AppMode.Local, dev.dietapp.data.local.ModeStore(env.context, env.session).mode.value) // the choice is remembered
    }

    // ---------- the agent reads, the user's database supplies the numbers ----------

    @Test fun `a message becomes entries on the phone with numbers from the user's database`() = runTest {
        needAgent()
        val chicken = base("куриная грудка", 165.0, 31.0, 3.6, 0.0)
        val buckwheat = base("гречка", 92.0, 3.4, 0.6, 19.9)
        val egg = base("яйцо варёное", 155.0, 12.6, 10.6, 1.1)
        agentSays(item("куриная грудка", 150.0, chicken), item("гречка", 200.0, buckwheat), item("яйцо варёное", 100.0, egg))
        say("куриная грудка 150 г, гречка 200 г и 2 яйца")
        val e = entries()
        assertEquals(listOf("куриная грудка", "гречка", "яйцо варёное"), e.map { it.name })
        assertEquals(listOf(247.5, 184.0, 155.0), e.map { it.kcal })
        assertEquals(listOf("ok", "ok", "ok"), e.map { it.status })
        assertTrue("nothing is waiting for a server", e.none { it.dirty })
        assertTrue(env.db.outbox().queued().isEmpty())
        assertEquals("куриная грудка", e.first().foodName)
        assertEquals(e[0].mealId, e[1].mealId)
    }

    @Test fun `each message is confirmed with a tick`() = runTest {
        needAgent()
        agentSays(item("банан", 120.0, base("банан", 89.0, 1.1, 0.3, 22.8)))
        env.localEngine.confirmations.test {
            say("банан")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `the model gives no id and the user's own food of that name is used`() = runTest {
        needAgent()
        base("борщ", 49.0, 2.0, 2.5, 4.5)
        agentSays(item("борщ", 300.0))
        say("борщ 300 г")
        val soup = entries().single()
        assertEquals(147.0, soup.kcal!!, 0.0)
        assertEquals("ok", soup.status)
        assertEquals("борщ", soup.foodName)
    }

    @Test fun `a food whose values are only an estimate is kept but shown as approximate, and waits`() = runTest {
        needAgent()
        val soup = base("борщ", 49.0, 2.0, 2.5, 4.5, estimated = true)
        agentSays(item("борщ", 300.0, soup))
        say("борщ 300 г")
        val e = entries().single()
        assertEquals("uncertain", e.status)
        assertEquals(147.0, e.kcal!!, 0.0)
        assertTrue("estimated values wait for the user", e.pending)
    }

    @Test fun `a food that is not in the base is kept without numbers and asked about, then answered by name`() = runTest {
        needAgent()
        agentSays(item("чахохбили", 250.0))
        say("чахохбили 250 г")
        val unknown = entries().single()
        assertEquals("unmatched", unknown.status)
        assertNull(unknown.kcal)
        assertEquals("Не нашёл «чахохбили» в базе продуктов. Что это точнее?", env.db.notes().latestOpenQuestion()!!.text)

        val chicken = base("курица", 190.0, 18.0, 12.0, 0.0)
        agentSays("""{"action": "update", "target_id": "${unknown.id}", "name": "курица", "grams": 250, "confidence": 0.9, "food_id": "my:${chicken.id}"}""")
        say("курица")
        val fixed = entries().single()
        assertEquals(unknown.id, fixed.id)
        assertEquals("курица", fixed.name)
        assertEquals(250.0, fixed.grams, 0.0)
        assertEquals(475.0, fixed.kcal!!, 0.0) // 190 kcal per 100 g
        assertEquals("ok", fixed.status)
    }

    @Test fun `the agent asks one question and the answer goes back with the pending question`() = runTest {
        needAgent()
        base("борщ", 49.0, 2.0, 2.5, 4.5)
        agentSays(item("суп", 300.0, confidence = 0.4, question = "Какой суп?"))
        say("суп")
        assertEquals("Какой суп?", env.db.notes().latestOpenQuestion()!!.text)

        agentSays("""{"action": "update", "target_id": "${entries().single().id}", "name": "борщ", "grams": 300, "confidence": 0.9}""")
        say("борщ")
        val body = Json.parseToJsonElement(env.server.takeRequest().let { env.server.takeRequest() }.body.readUtf8())
        val text = body.jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains(""""pending_question":{"question":"Какой суп?""""))
        assertEquals("борщ", entries().single().name)
        assertEquals(147.0, entries().single().kcal!!, 0.0)
    }

    @Test fun `corrections change the diary instead of adding to it`() = runTest {
        needAgent()
        val buckwheat = base("гречка", 92.0, 3.4, 0.6, 19.9)
        val butter = base("масло сливочное", 717.0, 0.9, 81.0, 0.1)
        val bread = base("хлеб", 250.0, 8.0, 3.0, 49.0)
        agentSays(item("гречка", 200.0, buckwheat), item("масло сливочное", 20.0, butter), item("хлеб", 60.0, bread))
        say("гречка 200 г, масло 20 г, хлеб 60 г")
        val butterEntry = entries().first { it.name == "масло сливочное" }

        agentSays("""{"action": "update", "target_id": "${butterEntry.id}", "name": "масло сливочное", "grams": 13.333333333333334, "confidence": 0.65}""")
        say("масла было меньше")
        val less = entries().first { it.id == butterEntry.id }
        assertEquals(13.333333333333334, less.grams, 1e-9)
        assertEquals(95.6, less.kcal!!, 0.0)
        assertEquals(3, entries().size)

        agentSays("""{"action": "remove", "target_id": "${entries().first { it.name == "хлеб" }.id}", "name": "хлеб", "grams": 0, "confidence": 0.9}""")
        say("убери хлеб")
        assertEquals(listOf("гречка", "масло сливочное"), entries().map { it.name })
        assertTrue("deleted for good, nothing to upload", env.db.entries().dirty().isEmpty())
    }

    @Test fun `a message with no food leaves a note instead of silence`() = runTest {
        needAgent()
        env.enqueue(200, toolCall("[]"))
        say("привет")
        assertTrue(entries().isEmpty())
        assertEquals(listOf("Не нашёл в сообщении еды."), env.localDiary.observeDay(DAY).first().notes.map { it.text })
    }

    @Test fun `weigh-ins, edits and deletes are final at once and never queued for a server`() = runTest {
        needAgent()
        agentSays(item("гречка", 200.0, base("гречка", 92.0, 3.4, 0.6, 19.9)))
        say("гречка 200 г")
        val id = entries().single().id
        env.diary.updateEntry(id, 100.0, null)
        assertEquals(92.0, env.db.entries().get(id)!!.kcal!!, 0.0)
        assertFalse(env.db.entries().get(id)!!.dirty)
        env.diary.addWeight(DAY, 82.4, env.clock.instant())
        assertTrue(env.db.weights().dirty().isEmpty())
        env.diary.deleteEntry(id)
        assertFalse(env.db.entries().get(id)!!.dirty)
        assertTrue("the sync trigger is never asked", env.trigger.requests.isEmpty())
    }

    @Test fun `the agent is shown the habitual meals, and as usual is its to repeat`() = runTest {
        needAgent()
        val oats = base("овсяная каша", 71.0, 2.5, 1.5, 12.0)
        val banana = base("банан", 89.0, 1.1, 0.3, 22.8)
        for (d in 25..27) {
            agentSays(item("овсяная каша", 250.0, oats), item("банан", 120.0, banana))
            say("овсянка 250 г, банан", LocalDate.parse("2026-09-$d"))
        }
        agentSays(item("овсяная каша", 250.0, oats), item("банан", 120.0, banana))
        say("как обычно")
        repeat(3) { env.server.takeRequest() }
        val context = env.server.takeRequest().body.readUtf8()
        assertTrue("the habitual meal is in what the agent is shown", context.contains("frequent") && context.contains("овсяная каша"))
        val today = entries()
        assertEquals(listOf("овсяная каша", "банан"), today.map { it.name })
        assertEquals(entries(LocalDate.parse("2026-09-27")).map { it.kcal }, today.map { it.kcal })
    }

    @Test fun `yesterday's entries are not touched by today's corrections`() = runTest {
        needAgent()
        agentSays(item("масло", 20.0, base("масло", 717.0, 0.9, 81.0, 0.1)))
        say("масло 20 г", LocalDate.parse("2026-09-29"))
        val yesterday = entries(LocalDate.parse("2026-09-29")).single()
        // the agent was shown only today's diary, so an id of yesterday's entry is one it was never given
        agentSays("""{"action": "update", "target_id": "${yesterday.id}", "name": "масло", "grams": 13, "confidence": 0.65}""")
        say("масла было меньше")
        assertEquals(20.0, entries(LocalDate.parse("2026-09-29")).single().grams, 0.0)
    }

    // ---------- no agent, no recording ----------

    @Test fun `without a key a message is not read, not guessed, and waits so it can be read after a key is added`() = runTest {
        val buckwheat = base("гречка", 92.0, 3.4, 0.6, 19.9) // even a food the base has: nothing reads the message
        say("гречка 200 г")
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals(NEEDS_KEY, failed.error)
        assertTrue(entries().isEmpty())
        assertEquals(0, env.server.requestCount)

        needAgent()
        agentSays(item("гречка", 200.0, buckwheat))
        env.localDiary.retryOutbox(failed.id)
        assertEquals(listOf("гречка"), entries().map { it.name })
        assertEquals(184.0, entries().single().kcal!!, 0.0)
        assertTrue(env.db.outbox().observeAll().first().isEmpty())
    }

    @Test fun `when the agent is unreachable the message stays, failed, and nothing is guessed in its place`() = runTest {
        needAgent()
        val buckwheat = base("гречка", 92.0, 3.4, 0.6, 19.9)
        repeat(3) { env.enqueue(500, "{}") }
        say("гречка 200 г")
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals("one short line, not a paragraph of exceptions", "Нет связи с агентом.", failed.error)
        assertTrue("the details are in the journal", env.journal.read().contains("HTTP 500"))
        assertTrue("the base knows гречка, and still the message was not recorded without the agent", entries().isEmpty())
        assertEquals(3, env.server.requestCount)
        assertTrue("the message is still in the chat", env.db.messages().observeDay(DAY.toString()).first().isNotEmpty())

        // "Повторить": the agent reads it again, from the start
        agentSays(item("гречка", 200.0, buckwheat))
        env.localDiary.retryOutbox(failed.id)
        assertEquals(184.0, entries().single().kcal!!, 0.0)
        assertTrue(env.db.outbox().observeAll().first().isEmpty())
    }

    @Test fun `a refused key fails the message with its own message and is not retried`() = runTest {
        needAgent()
        env.enqueue(401, """{"error": {"message": "bad key"}}""")
        say("банан")
        assertTrue(entries().isEmpty())
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals(
            "Ключ DeepSeek не подошёл (HTTP 401: bad key). Проверь его в «О приложении → Агент».",
            failed.error,
        )
        assertEquals(1, env.server.requestCount) // a bad key is not retried
    }

    @Test fun `an answer that is cut off half way is tried again`() = runTest {
        needAgent()
        val banana = base("банан", 89.0, 1.1, 0.3, 22.8)
        env.server.enqueue(
            okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(toolCall("[]"))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        agentSays(item("банан", 120.0, banana))
        say("банан")
        assertEquals(listOf("банан"), entries().map { it.name })
        assertEquals(2, env.server.requestCount)
    }

    @Test fun `a photo without a key is refused clearly, and kept so it can be read after a key is added`() = runTest {
        env.localDiary.sendMessage(null, byteArrayOf(1, 2, 3), DAY, NOW, MessageSource.Photo)
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals(NEEDS_KEY, failed.error)
        assertEquals(0, env.server.requestCount)

        needAgent()
        agentSays(item("гречка", 200.0, base("гречка", 92.0, 3.4, 0.6, 19.9)))
        env.localDiary.retryOutbox(failed.id)
        assertEquals(listOf("гречка"), entries().map { it.name })
        assertEquals("photo", entries().single().source)
        val image = Json.parseToJsonElement(env.server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,AQID", image)
        assertNull("the photo file is cleaned up", env.files.read(failed.id))
    }

    @Test fun `with a key but no connection a photo waits as failed`() = runTest {
        needAgent()
        repeat(3) { env.enqueue(503, "{}") }
        env.localDiary.sendMessage(null, byteArrayOf(9), DAY, NOW, MessageSource.Photo)
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals("Нет связи с агентом.", failed.error)
        assertTrue(entries().isEmpty())
        assertNotNull("the picture is kept for the next try", env.files.read(failed.id))
    }

    // ---------- the key goes to the model, and only to it ----------

    @Test fun `the agent reads the text, and only the model's key goes to it`() = runTest {
        env.loginAs() // a server token exists too: it must never be sent to DeepSeek
        needAgent()
        agentSays(item("куриная грудка", 150.0, base("куриная грудка", 165.0, 31.0, 3.6, 0.0)))
        say("курочка 150 грамм")

        assertEquals(247.5, entries().single().kcal!!, 0.0)
        val req = env.server.takeRequest()
        assertEquals("Bearer $MODEL_KEY", req.getHeader("Authorization"))
        assertEquals("/chat/completions", req.path)
        assertFalse(req.headers.names().any { it.equals("x-api-key", true) })
        assertEquals(1, env.server.requestCount)
    }

    @Test fun `a model that invents an entry id cannot touch anything`() = runTest {
        needAgent()
        agentSays(item("гречка", 200.0, base("гречка", 92.0, 3.4, 0.6, 19.9)))
        say("гречка")
        agentSays("""{"action": "remove", "target_id": "someone-elses-id", "name": "х", "grams": 0, "confidence": 0.9}""")
        say("убери всё")
        assertEquals(1, entries().size)
        assertEquals("Не понял, какую запись поправить. Уточни, пожалуйста.", env.db.notes().latestOpenQuestion()!!.text)
    }

    // ---------- one failing message does not block the rest ----------

    @Test fun `messages are processed in order and a failure does not block the next`() = runTest {
        needAgent()
        repeat(3) { env.enqueue(503, "{}") }
        env.localDiary.sendMessage(null, byteArrayOf(1), DAY, NOW, MessageSource.Photo) // fails: the agent is unreachable
        agentSays(item("банан", 120.0, base("банан", 89.0, 1.1, 0.3, 22.8)))
        say("банан")
        assertEquals(listOf("банан"), entries().map { it.name })
        assertEquals(listOf("failed"), env.db.outbox().observeAll().first().map { it.state })
    }

    // ---------- speech, settings, reset ----------

    @Test fun `server speech recognition is not available without a server`() = runTest {
        val speech = SpeechRepositoryImpl(env.api, env.json, env.mode)
        val error = speech.transcribe(byteArrayOf(1), "a.m4a", "audio/mp4", "ru").exceptionOrNull() as AppError
        assertEquals(LOCAL_SPEECH_MESSAGE, error.message)
        assertEquals(0, env.server.requestCount)
    }

    @Test fun `capabilities follow the mode and the key`() = runTest {
        env.mode.set(null)
        env.localSettings.capabilities.test {
            assertEquals(false, awaitItem().local)
            env.mode.set(AppMode.Local)
            val local = awaitItem()
            assertTrue(local.local && !local.modelKey)
            assertTrue(env.localSettings.saveKey(MODEL_KEY).isSuccess)
            assertTrue(awaitItem().modelKey)
            env.localSettings.clearKey()
            assertFalse(awaitItem().modelKey)
        }
    }

    @Test fun `a key that cannot be real is refused`() {
        for (bad in listOf("", "short", "has a space in it 12345", "   ")) {
            assertEquals("bad_key", (env.localSettings.saveKey(bad).exceptionOrNull() as AppError).code)
        }
        assertFalse(env.secrets.hasKey.value)
        assertTrue(env.localSettings.saveKey("  $MODEL_KEY  ").isSuccess)
        assertEquals(MODEL_KEY, env.secrets.deepseekKey)
    }

    @Test fun `resetting erases the diary, the key and the choice`() = runTest {
        needAgent()
        agentSays(item("гречка", 200.0, base("гречка", 92.0, 3.4, 0.6, 19.9)))
        say("гречка 200 г")
        env.diary.addWeight(DAY, 82.4, env.clock.instant())
        env.auth.logout()
        assertNull(env.mode.mode.value)
        assertFalse(env.auth.loggedIn.value)
        assertFalse(env.secrets.hasKey.value)
        assertTrue(env.db.entries().forDay(DAY.toString()).isEmpty())
        assertEquals(0, env.db.weights().dirty().size)
        assertNull(env.db.profile().get())
    }

    @Test fun `the model key is kept apart from the server session`() {
        env.session.save("server-token", "me@example.com")
        env.secrets.saveKey(MODEL_KEY)
        assertNotNull(env.session.token)
        env.session.clear()
        assertEquals(MODEL_KEY, env.secrets.deepseekKey) // signing out of a server does not forget the model key
    }

    // ---------- what is recorded at once, and what waits ----------

    private suspend fun dayTotals() = env.diary.observeDaySummaries().first()

    @Test fun `plain food the agent is sure of is recorded at once`() = runTest {
        needAgent()
        agentSays(item("гречка", 200.0, base("гречка", 92.0, 3.4, 0.6, 19.9)))
        say("гречка 200 г")
        assertFalse(entries().single().pending)
        assertEquals(184.0, dayTotals().single().totals.kcal, 1.0)
        assertTrue("no record question is written into the chat", env.db.notes().observeDay(DAY.toString()).first().none { it.kind == "confirm" })
    }

    @Test fun `food the agent is not sure of waits for the user`() = runTest {
        needAgent()
        agentSays(item("сосиски", 100.0, base("сосиски", 300.0, 11.0, 28.0, 1.5), ask = true))
        say("сосиски")
        assertTrue(entries().single().pending)
        assertTrue(dayTotals().isEmpty())
    }

    @Test fun `food that waits is counted once it is recorded`() = runTest {
        needAgent()
        agentSays(item("борщ", 300.0, base("борщ", 49.0, 2.0, 2.5, 4.5, estimated = true)))
        say("борщ 300 г") // estimated values: approximate, so it waits for the user
        val waiting = entries().single()
        assertTrue(waiting.pending)
        assertTrue(waiting.kcal!! > 0)
        assertTrue("not in the history totals yet", dayTotals().isEmpty())

        env.localDiary.recordPending(listOf(waiting.id), record = true)
        assertFalse(entries().single().pending)
        assertEquals(waiting.kcal!!, dayTotals().single().totals.kcal, 1.0)
        assertTrue("the answer is the entry's colour, not a line in the chat", env.db.notes().observeDay(DAY.toString()).first().isEmpty())
    }

    @Test fun `not recording takes the food back`() = runTest {
        needAgent()
        val soup = base("борщ", 49.0, 2.0, 2.5, 4.5, estimated = true)
        val dumplings = base("пельмени", 275.0, 12.0, 12.0, 29.0, estimated = true)
        agentSays(item("борщ", 300.0, soup), item("пельмени", 200.0, dumplings))
        say("борщ 300 г, пельмени 200 г")
        assertTrue("both are estimates: they wait", entries().all { it.pending })
        env.localDiary.recordPending(entries().map { it.id }, record = false)
        assertTrue(entries().isEmpty())
    }

    @Test fun `the chosen language is used for what the app writes`() = runTest {
        needAgent()
        env.mode.setLanguage(dev.dietapp.data.domain.Language.En)
        env.enqueue(200, toolCall("[]"))
        say("привет")
        assertEquals("Found no food in the message.", env.db.notes().observeDay(DAY.toString()).first().single().text)
        env.mode.setLanguage(dev.dietapp.data.domain.Language.Ru)
    }

    @Test fun `an answer to a question about a food belongs to that food`() = runTest {
        needAgent()
        agentSays(item("суши", 300.0, confidence = 0.4, question = "Какие суши?"))
        say("суши 300 г")
        val sushi = entries().single()
        val question = env.db.notes().observeDay(DAY.toString()).first().single { it.kind == "question" }
        assertEquals(sushi.id, question.targetEntryId)
        agentSays("""{"action": "update", "target_id": "${sushi.id}", "name": "роллы филадельфия", "grams": 300, "confidence": 0.9}""")
        say("роллы филадельфия")
        val answer = env.db.messages().observeDay(DAY.toString()).first().single { it.text == "роллы филадельфия" }
        assertEquals(sushi.id, answer.aboutEntryId)
    }
}
