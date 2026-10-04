package dev.dietapp.data.local.parse

import dev.dietapp.data.local.food.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantitiesTest {
    private val lexicon = OfflineParserTest.lexicon

    /** Tokens are folded (й→и, ё→е) before scanning, exactly as the parser does it. */
    private fun scan(vararg tokens: String): List<Quantity> {
        val folded = tokens.map { Text.fold(it) }
        return Quantities.scan(folded, BooleanArray(folded.size))
    }

    @Test fun `digits, decimals and fractions`() {
        assertEquals(200.0, scan("200", "г").single().value, 0.0)
        assertEquals(0.5, scan("0.5", "л").single().value, 0.0)
        assertEquals(0.5, scan("1/2", "стакана").single().value, 0.0)
        assertEquals(1.5, scan("1.5", "кг").single().value, 0.0)
    }

    @Test fun `number words`() {
        assertEquals(2.0, scan("два", "яйца").single().value, 0.0)
        assertEquals(2.0, scan("две", "ложки").single().value, 0.0)
        assertEquals(1.5, scan("полтора", "стакана").single().value, 0.0)
        assertEquals(2.5, scan("два", "с", "половиной", "стакана").single().value, 0.0)
        assertEquals(3.0, scan("три").single().value, 0.0)
        assertEquals(0.5, scan("пол", "яблока").single().value, 0.0)
    }

    @Test fun `several is a vague three`() {
        val q = scan("несколько", "долек").single()
        assertEquals(3.0, q.value, 0.0)
        assertEquals(true, q.vague)
    }

    @Test fun `a unit alone is one of it`() {
        val q = scan("ложка").single()
        assertEquals(1.0, q.value, 0.0)
        assertEquals(UnitKind.Tbsp, q.unit)
        assertEquals(UnitKind.Cup, scan("стакан").single().unit)
    }

    @Test fun `two-word units`() {
        assertEquals(UnitKind.Tsp, scan("2", "чайные", "ложки").single().unit)
        assertEquals(UnitKind.Tbsp, scan("2", "столовые", "ложки").single().unit)
        assertEquals(UnitKind.Tbsp, scan("2", "ст", "л").single().unit)
        assertEquals(UnitKind.Tsp, scan("1", "ч", "л").single().unit)
        assertEquals(UnitKind.Liter, scan("2", "л").single().unit)
    }

    @Test fun `other case endings of a unit`() {
        assertEquals(UnitKind.Tbsp, scan("2", "ложками").single().unit)
        assertEquals(UnitKind.Cup, scan("2", "стаканами").single().unit)
        assertEquals(UnitKind.Slice, scan("3", "кусочками").single().unit)
    }

    @Test fun `tokens taken by a food name are skipped`() {
        val covered = booleanArrayOf(true, true)
        assertEquals(emptyList<Quantity>(), Quantities.scan(listOf("творог", "9"), covered))
    }

    @Test fun `a number then its unit is one quantity`() {
        val q = scan("гречка", "200", "г", "и", "яйцо").single()
        assertEquals(1, q.start)
        assertEquals(3, q.end)
    }

    @Test fun `half forms are split`() {
        val isFood = { w: String -> lexicon.matchAt(listOf(w), 0) != null }
        assertEquals(listOf("пол", "стакана"), Quantities.splitHalf(listOf("полстакана"), isFood))
        assertEquals(listOf("пол", "кило"), Quantities.splitHalf(listOf("полкило"), isFood))
        assertEquals(listOf("пол", "банана"), Quantities.splitHalf(listOf("полбанана"), isFood))
        assertEquals(listOf("полезный", "полтора", "пол"), Quantities.splitHalf(listOf("полезный", "полтора", "пол"), isFood))
    }

    // ---------- grams ----------

    private val egg = lexicon.find("яйцо")!!
    private val oil = lexicon.find("масло сливочное")!!
    private val sugar = lexicon.find("сахар")!!

    private fun g(q: Quantity, food: LexEntry?) = Quantities.grams(q, food)

    @Test fun `measured weights are exact`() {
        assertEquals(200.0 to 0.9, g(Quantity(200.0, UnitKind.Gram, 0, 2), null))
        assertEquals(1500.0 to 0.9, g(Quantity(1.5, UnitKind.Kilo, 0, 2), null))
        assertEquals(330.0 to 0.9, g(Quantity(330.0, UnitKind.Milli, 0, 2), null))
        assertEquals(500.0 to 0.9, g(Quantity(0.5, UnitKind.Liter, 0, 2), null))
    }

    @Test fun `a bare number is grams when large, pieces when small`() {
        assertEquals(200.0 to 0.8, g(Quantity(200.0, null, 0, 1), null))
        assertEquals(100.0 to 0.8, g(Quantity(2.0, null, 0, 1), egg))
        assertNull(g(Quantity(2.0, null, 0, 1), null)) // two of what?
    }

    @Test fun `spoons use the food's own weight, else a generic one`() {
        assertEquals(14.0 to 0.8, g(Quantity(1.0, UnitKind.Tbsp, 0, 1, bareSpoon = true), oil))
        assertEquals(5.0 to 0.8, g(Quantity(1.0, UnitKind.Tsp, 0, 2), oil))
        assertEquals(15.0 to 0.65, g(Quantity(1.0, UnitKind.Tbsp, 0, 1, bareSpoon = true), null))
        assertEquals(4.0 to 0.8, g(Quantity(1.0, UnitKind.Tbsp, 0, 1, bareSpoon = true), sugar)) // a bare spoon of sugar is a teaspoon
        assertEquals(12.0 to 0.8, g(Quantity(1.0, UnitKind.Tbsp, 0, 2, bareSpoon = false), sugar)) // "столовая" is explicit
    }

    @Test fun `pieces need the food to know its weight`() {
        assertEquals(150.0 to 0.8, g(Quantity(3.0, UnitKind.Piece, 0, 2), egg))
        assertNull(g(Quantity(3.0, UnitKind.Piece, 0, 2), sugar))
    }

    @Test fun `a portion is the food's own portion`() {
        val dumplings = lexicon.find("пельмени")!!
        assertEquals(250.0 to 0.7, g(Quantity(1.0, UnitKind.Portion, 0, 1), dumplings))
        assertEquals(500.0 to 0.7, g(Quantity(2.0, UnitKind.Portion, 0, 1), dumplings))
    }

    @Test fun `a plate of soup is 300 g, of anything else a generic plate`() {
        assertEquals(300.0, g(Quantity(1.0, UnitKind.Plate, 0, 1), lexicon.find("борщ"))!!.first, 0.0)
        assertEquals(250.0, g(Quantity(1.0, UnitKind.Plate, 0, 1), null)!!.first, 0.0)
        assertEquals(0.6, g(Quantity(1.0, UnitKind.Plate, 0, 1), null)!!.second, 0.0)
    }

    @Test fun `vague counts are less certain`() {
        assertEquals(0.55, g(Quantity(3.0, UnitKind.Piece, 0, 2, vague = true), egg)!!.second, 0.0)
    }
}
