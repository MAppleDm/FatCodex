package dev.dietapp.data.repo

import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.toDomain
import dev.dietapp.data.domain.BodyProfile
import dev.dietapp.data.domain.DailyGoal
import dev.dietapp.data.domain.Energy
import dev.dietapp.data.domain.Weight
import dev.dietapp.data.domain.WeightTrend
import dev.dietapp.data.local.BodyStore
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * What the person said about themselves plus the diary's latest weigh-in, turned into what a day costs and the goal that
 * follows from it. The goal of the diary, the history and the export all come from here: nobody types it in.
 */
@Singleton
class BodyModel @Inject constructor(private val store: BodyStore, private val db: AppDatabase, private val clock: Clock) {

    val state: Flow<BodyState> = combine(store.profile, db.weights().observeAll()) { profile, rows ->
        compute(profile, rows.map { it.toDomain() }, LocalDate.now(clock))
    }

    suspend fun goal(): Int? = state.first().goal

    companion object {
        fun compute(profile: BodyProfile, weights: List<Weight>, today: LocalDate): BodyState {
            val age = profile.birthYear?.let { today.year - it }
            val weight = WeightTrend.compute(weights).lastOrNull()?.raw
            val estimate = if (profile.sex != null && age != null && profile.heightCm != null && weight != null && profile.activity != null) {
                Energy.estimate(profile.sex, age, profile.heightCm, weight, profile.activity)
            } else {
                null
            }
            return BodyState(
                profile.sex, age, profile.heightCm, profile.activity, weight, estimate,
                adjustment = profile.adjustment,
                goal = DailyGoal.of(estimate, profile.adjustment),
                goalLimited = DailyGoal.limited(estimate, profile.adjustment),
            )
        }
    }
}
