package dev.dietapp.data.repo

import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Weight
import dev.dietapp.data.local.FoodHit
import dev.dietapp.data.local.FoodInput
import dev.dietapp.data.local.ImportResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The diary as the UI sees it: always answers from the local database, never waits for the network. */
interface DiaryRepository {
    fun observeDay(day: LocalDate): Flow<DayContent>
    fun observeWeights(): Flow<List<Weight>>
    fun observeDaySummaries(): Flow<List<DaySummary>>
    fun observeProfile(): Flow<Profile>

    /** Ticks each time the server has turned a message into entries. */
    val confirmations: Flow<Unit>

    /**
     * Queue a message (text and/or a compressed JPEG). [day] is the day being viewed; the entry time is
     * [now]'s clock time on that day. If a clarifying question is open, the message is sent as its answer.
     */
    suspend fun sendMessage(text: String?, image: ByteArray?, day: LocalDate, now: ZonedDateTime, source: MessageSource)

    suspend fun updateEntry(id: String, grams: Double?, name: String?)
    suspend fun deleteEntry(id: String)
    suspend fun addWeight(day: LocalDate, kg: Double, now: Instant)
    suspend fun deleteWeight(id: String)
    suspend fun dismissNote(id: Long)

    /**
     * The user picked (or entered) values for a food the model proposed: the food goes into their base and the entry
     * the proposal was about gets its numbers and is recorded. The question and the answer stay with the entry.
     */
    suspend fun acceptProposal(noteId: Long, choice: dev.dietapp.data.domain.FoodChoice): Result<Unit>

    /** "пропустить": nothing goes into the base, the entry stays without numbers; the answer stays with the entry. */
    suspend fun skipProposal(noteId: Long)

    /** The user does not want to answer the app's question: it is closed, the next message is not taken as its answer. */
    suspend fun skipQuestion(noteId: Long)

    /**
     * The answer to "Удалить «X» из базы?" (or "Изменить…"): the agent's change to the food base is made ([apply]) or
     * dropped. Either way the question is closed and a line under the user's message says what happened.
     */
    suspend fun answerBaseChange(noteId: Long, apply: Boolean): Result<Unit>

    /** The answer to "Записать?": entries waiting for the user are counted from now on ([record]), or taken back. */
    suspend fun recordPending(entryIds: List<String>, record: Boolean)
    suspend fun discardOutbox(id: String)
    suspend fun retryOutbox(id: String)

    /** Push local changes now (and optionally pull the server's). Fire and forget. */
    fun requestSync(pull: Boolean = false)
}

/** The diary as a file (see HistoryExport). Works the same with a server and without: it reads the phone's own copy. */
interface ExportRepository {
    /** [period] ends today. Fails with code "empty" when there is nothing in it. */
    suspend fun export(format: dev.dietapp.data.export.ExportFormat, period: dev.dietapp.data.export.ExportPeriod): Result<dev.dietapp.data.export.ExportFile>
}

interface AuthRepository {
    val loggedIn: StateFlow<Boolean>
    suspend fun requestCode(email: String): Result<Unit>

    /** Logs in and loads the profile. Switching to a different account wipes the previous account's local data. */
    suspend fun verify(email: String, code: String): Result<Unit>

    suspend fun setGoal(goal: Int): Result<Unit>

    /** Local mode: no account and no server. Everything is processed on this phone. */
    suspend fun useWithoutServer()

    /**
     * Forgets the session and ALL local data (in local mode that is the only copy of the diary), the saved
     * model key included, and goes back to the start.
     */
    suspend fun logout()
}

interface SpeechRepository {
    /** Server-side recognition, for devices where on-device recognition is not available. */
    suspend fun transcribe(audio: ByteArray, filename: String, mime: String, language: String?): Result<String>
}

/**
 * The user's own food base, as the "База продуктов" screen sees it. The model changes the same table from the chat.
 * Failures come back as [Result] with a message that can be shown as is.
 */
interface FoodRepository {
    val foods: Flow<List<Food>>

    /** All sources, the user's own foods first (see FoodBase.search). */
    suspend fun search(query: String): List<FoodHit>

    /** Creates a food, or changes [id]. */
    suspend fun save(input: FoodInput, id: Long?): Result<Food>
    suspend fun delete(id: Long): Result<Unit>

    /** The whole base as a JSON file, for a person or a tool to read and edit. */
    suspend fun exportJson(): String
    suspend fun importJson(text: String): Result<ImportResult>
}

/** Starts a background attempt to push/pull. Implemented with WorkManager; faked in tests. */
interface SyncTrigger {
    fun requestSync(pull: Boolean = false)
}
