package dev.dietapp.data.repo

import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Weight
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

    /** Sets the weight of [day]: a weigh-in already made that day is corrected instead of another one being added. */
    suspend fun setWeightForDay(day: LocalDate, kg: Double, now: Instant)

    /** The small picture of a photo message, if this phone still has it. */
    suspend fun thumbnail(messageId: String): ByteArray?
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

/** What the person said about themselves, and the energy a day costs them worked out from it. */
data class BodyState(
    val sex: dev.dietapp.data.domain.Sex? = null,
    val age: Int? = null,
    val heightCm: Int? = null,
    val activity: dev.dietapp.data.domain.Activity? = null,
    /** The latest weigh-in of the diary. */
    val weightKg: Double? = null,
    /** Null until sex, age, height, weight and activity are all known. */
    val estimate: dev.dietapp.data.domain.EnergyEstimate? = null,
    /** The person's own correction of the goal, in kcal (minus to lose weight, plus to gain). */
    val adjustment: Int = 0,
    /** What a day costs plus the correction: the calorie goal of the diary. Null while the estimate is. */
    val goal: Int? = null,
    /** The correction would have taken the goal below the lowest the app allows, so the goal is held there. */
    val goalLimited: Boolean = false,
)

interface BodyRepository {
    val state: Flow<BodyState>

    /** The first-run questions were answered or skipped: they are not asked again. */
    val asked: Flow<Boolean>

    fun setSex(sex: dev.dietapp.data.domain.Sex)
    fun setAge(age: Int)
    fun setHeight(cm: Int)
    fun setActivity(activity: dev.dietapp.data.domain.Activity)

    /** Moves the goal away from what a day costs, up or down. */
    fun setAdjustment(kcal: Int)

    /** Becomes a weigh-in of today, so the diary and this page agree. */
    suspend fun setWeight(kg: Double)
    fun finishOnboarding()
}

interface AuthRepository {
    val loggedIn: StateFlow<Boolean>
    suspend fun requestCode(email: String): Result<Unit>

    /** Logs in and loads the profile. Switching to a different account wipes the previous account's local data. */
    suspend fun verify(email: String, code: String): Result<Unit>

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
 * The user's own food base (the only one there is), as the "База продуктов" screen sees it. The agent changes the same table from the chat.
 * Failures come back as [Result] with a message that can be shown as is.
 */
interface FoodRepository {
    val foods: Flow<List<Food>>

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
