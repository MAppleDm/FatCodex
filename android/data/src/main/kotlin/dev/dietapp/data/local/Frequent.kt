package dev.dietapp.data.local

import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.parse.FrequentItem
import dev.dietapp.data.local.parse.FrequentMeal
import dev.dietapp.data.local.parse.Preset
import java.time.Instant

/** One food of a past meal. A meal is everything logged from one message (they share [mealId]). */
data class MealRow(
    val mealId: String,
    val name: String,
    val grams: Double,
    val localMinutes: Int,
    val eatenAt: Instant,
    val position: Int,
    val per100: Per100?,
    val foodName: String?,
)

/**
 * Habitual meals for "как обычно": meals that repeat. A port of services/gateway/gateway/frequent.py.
 * Two meals are the same when they hold the same foods (by name); the latest occurrence supplies the grams
 * and the nutrition, so repeating a meal repeats its numbers exactly.
 */
object Frequent {
    const val MIN_REPEATS = 3
    const val MAX_MEALS = 5
    private const val MAX_LABEL_ITEMS = 3

    fun timeOfDay(localMinutes: Int): String = when {
        localMinutes in 5 * 60 until 11 * 60 -> "утром"
        localMinutes in 11 * 60 until 16 * 60 -> "днём"
        localMinutes in 16 * 60 until 22 * 60 -> "вечером"
        else -> "ночью"
    }

    fun meals(rows: Iterable<MealRow>, minRepeats: Int = MIN_REPEATS, limit: Int = MAX_MEALS): List<FrequentMeal> {
        val bySignature = rows.groupBy { it.mealId }.values.groupBy { items -> items.map { it.name.trim().lowercase() }.toSortedSet().toList() }

        val ranked = bySignature.values
            .filter { it.size >= minRepeats }
            .sortedWith(compareBy({ -it.size }, { -it.maxOf { items -> items.maxOf { r -> r.eatenAt } }.toEpochMilli() }))

        return ranked.take(limit).map { occurrences ->
            val latest = occurrences.maxBy { items -> items.maxOf { it.eatenAt } }
            val usual = occurrences.groupingBy { timeOfDay(it.first().localMinutes) }.eachCount().maxByOrNull { it.value }!!.key
            val ordered = latest.sortedWith(compareBy({ it.eatenAt }, { it.position }))
            FrequentMeal(
                label = "${ordered.take(MAX_LABEL_ITEMS).joinToString(", ") { it.name }} ($usual)",
                items = ordered.map { r ->
                    FrequentItem(r.name, null, r.grams, r.per100?.let { Preset(it, r.foodName ?: r.name, approximate = false) })
                },
            )
        }
    }
}
