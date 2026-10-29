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

enum class Screen { Main, Settings, Foods, Journal, Export, Agent, About, History, Me, Watch }

enum class VoicePhase { Idle, Listening, Recording, Transcribing }

/**
 * One line of the feed. The feed is a conversation, read like a messenger: each message the user sent, then what the app
 * made of it (its entries, then its lines), in the order it happened. A question, an offer of values or a change to the
 * food base is asked where it came up and stays there once answered, with the answer after it.
 * Things that belong to no message (weigh-ins, older entries) sit by their own time.
 */
sealed interface FeedItem {
    val key: String

    data class EntryItem(val entry: Entry) : FeedItem {
        override val key get() = "e:${entry.id}"
    }

    data class WeightItem(val weight: Weight, val trendKg: Double?, val actionsOpen: Boolean) : FeedItem {
        override val key get() = "w:${weight.id}"
    }

    /** What the user sent. [pending] is set while it is waiting to be processed, or has failed. */
    data class MessageItem(val message: Message, val pending: OutboxMessage?, val thumb: ByteArray? = null) : FeedItem {
        override val key get() = "m:${message.id}"
    }

    /**
     * A line from the app, or the user's pick. While it waits for the user: [proposal] for an offer of values,
     * [baseChange] for a change to the food base, [open] for a question answered by the next message.
     */
    data class NoteItem(
        val note: Note,
        val proposal: ProposalUi? = null,
        val baseChange: BaseChangeUi? = null,
        val open: Boolean = false,
    ) : FeedItem {
        override val key get() = "n:${note.id}"
    }
}

/** An entry being edited in place. [grams] stays text while the user types. */
data class EditState(val entryId: String, val name: String, val grams: String)

/** One ingredient of a dish being changed: which entry, which ingredient, and the grams as typed. */
data class PartEdit(val entryId: String, val index: Int, val grams: String)

data class WeightSummary(val latestKg: Double, val trendKg: Double, val raw: List<Double>, val trend: List<Double>)

/** Everything the main screen renders. */
data class MainUiState(
    val day: LocalDate,
    val today: LocalDate,
    val goal: Int?,
    val totals: Totals,
    val feed: List<FeedItem>,
    val summaryExpanded: Boolean = false,
    /** What is on screen is this day's own content: false before it has been read and in the moment another day is opened. */
    val loaded: Boolean = true,
    val weight: WeightSummary? = null,
    val draft: String = "",
    val editing: EditState? = null,
    val voice: VoicePhase = VoicePhase.Idle,
    /** A short transient message above the input (voice errors and the like). */
    val notice: String? = null,
    val cameraOpen: Boolean = false,
    val screen: Screen = Screen.Main,
    val capabilities: Capabilities = Capabilities(),
    /** The latest offer of values still waiting for a pick (each open one is also on its line of the feed). */
    val proposal: ProposalUi? = null,
    /** The latest "Удалить «X» из базы?" still waiting for a yes or a no. */
    val baseChange: BaseChangeUi? = null,
    /** The app's open question, answered by the next message. */
    val question: Note? = null,
    /** "Записать?" for the day's food that waits for the user, shown above the input. */
    val confirm: ConfirmUi? = null,
    /** A food just swiped away: "вернуть" brings it back for a few seconds, then it is deleted. */
    val undo: UndoUi? = null,
    /** Dishes whose ingredients are shown (entry ids), and the ingredient being changed. */
    val expandedParts: Set<String> = emptySet(),
    val partEdit: PartEdit? = null,
    /** What the watch said about this day, if anything. */
    val facts: dev.dietapp.data.domain.DayFacts? = null,
) {
    val isToday get() = day == today
    val isEmpty get() = feed.isEmpty()

    /** Without a server and without a model key nothing but a weigh-in can be read, so sending and the camera say so instead. */
    val needsKey get() = capabilities.local && !capabilities.modelKey
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

/** A change to the user's food base waiting for "да" or "нет". [question] may have several lines (what it is now, what it becomes). */
data class BaseChangeUi(val noteId: Long, val question: String, val busy: Boolean = false)

/** "Удалено: гречка · вернуть". */
data class UndoUi(val entryId: String, val name: String)

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

    /** A food (or a weight) is in the diary for good, counted: the short sound, like a messenger's for a message. */
    data object Added : UiEffect
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
    val baseChangeBusy: Long? = null,
    val confirmBusy: Boolean = false,
    /** The small pictures of the day's photo messages, by message id, as they have been read from the phone. */
    val thumbs: Map<String, ByteArray> = emptyMap(),
    val expandedParts: Set<String> = emptySet(),
    val partEdit: PartEdit? = null,
    /** Foods swiped away and not deleted yet (the undo window): out of the feed and the totals. */
    val hidden: Set<String> = emptySet(),
    val undo: UndoUi? = null,
)

