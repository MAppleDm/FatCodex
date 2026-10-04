package dev.dietapp.ui.main

import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.Entry
import dev.dietapp.data.domain.FoodChoice
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.Message
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.OutboxMessage
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Totals
import dev.dietapp.data.domain.Weight
import dev.dietapp.data.domain.WeightTrend
import dev.dietapp.data.local.Capabilities
import java.time.Instant
import java.time.LocalDate

enum class Screen { Main, Settings, Foods, Journal }

enum class VoicePhase { Idle, Listening, Recording, Transcribing }

/**
 * One line of the feed. The feed is a conversation: each message the user sent, then what the app made of it
 * (its entries, then its notes). Things that belong to no message (weigh-ins, older entries) sit by their own time.
 */
sealed interface FeedItem {
    val key: String

    /**
     * [history]: the conversation about this food (what the app asked, what the user answered, what it changed).
     * Folded into the entry: shown when it is opened, the user's last answer as a one-line [summary] under it.
     */
    data class EntryItem(val entry: Entry, val history: List<HistoryLine> = emptyList()) : FeedItem {
        override val key get() = "e:${entry.id}"
        val summary: String? get() = history.lastOrNull { it.from == HistoryLine.From.User }?.text
    }

    data class WeightItem(val weight: Weight, val trendKg: Double?, val actionsOpen: Boolean) : FeedItem {
        override val key get() = "w:${weight.id}"
    }

    /** What the user sent. [pending] is set while it is waiting to be processed, or has failed. */
    data class MessageItem(val message: Message, val pending: OutboxMessage?) : FeedItem {
        override val key get() = "m:${message.id}"
    }

    data class NoteItem(val note: Note) : FeedItem {
        override val key get() = "n:${note.id}"
    }
}

/** One line of the conversation about a food. */
data class HistoryLine(val text: String, val from: From, val at: Instant) {
    enum class From {
        /** A question or an offer of values. */
        AppAsks,
        /** What the app did ("Добавил в базу", "Исправил"). */
        App,
        /** The user's answer: a pick, or a message. */
        User,
    }
}

/** An entry being edited in place. [grams] stays text while the user types. */
data class EditState(val entryId: String, val name: String, val grams: String)

data class WeightSummary(val latestKg: Double, val trendKg: Double, val raw: List<Double>, val trend: List<Double>)

/** Everything the main screen renders. */
data class MainUiState(
    val day: LocalDate,
    val today: LocalDate,
    val goal: Int?,
    val totals: Totals,
    val feed: List<FeedItem>,
    val summaryExpanded: Boolean = false,
    val weight: WeightSummary? = null,
    val draft: String = "",
    val editing: EditState? = null,
    val voice: VoicePhase = VoicePhase.Idle,
    /** A short transient message above the input (voice errors and the like). */
    val notice: String? = null,
    val cameraOpen: Boolean = false,
    val screen: Screen = Screen.Main,
    val capabilities: Capabilities = Capabilities(),
    /** Values the model found for a food, waiting for the user to pick one: shown above the input. */
    val proposal: ProposalUi? = null,
    /** The app's open question, answered by the next message. Shown when no proposal is open. */
    val question: Note? = null,
    /** "Записать?" for the day's food that waits for the user. Shown when no proposal is open. */
    val confirm: ConfirmUi? = null,
) {
    val isToday get() = day == today
    val isEmpty get() = feed.isEmpty()

    /** Without a server and without a model key a photo cannot be read, so the camera does not open. */
    val photoNeedsKey get() = capabilities.local && !capabilities.modelKey
}

/**
 * "Нашёл «сосиски Ремит рубленые». Какие значения занести в базу?" with the options, or the user's own values being
 * typed ([custom] not null).
 */
data class ProposalUi(
    val noteId: Long,
    val question: String,
    val choices: List<FoodChoice>,
    val custom: ChoiceDraft? = null,
    val busy: Boolean = false,
)

/** The day's food waiting for "записать" or "не записывать". */
data class ConfirmUi(val entries: List<Entry>, val busy: Boolean = false)

/** The user's own values for a proposed food, as typed. */
data class ChoiceDraft(
    val name: String = "",
    val kcal: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
) {
    val per100: dev.dietapp.data.domain.Per100?
        get() {
            val v = listOf(kcal, protein, fat, carbs).map { it.replace(',', '.').toDoubleOrNull() ?: return null }
            return dev.dietapp.data.domain.Per100(v[0], v[1], v[2], v[3])
        }
    val canSave get() = name.isNotBlank() && per100 != null
}

/** One-shot things the UI does that are not state. */
sealed interface UiEffect {
    /** A record was confirmed: a short haptic tick instead of any visual flourish. */
    data object Confirm : UiEffect
}

/** The parts of the state that only live in the view model. */
internal data class Local(
    val day: LocalDate,
    val today: LocalDate,
    val draft: String = "",
    val draftFromVoice: Boolean = false,
    val voiceBase: String = "",
    val summaryExpanded: Boolean = false,
    val editing: EditState? = null,
    val weightActionsId: String? = null,
    val voice: VoicePhase = VoicePhase.Idle,
    val notice: String? = null,
    val cameraOpen: Boolean = false,
    val screen: Screen = Screen.Main,
    /** The proposal being answered with the user's own values, and those values. */
    val customFor: Long? = null,
    val custom: ChoiceDraft? = null,
    val proposalBusy: Long? = null,
    val confirmBusy: Boolean = false,
)

private const val TREND_POINTS = 30

