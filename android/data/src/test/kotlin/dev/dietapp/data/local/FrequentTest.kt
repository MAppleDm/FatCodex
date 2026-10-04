package dev.dietapp.data.local

import dev.dietapp.data.domain.Per100
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Port of services/gateway/tests/test_frequent.py. */
class FrequentTest {
    private val t0 = Instant.parse("2026-09-01T08:00:00Z")

    private fun meal(id: Any, day: Long, items: List<Pair<String, Double>>, minutes: Int = 8 * 60) =
        items.mapIndexed { i, (name, grams) -> MealRow(id.toString(), name, grams, minutes, t0.plusSeconds(day * 86_400), i, null, null) }

    private fun rows(vararg meals: List<MealRow>) = meals.flatMap { it }

    @Test fun `time of day buckets`() {
        assertEquals(
            listOf("ночью", "утром", "утром", "днём", "днём", "вечером", "вечером", "ночью", "ночью"),
            listOf(4 * 60 + 59, 5 * 60, 10 * 60 + 59, 11 * 60, 15 * 60 + 59, 16 * 60, 21 * 60 + 59, 22 * 60, 0).map(Frequent::timeOfDay),
        )
    }

    @Test fun `a meal repeated three times is habitual`() {
        val m = Frequent.meals(rows(*(0L..2L).map { meal(it, it, listOf("овсянка" to 200.0, "банан" to 100.0)) }.toTypedArray())).single()
        assertEquals("овсянка, банан (утром)", m.label)
        assertEquals(listOf("овсянка" to 200.0, "банан" to 100.0), m.items.map { it.name to it.grams })
    }

    @Test fun `two repeats are not enough, unless asked`() {
        val data = rows(*(0L..1L).map { meal(it, it, listOf("овсянка" to 200.0)) }.toTypedArray())
        assertEquals(emptyList<Any>(), Frequent.meals(data))
        assertEquals(1, Frequent.meals(data, minRepeats = 2).size)
    }

    @Test fun `the same foods match regardless of case, order and grams`() {
        val data = rows(
            meal(1, 1, listOf("Овсянка" to 150.0, "банан" to 100.0)),
            meal(2, 2, listOf("банан" to 120.0, "овсянка" to 200.0)),
            meal(3, 3, listOf("овсянка" to 250.0, "Банан" to 90.0)),
        )
        assertEquals(2, Frequent.meals(data).single().items.size)
    }

    @Test fun `different foods are different meals`() {
        val data = rows(
            meal(1, 1, listOf("овсянка" to 200.0)), meal(2, 2, listOf("овсянка" to 200.0)),
            meal(3, 3, listOf("овсянка" to 200.0, "банан" to 100.0)),
        )
        assertEquals(emptyList<Any>(), Frequent.meals(data))
    }

    @Test fun `the latest occurrence supplies the grams, whatever the input order`() {
        val a = rows(meal(1, 1, listOf("овсянка" to 100.0)), meal(2, 2, listOf("овсянка" to 300.0)), meal(3, 3, listOf("овсянка" to 200.0)))
        assertEquals(200.0, Frequent.meals(a).single().items.single().grams, 0.0)
        val b = rows(meal(3, 3, listOf("овсянка" to 200.0)), meal(1, 1, listOf("овсянка" to 100.0)), meal(2, 2, listOf("овсянка" to 300.0)))
        assertEquals(200.0, Frequent.meals(b).single().items.single().grams, 0.0)
    }

    @Test fun `most repeated first, and limited`() {
        val data = rows(
            *(0L..2L).map { meal("a$it", it, listOf("кофе" to 200.0), 8 * 60) }.toTypedArray(),
            *(0L..4L).map { meal("b$it", it, listOf("борщ" to 300.0), 13 * 60) }.toTypedArray(),
            *(0L..3L).map { meal("c$it", it, listOf("чай" to 250.0), 20 * 60) }.toTypedArray(),
        )
        assertEquals(listOf("борщ (днём)", "чай (вечером)", "кофе (утром)"), Frequent.meals(data).map { it.label })
        assertEquals(listOf("борщ (днём)"), Frequent.meals(data, limit = 1).map { it.label })
    }

    @Test fun `the usual time is the most common one`() {
        val data = rows(meal(1, 1, listOf("кофе" to 200.0), 8 * 60), meal(2, 2, listOf("кофе" to 200.0), 9 * 60), meal(3, 3, listOf("кофе" to 200.0), 15 * 60))
        assertEquals("кофе (утром)", Frequent.meals(data).single().label)
    }

    @Test fun `the label names at most three foods but the meal keeps all`() {
        val foods = listOf("а" to 1.0, "б" to 1.0, "в" to 1.0, "г" to 1.0)
        val m = Frequent.meals(rows(*(0L..2L).map { meal(it, it, foods) }.toTypedArray())).single()
        assertEquals("а, б, в (утром)", m.label)
        assertEquals(4, m.items.size)
    }

    @Test fun `nothing in, nothing out`() {
        assertEquals(emptyList<Any>(), Frequent.meals(emptyList()))
    }

    @Test fun `nutrition of the latest entry travels with the meal`() {
        val p = Per100(92.0, 3.38, 0.62, 19.94)
        val data = (0L..2L).flatMap { d ->
            listOf(MealRow("m$d", "гречка", 200.0, 8 * 60, t0.plusSeconds(d * 86_400), 0, p, "Buckwheat groats, roasted, cooked"))
        }
        val item = Frequent.meals(data).single().items.single()
        assertEquals(p, item.preset!!.per100)
        assertEquals("Buckwheat groats, roasted, cooked", item.preset!!.foodName)
        assertEquals(false, item.preset!!.approximate)
        assertNull(item.queryEn)
        assertNotNull(item.preset)
    }
}
