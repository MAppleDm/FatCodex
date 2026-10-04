package dev.dietapp.data.local.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whatever a parser returns is untrusted: validate it, then pick at most one clarifying question.
 * A port of `normalize` in services/ai-parser/ai_parser/parser.py (same rules, same fallback texts).
 */
object Normalizer {
    const val MAX_GRAMS = 5000.0
    const val UNCLEAR_TARGET_QUESTION = "Не понял, какую запись поправить. Уточни, пожалуйста."

    private fun cleanStr(value: JsonElement?, limit: Int): String? {
        val p = value as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(limit).trim().ifEmpty { null }
    }

    private fun number(value: JsonElement?): Double? {
        val p = value as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString && (p.content == "true" || p.content == "false")) return null // booleans are not numbers
        val parsed = (if (p.isString) p.content.replace(',', '.') else p.content).toDoubleOrNull()
        return parsed?.takeIf { it.isFinite() }
    }

    fun fallbackQuestion(item: ParsedItem): String =
        if (item.action == Action.Add) "Уточни, пожалуйста: сколько граммов «${item.name}»?"
        else "Уточни, пожалуйста, что именно поправить в «${item.name}»?"

    /** For the model's JSON: validate every raw item against the entries it is allowed to touch. */
    fun fromRaw(rawItems: JsonArray, entries: List<ContextEntry>, threshold: Double): ParseResult {
        val known = entries.associateBy { it.id }
        val items = ArrayList<ParsedItem>()
        var badTarget = false

        for (raw in rawItems) {
            val obj = raw as? JsonObject ?: continue
            val action = when ((cleanStr(obj["action"], 20) ?: "add").lowercase()) {
                "add" -> Action.Add
                "update" -> Action.Update
                "remove" -> Action.Remove
                else -> continue
            }
            val targetId = cleanStr(obj["target_id"], 64)
            val entry = targetId?.let { known[it] }
            if (action != Action.Add && entry == null) {
                badTarget = true // update/remove of something that is not in the diary: never guess
                continue
            }
            val name = cleanStr(obj["name"], 100) ?: entry?.name ?: continue
            val grams = if (action == Action.Remove) {
                0.0
            } else {
                val g = number(obj["grams"])
                if (g == null || g <= 0.0 || g > MAX_GRAMS) continue
                g
            }
            val confidence = (number(obj["confidence"]) ?: 0.5).coerceIn(0.0, 1.0)
            val query = cleanStr(obj["query_en"], 100)
            items += ParsedItem(
                action = action,
                targetId = if (action != Action.Add) targetId else null,
                name = name,
                queryEn = query?.lowercase(),
                grams = grams,
                confidence = confidence,
                clarifyQuestion = cleanStr(obj["clarify_question"], 200),
                foodId = cleanStr(obj["food_id"], 120),
                ask = (obj["needs_confirmation"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            )
        }

        if (rawItems.isNotEmpty() && items.isEmpty() && !badTarget) throw BadModelOutput("every item in the model output was invalid")
        return finalize(items, badTarget, threshold)
    }

    /** At most one question: the least sure item's own, else a fallback. Questions on other items are dropped. */
    fun finalize(items: List<ParsedItem>, badTarget: Boolean, threshold: Double): ParseResult {
        var question: String? = null
        val low = items.indices.filter { items[it].confidence < threshold }
        val result: List<ParsedItem>
        if (low.isNotEmpty()) {
            val chosen = low.minBy { items[it].confidence }
            question = items[chosen].clarifyQuestion ?: fallbackQuestion(items[chosen])
            result = items.mapIndexed { i, item -> item.copy(clarifyQuestion = if (i == chosen) question else null) }
        } else {
            result = items.map { it.copy(clarifyQuestion = null) }
        }
        if (badTarget && question == null) question = UNCLEAR_TARGET_QUESTION
        return ParseResult(result, question)
    }
}
