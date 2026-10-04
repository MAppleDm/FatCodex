package dev.dietapp.data.local.parse

import dev.dietapp.data.local.CatalogSource
import dev.dietapp.data.local.FoodResolver
import dev.dietapp.data.local.TestFiles
import dev.dietapp.data.local.food.FoodCatalog
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline parser on the real dictionary and the real USDA catalog, as shipped in the app. */
class OfflineParserTest {
    companion object {
        val lexicon: Lexicon by lazy {
            FileInputStream(TestFiles.repoFile("android/data/src/main/assets/local/ru_foods.json")).use { Lexicon.fromStream(it) }
        }
        val catalog: FoodCatalog by lazy {
            FileInputStream(TestFiles.repoFile("android/data/src/main/assets/foods_sr_legacy.tsv.gzip")).use { FoodCatalog.fromGzippedTsv(it) }
        }
        val resolver by lazy { FoodResolver(CatalogSource { catalog }, lexicon) }
    }

    private val parser = OfflineParser(lexicon)

    private fun parse(text: String, ctx: ParseContext = ParseContext()): ParseResult =
        runBlocking { parser.parse(ParseRequest(text, context = ctx)) }

    /** "name grams" for each item, to compare against what a person would write. */
    private fun shape(r: ParseResult) = r.items.map { "${it.name} ${it.grams.toInt()}" }

    private fun kcal(item: ParsedItem): Double? =
        runBlocking { resolver.resolve(item.name, item.queryEn, item.preset) }?.let { it.per100.kcal * item.grams / 100 }

    private val entries = listOf(
        ContextEntry("e1", "гречка", 200.0),
        ContextEntry("e2", "масло сливочное", 20.0),
        ContextEntry("e3", "хлеб", 60.0),
    )

    // ---------- plain food ----------

    @Test fun `grams after the food`() {
        assertEquals(listOf("гречка 200"), shape(parse("гречка 200 г")))
        assertEquals(listOf("гречка 200"), shape(parse("гречка 200г")))
        assertEquals(listOf("гречка 200"), shape(parse("Гречка 200")))
        assertEquals(listOf("гречка 150"), shape(parse("гречка 150 грамм")))
    }

    @Test fun `grams before the food`() {
        assertEquals(listOf("гречка 200"), shape(parse("200 г гречки")))
        assertEquals(listOf("творог 150"), shape(parse("150 грамм творога")))
    }

    @Test fun `a list of foods`() {
        assertEquals(listOf("куриная грудка 150", "гречка 200", "огурец 100"), shape(parse("куриная грудка 150 г, гречка 200 г, огурец")))
        assertEquals(listOf("рис 150", "яйцо варёное 100"), shape(parse("рис 150 и 2 яйца")))
        assertEquals(listOf("овсяная каша 250", "банан 120"), shape(parse("овсянка, банан")))
        assertEquals(listOf("гречка 100", "кефир 200"), shape(parse("гречка 100 г\nкефир")))
    }

    @Test fun `numbers of pieces use what a piece weighs`() {
        assertEquals(listOf("яйцо варёное 100"), shape(parse("2 яйца")))
        assertEquals(listOf("яйцо варёное 150"), shape(parse("три яйца")))
        assertEquals(listOf("яйцо варёное 250"), shape(parse("пять яиц")))
        assertEquals(listOf("банан 240"), shape(parse("2 банана")))
        assertEquals(listOf("яблоко 180"), shape(parse("яблоко")))
        assertEquals(listOf("хлеб 90"), shape(parse("3 ломтика хлеба")))
        assertEquals(listOf("пельмени 120"), shape(parse("10 пельменей")))
    }

