package dev.dietapp.data.local.parse

import dev.dietapp.data.local.food.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {
    private val lexicon = OfflineParserTest.lexicon

    @Test fun `case endings are tolerated`() {
        assertTrue(Lexicon.similar("яблоко", "яблок"))
        assertTrue(Lexicon.similar("яблоко", "яблоками"))
        assertTrue(Lexicon.similar("яица", "яицо")) // яйца / яйцо after folding
        assertTrue(Lexicon.similar("масло", "маслом"))
        assertTrue(Lexicon.similar("банан", "бананов"))
        assertTrue(Lexicon.similar("рис", "риса"))
        assertTrue(Lexicon.similar("лук", "луку"))
        assertTrue(Lexicon.similar("каша", "каше"))
    }

    @Test fun `different words are not confused`() {
        assertFalse("как is not какао", Lexicon.similar("как", "какао"))
        assertFalse("кило is not килька", Lexicon.similar("кило", "килька"))
        assertFalse(Lexicon.similar("масло", "масса"))
        assertFalse(Lexicon.similar("мясо", "масло"))
        assertFalse(Lexicon.similar("сок", "сон"))
        assertFalse(Lexicon.similar("сок", "соком")) // too far for a three-letter word: the dictionary lists such forms itself
        assertFalse(Lexicon.similar("банка", "банан"))
        assertFalse(Lexicon.similar("чай", "чаша"))
    }

    @Test fun `the longest phrase wins`() {
        val hit = lexicon.matchAt(Text.tokenize("ржаной хлеб"), 0)!!
        assertEquals("ржаной хлеб", hit.first.name)
        assertEquals(2, hit.second)
        assertEquals("хлеб", lexicon.matchAt(Text.tokenize("хлеб"), 0)!!.first.name)
    }

    @Test fun `find needs the whole text to be the name`() {
        assertEquals("борщ", lexicon.find("борщ")!!.name)
        assertEquals("гречка", lexicon.find("Гречневая каша")!!.name)
        assertNull(lexicon.find("борщ с мясом"))
        assertNull(lexicon.find(""))
        assertNull(lexicon.find("чахохбили"))
    }

    @Test fun `entries carry what a portion is`() {
        assertEquals(50.0, lexicon.find("яйцо")!!.units["piece"]!!, 0.0)
        assertEquals("tsp", lexicon.find("сахар")!!.bareSpoon)
        assertNotNull(lexicon.find("борщ")!!.per100)
        assertNotNull(lexicon.find("гречка")!!.query)
        assertEquals(5.0, lexicon.find("сахар")!!.withGrams!!, 0.0)
    }

    @Test fun `every entry is either a usda query or a table value`() {
        for (e in lexicon.entries) assertTrue("${e.name} needs a query or per100", (e.query != null) != (e.per100 != null))
    }

    @Test fun `names and phrases are not duplicated`() {
        val names = lexicon.entries.map { it.name }
        assertEquals("duplicate names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}", names.size, names.toSet().size)
        val phrases = lexicon.entries.flatMap { e -> e.phrases.map { it to e.name } }
        val clashes = phrases.groupBy({ it.first }, { it.second }).filter { it.value.toSet().size > 1 }
        assertTrue("one phrase for two foods: $clashes", clashes.isEmpty())
    }

    @Test fun `no one-word food is also a unit of measure`() {
        for (e in lexicon.entries) for (p in e.phrases) {
            if (p.size == 1) assertFalse("${e.name}: '${p[0]}' is a unit", Quantities.isUnitWord(p[0]))
        }
    }

    @Test fun `entries have sane amounts`() {
        for (e in lexicon.entries) {
            assertTrue("${e.name}: default ${e.defaultGrams}", e.defaultGrams in 1.0..1500.0)
            for ((unit, g) in e.units) assertTrue("${e.name}: $unit = $g g", g in 0.5..1000.0)
        }
    }
}
