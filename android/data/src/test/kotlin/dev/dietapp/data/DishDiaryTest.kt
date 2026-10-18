package dev.dietapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.db.EntryRow
import dev.dietapp.data.domain.Dish
import dev.dietapp.data.domain.DishPart
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.DishCodec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** A dish that was eaten: its ingredients can be changed one by one, and the entry follows. */
@RunWith(AndroidJUnit4::class)
class DishDiaryTest {
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    private val parts = listOf(
        DishPart("свёкла", 40.0, Per100(43.0, 1.6, 0.2, 9.6)),
        DishPart("говядина", 50.0, Per100(250.0, 26.0, 15.0, 0.0)),
        DishPart("бульон", 210.0, Per100(0.0, 0.0, 0.0, 0.0)),
    )

    private suspend fun eaten(parts: List<DishPart> = this.parts): EntryRow {
        val sum = Dish.totals(parts)
        val per = Dish.per100(parts)!!
        val row = entryRow("d1", "борщ", Dish.grams(parts)).copy(
            kcal = sum.kcal, protein = sum.protein, fat = sum.fat, carbs = sum.carbs,
            kcal100 = per.kcal, protein100 = per.protein, fat100 = per.fat, carbs100 = per.carbs,
            parts = DishCodec.encode(parts),
        )
        env.db.entries().upsert(row)
        return row
    }

    private suspend fun entry() = env.db.entries().get("d1")!!

    @Test fun `more of one ingredient makes the dish heavier and richer, and the numbers follow`() = runTest {
        eaten()
        env.diary.updatePart("d1", 1, 100.0) // twice the beef
        val e = entry()
        assertEquals(350.0, e.grams, 1e-9)
        assertEquals(142.2 + 125.0, e.kcal!!, 1e-9)
        assertEquals((142.2 + 125.0) / 350 * 100, e.kcal100!!, 1e-9)
        assertEquals(listOf(40.0, 100.0, 210.0), DishCodec.decode(e.parts).map { it.grams })
        assertEquals(Dish.totals(DishCodec.decode(e.parts)).protein, e.protein!!, 1e-9)
    }

    @Test fun `an ingredient taken out stays in the list with no grams, and can come back`() = runTest {
        eaten()
        env.diary.updatePart("d1", 1, 0.0)
        assertEquals(250.0, entry().grams, 1e-9)
        assertEquals(17.2, entry().kcal!!, 1e-9)
        assertEquals(listOf(40.0, 0.0, 210.0), DishCodec.decode(entry().parts).map { it.grams })
        env.diary.updatePart("d1", 1, 50.0)
        assertEquals(142.2, entry().kcal!!, 1e-9)
    }

    @Test fun `a dish of nothing is not a dish, so the last ingredient cannot be taken out`() = runTest {
        eaten(listOf(parts[0], parts[1].copy(grams = 0.0)))
        env.diary.updatePart("d1", 0, 0.0)
        assertEquals(40.0, entry().grams, 1e-9)
    }

    @Test fun `what makes no sense changes nothing`() = runTest {
        val before = eaten()
        env.diary.updatePart("d1", 7, 10.0)
        env.diary.updatePart("d1", -1, 10.0)
        env.diary.updatePart("d1", 0, -5.0)
        env.diary.updatePart("d1", 0, Double.NaN)
        env.diary.updatePart("d1", 0, 9_999.0)
        env.diary.updatePart("nobody", 0, 10.0)
        assertEquals(before, entry())
    }

    @Test fun `a plain entry has no ingredients to change`() = runTest {
        env.db.entries().upsert(entryRow("p1", "гречка", 200.0))
        env.diary.updatePart("p1", 0, 100.0)
        assertEquals(200.0, env.db.entries().get("p1")!!.grams, 0.0)
        assertNull(env.db.entries().get("p1")!!.parts)
    }

    @Test fun `eating a different amount of the dish keeps its ingredients in proportion`() = runTest {
        eaten()
        env.diary.updateEntry("d1", 150.0, null)
        val e = entry()
        assertEquals(listOf(20.0, 25.0, 105.0), DishCodec.decode(e.parts).map { it.grams })
        assertEquals(71.1, e.kcal!!, 1e-9)
    }

    @Test fun `renaming a dish leaves its ingredients alone`() = runTest {
        eaten()
        env.diary.updateEntry("d1", null, "борщ украинский")
        assertEquals(3, DishCodec.decode(entry().parts).size)
        assertEquals("борщ украинский", entry().name)
    }

    @Test fun `an ingredient changed in the diary does not touch the dish in the base`() = runTest {
        val dish = env.foods.save(dev.dietapp.data.local.FoodInput("борщ", Per100(0.0, 0.0, 0.0, 0.0), parts = parts))
        eaten()
        env.diary.updatePart("d1", 1, 100.0)
        assertEquals(listOf(40.0, 50.0, 210.0), env.foods.get(dish.id)!!.parts.map { it.grams })
    }
}