    @Test fun `spoons, cups and portions`() {
        assertEquals(listOf("масло сливочное 14"), shape(parse("ложка масла")))
        assertEquals(listOf("масло сливочное 5"), shape(parse("чайная ложка масла")))
        assertEquals(listOf("масло сливочное 28"), shape(parse("2 столовые ложки масла")))
        assertEquals(listOf("сахар 8"), shape(parse("2 ложки сахара"))) // a bare spoon of sugar is a teaspoon
        assertEquals(listOf("сахар 24"), shape(parse("2 ст.л. сахара")))
        assertEquals(listOf("мёд 7"), shape(parse("ложка мёда")))
        assertEquals(listOf("молоко 245"), shape(parse("стакан молока")))
        assertEquals(listOf("молоко 122"), shape(parse("полстакана молока")))
        assertEquals(listOf("борщ 300"), shape(parse("тарелка борща")))
        assertEquals(listOf("кофе 250"), shape(parse("кружка кофе")))
        assertEquals(listOf("грецкие орехи 30"), shape(parse("горсть орехов")))
    }

    @Test fun `half and one and a half`() {
        assertEquals(listOf("яблоко 90"), shape(parse("пол яблока")))
        assertEquals(listOf("банан 60"), shape(parse("полбанана")))
        assertEquals(listOf("молоко 367"), shape(parse("полтора стакана молока")))
        assertEquals(listOf("кефир 500"), shape(parse("пол литра кефира")))
        assertEquals(listOf("кефир 500"), shape(parse("полкило кефира")))
        assertEquals(listOf("кефир 250"), shape(parse("0,25 л кефира")))
        assertEquals(listOf("кефир 500"), shape(parse("0.5 л кефира")))
    }

    @Test fun `milliliters and kilograms`() {
        assertEquals(listOf("кефир 330"), shape(parse("кефир 330 мл")))
        assertEquals(listOf("арбуз 1500"), shape(parse("1,5 кг арбуза")))
    }

    @Test fun `inflected forms are understood`() {
        assertEquals(listOf("гречка 200"), shape(parse("гречки 200 г")))
        assertEquals(listOf("чай 250"), shape(parse("выпил чая")))
        assertEquals(listOf("яблоко 180"), shape(parse("съел яблок")))
        assertEquals(listOf("масло сливочное 14"), shape(parse("съел ложку масла")))
        assertEquals(listOf("рис 180"), shape(parse("риса")))
        assertEquals(listOf("лимон 20"), shape(parse("лимона")))
    }

    @Test fun `with and without`() {
        assertEquals(listOf("чай 250", "сахар 5"), shape(parse("чай с сахаром")))
        assertEquals(listOf("чай 250"), shape(parse("чай без сахара")))
        assertEquals(listOf("кофе с молоком 250"), shape(parse("кофе с молоком"))) // a dish of its own in the table
        assertEquals(listOf("борщ 300", "сметана 20"), shape(parse("борщ со сметаной")))
        assertEquals(listOf("чай 250", "сахар 8"), shape(parse("чай с 2 ложками сахара"))) // a bare spoon of sugar: 4 g
    }

    @Test fun `filler words are ignored`() {
        assertEquals(listOf("гречка 200"), shape(parse("Сегодня на обед съел гречку 200 г")))
        assertEquals(listOf("банан 120"), shape(parse("я поел банан")))
        assertEquals(emptyList<String>(), shape(parse("привет")))
        assertEquals(emptyList<String>(), shape(parse("   ")))
    }

    @Test fun `a longer phrase beats a shorter one`() {
        assertEquals(listOf("оливковое масло 14"), shape(parse("ложка оливкового масла")))
        assertEquals(listOf("ржаной хлеб 64"), shape(parse("2 ломтика ржаного хлеба")))
        assertEquals(listOf("куриная грудка 150"), shape(parse("куриная грудка")))
        assertEquals(listOf("яичница 100"), shape(parse("яичница")))
        assertEquals(listOf("вареники с творогом 200"), shape(parse("вареники с творогом")))
    }

    // ---------- confidence and questions ----------

    @Test fun `an exact weight is trusted, a default portion less so`() {
        assertEquals(0.9, parse("гречка 200 г").items.single().confidence, 1e-9)
        assertEquals(0.8, parse("2 яйца").items.single().confidence, 1e-9)
        assertEquals(0.65, parse("банан").items.single().confidence, 1e-9)
        assertNull(parse("банан").clarifyQuestion)
    }

    @Test fun `a vague dish asks for the amount, once`() {
        val r = parse("салат и суп")
        assertEquals(listOf("овощной салат", "суп"), r.items.map { it.name })
        assertEquals("Уточни, пожалуйста: сколько граммов «овощной салат»?", r.clarifyQuestion)
        assertEquals(1, r.items.count { it.clarifyQuestion != null })
    }

