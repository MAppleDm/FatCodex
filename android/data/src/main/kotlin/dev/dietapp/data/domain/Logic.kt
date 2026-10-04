package dev.dietapp.data.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Per-100g -> per-portion arithmetic. Identical to services/gateway and services/nutrition:
 * multiply, then round half up to one decimal. The app uses it to rescale an entry offline and gets
 * exactly the numbers the server would return.
 */
object NutrientMath {
    fun round1(value: Double): Double = BigDecimal(value.toString()).setScale(1, RoundingMode.HALF_UP).toDouble()

    fun scale(per100: Double, grams: Double): Double = round1(per100 * grams / 100.0)

    fun rescale(entry: Entry, grams: Double): Entry {
        val p = entry.per100 ?: return entry.copy(grams = grams)
        return entry.copy(
            grams = grams,
            kcal = scale(p.kcal, grams),
            protein = scale(p.protein, grams),
            fat = scale(p.fat, grams),
            carbs = scale(p.carbs, grams),
        )
    }
}

/** The safety floor for a calorie goal. The server enforces the same numbers. */
object CalorieGoal {
    const val MIN = 1200
    const val MAX = 6000

    sealed interface Check {
        data object Ok : Check
        data object TooLow : Check
        data object TooHigh : Check
    }

    fun check(value: Int): Check = when {
        value < MIN -> Check.TooLow
        value > MAX -> Check.TooHigh
        else -> Check.Ok
    }
}

/** "вес 82.4" typed into the chat is a weigh-in, not food. */
object WeightCommand {
    private val pattern = Regex("""^\s*(?:вес|weight)\s*[:\-]?\s+(\d{2,3}(?:[.,]\d{1,2})?)\s*(?:кг|kg)?\s*$""", RegexOption.IGNORE_CASE)
    private val range = 20.0..400.0

    fun parse(text: String): Double? {
        val match = pattern.matchEntire(text) ?: return null
        val kg = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return kg.takeIf { it in range }
    }
}

data class TrendPoint(val day: LocalDate, val raw: Double, val trend: Double)

/** Smoothed weight: for each weigh-in day, the mean of the daily weights in the last [window] calendar days. */
object WeightTrend {
    const val WINDOW_DAYS = 7

    fun compute(weights: List<Weight>, window: Int = WINDOW_DAYS): List<TrendPoint> {
        // several weigh-ins on one day count as one: the latest wins
        val daily = weights.groupBy { it.day }
            .mapValues { (_, list) -> list.maxBy { it.updatedAt }.kg }
            .toSortedMap()
        return daily.map { (day, kg) ->
            val from = day.minusDays(window - 1L)
            val inWindow = daily.subMap(from, day.plusDays(1)).values
            TrendPoint(day, kg, inWindow.average())
        }
    }
}
