package dev.dietapp.data.local.parse

import dev.dietapp.data.domain.Per100

enum class Action { Add, Update, Remove }

/** One thing the user said, as the parsers see it. Never carries calories the model made up: numbers come from the food database. */
data class ParsedItem(
    val action: Action,
    val targetId: String?,
    val name: String,
    val queryEn: String?,
    val grams: Double,
    val confidence: Double,
    val clarifyQuestion: String? = null,
    /** A food the model picked from the user's food database by id ("my:12"); wins over the name. */
    val foodId: String? = null,
    /** The model is not sure of this food's values (they depend on the brand, the recipe, the size): ask the user. */
    val ask: Boolean = false,
)

/**
 * [notes]: what the user should hear besides the entries (food base changes, the model's short reply).
 * [proposals]: foods the model found (on the web, or typical values) and wants the user to confirm before they go into
 * the base; the matching items are recorded without numbers until then.
 */
data class ParseResult(
    val items: List<ParsedItem>,
    val clarifyQuestion: String?,
    val notes: List<String> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    /** Changes to the user's food base the agent wants to make but may not before the user says yes. */
    val changes: List<BaseChange> = emptyList(),
)

/** "Which of these values should go into your base for [forItem]?" */
data class Proposal(val forItem: String, val question: String?, val choices: List<dev.dietapp.data.domain.FoodChoice>)

/**
 * A change to one of the user's own foods. The agent makes it at once when it is sure (standard mode), otherwise it
 * waits as a question for the user: "Удалить «X» из базы?". [question] is that question, [name] the food's name.
 */
sealed interface BaseChange {
    val name: String
    val question: String

    data class Delete(val id: Long, override val name: String, override val question: String) : BaseChange

    /** Creates a food ([id] null) or replaces the values of [id]. Values per 100 g. */
    data class Save(
        val id: Long?,
        override val name: String,
        val per100: Per100,
        val aliases: List<String>,
        /** Typical values (the model's) rather than numbers the user gave. */
        val estimated: Boolean,
        val note: String?,
        override val question: String,
    ) : BaseChange
}

data class ContextEntry(val id: String, val name: String, val grams: Double)

data class FrequentItem(val name: String, val queryEn: String?, val grams: Double)

data class FrequentMeal(val label: String, val items: List<FrequentItem>)

data class PendingQuestion(val question: String, val targetId: String?)

data class ParseContext(
    val entries: List<ContextEntry> = emptyList(),
    val frequent: List<FrequentMeal> = emptyList(),
    val pending: PendingQuestion? = null,
    val localTime: String? = null,
    /** "ru" or "en": the language the model writes questions and replies in. */
    val language: String? = null,
)

data class ParseRequest(
    val text: String?,
    val imageBase64: String? = null,
    val imageMime: String = "image/jpeg",
    val context: ParseContext = ParseContext(),
)

/** Turns a message into items. Implemented by the language model (the agent). */
interface MessageParser {
    suspend fun parse(request: ParseRequest): ParseResult
}

/** The model answered, but with nothing usable. Worth another attempt. */
class BadModelOutput(message: String) : Exception(message)