    @Test fun `an unknown food becomes an item without numbers and a question later`() {
        val r = parse("кальмары в кляре 200 г")
        assertEquals(1, r.items.size)
        // "кальмары" is known; "в кляре" is ignored
        assertEquals("кальмар", r.items.single().name)
        val u = parse("чахохбили 250 г").items.single()
        assertEquals("чахохбили", u.name)
        assertEquals(250.0, u.grams, 0.0)
        assertNull(u.queryEn)
        assertNull(u.preset)
        assertEquals(0.8, u.confidence, 1e-9)
        assertNull(parse("чахохбили").clarifyQuestion) // asking "how many grams?" would be the wrong question
    }

    @Test fun `an unknown food next to a known one keeps its amount, and the known one gets its own`() {
        val r = parse("казеиновый протеин 30 грамм с водой")
        assertEquals(listOf("казеиновый протеин 30", "вода 250"), shape(r))
        assertEquals("spelled as written, not folded", "казеиновый протеин", r.items[0].name)
        assertEquals(null, kcal(r.items[0]))
    }

    @Test fun `words right after a known food describe it, they are not a food`() {
        assertEquals(listOf("гречка 200"), shape(parse("гречка рассыпчатая 200 г")))
        assertEquals(listOf("банан 120"), shape(parse("банан спелый")))
    }

    @Test fun `an unknown food first in the phrase is kept even without an amount`() {
        assertEquals(listOf("абырвалг 100", "кофе 200"), shape(parse("абырвалг с кофе")))
    }

    @Test fun `an impossible amount is dropped`() {
        assertEquals(emptyList<String>(), shape(parse("гречка 9000 г")))
    }

    // ---------- numbers resolved against the real catalog ----------

    @Test fun `the numbers come from the database`() {
        fun k(text: String) = kcal(parse(text).items.first())!!
        assertEquals(184.0, k("гречка 200 г"), 0.5)
        assertEquals(247.5, k("куриная грудка 150 г"), 1.0)
        assertEquals(77.5, k("яйцо варёное"), 1.0)
        assertEquals(155.0, k("2 яйца"), 1.0)
        assertEquals(143.4, k("20 г сливочного масла"), 0.5)
        assertEquals(107.0, k("банан"), 5.0)
        assertEquals(309.0, k("лосось 150 г"), 2.0) // Atlantic salmon, cooked: 206 kcal per 100 g
    }

    @Test fun `russian dishes use the table and are marked approximate`() {
        val borscht = parse("тарелка борща").items.single()
        assertNotNull(borscht.preset)
        assertEquals(true, borscht.preset!!.approximate)
        assertEquals(147.0, kcal(borscht)!!, 1.0) // 49 kcal per 100 g, a 300 g plate
        val dumplings = parse("пельмени 200 г").items.single()
        assertEquals(550.0, kcal(dumplings)!!, 1.0)
    }

    @Test fun `every food in the dictionary resolves to something`() {
        val unresolved = lexicon.entries.filter { e ->
            runBlocking { resolver.resolve(e.name, e.query, null) } == null
        }.map { it.name }
        assertTrue("not found in the catalog: $unresolved", unresolved.isEmpty())
    }

    @Test fun `every dictionary food has a plausible energy density`() {
        for (e in lexicon.entries) {
            val per100 = runBlocking { resolver.resolve(e.name, e.query, null) }!!.per100
            assertTrue("${e.name}: ${per100.kcal} kcal/100g", per100.kcal in 0.0..900.0)
            assertTrue("${e.name}: macros ${per100.protein + per100.fat + per100.carbs} g/100g", per100.protein + per100.fat + per100.carbs <= 101.0)
        }
    }

    @Test fun `table values are consistent with their macros`() {
        // 4/9/4 kcal per gram of protein/fat/carbs: a typo in the table shows up here
        for (e in lexicon.entries.filter { it.per100 != null }) {
            val p = e.per100!!
            val atwater = 4 * p.protein + 9 * p.fat + 4 * p.carbs
            assertTrue("${e.name}: ${p.kcal} kcal vs ${atwater} from macros", kotlin.math.abs(p.kcal - atwater) <= maxOf(25.0, 0.2 * p.kcal))
        }
    }

