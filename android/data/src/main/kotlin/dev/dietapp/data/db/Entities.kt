package dev.dietapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Days are stored as ISO strings ("2026-09-30"): they sort correctly and survive time zone changes. */
@Entity(tableName = "entries", indices = [Index("day"), Index("dirty")])
data class EntryRow(
    @PrimaryKey val id: String,
    val mealId: String?,
    val day: String,
    val eatenAtMs: Long,
    val position: Int,
    val name: String,
    val grams: Double,
    val kcal: Double?,
    val protein: Double?,
    val fat: Double?,
    val carbs: Double?,
    val kcal100: Double?,
    val protein100: Double?,
    val fat100: Double?,
    val carbs100: Double?,
    val foodName: String?,
    val status: String,
    val confidence: Double,
    val source: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    /** Changed locally, not yet sent. While set, a sync pull never overwrites this row. */
    val dirty: Boolean,
    /** Local mode: recognised, waiting for the user's "записать" (the agent was not sure, or the numbers are only approximate). Not counted in any total. */
    @ColumnInfo(defaultValue = "0") val pending: Boolean = false,
)

@Entity(tableName = "weights", indices = [Index("day"), Index("dirty")])
data class WeightRow(
    @PrimaryKey val id: String,
    val day: String,
    val kg: Double,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val dirty: Boolean,
)

/** Messages waiting to be sent (and parsed) by the server. Survives offline periods and restarts. */
@Entity(tableName = "outbox", indices = [Index("createdAtMs")])
data class OutboxRow(
    @PrimaryKey val id: String,
    val text: String?,
    val hasImage: Boolean,
    val imageMime: String,
    val day: String,
    /** ISO-8601 with UTC offset, as sent to the server. */
    val eatenAt: String,
    val source: String,
    val pendingQuestion: String?,
    val pendingTargetId: String?,
    val state: String,
    val error: String?,
    val attempts: Int,
    val createdAtMs: Long,
)

@Entity(tableName = "notes", indices = [Index("day")])
data class NoteRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val kind: String,
    val text: String,
    val targetEntryId: String?,
    /** Questions stop being "the current question" once the user has answered (sent another message). */
    val resolved: Boolean,
    val createdAtMs: Long,
    /** The message this note answers, so the feed can show it right under that message. */
    val messageId: String? = null,
    /**
     * kind "proposal": the food options to choose from, as JSON (see ProposalCodec). kind "base_change": the change to
     * the food base that waits for a yes, as JSON (see BaseChangeCodec). The other kinds: "question", "info", "error"
     * and "answer" (what the user picked, kept with the entry the question was about).
     */
    val payload: String? = null,
)

/**
 * What the user sent, kept after it has been processed: the feed reads like a conversation, each message
 * followed by what the app made of it. The outbox row with the same id is the message's delivery state.
 */
@Entity(tableName = "messages", indices = [Index("day")])
data class MessageRow(
    @PrimaryKey val id: String,
    val day: String,
    val text: String?,
    val hasImage: Boolean,
    val source: String,
    /** Where it sits in the diary: the time of day on [day]. Entries made from it carry the same time. */
    val atMs: Long,
    val createdAtMs: Long,
    /** The entry the question this message answered was about: the message is part of that entry's conversation. */
    val aboutEntryId: String? = null,
)

/**
 * The user's own foods, the only nutrition database there is: what the agent found on the web and the user confirmed, and what
 * the user added by hand (casein protein, a favourite bar, a home recipe).
 * Values are per 100 g. Added by the user on the "База продуктов" screen, by the model (marked [estimated] unless
 * the user gave the numbers), or imported from a JSON file. [key] is the folded name and is unique.
 */
@Entity(tableName = "foods", indices = [Index(value = ["key"], unique = true)])
data class FoodRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val key: String,
    /** Other names it may be called by, one per line. */
    val aliases: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    /** Typical values guessed by the model rather than read from a label: shown with "~". */
    val estimated: Boolean,
    val note: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Who put it here: "user" (the screen or numbers typed in the chat), "model", "web" (a confirmed web find), "import". */
    @ColumnInfo(defaultValue = "user") val origin: String = "user",
    /** The page the numbers were taken from, for foods found on the web. */
    val url: String? = null,
)

@Entity(tableName = "profile")
data class ProfileRow(
    @PrimaryKey val id: Int = 1,
    val email: String?,
    val calorieGoal: Int?,
)

/** Result row of the per-day totals query (history). */
data class DaySummaryRow(
    val day: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
)
