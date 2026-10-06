package dev.dietapp.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.TestEnv
import dev.dietapp.data.db.NoteRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The first versions wrote "read without the model" under a message; they were wrong then and are noise now. */
@RunWith(AndroidJUnit4::class)
class LegacyNotesTest {
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    private suspend fun note(kind: String, text: String) =
        env.db.notes().insert(NoteRow(day = "2026-10-06", kind = kind, text = text, targetEntryId = null, resolved = true, createdAtMs = 1))

    private suspend fun texts() = env.db.notes().observeDay("2026-10-06").first().map { it.text }

    private suspend fun seed() {
        note("info", "Нет связи с моделью (UnknownHostException: Unable to resolve host). Разобрано без неё. Подробности — в журнале (О приложении → Журнал).")
        note("info", "Ключ DeepSeek не подошёл (HTTP 401: bad key). Разобрано без модели, проверь ключ в «О приложении → Агент». Подробности — в журнале.")
        note("info", "Could not reach the model. Read without it.")
        note("info", "The DeepSeek key was refused. Read without the model; check the key in About → Agent.")
        note("info", "Добавил в базу: казеиновый протеин — 360 ккал · Б 80 · Ж 1,5 · У 8 на 100 г (оценка)")
        note("info", "Не нашёл в сообщении еды.")
        note("question", "Какой суп?")
        note("error", "Не удалось.")
    }

    @Test fun `only the lines about reading without the model go, whatever the language`() = runTest {
        seed()
        assertEquals(4, env.db.notes().deleteLegacyFallback())
        assertEquals(
            listOf("Добавил в базу: казеиновый протеин — 360 ккал · Б 80 · Ж 1,5 · У 8 на 100 г (оценка)", "Не нашёл в сообщении еды.", "Какой суп?", "Не удалось."),
            texts(),
        )
    }

    @Test fun `it is done at start`() = runBlocking {
        seed()
        LegacyNotes(env.db, CoroutineScope(Dispatchers.Default)).cleanup.join()
        assertEquals(4, texts().size)
    }

    @Test fun `a clean diary is left alone`() = runTest {
        note("info", "Добавил в базу: гречка")
        assertEquals(0, env.db.notes().deleteLegacyFallback())
        assertEquals(listOf("Добавил в базу: гречка"), texts())
    }
}
