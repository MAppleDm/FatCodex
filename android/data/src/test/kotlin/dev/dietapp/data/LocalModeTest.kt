package dev.dietapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.local.AppMode
import dev.dietapp.data.local.KEY_REFUSED_NOTE
import dev.dietapp.data.local.MODEL_OFFLINE_NOTE
import dev.dietapp.data.local.PHOTO_NEEDS_KEY
import dev.dietapp.data.local.RecordMode
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

/** Local mode end to end: real Room, real catalog and dictionary, MockWebServer standing in for DeepSeek. */
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

    @Test fun `the goal is saved on the phone only, with the same safety floor`() = runTest {
        env.auth.useWithoutServer()
        assertTrue(env.auth.setGoal(1900).isSuccess)
        assertEquals(1900, env.db.profile().get()!!.calorieGoal)
        assertEquals("goal_too_low", (env.auth.setGoal(1100).exceptionOrNull() as AppError).code)
        assertEquals(1900, env.db.profile().get()!!.calorieGoal)
        assertEquals(0, env.server.requestCount)
    }

    @Test fun `a goal set earlier survives choosing local mode again`() = runTest {
        env.db.profile().upsert(dev.dietapp.data.db.ProfileRow(email = null, calorieGoal = 2100))
        env.auth.useWithoutServer()
        assertEquals(2100, env.db.profile().get()!!.calorieGoal)
    }

    @Test fun `an existing server session is a server user, not a new local one`() {
        env.session.save("tok", "me@example.com")
        env.mode.set(null)
        val reopened = dev.dietapp.data.local.ModeStore(env.context, env.session)
        assertEquals(AppMode.Server, reopened.mode.value)
        env.mode.set(AppMode.Local)
        assertEquals(AppMode.Local, dev.dietapp.data.local.ModeStore(env.context, env.session).mode.value) // the choice is remembered
    }

    // ---------- offline: no key, no network ----------

    @Test fun `a message becomes entries on the phone with numbers from the database`() = runTest {
        say("куриная грудка 150 г, гречка 200 г и 2 яйца")
        val e = entries()
        assertEquals(listOf("куриная грудка", "гречка", "яйцо варёное"), e.map { it.name })
        assertEquals(listOf(247.5, 184.0, 155.0), e.map { it.kcal })
        assertEquals(listOf("ok", "ok", "ok"), e.map { it.status })
        assertTrue("nothing is waiting for a server", e.none { it.dirty })
        assertEquals(0, env.server.requestCount)
        assertTrue(env.db.outbox().queued().isEmpty())
        assertTrue(e.first().foodName!!.startsWith("Chicken, broilers or fryers, breast"))
        assertEquals(e[0].mealId, e[1].mealId)
    }

    @Test fun `each message is confirmed with a tick`() = runTest {
        env.localEngine.confirmations.test {
            say("банан")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `table dishes are kept but shown as approximate`() = runTest {
        say("тарелка борща")
        val borscht = entries().single()
        assertEquals("uncertain", borscht.status)
        assertEquals(147.0, borscht.kcal!!, 0.0)
        assertEquals("борщ", borscht.foodName)
    }

    @Test fun `a vague dish asks for the amount, and the answer settles it`() = runTest {
        say("суп")
        val soup = entries().single()
        val question = env.db.notes().latestOpenQuestion()!!
        assertEquals("Уточни, пожалуйста: сколько граммов «суп»?", question.text)
        assertEquals(soup.id, question.targetEntryId)

        say("200 г")
        val after = entries().single()
        assertEquals(soup.id, after.id)
        assertEquals(200.0, after.grams, 0.0)
        assertEquals(90.0, after.kcal!!, 0.0)
        assertNull(env.db.notes().latestOpenQuestion())
    }

    @Test fun `a food it does not know is kept without numbers and asked about, then answered by name`() = runTest {
        say("чахохбили 250 г")
        val unknown = entries().single()
        assertEquals("unmatched", unknown.status)
        assertNull(unknown.kcal)
        assertEquals("Не нашёл «чахохбили» в базе продуктов. Что это точнее?", env.db.notes().latestOpenQuestion()!!.text)

        say("курица")
        val fixed = entries().single()
        assertEquals(unknown.id, fixed.id)
        assertEquals("курица", fixed.name)
        assertEquals(250.0, fixed.grams, 0.0)
        assertEquals(475.0, fixed.kcal!!, 0.0) // 190 kcal per 100 g
        assertEquals("ok", fixed.status)
    }

    @Test fun `corrections change the diary instead of adding to it`() = runTest {
        say("гречка 200 г, масло 20 г, хлеб 60 г")
        val butter = entries().first { it.name == "масло сливочное" }

        say("масла было меньше")
        val less = entries().first { it.id == butter.id }
        assertEquals(13.333333333333334, less.grams, 1e-9)
        assertEquals(95.6, less.kcal!!, 0.0)
        assertEquals(3, entries().size)

        say("убери хлеб")
        assertEquals(listOf("гречка", "масло сливочное"), entries().map { it.name })
        assertTrue("deleted for good, nothing to upload", env.db.entries().dirty().isEmpty())

        say("это была половина")
        assertEquals(6.666666666666667, entries().last().grams, 1e-9)
    }

    @Test fun `a message with no food leaves a note instead of silence`() = runTest {
        say("привет")
        assertTrue(entries().isEmpty())
        assertEquals(listOf("Не нашёл в сообщении еды."), env.localDiary.observeDay(DAY).first().notes.map { it.text })
    }

    @Test fun `weigh-ins, edits and deletes are final at once and never queued for a server`() = runTest {
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

    @Test fun `as usual repeats a habitual meal with its exact numbers`() = runTest {
        for (d in 25..27) say("овсянка 250 г, банан", LocalDate.parse("2026-09-$d"))
        say("как обычно")
        val today = entries()
        assertEquals(listOf("овсяная каша", "банан"), today.map { it.name })
        assertEquals(entries(LocalDate.parse("2026-09-27")).map { it.kcal }, today.map { it.kcal })
    }

    @Test fun `yesterday's entries are not touched by today's corrections`() = runTest {
        say("масло 20 г", LocalDate.parse("2026-09-29"))
        say("масла было меньше")
        assertEquals(20.0, entries(LocalDate.parse("2026-09-29")).single().grams, 0.0)
    }

    // ---------- photos ----------

    @Test fun `a photo without a key is refused clearly, and kept so it can be sent after a key is added`() = runTest {
        env.localDiary.sendMessage(null, byteArrayOf(1, 2, 3), DAY, NOW, MessageSource.Photo)
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertEquals(PHOTO_NEEDS_KEY, failed.error)
        assertEquals(0, env.server.requestCount)

        env.secrets.saveKey(MODEL_KEY)
        env.enqueue(200, toolCall("""[{"action": "add", "name": "гречка", "query_en": "buckwheat groats, cooked", "grams": 200, "confidence": 0.8}]"""))
        env.localDiary.retryOutbox(failed.id)
        assertEquals(listOf("гречка"), entries().map { it.name })
        assertEquals("photo", entries().single().source)
        val image = Json.parseToJsonElement(env.server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,AQID", image)
        assertNull("the photo file is cleaned up", env.files.read(failed.id))
    }

    @Test fun `with a key but no connection a photo waits as failed and the text fallback is not used for it`() = runTest {
        env.secrets.saveKey(MODEL_KEY)
        repeat(3) { env.enqueue(503, "{}") }
        env.localDiary.sendMessage(null, byteArrayOf(9), DAY, NOW, MessageSource.Photo)
        val failed = env.db.outbox().observeAll().first().single()
        assertEquals("failed", failed.state)
        assertTrue(failed.error!!.contains("нет связи с моделью"))
        assertTrue(entries().isEmpty())
    }

    // ---------- with a model key ----------

    @Test fun `with a key the model reads the text, and only the model's key goes to it`() = runTest {
        env.loginAs() // a server token exists too: it must never be sent to DeepSeek
        env.secrets.saveKey(MODEL_KEY)
        env.enqueue(200, toolCall("""[{"action": "add", "name": "куриная грудка", "query_en": "chicken, broilers or fryers, breast, meat only, cooked", "grams": 150, "confidence": 0.9}]"""))
        say("курочка 150 грамм")

        assertEquals(247.5, entries().single().kcal!!, 0.0)
        val req = env.server.takeRequest()
        assertEquals("Bearer $MODEL_KEY", req.getHeader("Authorization"))
        assertEquals("/chat/completions", req.path)
        assertFalse(req.headers.names().any { it.equals("x-api-key", true) })
        assertEquals(1, env.server.requestCount)
    }

    @Test fun `the model's russian name for a dish is found in the dictionary when its query fails`() = runTest {
        env.secrets.saveKey(MODEL_KEY)
        env.enqueue(200, toolCall("""[{"action": "add", "name": "борщ", "query_en": "beet soup with cabbage", "grams": 300, "confidence": 0.85}]"""))
        say("борщ 300 г")
        val e = entries().single()
        assertEquals(147.0, e.kcal!!, 0.0)
        assertEquals("uncertain", e.status)
    }

    @Test fun `the model asks one question and the answer goes back with the pending question`() = runTest {
        env.secrets.saveKey(MODEL_KEY)
        env.enqueue(200, toolCall("""[{"action": "add", "name": "суп", "query_en": "soup", "grams": 300, "confidence": 0.4, "clarify_question": "Какой суп?"}]"""))
        say("суп")
        assertEquals("Какой суп?", env.db.notes().latestOpenQuestion()!!.text)

        env.enqueue(200, toolCall("""[{"action": "update", "target_id": "${entries().single().id}", "name": "борщ", "grams": 300, "confidence": 0.9}]"""))
        say("борщ")
        val body = Json.parseToJsonElement(env.server.takeRequest().also { }.let { env.server.takeRequest() }.body.readUtf8())
        val text = body.jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains(""""pending_question":{"question":"Какой суп?""""))
        assertEquals("борщ", entries().single().name)
    }

    @Test fun `when the model is unreachable text is read offline, and the user is told`() = runTest {
        env.secrets.saveKey(MODEL_KEY)
        repeat(3) { env.enqueue(500, "{}") }
        say("гречка 200 г")
        assertEquals(184.0, entries().single().kcal!!, 0.0)
        val notes = env.localDiary.observeDay(DAY).first().notes
        assertEquals(
            listOf("Нет связи с моделью (HTTP 500: {}). Разобрано без неё. Подробности — в журнале (настройки)."),
            notes.map { it.text },
        )
        assertEquals(3, env.server.requestCount)
    }

    @Test fun `a refused key falls back offline with its own message`() = runTest {
        env.secrets.saveKey(MODEL_KEY)
        env.enqueue(401, """{"error": {"message": "bad key"}}""")
        say("банан")
        assertEquals(1, entries().size)
        assertEquals(
            listOf("Ключ DeepSeek не подошёл (HTTP 401: bad key). Разобрано без модели, проверь ключ в настройках. Подробности — в журнале (настройки)."),
            env.localDiary.observeDay(DAY).first().notes.map { it.text },
        )
        assertEquals(1, env.server.requestCount) // a bad key is not retried
    }

    @Test fun `a model that invents an entry id cannot touch anything`() = runTest {
        env.secrets.saveKey(MODEL_KEY)
        env.enqueue(200, toolCall("""[{"action": "add", "name": "гречка", "query_en": "buckwheat cooked", "grams": 200, "confidence": 0.9}]"""))
        say("гречка")
        env.enqueue(200, toolCall("""[{"action": "remove", "target_id": "someone-elses-id", "name": "х", "grams": 0, "confidence": 0.9}]"""))
        say("убери всё")
        assertEquals(1, entries().size)
        assertEquals("Не понял, какую запись поправить. Уточни, пожалуйста.", env.db.notes().latestOpenQuestion()!!.text)
    }

    // ---------- one failing message does not block the rest ----------

    @Test fun `messages are processed in order and a failure does not block the next`() = runTest {
        env.localDiary.sendMessage(null, byteArrayOf(1), DAY, NOW, MessageSource.Photo) // fails: no key
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
        env.secrets.saveKey(MODEL_KEY)
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

    // ---------- record modes ----------

    private suspend fun dayTotals() = env.diary.observeDaySummaries().first()

    @Test fun `standard mode records plain food at once`() = runTest {
        say("гречка 200 г")
        assertFalse(entries().single().pending)
        assertEquals(184.0, dayTotals().single().totals.kcal, 1.0)
        assertTrue("no record question is written into the chat", env.db.notes().observeDay(DAY.toString()).first().none { it.kind == "confirm" })
    }

    @Test fun `standard mode asks about food whose values are only typical`() = runTest {
        say("борщ 300 г")
        val soup = entries().single()
        assertEquals("uncertain", soup.status)
        assertTrue("table values of a dish vary from recipe to recipe", soup.pending)
        assertTrue(dayTotals().isEmpty())
    }

    @Test fun `precise mode makes every food wait, and it is counted once recorded`() = runTest {
        env.mode.setRecordMode(RecordMode.Precise)
        say("гречка 200 г")
        val waiting = entries().single()
        assertTrue(waiting.pending)
        assertEquals(184.0, waiting.kcal!!, 1.0)
        assertTrue("not in the history totals yet", dayTotals().isEmpty())

        env.localDiary.recordPending(listOf(waiting.id), record = true)
        assertFalse(entries().single().pending)
        assertEquals(184.0, dayTotals().single().totals.kcal, 1.0)
        assertTrue("the answer is the entry's colour, not a line in the chat", env.db.notes().observeDay(DAY.toString()).first().isEmpty())
    }

    @Test fun `not recording takes the food back`() = runTest {
        env.mode.setRecordMode(RecordMode.Precise)
        say("гречка 200 г, банан")
        env.localDiary.recordPending(entries().map { it.id }, record = false)
        assertTrue(entries().isEmpty())
    }

    @Test fun `the chosen language is used for what the app writes`() = runTest {
        env.mode.setLanguage(dev.dietapp.data.domain.Language.En)
        say("привет")
        assertEquals("Found no food in the message.", env.db.notes().observeDay(DAY.toString()).first().single().text)
        env.mode.setLanguage(dev.dietapp.data.domain.Language.Ru)
    }

    @Test fun `an answer to a question about a food belongs to that food`() = runTest {
        say("суши 300 г")
        val sushi = entries().single()
        val question = env.db.notes().observeDay(DAY.toString()).first().single { it.kind == "question" }
        assertEquals(sushi.id, question.targetEntryId)
        say("роллы филадельфия")
        val answer = env.db.messages().observeDay(DAY.toString()).first().single { it.text == "роллы филадельфия" }
        assertEquals(sushi.id, answer.aboutEntryId)
    }
}
