package dev.dietapp.data.domain

import dev.dietapp.data.local.DishCodec
import dev.dietapp.data.local.WorkoutCodec
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DishTest {
    private val beet = DishPart("свёкла", 40.0, Per100(43.0, 1.6, 0.2, 9.6))
    private val beef = DishPart("говядина", 50.0, Per100(250.0, 26.0, 15.0, 0.0))
    private val broth = DishPart("бульон", 210.0, Per100(0.0, 0.0, 0.0, 0.0))
    private val borscht = listOf(beet, beef, broth)

    @Test fun `a dish weighs what its ingredients weigh and holds what they hold`() {
        assertEquals(300.0, Dish.grams(borscht), 0.0)
        val total = Dish.totals(borscht)
        assertEquals(17.2 + 125.0, total.kcal, 1e-9)
        assertEquals(0.64 + 13.0, total.protein, 1e-9)
    }

    @Test fun `its own numbers per 100 g are the sum over the weight`() {
        val p = Dish.per100(borscht)!!
        assertEquals(47.4, p.kcal, 1e-9)
        assertEquals(4.546666, p.protein, 1e-5)
    }

    @Test fun `nothing that weighs nothing is a dish`() {
        assertNull(Dish.per100(emptyList()))
        assertNull(Dish.per100(listOf(beet.copy(grams = 0.0), beef.copy(grams = 0.0))))
    }

    @Test fun `for another portion the ingredients keep their proportions and the numbers per 100 g stay`() {
        val half = Dish.scaleTo(borscht, 150.0)
        assertEquals(listOf(20.0, 25.0, 105.0), half.map { it.grams })
        assertEquals(150.0, Dish.grams(half), 1e-9)
        assertEquals(Dish.per100(borscht)!!.kcal, Dish.per100(half)!!.kcal, 1e-9)
        assertEquals(Dish.totals(borscht).kcal / 2, Dish.totals(half).kcal, 1e-9)
    }

    @Test fun `scaling something empty changes nothing`() {
        assertEquals(emptyList<DishPart>(), Dish.scaleTo(emptyList(), 100.0))
        assertEquals(listOf(beet.copy(grams = 0.0)), Dish.scaleTo(listOf(beet.copy(grams = 0.0)), 100.0))
    }

    @Test fun `ingredients survive being stored as text`() {
        val text = DishCodec.encode(borscht)
        assertNotNull(text)
        assertEquals(borscht, DishCodec.decode(text))
    }

    @Test fun `a plain food is stored as nothing, and nothing or garbage reads as no ingredients`() {
        assertNull(DishCodec.encode(emptyList()))
        assertEquals(emptyList<DishPart>(), DishCodec.decode(null))
        assertEquals(emptyList<DishPart>(), DishCodec.decode(""))
        assertEquals(emptyList<DishPart>(), DishCodec.decode("{not json"))
    }

    @Test fun `workouts survive being stored as text, and an unknown kind is just a workout`() {
        val workouts = listOf(Workout(WorkoutKind.Running, 45), Workout(WorkoutKind.Yoga, 30))
        assertEquals(workouts, WorkoutCodec.decode(WorkoutCodec.encode(workouts)))
        assertNull(WorkoutCodec.encode(emptyList()))
        assertEquals(listOf(Workout(WorkoutKind.Other, 20)), WorkoutCodec.decode("""[{"kind": "Parkour", "minutes": 20}]"""))
        assertTrue(WorkoutCodec.decode("garbage").isEmpty())
    }

    @Test fun `a day with nothing in it is empty`() {
        val day = LocalDate.parse("2026-10-07")
        assertTrue(DayFacts(day).isEmpty)
        assertEquals(false, DayFacts(day, steps = 100).isEmpty)
        assertEquals("a weigh-in alone is something", false, DayFacts(day, weightKg = 119.4).isEmpty)
        assertEquals(false, DayFacts(day, workouts = listOf(Workout(WorkoutKind.Walking, 20))).isEmpty)
    }
}
