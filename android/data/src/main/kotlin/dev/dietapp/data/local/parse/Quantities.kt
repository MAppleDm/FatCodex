package dev.dietapp.data.local.parse

import dev.dietapp.data.local.food.Text

enum class UnitKind { Gram, Kilo, Milli, Liter, Piece, Slice, Tbsp, Tsp, Cup, Mug, Plate, Portion, Handful, Can, Pack, Bottle, Glass, Clove }

/** "200 г", "2 яйца" (no unit), "ложка" (value 1, no number written), "полстакана" (0.5 cup). Token range is [start, end). */
data class Quantity(
    val value: Double,
    val unit: UnitKind?,
    val start: Int,
    val end: Int,
    /** A bare "ложка": a teaspoon for sugar and honey, a tablespoon otherwise. */
    val bareSpoon: Boolean = false,
    val vague: Boolean = false,
)

/** Russian numbers, units of measure and what they weigh. All words are in their folded form (ё→е, й→и). */
object Quantities {
    private val NUMBER_WORDS: Map<String, Double> = buildMap {
        fun put(value: Double, vararg words: String) = words.forEach { this[Text.fold(it)] = value }
        put(1.0, "один", "одна", "одно", "одну", "одного", "одной", "одним")
        put(2.0, "два", "две", "двух", "двое", "двумя", "пара", "пару", "пары", "парочка", "парочку")
        put(3.0, "три", "трех", "трёх", "тремя")
        put(4.0, "четыре", "четырех", "четырёх")
        put(5.0, "пять", "пяти")
        put(6.0, "шесть", "шести")
        put(7.0, "семь", "семи")
        put(8.0, "восемь", "восьми")
        put(9.0, "девять", "девяти")
        put(10.0, "десять", "десяти", "десяток")
        put(12.0, "дюжина", "дюжину")
        put(1.5, "полтора", "полторы", "полутора")
        put(0.5, "пол", "полу", "половина", "половину", "половины", "половинка", "половинку", "половинки")
        put(0.25, "четверть", "четверти")
        put(0.33, "треть", "трети")
    }
    private const val SEVERAL = "несколько"

    private val UNIT_WORDS: Map<String, UnitKind> = buildMap {
        fun put(unit: UnitKind, vararg words: String) = words.forEach { this[Text.fold(it)] = unit }
        put(UnitKind.Gram, "г", "гр", "грамм", "грамма", "граммов", "граммы", "грамму")
        put(UnitKind.Kilo, "кг", "килограмм", "килограмма", "килограммов", "кило")
        put(UnitKind.Milli, "мл", "миллилитр", "миллилитра", "миллилитров")
        put(UnitKind.Liter, "л", "литр", "литра", "литров", "литре")
        put(UnitKind.Piece, "шт", "штука", "штуки", "штук", "штуку", "штучка", "штучки", "штучку")
        put(UnitKind.Slice, "кусок", "куска", "кусков", "кусочек", "кусочка", "кусочков", "кусочки", "ломтик", "ломтика",
            "ломтиков", "ломтики", "ломоть", "долька", "дольки", "долек", "долю", "пласт", "пластик", "пластинка", "пластинки")
        put(UnitKind.Tbsp, "ложка", "ложки", "ложек", "ложку", "ложкой", "ложечка", "ложечки")
        put(UnitKind.Cup, "стакан", "стакана", "стаканов", "стакане", "чашка", "чашки", "чашек", "чашку")
        put(UnitKind.Mug, "кружка", "кружки", "кружек", "кружку")
        put(UnitKind.Plate, "тарелка", "тарелки", "тарелку", "тарелок", "миска", "миску", "миски")
        put(UnitKind.Portion, "порция", "порции", "порцию", "порций")
        put(UnitKind.Handful, "горсть", "горсти", "горстью", "горстка", "горстки", "горстку")
        put(UnitKind.Can, "банка", "банки", "банку", "банок")
        put(UnitKind.Pack, "пачка", "пачки", "пачку", "упаковка", "упаковки", "упаковку")
        put(UnitKind.Bottle, "бутылка", "бутылки", "бутылку", "бутылок")
        put(UnitKind.Glass, "рюмка", "рюмки", "рюмку", "бокал", "бокала", "бокалов", "стопка", "стопки", "стопку")
        put(UnitKind.Clove, "зубчик", "зубчика", "зубчиков", "зубчики")
    }
    private val TABLESPOON_ADJ = setOf("столовая", "столовые", "столовых", "столовую", "десертная", "десертные", "десертную").map(Text::fold).toSet()
    private val TEASPOON_ADJ = setOf("чайная", "чайные", "чайных", "чайную").map(Text::fold).toSet()
    private val SPOON = setOf("ложка", "ложки", "ложек", "ложку", "ложкой", "ложечка", "ложечки").map(Text::fold).toSet()

