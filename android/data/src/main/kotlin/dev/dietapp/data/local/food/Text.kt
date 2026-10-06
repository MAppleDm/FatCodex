package dev.dietapp.data.local.food

import java.text.Normalizer

/** Text normalisation for matching the names of the user's own foods. */
object Text {
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Words that carry no signal in a food's name ("Egg, whole, raw" / "meat only"). */
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

    /**
     * Do two folded words name the same thing, allowing for case endings?
     *  - three letters: exactly, or one more letter (рис / риса), never looser ("как" is not "какао");
     *  - four letters: one more or fewer letter after the same first three;
     *  - longer: the same start (at least four letters, the last one aside) and endings up to three letters apart.
     */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
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
