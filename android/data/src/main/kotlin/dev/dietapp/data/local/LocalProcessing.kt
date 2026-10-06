package dev.dietapp.data.local

import androidx.room.withTransaction
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.EntryRow
import dev.dietapp.data.db.NoteRow
import dev.dietapp.data.db.OutboxRow
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Lang
import dev.dietapp.data.domain.Lang.t
import dev.dietapp.data.domain.NutrientMath
import dev.dietapp.data.local.parse.Action
import dev.dietapp.data.local.parse.ContextEntry
import dev.dietapp.data.local.parse.MessageParser
import dev.dietapp.data.local.parse.ModelFailure
import dev.dietapp.data.local.parse.ModelTrace
import dev.dietapp.data.local.parse.ParseContext
import dev.dietapp.data.local.parse.ParseRequest
import dev.dietapp.data.local.parse.ParseResult
import dev.dietapp.data.local.parse.ParsedItem
import dev.dietapp.data.local.parse.PendingQuestion
import dev.dietapp.data.net.AppError
import dev.dietapp.data.sync.OutboxFiles
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

val NEEDS_KEY get() = t("Нужен ключ DeepSeek: добавь его в «О приложении → Агент».", "A DeepSeek key is needed: add it in About → Agent.")
val NO_FOOD_NOTE get() = t("Не нашёл в сообщении еды.", "Found no food in the message.")
val JOURNAL_HINT get() = t("Подробности — в журнале (О приложении → Журнал).", "Details are in the journal (About → Journal).")

/**
 * Why the agent could not read a message, in words for the person. The message stays in the chat, with "Повторить".
 * A connection that did not work (no network, a timeout, a broken answer, a busy server) is one short line: the person can
 * do nothing about it but try again, and the exact exception is in the journal. A refused key says what to do about it.
 */
fun modelError(e: ModelFailure): AppError = when (e) {
    is ModelFailure.Auth -> AppError(
        t("Ключ DeepSeek не подошёл (${e.detail}). Проверь его в «О приложении → Агент».", "The DeepSeek key was refused (${e.detail}). Check it in About → Agent."),
        "bad_key", retryable = false,
    )
    is ModelFailure.Unavailable -> AppError(
        t("Нет связи с агентом.", "No connection to the agent."),
        "offline", retryable = true,
    )
    is ModelFailure.BadOutput, is ModelFailure.Rejected -> AppError(
        t("Агент ответил непонятно.", "The agent's answer made no sense.") + " $JOURNAL_HINT",
        "bad_output", retryable = e.retryable,
    )
}

/**
 * Hands a message to the agent. There is no other way to read one: no key, or an agent that cannot be reached, is an
 * error the person sees under the message, with a way to try again. Nothing is guessed in its place.
 */
