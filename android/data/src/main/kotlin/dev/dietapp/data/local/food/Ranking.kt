package dev.dietapp.data.local.food

import kotlin.math.min
import kotlin.math.pow

/**
 * Re-ranking of candidate foods for a query. A port of services/nutrition/nutrition/search.py (read its
 * header for the reasoning). Keep the word lists and the arithmetic identical: FoodCatalogParityTest pins
 * both implementations to the same golden answers.
 */
object Ranking {
    private fun stems(words: String): Set<String> = words.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapTo(HashSet()) { Text.stem(it) }

    /** What people say -> what USDA calls it. Applied to queries only, longest phrase first. */
    val SYNONYMS: Map<String, String> = linkedMapOf(
        "hot dog" to "frankfurter meat", "hotdog" to "frankfurter meat", "french fries" to "potatoes french fried",
        "corn flakes" to "cereals corn flakes", "cornflakes" to "cereals corn flakes", "cream cheese" to "cheese cream",
        "oatmeal" to "oats", "porridge" to "oats", "yoghurt" to "yogurt", "courgette" to "zucchini", "aubergine" to "eggplant",
        "mince" to "ground", "minced" to "ground", "coriander" to "cilantro", "prawn" to "shrimp", "prawns" to "shrimp",
        "crisps" to "snacks potato chips", "beetroot" to "beets", "rocket" to "arugula", "biscuit" to "cookies",
        "biscuits" to "cookies", "ketchup" to "catsup", "kiwi" to "kiwifruit", "bacon" to "pork cured bacon",
    )
    private val SYNONYM_RE = Regex("\\b(" + SYNONYMS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b")

    val CATEGORY_HEADS = stems(
        "beverages cereals nuts seeds candies snacks spices soup soups babyfood fast restaurant beans legumes game sauce " +
            "sweeteners desserts fish dressing crustaceans mollusks",
    )
    val CATEGORY_FILLER = stems("ready eat food foods alcoholic baby salad meat")
    val PROCESSED = stems(
        "breaded battered frozen canned microwaved prepared restaurant babyfood fast imitation substitute artificial " +
            "dehydrated powder mix syrup flavored sweetened instant candies snacks dessert pie sandwich nuggets tenders patty " +
            "sticks rolls croissants cake cookies muffin pastry gravy spread dip juice drink smoothie shake paste flour bran " +
            "oil extract concentrate mayonnaise crackers bagels bread baby formula marmalade jam jelly kimchi soymilk " +
            "skin feet neck giblets wing wings dry evaporated condensed buttermilk chocolate yolk deli rotisserie seasoned " +
            "prepackaged fermented",
    )
    val EXOTIC = stems("sheep goat human buffalo camel mare donkey deer elk bison moose canadian")
    val LESS_COMMON = stems("light lite diet reduced low lowfat nonfat free decaffeinated fried")
    val PREFERRED = stems("whole raw fresh regular plain red ripe granulated cultured black roasted enriched")
    val STATE_WORDS = stems("cooked boiled fried roasted baked dried stewed braised grilled broiled steamed smoked pickled")
    private val NOISE_SEGMENTS: List<Set<String>> = listOf(
        setOf("broiler", "fryer"), setOf("meat"), setOf("roasting"), setOf("all", "commercial", "variety"),
        setOf("year", "round", "average"), setOf("cured"), setOf("fresh"), setOf("carbonated"),
    )
    private val BRAND = Regex("[A-Z]{3,}")
    private val NOT_BRANDS = setOf("USDA", "NFS", "UHT", "DHA", "ARA", "PUFA", "MUFA", "RTE", "FDA")

    private fun isBranded(name: String) = BRAND.findAll(name).any { it.value !in NOT_BRANDS }

    class NameInfo(
        val head: Set<String>,
        val second: Set<String>,
        val later: Set<String>,
        val category: Set<String>,
        val all: Set<String>,
        val branded: Boolean,
    )

    fun analyse(name: String, brand: String? = null): NameInfo {
        val everything: Set<String> = Text.stemSet(name) + (if (brand != null) Text.stemSet(brand) else emptySet())
        var segments: List<Set<String>> = name.split(",").map { Text.stemSet(it) }.filter { it.isNotEmpty() && it !in NOISE_SEGMENTS }
        var category: Set<String> = emptySet()
        if (segments.size > 1 && segments[0].any { it in CATEGORY_HEADS } && (CATEGORY_HEADS + CATEGORY_FILLER).containsAll(segments[0])) {
            category = segments[0]
            segments = segments.drop(1)
        }
        if (brand != null) segments = segments + listOf(Text.stemSet(brand))
        if (segments.isEmpty() || STATE_WORDS.containsAll(segments[0])) segments = listOf(emptySet<String>()) + segments
        return NameInfo(
            head = segments[0],
            second = segments.getOrElse(1) { emptySet() },
            later = if (segments.size > 2) segments.drop(2).flatten().toSet() else emptySet(),
            category = category,
            all = everything,
            branded = isBranded(name),
        )
    }

    fun expandQuery(query: String): String {
        val folded = Text.tokenize(query).joinToString(" ")
        return SYNONYM_RE.replace(folded) { SYNONYMS.getValue(it.groupValues[1]) }
    }

    private fun round4(x: Double) = Math.round(x * 10_000.0) / 10_000.0

    /** (score, coverage) of a food name for a query, both 0..1. */
    fun scoreMatch(query: Set<String>, info: NameInfo): Pair<Double, Double> {
        if (query.isEmpty() || info.all.isEmpty()) return 0.0 to 0.0
        val inName = query.intersect(info.all)
        val content = query - STATE_WORDS
        if (inName.isEmpty() || (content.isNotEmpty() && content.none { it in info.all })) return 0.0 to 0.0
        val coverage = inName.size.toDouble() / query.size

        fun position(token: String) = when {
            token in info.head -> 1.0
            token in info.second -> 0.6
            token in info.later -> 0.3
            else -> 0.0
        }

        val where = query.sumOf { position(it) } / query.size
        val headPrecision = if (info.head.isNotEmpty()) query.count { it in info.head }.toDouble() / info.head.size else 0.0
        val namePrecision = inName.size.toDouble() / info.all.size
        var score = 0.5 * where + 0.3 * headPrecision + 0.2 * namePrecision

        val zone = info.head + info.second + info.category
        val inZone = zone.intersect(PROCESSED) - query
        val elsewhere = (info.all.intersect(PROCESSED) - query) - inZone
        score *= 0.85.pow(min(2, inZone.size)) * 0.93.pow(min(2, elsewhere.size))
        score *= 0.85.pow(min(1, (zone.intersect(EXOTIC) - query).size))
        score *= 0.9.pow(min(2, (info.all.intersect(LESS_COMMON) - query).size))
        score *= 1.03.pow(min(3, (info.all.intersect(PREFERRED) - query).size))
        if (info.branded) score *= 0.7
        if (query.none { it in STATE_WORDS } && info.all.any { it in STATE_WORDS }) score *= 0.93
        score *= coverage * coverage
        return round4(min(score, 1.0)) to round4(coverage)
    }
}
