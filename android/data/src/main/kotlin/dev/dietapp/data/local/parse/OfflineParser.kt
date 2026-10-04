package dev.dietapp.data.local.parse

import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.food.Text

/** A photo cannot be read without the model. The caller says so instead of guessing. */
class PhotoNeedsModel : Exception("photo needs the model")

/**
 * Understands what people type into the diary, in Russian, with no network and no model:
 * "гречка 200 г, 2 яйца, ложка масла", "чай с сахаром", "как обычно", and corrections such as
 * "масла было меньше", "это была половина", "убери хлеб".
 *
 * It knows the foods in [Lexicon]. Anything else becomes an item without nutrition, which turns into one
 * question ("Не нашёл «…»"), never into invented numbers.
 */
class OfflineParser(private val lexicon: Lexicon, private val threshold: Double = 0.6) : MessageParser {

    override suspend fun parse(request: ParseRequest): ParseResult {
        if (request.imageBase64 != null) throw PhotoNeedsModel()
        val text = request.text?.takeIf { it.isNotBlank() } ?: return ParseResult(emptyList(), null)
        return parseText(text, request.context)
    }

    fun parseText(text: String, ctx: ParseContext): ParseResult {
        val whole = tokenize(text)
        if (whole.isEmpty()) return ParseResult(emptyList(), null)

        usualMeal(whole, ctx)?.let { return it }
        correction(whole, ctx)?.let { return it }
        answer(whole, ctx)?.let { return it }
        return Normalizer.finalize(foods(text), badTarget = false, threshold = threshold)
    }

    // ---------- tokens ----------

    private fun tokenize(text: String): List<String> {
        val folded = Text.fold(text.replace(Regex("(?<=\\d),(?=\\d)"), "."))
        val raw = TOKEN.findAll(folded).map { it.value }.toList()
        return Quantities.splitHalf(raw) { word -> lexicon.matchAt(listOf(word), 0) != null }
    }

    private fun segments(text: String): List<List<String>> {
        val folded = Text.fold(text.replace(Regex("(?<=\\d),(?=\\d)"), "."))
        return folded.split(SEPARATORS).map { part ->
            Quantities.splitHalf(TOKEN.findAll(part).map { it.value }.toList()) { w -> lexicon.matchAt(listOf(w), 0) != null }
        }.filter { it.isNotEmpty() }
    }

    // ---------- "как обычно" ----------

    private fun usualMeal(tokens: List<String>, ctx: ParseContext): ParseResult? {
        // "как обычно" alone, or "на ужин как обычно": no food named. "гречка 200 г, как обычно" is just the buckwheat.
        val asksForUsual = tokens.any { t -> USUAL.any { Lexicon.similar(t, it) } } && foodHits(tokens).isEmpty()
        if (!asksForUsual) return null
        if (ctx.frequent.isEmpty()) {
            return ParseResult(emptyList(), "Пока нет привычных приёмов пищи: они появятся, когда ты запишешь одно и то же три раза.")
        }
        val wanted = when {
            tokens.any { Lexicon.similar(it, "завтрак") } -> "утром"
            tokens.any { Lexicon.similar(it, "обед") } -> "днём"
            tokens.any { Lexicon.similar(it, "ужин") } -> "вечером"
            else -> bucketOf(ctx.localTime)
        }
        val meal = ctx.frequent.firstOrNull { it.label.contains(wanted) } ?: ctx.frequent.first()
        val items = meal.items.map {
            ParsedItem(Action.Add, null, it.name, it.queryEn, it.grams, 0.9, preset = it.preset)
        }
        return Normalizer.finalize(items, badTarget = false, threshold = threshold)
    }