class ParserChooser(
    private val model: MessageParser,
    private val hasKey: () -> Boolean,
    private val trace: ModelTrace = ModelTrace.None,
    /** However the model is doing (slow answers, retries, many tool rounds), a message never waits longer than this. */
    private val budgetMs: Long = 75_000,
) {
    suspend fun parse(request: ParseRequest): ParseResult {
        if (!hasKey()) throw AppError(NEEDS_KEY, "no_key")
        val failure = try {
            val parsed = withTimeoutOrNull(budgetMs) { model.parse(request) }
            if (parsed != null) return parsed
            trace.error("model", "no result within ${budgetMs / 1000} s, giving up on the model for this message")
            ModelFailure.Unavailable(t("нет ответа за ${budgetMs / 1000} с", "no answer in ${budgetMs / 1000} s"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelFailure) {
            e
        } catch (e: Exception) {
            // a bug on our side, not the model's: never lose the message over it, and leave a trace to fix it
            trace.error("model", "unexpected error while talking to the model", e)
            ModelFailure.BadOutput("${e.javaClass.simpleName}: ${e.message}")
        }
        trace.error("model", "the model did not help (${failure.kind}: ${failure.detail}); the message stays, to be tried again")
        throw modelError(failure)
    }
}

/**
 * Local-mode counterpart of the gateway's `MessageProcessor`: one queued message in, diary changes out, all on the
 * phone. Same rules: the agent only names foods and grams, the numbers come from the user's own food database, anything
 * not found is kept without numbers and turned into one question.
 */
class LocalMessageProcessor(
    private val db: AppDatabase,
    private val parsers: ParserChooser,
    private val resolver: FoodResolver,
    private val files: OutboxFiles,
    private val clock: Clock,
    private val threshold: Double = 0.6,
) {
    suspend fun process(row: OutboxRow) {
        val now = clock.millis()
        val eaten = OffsetDateTime.parse(row.eatenAt)
        val known = loadKnown(row)
        val image = if (row.hasImage) {
            val bytes = files.read(row.id) ?: throw AppError(t("Фото не найдено на устройстве.", "The photo is no longer on the phone."), "photo_missing")
            Base64.getEncoder().encodeToString(bytes)
        } else {
            null
        }
        val context = ParseContext(
            entries = known.values.map { ContextEntry(it.id, it.name, it.grams) },
            frequent = frequentMeals(row.day),
            pending = row.pendingQuestion?.let { PendingQuestion(it, row.pendingTargetId) },
            localTime = "%02d:%02d".format(eaten.hour, eaten.minute),
            language = Lang.current.code,
        )
        val parsed = parsers.parse(ParseRequest(row.text, image, row.imageMime, context))

        // Looking foods up is done before the transaction.
        // A food the model proposed values for waits for the user's pick: a generic lookup ("frankfurter" for branded
        // sausages) would give it numbers that are not the product's, and the pick would have nothing to fill in.
        val proposedKeys = parsed.proposals.map { FoodBase.keyOf(it.forItem) }
        fun proposedFor(item: ParsedItem): Boolean {
            val key = FoodBase.keyOf(item.name)
            return key.isNotEmpty() && item.foodId == null && proposedKeys.any { it.isNotEmpty() && (it == key || it.contains(key) || key.contains(it)) }
        }
        val resolved = parsed.items.map { item ->
            val target = if (item.action == Action.Add) null else known[item.targetId]
            if (item.action == Action.Remove || (item.action != Action.Add && target == null)) null
            else if (item.action == Action.Add && proposedFor(item)) null
            else if (needsLookup(item, target)) resolver.resolve(item.name, item.foodId) else null
        }

        db.withTransaction {
            val touched = ArrayList<Pair<Int, EntryRow>>()
            // what the user should see under their message, besides new entries
            val replies = ArrayList<Pair<String, String?>>() // the line, and the entry it is about
            parsed.items.forEachIndexed { idx, item ->
                val target = if (item.action == Action.Add) null else known[item.targetId]
                if (item.action != Action.Add && target == null) return@forEachIndexed // never touch what the parser was not shown
                when (item.action) {
                    Action.Remove -> {
                        db.entries().markDeleted(target!!.id, dirty = false)
                        db.notes().resolveForEntry(target.id)
                        replies += t("Убрал: ", "Removed: ") + target.name to null
                    }
                    Action.Update -> {
                        val updated = if (needsLookup(item, target)) {
                            withNutrition(target!!.copy(name = item.name), item, resolved[idx])
                        } else {
                            rescale(target!!, item)
                        }
                        val saved = updated.copy(updatedAtMs = now, dirty = false)
                        db.entries().upsert(saved)
                        touched += idx to saved
                        replies += t("Исправил: ", "Changed: ") + describe(saved) to saved.id
                    }
                    Action.Add -> {
                        val fresh = EntryRow(
                            id = UUID.nameUUIDFromBytes("${row.id}:$idx".toByteArray()).toString(),
                            mealId = row.id, day = row.day, eatenAtMs = eaten.toInstant().toEpochMilli(), position = idx,
                            name = item.name, grams = item.grams, kcal = null, protein = null, fat = null, carbs = null,
                            kcal100 = null, protein100 = null, fat100 = null, carbs100 = null, foodName = null,
                            status = "unmatched", confidence = item.confidence, source = row.source,
                            updatedAtMs = now, deleted = false, dirty = false,
                        )
                        val filled = withNutrition(fresh, item, resolved[idx])
                        // Food with known, stable values goes in at once; the agent's doubt, "~" values or no values at
                        // all wait for the user.
                        val saved = filled.copy(pending = item.ask || filled.status != EntryStatus.Ok.toWire())
                        db.entries().upsert(saved)
                        touched += idx to saved
                    }
                }
            }

            // a proposal fills in an entry that has no numbers yet: the entry with the same name, else the first such entry
            val proposed = parsed.proposals.map { p ->
                val key = FoodBase.keyOf(p.forItem)
                val open = touched.map { it.second }.filter { it.kcal100 == null }
                p to (open.firstOrNull { FoodBase.keyOf(it.name) == key }
                    ?: open.firstOrNull { FoodBase.keyOf(it.name).let { n -> n.contains(key) || key.contains(n) } } ?: open.firstOrNull())
            }
            val covered = proposed.mapNotNull { it.second?.id }.toSet()

            // at most one question: the parser's own, else one about something we could not find (and nobody proposed)
            var question = parsed.clarifyQuestion
            var target: String? = null
            if (question != null) {
                target = touched.firstOrNull { (idx, _) -> parsed.items[idx].clarifyQuestion != null }?.second?.id
            } else {
                touched.firstOrNull { it.second.status == "unmatched" && it.second.id !in covered }?.second?.let {
                    question = notFoundQuestion(it.name, row.pendingQuestion)
                    target = it.id
                }
            }

            // under the message, in this order: food base changes and replies, what was corrected, proposals, changes to ask about, the question
            var at = now
            suspend fun note(kind: String, text: String, targetId: String? = null, open: Boolean = false, payload: String? = null) {
                db.notes().insert(
                    NoteRow(day = row.day, kind = kind, text = text, targetEntryId = targetId, resolved = !open,
                        createdAtMs = at++, messageId = row.id, payload = payload),
                )
            }
            parsed.notes.forEach { note("info", it) }
            replies.forEach { (text, entry) -> note("info", text, entry) }
            proposed.forEach { (p, entry) ->
                val text = p.question ?: t("Нашёл «${p.forItem}». Какие значения занести в базу?", "Found “${p.forItem}”. Which values go into your base?")
                note("proposal", text, entry?.id, open = true, payload = ProposalCodec.encode(p.choices))
            }
            // a change to the food base the agent may not make without a yes: asked above the input, applied on the answer
            parsed.changes.forEach { c -> note("base_change", c.question, open = true, payload = BaseChangeCodec.encode(c)) }
            val q = question
            if (q != null) {
                note("question", q, target, open = true)
            } else if (touched.isEmpty() && replies.isEmpty() && parsed.notes.isEmpty() && proposed.isEmpty() && parsed.changes.isEmpty()) {
                note("info", NO_FOOD_NOTE)
            }
            db.outbox().delete(row.id)
        }
    }

    /** The same question twice in a row helps nobody: the second time, say what can be done about it. */
    private fun notFoundQuestion(name: String, previous: String?): String {
        val first = t("Не нашёл «$name» в базе продуктов. Что это точнее?", "Could not find “$name” in the food base. What is it, more exactly?")
        val again = t("Всё ещё не нашёл «$name»", "Still could not find “$name”")
        return if (previous == first || previous?.startsWith(again) == true) {
            again + t(". Напиши, сколько в нём ккал и БЖУ на 100 г, или добавь его в базу продуктов в настройках.",
                ". Write its kcal, protein, fat and carbs per 100 g, or add it to the food base in settings.")
        } else {
            first
        }
    }

    /** "гречка — 200 г, 184 ккал" for the line under a correction. */
    private fun describe(e: EntryRow): String {
        val grams = if (e.grams == Math.rint(e.grams)) e.grams.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, e.grams)
        val kcal = e.kcal?.let { (if (e.status == "uncertain") "~" else "") + Math.round(it) + t(" ккал", " kcal") }
        return listOfNotNull("${e.name} — $grams " + t("г", "g"), kcal).joinToString(", ")
    }

    private fun sameName(a: String, b: String) = a.trim().lowercase().split(Regex("\\s+")) == b.trim().lowercase().split(Regex("\\s+"))

    /** Rescaling needs no lookup: same food, only the amount changed (the common "масла было меньше"). */
    private fun needsLookup(item: ParsedItem, target: EntryRow?): Boolean = when (item.action) {
        Action.Add -> true
        Action.Remove -> false
        Action.Update -> !(target != null && target.kcal100 != null && sameName(item.name, target.name))
    }

    private fun rescale(target: EntryRow, item: ParsedItem): EntryRow {
        var row = target.copy(grams = item.grams, confidence = item.confidence)
        if (row.kcal100 != null) {
            row = row.copy(
                kcal = NutrientMath.scale(row.kcal100!!, item.grams), protein = NutrientMath.scale(row.protein100!!, item.grams),
                fat = NutrientMath.scale(row.fat100!!, item.grams), carbs = NutrientMath.scale(row.carbs100!!, item.grams),
            )
        }
        // a clear amount confirms the entry
        return row.copy(status = if (item.confidence >= threshold) "ok" else "uncertain")
    }

    private fun withNutrition(row: EntryRow, item: ParsedItem, found: Resolved?): EntryRow {
        val base = row.copy(grams = item.grams, confidence = item.confidence, name = item.name)
        if (found == null) {
            return base.copy(
                kcal = null, protein = null, fat = null, carbs = null, kcal100 = null, protein100 = null, fat100 = null, carbs100 = null,
                foodName = null, status = EntryStatus.Unmatched.toWire(),
            )
        }
        val p = found.per100
        return base.copy(
            kcal100 = p.kcal, protein100 = p.protein, fat100 = p.fat, carbs100 = p.carbs,
            kcal = NutrientMath.scale(p.kcal, item.grams), protein = NutrientMath.scale(p.protein, item.grams),
            fat = NutrientMath.scale(p.fat, item.grams), carbs = NutrientMath.scale(p.carbs, item.grams),
            foodName = found.foodName,
            // an estimated food and low confidence both show as "~"
            status = if (found.approximate || item.confidence < threshold) "uncertain" else "ok",
        )
    }

    private suspend fun loadKnown(row: OutboxRow): Map<String, EntryRow> {
        val known = LinkedHashMap<String, EntryRow>()
        db.entries().forDay(row.day).forEach { known[it.id] = it }
        row.pendingTargetId?.let { id ->
            if (id !in known) db.entries().get(id)?.takeIf { !it.deleted }?.let { known[it.id] = it } // a question about an earlier day
        }
        return known
    }

    private suspend fun frequentMeals(day: String) = Frequent.meals(
        db.entries().history(LocalDate.parse(day).minusDays(HISTORY_DAYS).toString(), day).mapNotNull { e ->
            val per100 = if (e.kcal100 != null) dev.dietapp.data.domain.Per100(e.kcal100, e.protein100!!, e.fat100!!, e.carbs100!!) else null
            val at = Instant.ofEpochMilli(e.eatenAtMs).atZone(clock.zone)
            MealRow(e.mealId ?: return@mapNotNull null, e.name, e.grams, at.hour * 60 + at.minute, at.toInstant(), e.position, per100, e.foodName)
        },
    )

    private companion object {
        const val HISTORY_DAYS = 60L
    }
}

/** Processes queued messages in local mode. One at a time, oldest first. */
@Singleton
class LocalEngine @Inject constructor(
    private val db: AppDatabase,
    private val processor: LocalMessageProcessor,
    private val files: OutboxFiles,
    private val trace: DiagLog,
) {
    private val mutex = Mutex()
    private val _confirmations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Ticks each time a message has become diary entries (drives the haptic confirmation). */
    val confirmations: SharedFlow<Unit> = _confirmations.asSharedFlow()

    suspend fun run() = mutex.withLock {
        for (row in db.outbox().queued()) {
            val started = System.nanoTime()
            trace.log("message", "processing \"${row.text ?: "(photo)"}\"" + (if (row.hasImage) " with a photo" else "") +
                (row.pendingQuestion?.let { " as the answer to \"$it\"" } ?: ""))
            try {
                processor.process(row)
                files.delete(row.id)
                _confirmations.tryEmit(Unit)
                trace.log("message", "done in ${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = (e as? AppError)?.message ?: t("Не удалось разобрать сообщение", "Could not read the message") + " (${e.javaClass.simpleName}). $JOURNAL_HINT"
                trace.error("message", "failed: $message", e.takeIf { it !is AppError })
                db.outbox().upsert(row.copy(state = "failed", error = message, attempts = row.attempts + 1))
            }
        }
    }
}
