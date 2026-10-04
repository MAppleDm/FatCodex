package dev.dietapp.data.local.food

import java.text.Normalizer

/**
 * Text normalisation. A line-for-line port of services/nutrition/nutrition/text.py: the two must agree,
 * because the same catalog is ranked by both (FoodCatalogParityTest checks it on real data).
 */
object Text {
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Words that carry no signal in USDA-style names ("Egg, whole, raw" / "meat only"). */
    val STOPWORDS: Set<String> = setOf("a", "an", "and", "or", "of", "the", "with", "in", "on", "for", "to", "only", "from")

    /** Lowercase and strip diacritics (also maps ё->е, й->и). */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase()
        return buildString(decomposed.length) {
            for (ch in decomposed) if (!isCombining(ch)) append(ch)
        }
    }

    private fun isCombining(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Folded alphanumeric tokens without stopwords, order preserved, duplicates removed. */
    fun tokenize(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in TOKEN.findAll(fold(text))) if (m.value !in STOPWORDS) seen.add(m.value)
        return seen.toList()
    }

    private fun isAscii(token: String) = token.all { it.code < 128 }

    /** Very light stemmer: English plurals, and Russian endings via a fixed-length cut. Crude on purpose. */
    fun stem(token: String): String {
        if (isAscii(token)) {
            val n = token.length
            return when {
                n > 4 && token.endsWith("ies") -> token.dropLast(3) + "y"
                n > 4 && (token.endsWith("oes") || token.endsWith("ches") || token.endsWith("shes") ||
                    token.endsWith("xes") || token.endsWith("sses")) -> token.dropLast(2)
                n > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") -> token.dropLast(1)
                else -> token
            }
        }
        val cut = when {
            token.length >= 5 -> 2
            token.length == 4 -> 1
            else -> 0
        }
        return token.dropLast(cut)
    }

    fun stemSet(text: String): Set<String> = tokenize(text).mapTo(LinkedHashSet()) { stem(it) }
}