    // ---------- "как обычно" ----------

    @Test fun `as usual picks the habitual meal for the time of day`() {
        val breakfast = FrequentMeal("овсянка, банан (утром)", listOf(FrequentItem("овсяная каша", "oats, cooked", 250.0), FrequentItem("банан", "bananas, raw", 120.0)))
        val dinner = FrequentMeal("гречка, курица (вечером)", listOf(FrequentItem("гречка", null, 200.0)))
        val ctx = ParseContext(frequent = listOf(dinner, breakfast), localTime = "08:10")
        assertEquals(listOf("овсяная каша 250", "банан 120"), shape(parse("как обычно", ctx)))
        assertEquals(listOf("гречка 200"), shape(parse("на ужин как обычно", ctx)))
        assertEquals(listOf("овсяная каша 250", "банан 120"), shape(parse("мой обычный завтрак", ctx)))
        assertEquals(listOf("гречка 200"), shape(parse("как обычно", ctx.copy(localTime = "19:30"))))
    }

    @Test fun `as usual with no history explains itself`() {
        val r = parse("как обычно")
        assertTrue(r.items.isEmpty())
        assertTrue(r.clarifyQuestion!!.contains("три раза"))
    }

    @Test fun `as usual keeps the nutrition of the earlier entry`() {
        val preset = Preset(dev.dietapp.data.domain.Per100(100.0, 1.0, 1.0, 1.0), "Something", false)
        val ctx = ParseContext(frequent = listOf(FrequentMeal("x (утром)", listOf(FrequentItem("штука", null, 50.0, preset)))), localTime = "08:00")
        assertEquals(preset, parse("как обычно", ctx).items.single().preset)
    }

    @Test fun `a plain food message mentioning usual is not a request`() {
        assertEquals(listOf("гречка 200"), shape(parse("гречка 200 г, как обычно")))
    }

    // ---------- corrections ----------

    @Test fun `remove`() {
        val r = parse("убери хлеб", ParseContext(entries = entries))
        assertEquals(listOf(Action.Remove), r.items.map { it.action })
        assertEquals("e3", r.items.single().targetId)
        assertEquals("e3", parse("удали хлеб", ParseContext(entries = entries)).items.single().targetId)
        assertEquals("e3", parse("убери", ParseContext(entries = entries)).items.single().targetId) // the last entry
    }

    @Test fun `remove of something not in the diary asks which`() {
        val r = parse("убери борщ", ParseContext(entries = entries))
        assertTrue(r.items.isEmpty())
        assertEquals(Normalizer.UNCLEAR_TARGET_QUESTION, r.clarifyQuestion)
        assertEquals(Normalizer.UNCLEAR_TARGET_QUESTION, parse("убери").clarifyQuestion)
    }

    @Test fun `half of the last entry`() {
        val r = parse("это была половина", ParseContext(entries = entries))
        val item = r.items.single()
        assertEquals(Action.Update, item.action)
        assertEquals("e3", item.targetId)
        assertEquals(30.0, item.grams, 0.0)
        assertEquals(30.0, parse("пополам", ParseContext(entries = entries)).items.single().grams, 0.0)
        assertEquals(30.0, parse("в два раза меньше", ParseContext(entries = entries)).items.single().grams, 0.0)
        assertEquals(30.0, parse("вдвое меньше", ParseContext(entries = entries)).items.single().grams, 0.0)
    }

    @Test fun `less of a named food`() {
        val r = parse("масла было меньше", ParseContext(entries = entries))
        val item = r.items.single()
        assertEquals("e2", item.targetId)
        assertEquals(20.0 * 2 / 3, item.grams, 1e-9)
        assertEquals(0.65, item.confidence, 1e-9)
        assertEquals(300.0, parse("гречки было больше", ParseContext(entries = entries)).items.single().grams, 1e-9)
    }

    @Test fun `twice as much`() {
        assertEquals(40.0, parse("масла было вдвое больше", ParseContext(entries = entries)).items.single().grams, 0.0)
    }

