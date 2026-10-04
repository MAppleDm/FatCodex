package dev.dietapp.data.repo

import androidx.room.withTransaction
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.MessageRow
import dev.dietapp.data.db.NoteRow
import dev.dietapp.data.db.OutboxRow
import dev.dietapp.data.db.WeightRow
import dev.dietapp.data.db.toDomain
import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.NutrientMath
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Weight
import dev.dietapp.data.domain.FoodChoice
import dev.dietapp.data.local.Author
import dev.dietapp.data.local.BaseChangeCodec
import dev.dietapp.data.domain.Lang.t
import dev.dietapp.data.local.FoodBase
import dev.dietapp.data.local.FoodInput
import dev.dietapp.data.local.FoodTools
import dev.dietapp.data.local.LocalEngine
import dev.dietapp.data.net.AppError
import dev.dietapp.data.local.ModeStore
import dev.dietapp.data.net.SessionStore
import dev.dietapp.data.sync.OutboxFiles
import dev.dietapp.data.sync.SyncEngine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@Singleton
class DiaryRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val files: OutboxFiles,
    private val session: SessionStore,
    private val sync: SyncTrigger,
    private val mode: ModeStore,
    engine: SyncEngine,
    localEngine: LocalEngine,
    private val clock: Clock,
    private val foods: FoodBase,
) : DiaryRepository {

    /** In local mode nothing is ever sent to a server: edits are final at once and never marked unsent. */
    private val unsent: Boolean get() = !mode.isLocal

    override val confirmations: Flow<Unit> = merge(engine.confirmations, localEngine.confirmations)

    override fun observeDay(day: LocalDate): Flow<DayContent> {
        val key = day.toString()
        return combine(
            db.entries().observeDay(key),
            db.outbox().observeAll(),
            db.notes().observeDay(key),
            db.weights().observeAll(),
            db.messages().observeDay(key),
        ) { entries, outbox, notes, weights, messages ->
            DayContent(
                entries = entries.map { it.toDomain() },
                outbox = outbox.filter { it.day == key }.map { it.toDomain() },
                notes = notes.map { it.toDomain() },
                weights = weights.filter { it.day == key }.map { it.toDomain() },
                messages = messages.map { it.toDomain() },
            )
        }
    }

    override fun observeWeights(): Flow<List<Weight>> = db.weights().observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeDaySummaries(): Flow<List<DaySummary>> =
        db.entries().observeDaySummaries().map { rows -> rows.map { it.toDomain() } }

    override fun observeProfile(): Flow<Profile> = db.profile().observe().map { it.toDomain(session.email) }

    override suspend fun sendMessage(text: String?, image: ByteArray?, day: LocalDate, now: ZonedDateTime, source: MessageSource) {
        require(!text.isNullOrBlank() || image != null) { "nothing to send" }
        val id = UUID.randomUUID().toString()
        val question = db.notes().latestOpenQuestion()
        if (image != null) files.write(id, image)
        // viewing an earlier day: log it at the current time of day on that day (LocalDate is a TemporalAdjuster)
        val eatenAt = now.with(day).toOffsetDateTime()
        val kind = when (if (image != null) MessageSource.Photo else source) {
            MessageSource.Text -> "text"
            MessageSource.Voice -> "voice"
            MessageSource.Photo -> "photo"
        }
        val body = text?.trim()?.takeIf { it.isNotEmpty() }
        db.withTransaction {
            // the message stays in the feed after it has been processed; the outbox row is its delivery state
            db.messages().upsert(
                MessageRow(id = id, day = day.toString(), text = body, hasImage = image != null, source = kind,
                    atMs = eatenAt.toInstant().toEpochMilli(), createdAtMs = clock.millis(), aboutEntryId = question?.targetEntryId),
            )
            db.outbox().upsert(
                OutboxRow(
                    id = id,
                    text = body,
                    hasImage = image != null,
                    imageMime = "image/jpeg",
                    day = day.toString(),
                    eatenAt = eatenAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    source = kind,
                    pendingQuestion = question?.text,
                    pendingTargetId = question?.targetEntryId,
                    state = "queued",
                    error = null,
                    attempts = 0,
                    createdAtMs = clock.millis(),
                ),
            )
            if (question != null) db.notes().resolve(question.id)
        }
        sync.requestSync()
    }

    override suspend fun updateEntry(id: String, grams: Double?, name: String?) {
        val row = db.entries().get(id) ?: return
        var next = row
        if (grams != null && grams > 0 && grams != row.grams) {
            val scaled = NutrientMath.rescale(row.toDomain(), grams)
            next = next.copy(
                grams = grams, kcal = scaled.kcal, protein = scaled.protein, fat = scaled.fat, carbs = scaled.carbs,
                // the user has just confirmed the amount by hand: mirrors what the server does
                status = if (row.status == EntryStatus.Uncertain.toWire()) EntryStatus.Ok.toWire() else row.status,
                confidence = if (row.status == EntryStatus.Uncertain.toWire()) 1.0 else row.confidence,
            )
        }
        val newName = name?.trim()
        if (!newName.isNullOrEmpty() && newName != row.name) next = next.copy(name = newName)
        if (next == row) return
        db.entries().upsert(next.copy(updatedAtMs = clock.millis(), dirty = unsent))
        if (unsent) sync.requestSync()
    }

    override suspend fun deleteEntry(id: String) {
        db.entries().markDeleted(id, dirty = unsent)
        db.notes().resolveForEntry(id)
        if (unsent) sync.requestSync()
    }

    override suspend fun addWeight(day: LocalDate, kg: Double, now: Instant) {
        db.weights().upsert(
            WeightRow(id = UUID.randomUUID().toString(), day = day.toString(), kg = kg, updatedAtMs = now.toEpochMilli(),
                deleted = false, dirty = unsent),
        )
        if (unsent) sync.requestSync()
    }

    override suspend fun deleteWeight(id: String) {
        db.weights().markDeleted(id, dirty = unsent)
        if (unsent) sync.requestSync()
    }

    override suspend fun dismissNote(id: Long) = db.notes().delete(id)

    override suspend fun acceptProposal(noteId: Long, choice: FoodChoice): Result<Unit> = guarded {
        val note = db.notes().get(noteId)?.takeIf { !it.resolved }
            ?: throw AppError(t("Это предложение уже неактуально.", "This question has already been answered."), "stale")
        val food = foods.save(
            FoodInput(
                name = choice.name,
                per100 = choice.per100,
                estimated = choice.estimated,
                note = listOfNotNull(choice.source, choice.note).joinToString(". ").ifEmpty { null },
                origin = when {
                    choice.url != null -> "web"
                    choice.estimated -> "model"
                    else -> "user"
                },
                url = choice.url,
            ),
            author = Author.User,
        )
        var now = clock.millis()
        db.withTransaction {
            // the pick, short: the full values follow in the "Добавил в базу" line; both stay with the entry
            answer(note, listOfNotNull(choice.name, choice.url?.let(::hostOf) ?: choice.source).joinToString(" · "), now++)
            note.targetEntryId?.let { db.entries().get(it) }?.takeIf { !it.deleted }?.let { e ->
                val p = food.per100
                db.entries().upsert(
                    e.copy(
                        kcal100 = p.kcal, protein100 = p.protein, fat100 = p.fat, carbs100 = p.carbs,
                        kcal = NutrientMath.scale(p.kcal, e.grams), protein = NutrientMath.scale(p.protein, e.grams),
                        fat = NutrientMath.scale(p.fat, e.grams), carbs = NutrientMath.scale(p.carbs, e.grams),
                        foodName = food.name, status = if (food.estimated) "uncertain" else "ok",
                        // picking the values is the user's decision about this food: it is recorded
                        pending = false,
                        updatedAtMs = now, dirty = unsent,
                    ),
                )
            }
            db.notes().resolve(noteId)
            db.notes().insert(
                NoteRow(day = note.day, kind = "info", text = t("Добавил в базу: ", "Added to the base: ") + FoodTools.describe(food),
                    targetEntryId = note.targetEntryId, resolved = true, createdAtMs = now, messageId = note.messageId),
            )
        }
        if (unsent) sync.requestSync()
    }

    /** "пропустить": the entry stays as it was, without numbers; it still waits for "записать" or "не записывать". */
    override suspend fun skipProposal(noteId: Long) {
        val note = db.notes().get(noteId)?.takeIf { !it.resolved } ?: return
        db.withTransaction {
            db.notes().resolve(noteId)
            answer(note, t("пропустить", "skip"), clock.millis())
        }
    }

    override suspend fun skipQuestion(noteId: Long) = db.notes().resolve(noteId)

    override suspend fun answerBaseChange(noteId: Long, apply: Boolean): Result<Unit> = guarded {
        val note = db.notes().get(noteId)?.takeIf { !it.resolved && it.kind == "base_change" }
            ?: throw AppError(t("Этот вопрос уже неактуален.", "This question has already been answered."), "stale")
        val change = BaseChangeCodec.decode(note.payload)
        val line = when {
            change == null -> t("Не удалось прочитать изменение, ничего не изменил.", "Could not read the change; nothing was changed.")
            !apply -> t("Оставил как есть: ", "Left as it is: ") + change.name
            // the food may be gone or renamed since the question was asked: say so instead of failing, the question is moot
            else -> try { FoodTools.applyChange(foods, change).line } catch (e: AppError) { e.message ?: change.name }
        }
        db.withTransaction {
            db.notes().resolve(noteId)
            db.notes().insert(
                NoteRow(day = note.day, kind = "info", text = line, targetEntryId = null, resolved = true,
                    createdAtMs = clock.millis(), messageId = note.messageId),
            )
        }
    }

    override suspend fun recordPending(entryIds: List<String>, record: Boolean) {
        if (entryIds.isEmpty()) return
        db.withTransaction {
            if (record) {
                db.entries().confirm(entryIds, clock.millis())
            } else {
                entryIds.forEach { id ->
                    db.entries().markDeleted(id, dirty = false)
                    db.notes().resolveForEntry(id)
                }
            }
        }
    }

    /** The user's side of a question the app asked, kept with the entry it was about. */
    private suspend fun answer(question: NoteRow, text: String, at: Long) {
        db.notes().insert(
            NoteRow(day = question.day, kind = "answer", text = text, targetEntryId = question.targetEntryId, resolved = true,
                createdAtMs = at, messageId = question.messageId),
        )
    }

    /** A message that could not be processed is taken back entirely: it was never turned into anything. */
    override suspend fun discardOutbox(id: String) {
        db.withTransaction {
            db.outbox().delete(id)
            db.messages().delete(id)
        }
        files.delete(id)
    }

    override suspend fun retryOutbox(id: String) {
        val row = db.outbox().get(id) ?: return
        db.outbox().upsert(row.copy(state = "queued", error = null))
        sync.requestSync()
    }

    override fun requestSync(pull: Boolean) = sync.requestSync(pull)

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
}
