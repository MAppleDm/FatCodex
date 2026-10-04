package dev.dietapp

import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Entry
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Message
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.OutboxMessage
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Source
import dev.dietapp.data.domain.Weight
import dev.dietapp.data.domain.Food
import dev.dietapp.data.local.Capabilities
import dev.dietapp.data.local.FoodHit
import dev.dietapp.data.local.FoodInput
import dev.dietapp.data.local.ImportResult
import dev.dietapp.data.repo.FoodRepository
import dev.dietapp.data.local.LocalSettings
import dev.dietapp.data.net.AppError
import dev.dietapp.data.repo.AuthRepository
import dev.dietapp.data.repo.DiaryRepository
import dev.dietapp.data.repo.SpeechRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Clock used by the tests: 2026-09-30 10:00 in UTC+3. */
val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T07:00:00Z"), ZoneOffset.ofHours(3))
val TODAY: LocalDate = LocalDate.parse("2026-09-30")

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
        // the app follows the phone's language until one is chosen, and Robolectric's phone is English: these tests read Russian
        dev.dietapp.data.domain.Lang.current = dev.dietapp.data.domain.Language.Ru
        dev.dietapp.coreui.CoreTexts.english = false
    }
    override fun finished(description: Description) = Dispatchers.resetMain()
}

data class Sent(val text: String?, val image: ByteArray?, val day: LocalDate, val now: ZonedDateTime, val source: MessageSource)

fun entry(
    id: String,
    name: String = "гречка",
    grams: Double = 200.0,
    kcal: Double? = 184.0,
    day: LocalDate = TODAY,
    at: String = "2026-09-30T05:00:00Z",
    status: EntryStatus = EntryStatus.Ok,
    protein: Double? = if (kcal != null) 6.8 else null,
    fat: Double? = if (kcal != null) 1.2 else null,
    carbs: Double? = if (kcal != null) 39.9 else null,
) = Entry(
    id = id, mealId = null, day = day, eatenAt = Instant.parse(at), position = 0, name = name, grams = grams,
    kcal = kcal, protein = protein, fat = fat, carbs = carbs,
    per100 = if (kcal != null) Per100(92.0, 3.38, 0.62, 19.94) else null, foodName = null, status = status, confidence = 0.9,
    source = Source.Text, updatedAt = Instant.parse(at), dirty = false,
)

/** An in-memory diary that records what the UI asks of it. Tests can play the server by editing the flows. */
class FakeDiary : DiaryRepository {
    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val outbox = MutableStateFlow<List<OutboxMessage>>(emptyList())
    val notes = MutableStateFlow<List<Note>>(emptyList())
    val weights = MutableStateFlow<List<Weight>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val profile = MutableStateFlow(Profile("me@example.com", 1900))
    val summaries = MutableStateFlow<List<DaySummary>>(emptyList())
    val confirmationTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val sent = mutableListOf<Sent>()
    val updates = mutableListOf<Triple<String, Double?, String?>>()
    val deletedEntries = mutableListOf<String>()
    val addedWeights = mutableListOf<Pair<LocalDate, Double>>()
    val deletedWeights = mutableListOf<String>()
    val dismissedNotes = mutableListOf<Long>()
    val discardedOutbox = mutableListOf<String>()
    val syncRequests = mutableListOf<Boolean>()

    /** Play the server: called after a message is queued. */
    var onSend: (Sent) -> Unit = {}

    override fun observeDay(day: LocalDate): Flow<DayContent> = combine(entries, outbox, notes, weights, messages) { e, o, n, w, m ->
        DayContent(
            e.filter { it.day == day }, o.filter { it.day == day }, n.filter { it.day == day }, w.filter { it.day == day },
            m.filter { it.day == day },
        )
    }

    override fun observeWeights(): Flow<List<Weight>> = weights
    override fun observeDaySummaries(): Flow<List<DaySummary>> = summaries
    override fun observeProfile(): Flow<Profile> = profile
    override val confirmations: Flow<Unit> = confirmationTicks

    override suspend fun sendMessage(text: String?, image: ByteArray?, day: LocalDate, now: ZonedDateTime, source: MessageSource) {
        Sent(text, image, day, now, source).also { sent += it; onSend(it) }
    }

    override suspend fun updateEntry(id: String, grams: Double?, name: String?) { updates += Triple(id, grams, name) }
    override suspend fun deleteEntry(id: String) { deletedEntries += id }
    override suspend fun addWeight(day: LocalDate, kg: Double, now: Instant) {
        addedWeights += day to kg
        weights.value = weights.value + Weight("w${addedWeights.size}", day, kg, now)
    }
    override suspend fun deleteWeight(id: String) { deletedWeights += id }
    override suspend fun dismissNote(id: Long) { dismissedNotes += id; notes.value = notes.value.filter { it.id != id } }

    val accepted = mutableListOf<Pair<Long, dev.dietapp.data.domain.FoodChoice>>()
    var acceptResult: Result<Unit> = Result.success(Unit)
    override suspend fun acceptProposal(noteId: Long, choice: dev.dietapp.data.domain.FoodChoice): Result<Unit> {
        accepted += noteId to choice
        if (acceptResult.isSuccess) notes.value = notes.value.map { if (it.id == noteId) it.copy(resolved = true) else it }
        return acceptResult
    }
    val skipped = mutableListOf<Long>()
    override suspend fun skipProposal(noteId: Long) {
        skipped += noteId
        notes.value = notes.value.map { if (it.id == noteId) it.copy(resolved = true) else it }
    }

