package dev.dietapp.data.domain

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientMathTest {
    // The same vectors as services/gateway/tests and services/nutrition/tests: client and server must agree.
    @Test fun `scale matches the backend`() {
        assertEquals(247.5, NutrientMath.scale(165.0, 150.0), 0.0)
        assertEquals(46.5, NutrientMath.scale(31.02, 150.0), 0.0)
        assertEquals(5.4, NutrientMath.scale(3.57, 150.0), 0.0) // 5.355 -> half up, not banker's
        assertEquals(143.4, NutrientMath.scale(717.0, 20.0), 0.0)
        assertEquals(71.7, NutrientMath.scale(717.0, 10.0), 0.0)
        assertEquals(8.1, NutrientMath.scale(81.11, 10.0), 0.0)
        assertEquals(184.0, NutrientMath.scale(92.0, 200.0), 0.0)
        assertEquals(39.9, NutrientMath.scale(19.94, 200.0), 0.0)
    }

    @Test fun `rounds half up`() {
        assertEquals(0.3, NutrientMath.round1(0.25), 0.0)
        assertEquals(0.4, NutrientMath.round1(0.35), 0.0)
        assertEquals(247.5, NutrientMath.round1(247.49999999999997), 0.0)
        assertEquals(0.0, NutrientMath.round1(0.04), 0.0)
    }

    @Test fun `rescale recomputes every number from per100`() {
        val butter = entry(grams = 20.0, per100 = Per100(717.0, 0.85, 81.11, 0.06), kcal = 143.4)
        val half = NutrientMath.rescale(butter, 10.0)
        assertEquals(10.0, half.grams, 0.0)
        assertEquals(71.7, half.kcal!!, 0.0)
        assertEquals(8.1, half.fat!!, 0.0)
        assertEquals(0.1, half.protein!!, 0.0) // 0.085 -> 0.1
    }

    @Test fun `rescale of an entry without numbers only changes grams`() {
        val unknown = entry(grams = 300.0, per100 = null, kcal = null)
        val r = NutrientMath.rescale(unknown, 150.0)
        assertEquals(150.0, r.grams, 0.0)
        assertNull(r.kcal)
    }
}

class TotalsTest {
    @Test fun `sums numbers and ignores entries without any`() {
        val t = Totals.of(
            listOf(
                entry(kcal = 247.5, protein = 46.5, fat = 5.4, carbs = 0.0),
                entry(kcal = 184.0, protein = 6.8, fat = 1.2, carbs = 39.9),
                entry(kcal = null, protein = null, fat = null, carbs = null),
            ),
        )
        assertEquals(431.5, t.kcal, 1e-9)
        assertEquals(53.3, t.protein, 1e-9)
        assertEquals(6.6, t.fat, 1e-9)
        assertEquals(39.9, t.carbs, 1e-9)
    }

    @Test fun `empty day is zero`() {
        assertEquals(Totals.Zero, Totals.of(emptyList()))
    }
}

class WeightCommandTest {
    @Test fun `recognises weigh-ins`() {
        assertEquals(82.4, WeightCommand.parse("вес 82.4")!!, 0.0)
        assertEquals(82.4, WeightCommand.parse("вес 82,4")!!, 0.0)
        assertEquals(82.0, WeightCommand.parse("Вес 82")!!, 0.0)
        assertEquals(82.4, WeightCommand.parse("  вес: 82.4 кг  ")!!, 0.0)
        assertEquals(82.4, WeightCommand.parse("ВЕС - 82.4кг")!!, 0.0)
        assertEquals(82.4, WeightCommand.parse("weight 82.4 kg")!!, 0.0)
        assertEquals(105.25, WeightCommand.parse("вес 105.25")!!, 0.0)
    }

    @Test fun `food and nonsense are not weigh-ins`() {
        listOf(
            "весы 82.4", "вес", "вес kg", "гречка 200 г", "вес 82.4 и гречка", "82.4", "вес 5", "вес 500", "вес 82.4.1",
            "в весе 82", "",
        ).forEach { assertNull("'$it' should not parse", WeightCommand.parse(it)) }
    }
}

class CalorieGoalTest {
    @Test fun `floor and ceiling`() {
        assertEquals(CalorieGoal.Check.TooLow, CalorieGoal.check(1199))
        assertEquals(CalorieGoal.Check.Ok, CalorieGoal.check(1200))
        assertEquals(CalorieGoal.Check.Ok, CalorieGoal.check(6000))
        assertEquals(CalorieGoal.Check.TooHigh, CalorieGoal.check(6001))
        assertEquals(CalorieGoal.Check.TooLow, CalorieGoal.check(-5))
    }
}

class WeightTrendTest {
    private fun w(day: String, kg: Double, at: Long = 0) = Weight("$day-$kg-$at", LocalDate.parse(day), kg, Instant.ofEpochSecond(at))

    @Test fun `no weigh-ins, no trend`() {
        assertTrue(WeightTrend.compute(emptyList()).isEmpty())
    }

    @Test fun `first point is its own trend`() {
        val p = WeightTrend.compute(listOf(w("2026-09-01", 82.0))).single()
        assertEquals(82.0, p.raw, 0.0)
        assertEquals(82.0, p.trend, 0.0)
    }

    @Test fun `moving average over seven calendar days`() {
        val days = (1..9).map { w("2026-09-0$it", 80.0 + it) } // 81..89
        val points = WeightTrend.compute(days)
        assertEquals(9, points.size)
        assertEquals(81.0, points[0].trend, 1e-9)
        assertEquals(81.5, points[1].trend, 1e-9)                  // mean(81,82)
        assertEquals(84.0, points[6].trend, 1e-9)                  // mean(81..87)
        assertEquals(85.0, points[7].trend, 1e-9)                  // 09-01 drops out: mean(82..88)
        assertEquals(86.0, points[8].trend, 1e-9)                  // mean(83..89)
    }

    @Test fun `gaps are calendar gaps, not skipped entries`() {
        val points = WeightTrend.compute(listOf(w("2026-09-01", 80.0), w("2026-09-10", 90.0)))
        assertEquals(90.0, points[1].trend, 1e-9) // 09-01 is older than seven days
    }

    @Test fun `latest weigh-in of a day wins`() {
        val p = WeightTrend.compute(listOf(w("2026-09-01", 82.0, at = 1), w("2026-09-01", 81.0, at = 5))).single()
        assertEquals(81.0, p.raw, 0.0)
    }

    @Test fun `result is sorted by day regardless of input order`() {
        val p = WeightTrend.compute(listOf(w("2026-09-03", 3.0 + 80), w("2026-09-01", 81.0), w("2026-09-02", 82.0)))
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03"), p.map { it.day.toString() })
    }

    @Test fun `window is configurable`() {
        val days = (1..4).map { w("2026-09-0$it", 80.0 + it) }
        assertEquals(83.5, WeightTrend.compute(days, window = 2).last().trend, 1e-9)
    }
}

internal fun entry(
    grams: Double = 100.0,
    per100: Per100? = null,
    kcal: Double? = 100.0,
    protein: Double? = 1.0,
    fat: Double? = 1.0,
    carbs: Double? = 1.0,
) = Entry(
    id = "e-${System.nanoTime()}", mealId = null, day = LocalDate.parse("2026-09-30"), eatenAt = Instant.EPOCH, position = 0,
    name = "еда", grams = grams, kcal = kcal, protein = protein, fat = fat, carbs = carbs, per100 = per100, foodName = null,
    status = EntryStatus.Ok, confidence = 1.0, source = Source.Text, updatedAt = Instant.EPOCH, dirty = false,
)
