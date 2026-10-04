package dev.dietapp.data.local.parse

import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.food.Text
import java.io.InputStream
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A food the offline parser knows by its Russian name: how to find it in the USDA catalog (`query`) or its own
 * table values (`per100`), what a portion is, and what a spoon or a piece weighs for *this* food.
 */
class LexEntry(
    val name: String,
    val phrases: List<List<String>>,
    val query: String?,
    val per100: Per100?,
    val defaultGrams: Double,
    val units: Map<String, Double>,
    /** Grams when it is added with "с" ("чай с сахаром"). */
    val withGrams: Double?,
    /** True when a plain mention says little about the amount ("суп", "салат"): the portion is then a guess. */
    val variable: Boolean,
    /** What a bare "ложка" means for this food (sugar and honey: a teaspoon). */
    val bareSpoon: String?,
)

class Lexicon(val entries: List<LexEntry>) {
    private val phrases: List<Pair<LexEntry, List<String>>> = entries.flatMap { e -> e.phrases.map { e to it } }

    /**
     * The longest phrase that starts at tokens[start], with the number of tokens it covers. Among equally long ones
     * the one with more words spelled exactly wins ("вода" is water even though it looks like "водка"), then the
     * earlier entry.
     */
    fun matchAt(tokens: List<String>, start: Int): Pair<LexEntry, Int>? {
        var best: Pair<LexEntry, Int>? = null
        var bestExact = -1
        for ((entry, phrase) in phrases) {
            if (phrase.size > tokens.size - start) continue
            if (best != null && phrase.size < best.second) continue
            if (!phrase.indices.all { similar(phrase[it], tokens[start + it]) }) continue
            val exact = phrase.indices.count { phrase[it] == tokens[start + it] }
            if (best == null || phrase.size > best.second || exact > bestExact) {
                best = entry to phrase.size
                bestExact = exact
            }
        }
        return best
    }

    /** The entry whose phrase is the whole of [text] (used when the model gives a Russian name for a food). */
    fun find(text: String): LexEntry? {
        val tokens = Text.tokenize(text)
        if (tokens.isEmpty()) return null
        val hit = matchAt(tokens, 0)
        return hit?.takeIf { it.second == tokens.size }?.first
    }

    companion object {
        fun parse(json: String): Lexicon {
            val foods = Json.parseToJsonElement(json).jsonObject["foods"]!!.jsonArray
            return Lexicon(foods.map { entry(it.jsonObject) })
        }

        fun fromStream(stream: InputStream): Lexicon = parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })

        private fun entry(o: JsonObject): LexEntry {
            val p = o["p"]?.jsonArray?.map { it.jsonPrimitive.double }
            return LexEntry(
                name = o["n"]!!.jsonPrimitive.content,
                phrases = o["ru"]!!.jsonArray.map { Text.tokenize(it.jsonPrimitive.content) }.filter { it.isNotEmpty() },
                query = o["q"]?.jsonPrimitive?.content,
                per100 = p?.let { Per100(it[0], it[1], it[2], it[3]) },
                defaultGrams = o["g"]?.jsonPrimitive?.double ?: 100.0,
                units = (o["u"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).double } ?: emptyMap(),
                withGrams = o["w"]?.jsonPrimitive?.double,
                variable = o["v"] != null,
                bareSpoon = o["sp"]?.jsonPrimitive?.content,
            )
        }

        /**
         * Do two folded Russian words name the same thing, allowing for case endings?
         *  - three letters: exactly, or one more letter (рис / риса), never looser ("как" is not "какао");
         *  - four letters: the same length or one more, sharing three letters (мясо / мяса), but "кило" is not "килька";
         *  - five or more: one is the other plus up to three letters and they share all but the last letter,
         *    at least four (яблок / яблоко / яблоками), but "масло" is not "масса".
         */
        fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            val shorter = min(a.length, b.length)
            val longer = maxOf(a.length, b.length)
            if (shorter < 3) return false
            var p = 0
            while (p < shorter && a[p] == b[p]) p++
            return when (shorter) {
                3 -> longer == 4 && p == 3
                4 -> longer - shorter <= 1 && p >= 3
                else -> longer - shorter <= 3 && p >= shorter - 1 && p >= 4
            }
        }
    }
}