    val skippedQuestions = mutableListOf<Long>()
    override suspend fun skipQuestion(noteId: Long) {
        skippedQuestions += noteId
        notes.value = notes.value.map { if (it.id == noteId) it.copy(resolved = true) else it }
    }

    val recorded = mutableListOf<Pair<List<String>, Boolean>>()
    override suspend fun recordPending(entryIds: List<String>, record: Boolean) {
        recorded += entryIds to record
        entries.value = entries.value.mapNotNull { e ->
            when {
                e.id !in entryIds -> e
                record -> e.copy(pending = false)
                else -> null
            }
        }
    }
    override suspend fun discardOutbox(id: String) { discardedOutbox += id }
    override suspend fun retryOutbox(id: String) = Unit
    override fun requestSync(pull: Boolean) { syncRequests += pull }
}

class FakeSpeech : SpeechRepository {
    var result: Result<String> = Result.success("две вареные яйца")
    val calls = mutableListOf<Triple<Int, String, String?>>()

    override suspend fun transcribe(audio: ByteArray, filename: String, mime: String, language: String?): Result<String> {
        calls += Triple(audio.size, mime, language)
        return result
    }
}

class FakeAuth : AuthRepository {
    private val _loggedIn = MutableStateFlow(false)
    override val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()
    val calls = mutableListOf<String>()
    var requestCodeResult: Result<Unit> = Result.success(Unit)
    var verifyResult: Result<Unit> = Result.success(Unit)
    var goalResult: Result<Unit> = Result.success(Unit)
    /** What a successful verify does to the world (log in, load the goal...). */
    var afterVerify: () -> Unit = { _loggedIn.value = true }
    var afterGoal: (Int) -> Unit = {}

    override suspend fun requestCode(email: String): Result<Unit> { calls += "code:$email"; return requestCodeResult }
    override suspend fun verify(email: String, code: String): Result<Unit> {
        calls += "verify:$email:$code"
        if (verifyResult.isSuccess) afterVerify()
        return verifyResult
    }
    override suspend fun setGoal(goal: Int): Result<Unit> {
        calls += "goal:$goal"
        if (goalResult.isSuccess) afterGoal(goal)
        return goalResult
    }
    override suspend fun useWithoutServer() { calls += "local"; _loggedIn.value = true; afterLocal() }
    override suspend fun logout() { calls += "logout"; _loggedIn.value = false }
    fun signIn() { _loggedIn.value = true }

    /** What choosing "без сервера" does to the world besides signing in. */
    var afterLocal: () -> Unit = {}
}

/** Local-mode settings the UI sees: flip [capabilities] to play "no server" / "model key set". */
class FakeLocalSettings(local: Boolean = false, modelKey: Boolean = false) : LocalSettings {
    val state = MutableStateFlow(Capabilities(local = local, modelKey = modelKey))
    override val capabilities: Flow<Capabilities> = state
    val saved = mutableListOf<String>()
    var cleared = 0

    override fun saveKey(key: String): Result<Unit> {
        if (key.length < 10) return Result.failure(AppError("Это не похоже на ключ DeepSeek.", "bad_key"))
        saved += key
        state.value = state.value.copy(modelKey = true)
        return Result.success(Unit)
    }

    override fun clearKey() { cleared++; state.value = state.value.copy(modelKey = false) }
    override fun setWebSearch(on: Boolean) { state.value = state.value.copy(webSearch = on) }
    override fun setRecordMode(mode: dev.dietapp.data.local.RecordMode) { state.value = state.value.copy(recordMode = mode) }
    override fun setLanguage(language: dev.dietapp.data.domain.Language) { state.value = state.value.copy(language = language) }
}

/** The user's food base in memory; [hits] is what a search returns from the built-in sources. */
class FakeFoods : FoodRepository {
    override val foods = MutableStateFlow<List<Food>>(emptyList())
    var hits: List<FoodHit> = emptyList()
    val saved = mutableListOf<Pair<FoodInput, Long?>>()
    var saveResult: Result<Unit> = Result.success(Unit)
    var imported: String? = null
    var importResult: Result<ImportResult> = Result.success(ImportResult(1, 0, emptyList()))
    private var nextId = 1L

    override suspend fun search(query: String): List<FoodHit> = hits

    override suspend fun save(input: FoodInput, id: Long?): Result<Food> {
        saved += input to id
        saveResult.exceptionOrNull()?.let { return Result.failure(it) }
        val food = Food(id ?: nextId++, input.name, input.aliases, input.per100, input.estimated, input.note, Instant.EPOCH)
        foods.value = foods.value.filter { it.id != food.id } + food
        return Result.success(food)
    }

    override suspend fun delete(id: Long): Result<Unit> {
        foods.value = foods.value.filter { it.id != id }
        return Result.success(Unit)
    }

    override suspend fun exportJson(): String = """{"format": "dietapp-foods", "foods": []}"""

    override suspend fun importJson(text: String): Result<ImportResult> {
        imported = text
        return importResult
    }
}

fun failure(message: String, code: String? = null) = Result.failure<Unit>(AppError(message, code))
