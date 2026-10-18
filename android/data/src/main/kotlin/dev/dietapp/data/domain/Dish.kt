package dev.dietapp.data.domain

/**
 * One ingredient of a dish ("атом"): how much of it, and its own values per 100 g. In a food of the user's base the amounts are
 * those of a typical serving; in a diary entry they are what was eaten. The dish's own numbers are never stored apart from its
 * ingredients: they are what the ingredients add up to ([Dish.per100]), so the two can never disagree.
 */
data class DishPart(val name: String, val grams: Double, val per100: Per100) {
    val kcal: Double get() = per100.kcal * grams / 100
    val protein: Double get() = per100.protein * grams / 100
    val fat: Double get() = per100.fat * grams / 100
    val carbs: Double get() = per100.carbs * grams / 100
}

object Dish {
    const val MIN_PARTS = 2
    const val MAX_PARTS = 16
    const val MAX_PART_GRAMS = 5000.0

    fun grams(parts: List<DishPart>): Double = parts.sumOf { it.grams }

    /** What these amounts hold, in all. */
    fun totals(parts: List<DishPart>): Totals = Totals(parts.sumOf { it.kcal }, parts.sumOf { it.protein }, parts.sumOf { it.fat }, parts.sumOf { it.carbs })

    /** Values per 100 g of the dish itself: the ingredients' sum over their total weight. Null when nothing weighs anything. */
    fun per100(parts: List<DishPart>): Per100? {
        val grams = grams(parts)
        if (grams <= 0.0) return null
        val t = totals(parts)
        return Per100(t.kcal / grams * 100, t.protein / grams * 100, t.fat / grams * 100, t.carbs / grams * 100)
    }

    /** The same ingredients in the same proportions, for [grams] of the dish. */
    fun scaleTo(parts: List<DishPart>, grams: Double): List<DishPart> {
        val total = grams(parts)
        if (parts.isEmpty() || total <= 0.0) return parts
        val factor = grams / total
        return parts.map { it.copy(grams = it.grams * factor) }
    }
}

/** What a workout was, in words a person uses; the watch's own code is mapped to this when it is read. */
enum class WorkoutKind { Running, Walking, Cycling, Strength, Swimming, Yoga, Hiking, Other }

data class Workout(val kind: WorkoutKind, val minutes: Int)

/**
 * What a watch (through Health Connect) says about one day, read by the app and never typed: sleep of the night that ended
 * that day, steps, workouts, and the morning weigh-in of smart scales. Any of them may be missing.
 */
data class DayFacts(
    val day: java.time.LocalDate,
    val sleepMinutes: Int? = null,
    val steps: Int? = null,
    val workouts: List<Workout> = emptyList(),
    /** The first weigh-in of the day from smart scales (kg). It becomes a weigh-in of the diary, not a fact of the day. */
    val weightKg: Double? = null,
) {
    val isEmpty: Boolean get() =
        sleepMinutes == null && steps == null && workouts.isEmpty() && weightKg == null
}
