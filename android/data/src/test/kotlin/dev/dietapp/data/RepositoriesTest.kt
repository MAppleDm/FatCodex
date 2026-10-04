package dev.dietapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.dietapp.data.db.NoteRow
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.net.AppError
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val TODAY = LocalDate.parse("2026-09-30")
private val NOW = ZonedDateTime.of(2026, 9, 30, 8, 15, 7, 0, ZoneOffset.ofHours(3))
private const val E1 = "11111111-1111-4111-8111-111111111111"

@RunWith(AndroidJUnit4::class)
class DiaryRepositoryTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv().also { it.loginAs() } }
    @After fun tearDown() = env.close()

    @Test fun `sending queues a message and asks for a sync`() = runTest {
        env.diary.sendMessage("  гречка 200 г ", null, TODAY, NOW, MessageSource.Voice)
        val row = env.db.outbox().queued().single()
        assertEquals("гречка 200 г", row.text)
        assertEquals("voice", row.source)
        assertEquals("2026-09-30T08:15:07+03:00", row.eatenAt)
        assertEquals("2026-09-30", row.day)
        assertFalse(row.hasImage)
        assertEquals(listOf(false), env.trigger.requests)
    }

    @Test fun `viewing an earlier day logs the meal on that day at the current time of day`() = runTest {
        env.diary.sendMessage("вчера ужин", null, LocalDate.parse("2026-09-28"), NOW, MessageSource.Text)
        val row = env.db.outbox().queued().single()
        assertEquals("2026-09-28", row.day)
        assertEquals("2026-09-28T08:15:07+03:00", row.eatenAt)
    }

    @Test fun `a photo is stored on disk and always marked as a photo`() = runTest {
        env.diary.sendMessage(null, byteArrayOf(9, 9), TODAY, NOW, MessageSource.Text)
        val row = env.db.outbox().queued().single()
        assertTrue(row.hasImage)
        assertEquals("photo", row.source)
        assertEquals(listOf<Byte>(9, 9), env.files.read(row.id)!!.toList())
    }

    @Test fun `an empty message is refused`() = runTest {
        try {
            env.diary.sendMessage("   ", null, TODAY, NOW, MessageSource.Text)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(env.db.outbox().queued().isEmpty())
    }

    @Test fun `an open question is attached to the next message and closed`() = runTest {
        env.db.notes().insert(NoteRow(day = "2026-09-30", kind = "question", text = "Какой суп?", targetEntryId = E1,
            resolved = false, createdAtMs = 1))
        env.diary.sendMessage("борщ", null, TODAY, NOW, MessageSource.Text)
        val row = env.db.outbox().queued().single()
        assertEquals("Какой суп?", row.pendingQuestion)
        assertEquals(E1, row.pendingTargetId)
        assertNull(env.db.notes().latestOpenQuestion())
    }

    @Test fun `only the latest open question is used`() = runTest {
        env.db.notes().insert(NoteRow(day = "2026-09-30", kind = "question", text = "старый", targetEntryId = null, resolved = false, createdAtMs = 1))
        env.db.notes().insert(NoteRow(day = "2026-09-30", kind = "question", text = "новый", targetEntryId = null, resolved = false, createdAtMs = 2))
        env.diary.sendMessage("ответ", null, TODAY, NOW, MessageSource.Text)
        assertEquals("новый", env.db.outbox().queued().single().pendingQuestion)
    }

    @Test fun `editing grams rescales offline with the server's arithmetic and marks the row unsent`() = runTest {
        env.db.entries().upsert(entryRow(id = E1, grams = 200.0, status = "uncertain"))
        env.diary.updateEntry(E1, grams = 150.0, name = null)
        val row = env.db.entries().get(E1)!!
        assertEquals(150.0, row.grams, 0.0)
        assertEquals(138.0, row.kcal!!, 0.0)       // 92 * 1.5
        assertEquals(5.1, row.protein!!, 0.0)      // 3.38 * 1.5 = 5.07
        assertEquals(29.9, row.carbs!!, 0.0)       // 19.94 * 1.5 = 29.91
        assertEquals("ok", row.status)             // typing the amount confirms it
        assertEquals(1.0, row.confidence, 0.0)
        assertTrue(row.dirty)
        assertEquals(listOf(false), env.trigger.requests)
    }

    @Test fun `editing an entry that was not found keeps it without numbers`() = runTest {
        env.db.entries().upsert(entryRow(id = E1, status = "unmatched", matched = false))
        env.diary.updateEntry(E1, grams = 120.0, name = null)
        val row = env.db.entries().get(E1)!!
        assertEquals(120.0, row.grams, 0.0)
        assertNull(row.kcal)
        assertEquals("unmatched", row.status)
    }

    @Test fun `renaming marks the row unsent and keeps the old numbers until the server answers`() = runTest {
        env.db.entries().upsert(entryRow(id = E1))
        env.diary.updateEntry(E1, grams = null, name = "  рис  ")
        val row = env.db.entries().get(E1)!!
        assertEquals("рис", row.name)
        assertTrue(row.dirty)
        assertEquals(184.0, row.kcal!!, 1e-6)
    }

    @Test fun `a no-op edit changes nothing and does not sync`() = runTest {
        env.db.entries().upsert(entryRow(id = E1, grams = 200.0))
        env.diary.updateEntry(E1, grams = 200.0, name = "гречка")
        env.diary.updateEntry(E1, grams = null, name = "   ")
        env.diary.updateEntry(E1, grams = -5.0, name = null)
        assertFalse(env.db.entries().get(E1)!!.dirty)
        assertTrue(env.trigger.requests.isEmpty())
    }

    @Test fun `deleting hides the entry at once, queues the delete and closes questions about it`() = runTest {
        env.db.entries().upsert(entryRow(id = E1))
        env.db.notes().insert(NoteRow(day = "2026-09-30", kind = "question", text = "Сколько?", targetEntryId = E1,
            resolved = false, createdAtMs = 1))
        env.diary.deleteEntry(E1)
        env.diary.observeDay(TODAY).test {
            assertTrue(awaitItem().entries.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(env.db.entries().get(E1)!!.dirty)
        assertNull(env.db.notes().latestOpenQuestion())
    }

    @Test fun `the day view combines entries, waiting messages, notes and weights of that day only`() = runTest {
        env.db.entries().upsert(entryRow(id = E1))
        env.db.entries().upsert(entryRow(id = "other-day", day = "2026-09-29"))
        env.diary.sendMessage("ещё", null, TODAY, NOW, MessageSource.Text)
        env.diary.sendMessage("вчера", null, LocalDate.parse("2026-09-29"), NOW, MessageSource.Text)
        env.diary.addWeight(TODAY, 82.4, env.clock.instant())
        env.diary.addWeight(LocalDate.parse("2026-09-29"), 83.0, env.clock.instant())
        env.db.notes().insert(NoteRow(day = "2026-09-30", kind = "info", text = "заметка", targetEntryId = null, resolved = true, createdAtMs = 1))

        env.diary.observeDay(TODAY).test {
            val day = awaitItem()
            assertEquals(listOf(E1), day.entries.map { it.id })
            assertEquals(listOf("ещё"), day.outbox.map { it.text })
            assertEquals(listOf(82.4), day.weights.map { it.kg })
            assertEquals(listOf(NoteKind.Info), day.notes.map { it.kind })
            assertEquals(EntryStatus.Ok, day.entries.single().status)
            assertNotNull(day.entries.single().per100)
            cancelAndIgnoreRemainingEvents()
        }
        env.diary.observeWeights().test {
            assertEquals(listOf(83.0, 82.4), awaitItem().map { it.kg })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `history lists totals per day, newest first, ignoring deleted and unmatched entries`() = runTest {
        env.db.entries().upsert(entryRow(id = "a", day = "2026-09-29", grams = 100.0))
        env.db.entries().upsert(entryRow(id = "b", day = "2026-09-30", grams = 100.0))
        env.db.entries().upsert(entryRow(id = "c", day = "2026-09-30", grams = 100.0))
        env.db.entries().upsert(entryRow(id = "d", day = "2026-09-30", grams = 100.0, deleted = true))
        env.db.entries().upsert(entryRow(id = "e", day = "2026-09-30", matched = false, status = "unmatched"))
        env.diary.observeDaySummaries().test {
            val days = awaitItem()
            assertEquals(listOf("2026-09-30", "2026-09-29"), days.map { it.day.toString() })
            assertEquals(184.0, days[0].totals.kcal, 1e-6)
            assertEquals(92.0, days[1].totals.kcal, 1e-6)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `discarding a message removes its photo too`() = runTest {
        env.diary.sendMessage(null, byteArrayOf(1), TODAY, NOW, MessageSource.Photo)
        val id = env.db.outbox().queued().single().id
        env.diary.discardOutbox(id)
        assertTrue(env.db.outbox().queued().isEmpty())
        assertNull(env.files.read(id))
    }

    @Test fun `dismissing a note removes it`() = runTest {
        val id = env.db.notes().insert(NoteRow(day = "2026-09-30", kind = "info", text = "x", targetEntryId = null, resolved = true, createdAtMs = 1))
        env.diary.dismissNote(id)
        env.diary.observeDay(TODAY).test {
            assertTrue(awaitItem().notes.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class AuthRepositoryTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() = env.close()

    private fun tokenJson() = """{"access_token": "jwt-abc", "token_type": "bearer", "expires_in": 2592000}"""

    @Test fun `requesting a code posts the trimmed address`() = runTest {
        env.enqueueNoContent()
        assertTrue(env.auth.requestCode("  me@example.com ").isSuccess)
        val req = env.server.takeRequest()
        assertEquals("/v1/auth/request-code", req.path)
        assertEquals("""{"email":"me@example.com"}""", req.body.readUtf8())
        assertNull("no token before login", req.getHeader("Authorization"))
    }

    @Test fun `server messages reach the caller unchanged`() = runTest {
        env.enqueue(429, errorJson("rate_limited", "Код уже отправлен. Подожди минуту и запроси снова."))
        val error = env.auth.requestCode("me@example.com").exceptionOrNull() as AppError
        assertEquals("Код уже отправлен. Подожди минуту и запроси снова.", error.message)
        assertEquals("rate_limited", error.code)
        assertTrue(error.retryable)
    }

    @Test fun `offline is a readable error`() = runTest {
        env.server.shutdown()
        val error = env.auth.requestCode("me@example.com").exceptionOrNull() as AppError
        assertEquals("Нет связи с сервером.", error.message)
        assertEquals("offline", error.code)
    }

    @Test fun `verify logs in, loads the profile and pulls the diary`() = runTest {
        env.enqueue(200, tokenJson())
        env.enqueue(200, """{"email": "me@example.com", "calorie_goal": 1900}""")
        assertTrue(env.auth.verify(" Me@Example.com ", "123456").isSuccess)

        assertTrue(env.session.loggedIn.value)
        assertEquals("jwt-abc", env.session.token)
        assertEquals("/v1/auth/verify", env.server.takeRequest().path)
        val me = env.server.takeRequest()
        assertEquals("/v1/me", me.path)
        assertEquals("Bearer jwt-abc", me.getHeader("Authorization"))
        assertEquals(1900, env.db.profile().get()!!.calorieGoal)
        assertEquals(listOf(true), env.trigger.requests)
    }

    @Test fun `a wrong code leaves the user logged out`() = runTest {
        env.enqueue(400, errorJson("invalid_code", "Код неверный или устарел. Запроси новый."))
        val error = env.auth.verify("me@example.com", "000000").exceptionOrNull() as AppError
        assertEquals("invalid_code", error.code)
        assertFalse(env.session.loggedIn.value)
    }

    @Test fun `logging in as someone else wipes the previous account's data`() = runTest {
        env.db.profile().upsert(dev.dietapp.data.db.ProfileRow(email = "old@example.com", calorieGoal = 2000))
        env.db.entries().upsert(entryRow())
        env.enqueue(200, tokenJson())
        env.enqueue(200, """{"email": "new@example.com", "calorie_goal": null}""")
        env.auth.verify("new@example.com", "123456")
        assertNull(env.db.entries().get(E1))
        assertNull(env.db.profile().get()!!.calorieGoal)
    }

    @Test fun `logging in again as the same person keeps unsent work`() = runTest {
        env.db.profile().upsert(dev.dietapp.data.db.ProfileRow(email = "me@example.com", calorieGoal = 2000))
        env.db.entries().upsert(entryRow(dirty = true))
        env.enqueue(200, tokenJson())
        env.enqueue(200, """{"email": "me@example.com", "calorie_goal": 2000}""")
        env.auth.verify("me@example.com", "123456")
        assertTrue(env.db.entries().get(E1)!!.dirty)
    }

    @Test fun `goals below the floor are refused without asking the server`() = runTest {
        val error = env.auth.setGoal(1100).exceptionOrNull() as AppError
        assertEquals("goal_too_low", error.code)
        assertTrue(error.message!!.contains("1 200"))
        assertEquals(0, env.server.requestCount)
        assertEquals("goal_too_high", (env.auth.setGoal(6001).exceptionOrNull() as AppError).code)
    }

    @Test fun `a valid goal is saved on the server and locally`() = runTest {
        env.loginAs()
        env.enqueue(200, """{"email": "me@example.com", "calorie_goal": 1900}""")
        assertTrue(env.auth.setGoal(1900).isSuccess)
        val req = env.server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("""{"calorie_goal":1900}""", req.body.readUtf8())
        assertEquals(1900, env.db.profile().get()!!.calorieGoal)
    }

    @Test fun `logout forgets the session and all local data`() = runTest {
        env.loginAs()
        env.db.entries().upsert(entryRow())
        env.diary.sendMessage("x", byteArrayOf(1), TODAY, NOW, MessageSource.Text)
        val outboxId = env.db.outbox().queued().single().id
        env.auth.logout()
        assertFalse(env.session.loggedIn.value)
        assertNull(env.db.entries().get(E1))
        assertTrue(env.db.outbox().queued().isEmpty())
        assertNull(env.files.read(outboxId))
    }
}