    private fun bucketOf(localTime: String?): String {
        val minutes = localTime?.split(":")?.let { (it.getOrNull(0)?.toIntOrNull() ?: 12) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: 12 * 60
        return when {
            minutes in 5 * 60 until 11 * 60 -> "утром"
            minutes in 11 * 60 until 16 * 60 -> "днём"
            minutes in 16 * 60 until 22 * 60 -> "вечером"
            else -> "ночью"
        }
    }

    // ---------- corrections: "убери хлеб", "это была половина", "масла было меньше", "не 200, а 150" ----------

    private fun hasCue(tokens: List<String>): Boolean =
        tokens.any { it in CUES } || ("не" in tokens && "а" in tokens) || ("самом" in tokens && "деле" in tokens)

    private fun correction(tokens: List<String>, ctx: ParseContext): ParseResult? {
        val removeAt = tokens.indexOfFirst { it in REMOVE }
        if (removeAt >= 0) {
            val rest = tokens.drop(removeAt + 1)
            val named = namedEntry(rest, ctx.entries)
            val target = if (rest.none { it !in FILLERS && it !in CONNECTORS }) ctx.entries.lastOrNull() else named
            return if (target == null) {
                ParseResult(emptyList(), Normalizer.UNCLEAR_TARGET_QUESTION)
            } else {
                Normalizer.finalize(listOf(ParsedItem(Action.Remove, target.id, target.name, null, 0.0, 0.9)), false, threshold)
            }
        }
        if (ctx.entries.isEmpty() || !hasCue(tokens)) return null

        val named = namedEntry(tokens, ctx.entries)
        // "съел банан" is food. "банана было меньше" is about the banana already in the diary.
        if (named == null && foodHits(tokens).isNotEmpty()) return null
        val target = named ?: ctx.entries.last()

        val quantities = Quantities.scan(tokens, BooleanArray(tokens.size))
        val withUnit = quantities.lastOrNull { it.unit != null }
        val factor = factorOf(tokens)
        val grams: Double
        val confidence: Double
        when {
            withUnit != null -> {
                val (g, c) = Quantities.grams(withUnit, lexicon.find(target.name)) ?: return null
                grams = g
                confidence = c
            }
            factor != null -> {
                grams = target.grams * factor.first
                confidence = factor.second
            }
            else -> {
                // "яиц было 3", "не 200, а 150": a bare number. 20 and more is grams, fewer is a count of pieces.
                val bare = quantities.lastOrNull { it.value >= 1.0 } ?: return null
                val (g, c) = Quantities.grams(bare, lexicon.find(target.name)) ?: return null
                grams = g
                confidence = c
            }
        }
        if (grams <= 0.0 || grams > Normalizer.MAX_GRAMS) return null
        val item = ParsedItem(Action.Update, target.id, target.name, null, grams, confidence)
        return Normalizer.finalize(listOf(item), badTarget = false, threshold = threshold)
    }

    /** (factor, confidence) for "вдвое меньше", "это была половина", "побольше". */
    private fun factorOf(tokens: List<String>): Pair<Double, Double>? {
        val has = { word: String -> tokens.any { Lexicon.similar(it, word) } }
        return when {
            has("пополам") || has("половина") || has("половину") || (has("вдвое") && has("меньше")) ||
                (has("раза") && has("два") && has("меньше")) -> 0.5 to 0.8
            has("вдвое") || (has("раза") && has("два") && has("больше")) || has("двойная") || has("двойную") -> 2.0 to 0.8
            has("втрое") -> 3.0 to 0.8
            has("меньше") || has("поменьше") || has("чуть") && has("меньше") -> 2.0 / 3.0 to 0.65
            has("больше") || has("побольше") -> 1.5 to 0.65
            else -> null
        }
    }

    /** The most recent diary entry any token of [tokens] names. */
    private fun namedEntry(tokens: List<String>, entries: List<ContextEntry>): ContextEntry? {
        val words = tokens.filter { it !in FILLERS && it !in CONNECTORS && it !in CUES && it !in REMOVE && it.length >= 3 && !it.first().isDigit() }
        return entries.asReversed().firstOrNull { entry ->
            val entryWords = Text.tokenize(entry.name)
            words.any { w -> entryWords.any { Lexicon.similar(it, w) } }
        }
    }

    // ---------- answer to the question we asked ----------

    private fun answer(tokens: List<String>, ctx: ParseContext): ParseResult? {
        val pending = ctx.pending ?: return null
        val target = pending.targetId?.let { id -> ctx.entries.firstOrNull { it.id == id } } ?: return null

        val hits = foodHits(tokens)
        val covered = BooleanArray(tokens.size)
        hits.forEach { h -> for (k in h.start until h.end) covered[k] = true }
        val quantity = Quantities.scan(tokens, covered).firstOrNull()

        // A reply is short ("борщ", "300 г", "это гречка"). Longer text is a new message, not an answer.
        if (hits.size > 1 || tokens.size > 6) return null

        val item = if (hits.isNotEmpty()) {
            val entry = hits.first().entry
            val grams = quantity?.let { Quantities.grams(it, entry) }?.first ?: target.grams
            update(target, entry, grams, 0.8)
        } else if (quantity != null) {
            val g = Quantities.grams(quantity, lexicon.find(target.name))?.first ?: quantity.value
            ParsedItem(Action.Update, target.id, target.name, null, g, 0.85)
        } else {
            val name = tokens.filter { it !in FILLERS && it !in CONNECTORS }.joinToString(" ")
            if (name.isBlank()) return null
            ParsedItem(Action.Update, target.id, name, null, target.grams, 0.7)
        }
        if (item.grams <= 0.0 || item.grams > Normalizer.MAX_GRAMS) return null
        return Normalizer.finalize(listOf(item), badTarget = false, threshold = threshold)
    }

    private fun update(target: ContextEntry, entry: LexEntry, grams: Double, confidence: Double) =
        ParsedItem(Action.Update, target.id, entry.name, entry.query, grams, confidence, preset = presetOf(entry))

    // ---------- plain food: "гречка 200 г, 2 яйца и ложка масла" ----------

    private class FoodHit(val entry: LexEntry, val start: Int, val end: Int)

    private fun foodHits(tokens: List<String>): List<FoodHit> {
        val hits = ArrayList<FoodHit>()
        var i = 0
        while (i < tokens.size) {
            val m = lexicon.matchAt(tokens, i)
            if (m != null) { hits += FoodHit(m.first, i, i + m.second); i += m.second } else i++
        }
        return hits
    }

    private fun presetOf(entry: LexEntry): Preset? = entry.per100?.let { Preset(it, entry.name, approximate = true) }

    private fun foods(text: String): List<ParsedItem> {
        // folding turns "казеиновый" into "казеиновыи": a name we do not know is shown as the user wrote it
        val spelled = TOKEN.findAll(text.lowercase()).associate { Text.fold(it.value) to it.value }
        val items = ArrayList<ParsedItem>()
        for (tokens in segments(text)) items += segment(tokens) { spelled[it] ?: it }
        return items
    }

    private fun segment(tokens: List<String>, spelled: (String) -> String): List<ParsedItem> {
        val hits = foodHits(tokens)
        val covered = BooleanArray(tokens.size)
        hits.forEach { h -> for (k in h.start until h.end) covered[k] = true }
        val quantities = Quantities.scan(tokens, covered)
        quantities.forEach { q -> for (k in q.start until q.end) covered[k] = true }

        // "без сахара": the food is explicitly left out
        val excluded = hits.filter { h -> (1..2).any { d -> tokens.getOrNull(h.start - d) == "без" } }
        val kept = hits - excluded.toSet()

        // words we do not know next to ones we do ("казеиновый протеин 30 г с водой"): a food of their own
        val strangers = if (kept.isEmpty()) emptyList() else unknownRuns(tokens, covered, quantities, kept)

        // each quantity goes to the nearest food that has no quantity yet
        val bound = HashMap<Any, Quantity>()
        for (q in quantities) {
            val free = (kept + strangers).filter { it !in bound }
            val target = free.minByOrNull { if (it is FoodHit) gap(q, it.start, it.end) else gap(q, (it as Run).start, it.end) } ?: continue
            bound[target] = q
        }

        val items = ArrayList<Pair<Int, ParsedItem>>()
        for (hit in kept) {
            val e = hit.entry
            val q = bound[hit]
            val withModifier = tokens.getOrNull(hit.start - 1).let { it == "с" || it == "со" }
            val measured = q?.let { Quantities.grams(it, e) }
            val (grams, confidence) = when {
                measured != null -> measured
                withModifier && e.withGrams != null -> e.withGrams to 0.6
                else -> e.defaultGrams to if (e.variable) 0.55 else 0.65
            }
            if (grams <= 0.0 || grams > Normalizer.MAX_GRAMS) continue
            items += hit.start to ParsedItem(Action.Add, null, e.name, e.query, grams, confidence, preset = presetOf(e))
        }
        for (run in strangers) {
            val measured = bound[run]?.let { Quantities.grams(it, null) }
            val grams = measured?.first ?: 100.0
            if (grams <= 0.0 || grams > Normalizer.MAX_GRAMS) continue
            items += run.start to ParsedItem(Action.Add, null, run.words.joinToString(" ", transform = spelled), null, grams, if (measured != null) 0.8 else 0.7)
        }

        if (kept.isEmpty() && excluded.isEmpty()) unknown(tokens, covered, quantities, spelled)?.let { items += 0 to it }
        return items.sortedBy { it.first }.map { it.second }
    }

    /** A run of words the dictionary does not know, standing where a food would. */
    private class Run(val start: Int, val end: Int, val words: List<String>)

    /**
     * Unknown words that name a food of their own: up to four in a row, not right after a known food (then they
     * describe it: "гречка рассыпчатая"), and either starting the phrase or right next to an amount.
     */
    private fun unknownRuns(tokens: List<String>, covered: BooleanArray, quantities: List<Quantity>, hits: List<FoodHit>): List<Run> {
        fun word(i: Int) = !covered[i] && tokens[i] !in FILLERS && tokens[i] !in CONNECTORS && !tokens[i][0].isDigit()
        val runs = ArrayList<Run>()
        var i = 0
        while (i < tokens.size) {
            if (!word(i)) { i++; continue }
            var j = i
            while (j < tokens.size && word(j)) j++
            // "гречка рассыпчатая", "кальмары в кляре": right after a food, or after a food and a preposition
            val afterFood = hits.any { it.end == i || (it.end == i - 1 && tokens[i - 1] in CONNECTORS) }
            val nextToAmount = quantities.any { it.start == j || it.end == i }
            if (j - i <= 4 && !afterFood && (i == 0 || nextToAmount)) runs += Run(i, j, tokens.subList(i, j))
            i = j
        }
        return runs
    }

    /** Words we do not know: one item without nutrition, so the app can ask what it is. */
    private fun unknown(tokens: List<String>, covered: BooleanArray, quantities: List<Quantity>, spelled: (String) -> String): ParsedItem? {
        val words = tokens.indices.filter { !covered[it] && tokens[it] !in FILLERS && tokens[it] !in CONNECTORS && !tokens[it][0].isDigit() }
            .map { spelled(tokens[it]) }
        if (words.isEmpty() || words.size > 4) return null
        val q = quantities.firstOrNull()
        val measured = q?.let { Quantities.grams(it, null) }
        val grams = measured?.first ?: 100.0
        if (grams <= 0.0 || grams > Normalizer.MAX_GRAMS) return null
        // 0.7: sure enough that the question is "what is it?", not "how many grams?"
        return ParsedItem(Action.Add, null, words.joinToString(" "), null, grams, if (measured != null) 0.8 else 0.7)
    }

    private fun gap(q: Quantity, start: Int, end: Int): Int = when {
        q.end <= start -> start - q.end
        end <= q.start -> q.start - end
        else -> 0
    }

    companion object {
        private val TOKEN = Regex("\\d+/\\d+|\\d+(?:[.,]\\d+)?|\\p{L}+")
        private val SEPARATORS = Regex("\\s+(?:и|плюс)\\s+|[,;\\n+]")

        private fun folded(vararg words: String): Set<String> = words.map { Text.fold(it) }.toSet()

        private val USUAL = listOf("обычно", "обычный", "обычное", "обычную", "обычного", "привычный", "привычное", "привычную", "всегда").map(Text::fold)
        private val REMOVE = folded("убери", "убрать", "удали", "удалить", "сотри", "стереть", "вычеркни", "убирай")
        private val CUES = folded(
            "было", "была", "были", "оказалось", "оказалась", "вдвое", "втрое", "пополам", "меньше", "поменьше", "больше",
            "побольше", "половина", "половину", "двойная", "двойную",
        )

        val FILLERS: Set<String> = folded(
            "я", "мне", "съел", "съела", "съели", "ел", "ела", "поел", "поела", "покушал", "покушала", "позавтракал", "пообедал",
            "поужинал", "перекусил", "перекусила", "выпил", "выпила", "пил", "пила", "завтрак", "обед", "ужин", "перекус",
            "сегодня", "вчера", "утром", "днем", "днём", "вечером", "ночью", "примерно", "около", "где", "то", "порядка", "еще",
            "ещё", "тоже", "также", "был", "была", "было", "были", "потом", "затем", "после", "до", "это", "вот", "как", "всего",
            "только", "немного", "чуть", "мало", "много", "небольшая", "небольшой", "большая", "большой", "маленькая", "маленький",
            "домашний", "домашняя", "свежий", "свежая", "привет", "здравствуй", "добрый", "спасибо", "пожалуйста", "ок", "ладно",
            "приблизительно", "типа", "наверное", "кажется", "штук", "штуки", "обычно", "обычный", "обычное", "всегда",
        )
        val CONNECTORS: Set<String> = folded("с", "со", "из", "в", "во", "на", "к", "по", "за", "для", "от", "у", "о", "об", "и", "а", "но", "не", "без", "ли", "же", "бы")
    }
}

/** The offline parser over the current dictionary, which includes the user's own foods (they can change any time). */
class LiveOfflineParser(private val lexicon: suspend () -> Lexicon) : MessageParser {
    override suspend fun parse(request: ParseRequest): ParseResult = OfflineParser(lexicon()).parse(request)
}
