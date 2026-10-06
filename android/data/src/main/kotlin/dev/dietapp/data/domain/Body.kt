package dev.dietapp.data.domain

import kotlin.math.roundToInt

enum class Sex { Male, Female }

/** How much a person moves in a week, with the usual multiplier for the resting energy. */
enum class Activity(val factor: Double) {
    Sedentary(1.2),
    Light(1.375),
    Moderate(1.55),
    High(1.725),
    Extreme(1.9),
}

/**
 * What the person told the app about themselves. Not here: the weight (it is the latest weigh-in of the diary, so
 * "вес 82.4" in the chat keeps this page up to date) and the age (worked out from [birthYear], so it does not go stale).
 */
data class BodyProfile(
    val sex: Sex? = null,
    val birthYear: Int? = null,
    val heightCm: Int? = null,
    val activity: Activity? = null,
    /** The person's own correction of the daily goal, in kcal: minus to lose weight, plus to gain. 0 keeps what a day costs. */
    val adjustment: Int = 0,
)

/** [bmr]: energy at rest in a day. [tdee]: the average a day costs with the person's activity. Both in kcal, to ten. */
data class EnergyEstimate(val bmr: Int, val tdee: Int, val factor: Double)

/**
 * The Mifflin–St Jeor equation for the energy at rest, times the activity multiplier for the average a day costs. It is an
 * estimate for an average adult, good to within some tens of percent for a person, not a medical figure.
 */
object Energy {
    const val MIN_AGE = 14
    const val MAX_AGE = 100
    const val MIN_HEIGHT = 120
    const val MAX_HEIGHT = 220
    const val MIN_WEIGHT = 30.0
    const val MAX_WEIGHT = 250.0

    const val DEFAULT_AGE = 30
    const val DEFAULT_HEIGHT = 170
    const val DEFAULT_WEIGHT = 70.0

    /** 10 · kg + 6,25 · cm − 5 · years, then +5 for a man or −161 for a woman. */
    fun bmr(sex: Sex, age: Int, heightCm: Int, weightKg: Double): Double =
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + if (sex == Sex.Male) 5.0 else -161.0

    fun estimate(sex: Sex, age: Int, heightCm: Int, weightKg: Double, activity: Activity): EnergyEstimate {
        val rest = bmr(sex, age, heightCm, weightKg)
        return EnergyEstimate(tens(rest), tens(rest * activity.factor), activity.factor)
    }

    /** A figure with more precision than the formula has would only pretend. */
    private fun tens(kcal: Double) = (kcal / 10.0).roundToInt() * 10
}

/**
 * The calorie goal of a day. It is never typed in: it is what a day costs ([EnergyEstimate.tdee]) plus the person's own
 * correction, and it stays between the limits the app has always held to ([CalorieGoal.MIN] and [CalorieGoal.MAX]).
 */
object DailyGoal {
    const val MIN_ADJUSTMENT = -1000
    const val MAX_ADJUSTMENT = 1000
    const val ADJUSTMENT_STEP = 50

    /** Null until what a day costs is known. */
    fun of(estimate: EnergyEstimate?, adjustment: Int): Int? =
        estimate?.let { (it.tdee + adjustment).coerceIn(CalorieGoal.MIN, CalorieGoal.MAX) }

    /** The correction asked for more than the lower limit allows, so the goal is held at the limit. */
    fun limited(estimate: EnergyEstimate?, adjustment: Int): Boolean =
        estimate != null && estimate.tdee + adjustment < CalorieGoal.MIN
}