internal fun buildState(
    local: Local,
    content: DayContent,
    allWeights: List<Weight>,
    profile: Profile,
    capabilities: Capabilities = Capabilities(),
): MainUiState {
    val trend = WeightTrend.compute(allWeights)
    val trendByDay = trend.associate { it.day to it.trend }

    // A message anchors its replies: they sort at the message's time, right after it, entries before notes.
    // Everything else sorts at its own time.
    val pending = content.outbox.associateBy { it.id }
    val messages = content.messages.associateBy { it.id } +
        content.outbox.filter { o -> content.messages.none { it.id == o.id } }
            .associate { it.id to Message(it.id, it.day, it.text, it.hasImage, it.createdAt) }

    val fold = fold(content, pending.keys)

    data class Row(val at: Instant, val anchor: String, val order: Int, val sub: String, val item: FeedItem)
    val rows = buildList {
        messages.values.filter { it.id !in fold.messages }.forEach { add(Row(it.at, "m:${it.id}", 0, "", FeedItem.MessageItem(it, pending[it.id]))) }
        content.entries.forEach { e ->
            val m = e.mealId?.let(messages::get)
            val item = FeedItem.EntryItem(e, fold.history[e.id].orEmpty())
            add(if (m != null) Row(m.at, "m:${m.id}", 1, "%06d:%s".format(e.position, e.id), item) else Row(e.eatenAt, "e:${e.position}:${e.id}", 0, "", item))
        }
        content.weights.forEach {
            add(Row(it.updatedAt, "w:${it.id}", 0, "", FeedItem.WeightItem(it, trendByDay[it.day], local.weightActionsId == it.id)))
        }
        // the conversation about a food lives in its entry; open questions are asked in the panel above the input
        content.notes.filter { it.id !in fold.notes }.forEach { n ->
            val m = n.messageId?.let(messages::get)
            val sub = "%020d:%020d".format(n.createdAt.toEpochMilli(), n.id)
            add(if (m != null) Row(m.at, "m:${m.id}", 2, sub, FeedItem.NoteItem(n)) else Row(n.createdAt, "n:$sub", 0, "", FeedItem.NoteItem(n)))
        }
    }.sortedWith(compareBy<Row>({ it.at }, { it.anchor }, { it.order }, { it.sub }))

    val proposal = content.notes.lastOrNull { it.kind == NoteKind.Proposal && !it.resolved && it.choices.isNotEmpty() }?.let { n ->
        ProposalUi(
            noteId = n.id, question = n.text, choices = n.choices,
            custom = local.custom?.takeIf { local.customFor == n.id },
            busy = local.proposalBusy == n.id,
        )
    }
    val question = content.notes.lastOrNull { it.kind == NoteKind.Question && !it.resolved }
    val waiting = content.entries.filter { it.pending }
    val confirm = if (waiting.isEmpty()) null else ConfirmUi(waiting, busy = local.confirmBusy)

    val points = trend.takeLast(TREND_POINTS)
    return MainUiState(
        day = local.day,
        today = local.today,
        goal = profile.calorieGoal,
        // food waiting for "записать" is shown, but not counted until it is confirmed
        totals = Totals.of(content.entries.filterNot { it.pending }),
        feed = rows.map { it.item },
        summaryExpanded = local.summaryExpanded,
        weight = points.lastOrNull()?.let {
            WeightSummary(it.raw, it.trend, points.map { p -> p.raw }, points.map { p -> p.trend })
        },
        draft = local.draft,
        editing = local.editing,
        voice = local.voice,
        notice = local.notice,
        cameraOpen = local.cameraOpen,
        screen = local.screen,
        capabilities = capabilities,
        proposal = proposal,
        question = if (proposal != null) null else question,
        confirm = if (proposal != null) null else confirm,
    )
}

/** What is folded away from the feed: [notes] and [messages] by id, and the conversation of each entry. */
internal class Fold(val notes: Set<Long>, val messages: Set<String>, val history: Map<String, List<HistoryLine>>)

/**
 * Sorts the day's conversation. Everything about one food (the app's questions and offers, the user's picks and
 * answering messages, what the app changed) folds into that food's entry, in the order it happened. Open questions
 * are asked in the panel above the input. The rest are lines of the chat.
 */
internal fun fold(content: DayContent, unprocessed: Set<String>): Fold {
    val ids = content.entries.mapTo(HashSet()) { it.id }
    val notes = HashSet<Long>()
    val messages = HashSet<String>()
    val lines = HashMap<String, MutableList<HistoryLine>>()
    fun attach(entry: String, line: HistoryLine) = lines.getOrPut(entry) { ArrayList() }.add(line)

    for (n in content.notes) {
        val target = n.targetEntryId
        val asks = n.kind == NoteKind.Question || n.kind == NoteKind.Proposal
        when {
            asks && !n.resolved -> notes += n.id
            n.kind == NoteKind.Error -> Unit
            target != null && target in ids -> {
                notes += n.id
                val from = when (n.kind) {
                    NoteKind.Answer -> HistoryLine.From.User
                    NoteKind.Question, NoteKind.Proposal -> HistoryLine.From.AppAsks
                    else -> HistoryLine.From.App
                }
                attach(target, HistoryLine(n.text, from, n.createdAt))
            }
            target != null -> notes += n.id // its entry was deleted
            n.kind == NoteKind.Proposal || n.kind == NoteKind.Answer -> notes += n.id
        }
    }
    for (m in content.messages) {
        val about = m.aboutEntryId ?: continue
        // while it is being processed the message stays in sight, so the user sees it was taken
        if (about in ids && m.id !in unprocessed) {
            messages += m.id
            attach(about, HistoryLine(listOfNotNull(m.text, if (m.hasImage) dev.dietapp.Texts.PHOTO else null).joinToString(" · "),
                HistoryLine.From.User, m.sentAt))
        }
    }
    return Fold(notes, messages, lines.mapValues { (_, l) -> l.sortedBy { it.at } })
}