    @Test fun `a corrected weight`() {
        val r = parse("не 200, а 150", ParseContext(entries = listOf(entries[0])))
        assertEquals(150.0, r.items.single().grams, 0.0)
        assertEquals("e1", r.items.single().targetId)
        assertEquals(150.0, parse("гречки было 150 г", ParseContext(entries = entries)).items.single().grams, 0.0)
        assertEquals(15.0, parse("масла было 15 г", ParseContext(entries = entries)).items.single().grams, 0.0)
        assertEquals("e2", parse("масла было 15 г", ParseContext(entries = entries)).items.single().targetId)
        assertEquals(28.0, parse("масла было 2 ложки", ParseContext(entries = entries)).items.single().grams, 0.0)
        assertEquals(150.0, parse("на самом деле 150", ParseContext(entries = entries.take(1))).items.single().grams, 0.0)
    }

    @Test fun `a food that is not in the diary yet is added, not corrected`() {
        val r = parse("банана было 2", ParseContext(entries = entries))
        assertEquals(listOf(Action.Add), r.items.map { it.action })
    }

    @Test fun `a correction with nothing to correct is plain parsing`() {
        assertEquals(listOf("гречка 200"), shape(parse("гречка 200 г было", ParseContext())))
    }

    @Test fun `new food is not mistaken for a correction`() {
        assertEquals(listOf("банан 120"), shape(parse("съел банан", ParseContext(entries = entries))))
        assertEquals(listOf("кефир 200"), shape(parse("кефир, а потом ещё", ParseContext(entries = entries))))
    }

    // ---------- answers to our question ----------

    @Test fun `a number answers how many grams`() {
        val ctx = ParseContext(entries = entries, pending = PendingQuestion("Сколько примерно грамм?", "e1"))
        val r = parse("300", ctx)
        assertEquals(Action.Update, r.items.single().action)
        assertEquals("e1", r.items.single().targetId)
        assertEquals(300.0, r.items.single().grams, 0.0)
        assertEquals(250.0, parse("250 г", ctx).items.single().grams, 0.0)
        assertEquals(480.0, parse("2 стакана", ctx).items.single().grams, 0.0)
    }

    @Test fun `a name answers what is it`() {
        val ctx = ParseContext(
            entries = listOf(ContextEntry("e9", "суши", 300.0)),
            pending = PendingQuestion("Не нашёл «суши» в базе продуктов. Что это точнее?", "e9"),
        )
        val item = parse("борщ", ctx).items.single()
        assertEquals(Action.Update, item.action)
        assertEquals("e9", item.targetId)
        assertEquals("борщ", item.name)
        assertEquals(300.0, item.grams, 0.0)
        assertNotNull(item.preset)
        assertEquals("креветки", parse("креветки 100 г", ctx).items.single().name)
        assertEquals(100.0, parse("креветки 100 г", ctx).items.single().grams, 0.0)
    }

    @Test fun `an unknown name answers what is it, without nutrition`() {
        val ctx = ParseContext(entries = listOf(ContextEntry("e9", "суши", 300.0)), pending = PendingQuestion("?", "e9"))
        val item = parse("ролл калифорния", ctx).items.single()
        assertEquals("ролл калифорния", item.name)
        assertNull(item.preset)
    }

    @Test fun `a long message is a new meal even while a question is open`() {
        val ctx = ParseContext(entries = entries, pending = PendingQuestion("Сколько грамм?", "e1"))
        assertEquals(listOf(Action.Add, Action.Add, Action.Add), parse("кофе, банан и ещё творог 150 г", ctx).items.map { it.action })
    }

    @Test fun `a question about nothing in particular does not hijack the next message`() {
        val ctx = ParseContext(entries = entries, pending = PendingQuestion("Что это?", null))
        assertEquals(listOf(Action.Add), parse("банан", ctx).items.map { it.action })
    }

    // ---------- photos ----------

    @Test fun `a photo cannot be read offline`() {
        try {
            runBlocking { parser.parse(ParseRequest(null, imageBase64 = "AAAA")) }
            org.junit.Assert.fail("expected PhotoNeedsModel")
        } catch (_: PhotoNeedsModel) {
        }
    }
}
