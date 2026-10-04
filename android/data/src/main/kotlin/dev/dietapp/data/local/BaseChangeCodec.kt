package dev.dietapp.data.local

import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.parse.BaseChange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A change to the food base that waits for the user's yes, stored as JSON in `notes.payload` (kind "base_change"). */
object BaseChangeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(change: BaseChange): String = json.encodeToString(
        Stored.serializer(),
        when (change) {
            is BaseChange.Delete -> Stored(op = DELETE, id = change.id, name = change.name, question = change.question)
            is BaseChange.Save -> Stored(
                op = SAVE, id = change.id, name = change.name, question = change.question,
                kcal = change.per100.kcal, protein = change.per100.protein, fat = change.per100.fat, carbs = change.per100.carbs,
                aliases = change.aliases, estimated = change.estimated, note = change.note,
            )
        },
    )

    /** Null when the payload is missing or unreadable (the change is then dropped, never guessed). */
    fun decode(payload: String?): BaseChange? {
        if (payload.isNullOrBlank()) return null
        val s = runCatching { json.decodeFromString(Stored.serializer(), payload) }.getOrNull() ?: return null
        return when (s.op) {
            DELETE -> BaseChange.Delete(s.id ?: return null, s.name, s.question)
            SAVE -> BaseChange.Save(
                s.id, s.name, Per100(s.kcal ?: return null, s.protein ?: return null, s.fat ?: return null, s.carbs ?: return null),
                s.aliases, s.estimated, s.note, s.question,
            )
            else -> null
        }
    }

    private const val DELETE = "delete"
    private const val SAVE = "save"

    @Serializable
    private data class Stored(
        val op: String,
        val id: Long? = null,
        val name: String,
        val question: String,
        val kcal: Double? = null,
        val protein: Double? = null,
        val fat: Double? = null,
        val carbs: Double? = null,
        val aliases: List<String> = emptyList(),
        val estimated: Boolean = false,
        val note: String? = null,
    )
}