    /** A spoon with no "столовая"/"чайная" in front: "ложка", "ложками". */
    private fun isSpoon(token: String) = token in SPOON || Lexicon.similar(token, "ложка")

    private val DIGITS = Regex("^\\d+(?:[.,]\\d+)?$")
    private val FRACTION = Regex("^(\\d+)/(\\d+)$")

    fun isUnitWord(token: String): Boolean = parseUnit(listOf(token), 0) != null

    /** Value and token count of a number at [i]: "200", "0.5", "1/2", "два", "полтора", "два с половиной". */
    fun parseNumber(tokens: List<String>, i: Int): Pair<Double, Int>? {
        val t = tokens[i]
        if (DIGITS.matches(t)) return t.replace(',', '.').toDouble() to 1
        FRACTION.matchEntire(t)?.let { m ->
            val d = m.groupValues[2].toDouble()
            if (d != 0.0) return m.groupValues[1].toDouble() / d to 1
        }
        NUMBER_WORDS[t]?.let { v ->
            // "два с половиной"
            if (v >= 1.0 && v == v.toInt().toDouble() && i + 2 < tokens.size && tokens[i + 1] == "с" &&
                tokens[i + 2].startsWith("половин")
            ) {
                return v + 0.5 to 3
            }
            return v to 1
        }
        return null
    }

    /** The unit named at [i], and how many tokens it takes ("ст л", "чайная ложка", "столовая ложка" take two). */
    fun parseUnit(tokens: List<String>, i: Int): Pair<UnitKind, Int>? {
        val t = tokens[i]
        val next = tokens.getOrNull(i + 1)
        if (t == "ст" && next != null && (next == "л" || next in SPOON)) return UnitKind.Tbsp to 2
        if (t == "ч" && next != null && (next == "л" || next in SPOON)) return UnitKind.Tsp to 2
        if (t in TABLESPOON_ADJ && next != null && next in SPOON) return UnitKind.Tbsp to 2
        if (t in TEASPOON_ADJ && next != null && next in SPOON) return UnitKind.Tsp to 2
        UNIT_WORDS[t]?.let { return it to 1 }
        // other case endings ("ложками", "стаканами", "кусками"): close to a known unit of five letters or more
        if (t.length >= 5) UNIT_WORDS.entries.firstOrNull { (word, _) -> word.length >= 5 && Lexicon.similar(t, word) }?.let { return it.value to 1 }
        return null
    }

    /**
     * Splits glued "пол" forms ("полстакана" → "пол" "стакана", "полкило", "полбанана") when the rest is a unit
     * or a known food, so that the number and the thing are separate tokens.
     */
    fun splitHalf(tokens: List<String>, isFood: (String) -> Boolean): List<String> {
        val out = ArrayList<String>(tokens.size)
        for (t in tokens) {
            val rest = t.drop(3)
            if (t.startsWith("пол") && t.length >= 6 && t !in NUMBER_WORDS && (isUnitWord(rest) || isFood(rest))) {
                out += "пол"
                out += rest
            } else {
                out += t
            }
        }
        return out
    }