private const val TREND_POINTS = 30

internal fun buildState(
    local: Local,
    dayContent: DayContent,
    allWeights: List<Weight>,
    profile: Profile,
    capabilities: Capabilities = Capabilities(),
    loaded: Boolean = true,
): MainUiState {
    val content = if (local.hidden.isEmpty()) dayContent else dayContent.copy(entries = dayContent.entries.filter { it.id !in local.hidden })
    val trend = WeightTrend.compute(allWeights)
    val trendByDay = trend.associate { it.day to it.trend }

    // A message anchors its replies: they sort at the message's time, right after it, entries before notes.
    // Everything else sorts at its own time.
    val pending = content.outbox.associateBy { it.id }
    val messages = content.messages.associateBy { it.id } +
        content.outbox.filter { o -> content.messages.none { it.id == o.id } }
            .associate { it.id to Message(it.id, it.day, it.text, it.hasImage, it.createdAt) }

    // what is still asked: on its own line of the feed, where it came up
    val proposals = content.notes.filter { it.kind == NoteKind.Proposal && !it.resolved && it.choices.isNotEmpty() }.associate { n ->
        n.id to ProposalUi(
            noteId = n.id, question = n.text, choices = n.choices,
            custom = local.custom?.takeIf { local.customFor == n.id },
            busy = local.proposalBusy == n.id,
        )
    }
    val changes = content.notes.filter { it.kind == NoteKind.BaseChange && !it.resolved }.associate { n ->
        n.id to BaseChangeUi(n.id, n.text, busy = local.baseChangeBusy == n.id)
    }
    val question = content.notes.lastOrNull { it.kind == NoteKind.Question && !it.resolved }

    data class Row(val at: Instant, val anchor: String, val order: Int, val sub: String, val item: FeedItem)
    val rows = buildList {
        messages.values.forEach { add(Row(it.at, "m:${it.id}", 0, "", FeedItem.MessageItem(it, pending[it.id], local.thumbs[it.id]))) }
        content.entries.forEach { e ->
            val m = e.mealId?.let(messages::get)
            val item = FeedItem.EntryItem(e)
            add(if (m != null) Row(m.at, "m:${m.id}", 1, "%06d:%s".format(e.position, e.id), item) else Row(e.eatenAt, "e:${e.position}:${e.id}", 0, "", item))
        }
        content.weights.forEach {
            add(Row(it.updatedAt, "w:${it.id}", 0, "", FeedItem.WeightItem(it, trendByDay[it.day], local.weightActionsId == it.id)))
        }
        content.notes.forEach { n ->
            val m = n.messageId?.let(messages::get)
            val sub = "%020d:%020d".format(n.createdAt.toEpochMilli(), n.id)
            val item = FeedItem.NoteItem(n, proposals[n.id], changes[n.id], open = n.id == question?.id)
            add(if (m != null) Row(m.at, "m:${m.id}", 2, sub, item) else Row(n.createdAt, "n:$sub", 0, "", item))
        }
    }.sortedWith(compareBy<Row>({ it.at }, { it.anchor }, { it.order }, { it.sub }))

    // a food a question or an offer is about waits for its answer, not for "записать": recording it now would count it without numbers
    val asked = content.notes.filter { !it.resolved && (it.kind == NoteKind.Question || it.kind == NoteKind.Proposal) }.mapNotNullTo(HashSet()) { it.targetEntryId }
    val waiting = content.entries.filter { it.pending && it.id !in asked }
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
        loaded = loaded,
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
        proposal = proposals.values.lastOrNull(),
        baseChange = changes.values.lastOrNull(),
        question = question,
        confirm = confirm,
        undo = local.undo,
        expandedParts = local.expandedParts,
        partEdit = local.partEdit,
        facts = content.facts,
    )
}
