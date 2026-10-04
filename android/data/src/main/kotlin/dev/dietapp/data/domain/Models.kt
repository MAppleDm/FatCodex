package dev.dietapp.data.domain

import java.time.Instant
import java.time.LocalDate

enum class EntryStatus {
    Ok, Uncertain, Unmatched;

    companion object {
        fun fromWire(value: String): EntryStatus = when (value) {
            "ok" -> Ok
            "uncertain" -> Uncertain
            else -> Unmatched
        }
    }

    fun toWire(): String = name.lowercase()
}

enum class Source {
    Text, Voice, Photo;

    companion object {
        fun fromWire(value: String): Source = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Text
    }

    fun toWire(): String = name.lowercase()
}

data class Per100(val kcal: Double, val protein: Double, val fat: Double, val carbs: Double)

/** One food item in the diary. Numbers are null when the food could not be found in the database. */
data class Entry(
    val id: String,
    val mealId: String?,
    val day: LocalDate,
    val eatenAt: Instant,
    val position: Int,
    val name: String,
    val grams: Double,
    val kcal: Double?,
    val protein: Double?,
    val fat: Double?,
    val carbs: Double?,
    val per100: Per100?,
    val foodName: String?,
    val status: EntryStatus,
    val confidence: Double,
    val source: Source,
    val updatedAt: Instant,
    /** A local edit that has not reached the server yet. */
    val dirty: Boolean,
    /** Recognised but not confirmed yet: shown, not counted. */
    val pending: Boolean = false,
)

data class Weight(val id: String, val day: LocalDate, val kg: Double, val updatedAt: Instant)

/**
 * [Answer]: what the user picked in a proposal, kept with the entry it was about. [BaseChange]: a change to the user's
 * food base the agent is not allowed to make before the user says yes ("Удалить «X» из базы?"); asked above the input.
 */
enum class NoteKind { Question, Info, Error, Proposal, Answer, BaseChange }

/** A line from the app in the chat: a clarifying question or a notice. */
data class Note(
    val id: Long,
    val day: LocalDate,
    val kind: NoteKind,
    val text: String,
    val targetEntryId: String?,
    val createdAt: Instant,
    /** The message this note answers, if any. */
    val messageId: String? = null,
    val resolved: Boolean = false,
    /** For a [NoteKind.Proposal]: the foods to choose from, the first is the model's pick. */
    val choices: List<FoodChoice> = emptyList(),
)

/**
 * One way to fill in a food the base does not have, offered for the user to confirm: values per 100 g and where they
 * come from. [estimated]: typical values rather than a specific product's label.
 */
data class FoodChoice(
    val name: String,
    val per100: Per100,
    val source: String? = null,
    val url: String? = null,
    val note: String? = null,
    val estimated: Boolean = false,
)

/** Something the user sent: shown in the feed with what the app made of it right below. */
data class Message(
    val id: String,
    val day: LocalDate,
    val text: String?,
    val hasImage: Boolean,
    val at: Instant,
    /** It answered the app's question about this entry: it belongs to that entry's conversation. */
    val aboutEntryId: String? = null,
    /** When it was sent ([at] is where it sits in the diary, which differs when logging an earlier day). */
    val sentAt: Instant = at,
)

/** One of the user's own foods (values per 100 g). */
data class Food(
    val id: Long,
    val name: String,
    val aliases: List<String>,
    val per100: Per100,
    val estimated: Boolean,
    val note: String?,
    val updatedAt: Instant,
    val createdAt: Instant = updatedAt,
    /** "user", "model", "web" or "import". */
    val origin: String = "user",
    val url: String? = null,
)

enum class OutboxState { Queued, Failed }

/** A message the user sent that has not been turned into entries yet (offline, or waiting for the server). */
data class OutboxMessage(
    val id: String,
    val text: String?,
    val hasImage: Boolean,
    val day: LocalDate,
    val createdAt: Instant,
    val state: OutboxState,
    val error: String?,
    val attempts: Int,
)

data class Profile(val email: String?, val calorieGoal: Int?)

data class DayContent(
    val entries: List<Entry>,
    val outbox: List<OutboxMessage>,
    val notes: List<Note>,
    val weights: List<Weight>,
    val messages: List<Message> = emptyList(),
) {
    companion object {
        val Empty = DayContent(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

data class Totals(val kcal: Double, val protein: Double, val fat: Double, val carbs: Double) {
    companion object {
        val Zero = Totals(0.0, 0.0, 0.0, 0.0)

        /** Entries without numbers (not found in the food database) simply contribute nothing. */
        fun of(entries: Iterable<Entry>): Totals {
            var kcal = 0.0
            var protein = 0.0
            var fat = 0.0
            var carbs = 0.0
            for (e in entries) {
                kcal += e.kcal ?: 0.0
                protein += e.protein ?: 0.0
                fat += e.fat ?: 0.0
                carbs += e.carbs ?: 0.0
            }
            return Totals(kcal, protein, fat, carbs)
        }
    }
}

data class DaySummary(val day: LocalDate, val totals: Totals)

enum class MessageSource { Text, Voice, Photo }