    /**
     * Finds the quantities in [tokens], skipping tokens already taken by food names ([covered]).
     * A number is followed by an optional unit; a unit alone ("ложка масла", "стакан молока") counts as one.
     */
    fun scan(tokens: List<String>, covered: BooleanArray): List<Quantity> {
        val out = ArrayList<Quantity>()
        var i = 0
        while (i < tokens.size) {
            if (covered[i]) { i++; continue }
            val number = parseNumber(tokens, i)
            if (number != null) {
                val j = i + number.second
                val unit = if (j < tokens.size && !covered[j]) parseUnit(tokens, j) else null
                val end = j + (unit?.second ?: 0)
                out += Quantity(number.first, unit?.first, i, end, bareSpoon = unit?.first == UnitKind.Tbsp && isSpoon(tokens[j]))
                i = end
                continue
            }
            if (tokens[i] == SEVERAL) {
                val j = i + 1
                val unit = if (j < tokens.size && !covered[j]) parseUnit(tokens, j) else null
                out += Quantity(3.0, unit?.first, i, j + (unit?.second ?: 0), vague = true)
                i = j + (unit?.second ?: 0)
                continue
            }
            val unit = parseUnit(tokens, i)
            if (unit != null) {
                out += Quantity(1.0, unit.first, i, i + unit.second, bareSpoon = unit.first == UnitKind.Tbsp && isSpoon(tokens[i]))
                i += unit.second
                continue
            }
            i++
        }
        return out
    }

    private val GENERIC = mapOf(
        UnitKind.Tbsp to 15.0, UnitKind.Tsp to 5.0, UnitKind.Cup to 240.0, UnitKind.Mug to 250.0, UnitKind.Plate to 250.0,
        UnitKind.Handful to 30.0, UnitKind.Can to 330.0, UnitKind.Pack to 200.0, UnitKind.Bottle to 500.0,
        UnitKind.Glass to 150.0, UnitKind.Slice to 30.0, UnitKind.Clove to 3.0,
    )

    /**
     * Grams for [q] of [food] and how sure we are. Weights stated in grams, kilos, millilitres or litres are exact;
     * a spoon or a piece uses the food's own table when it has one, a generic value otherwise.
     * Null when the amount cannot be worked out ("3 штуки гречки").
     */
    fun grams(q: Quantity, food: LexEntry?): Pair<Double, Double>? {
        val own = food?.units.orEmpty()
        fun perUnit(vararg keys: String): Double? = keys.firstNotNullOfOrNull { own[it] }
        val certain = if (q.vague) 0.55 else 0.8
        return when (val unit = q.unit) {
            UnitKind.Gram, UnitKind.Milli -> q.value to 0.9
            UnitKind.Kilo, UnitKind.Liter -> q.value * 1000 to 0.9
            null -> when {
                q.value >= 20 -> q.value to 0.8 // "гречка 200": grams
                else -> perUnit("piece", "slice")?.let { q.value * it to certain }
            }
            UnitKind.Piece -> perUnit("piece", "slice")?.let { q.value * it to certain }
            UnitKind.Tbsp -> {
                val spoon = if (q.bareSpoon && food?.bareSpoon == "tsp") UnitKind.Tsp else UnitKind.Tbsp
                perUnit(spoon.key())?.let { q.value * it to certain } ?: (q.value * GENERIC.getValue(spoon) to 0.65)
            }
            UnitKind.Portion -> food?.let { f -> q.value * (own["portion"] ?: f.defaultGrams) to 0.7 }
            UnitKind.Plate -> q.value * (perUnit("plate", "portion") ?: GENERIC.getValue(unit)) to if (perUnit("plate", "portion") != null) certain else 0.6
            UnitKind.Mug -> q.value * (perUnit("mug", "cup") ?: GENERIC.getValue(unit)) to if (perUnit("mug", "cup") != null) certain else 0.65
            else -> perUnit(unit.key())?.let { q.value * it to certain } ?: (q.value * GENERIC.getValue(unit) to 0.65)
        }
    }

    private fun UnitKind.key(): String = when (this) {
        UnitKind.Tbsp -> "tbsp"; UnitKind.Tsp -> "tsp"; UnitKind.Cup -> "cup"; UnitKind.Mug -> "mug"; UnitKind.Plate -> "plate"
        UnitKind.Portion -> "portion"; UnitKind.Handful -> "handful"; UnitKind.Can -> "can"; UnitKind.Pack -> "pack"
        UnitKind.Bottle -> "bottle"; UnitKind.Glass -> "glass"; UnitKind.Slice -> "slice"; UnitKind.Clove -> "clove"
        UnitKind.Piece -> "piece"; else -> name.lowercase()
    }
}
