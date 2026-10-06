package dev.dietapp.data.repo

import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.DailyGoal
import dev.dietapp.data.domain.Sex
import dev.dietapp.data.local.BodyStore
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class BodyRepositoryImpl @Inject constructor(
    private val store: BodyStore,
    model: BodyModel,
    private val diary: DiaryRepository,
    private val clock: Clock,
) : BodyRepository {

    override val state: Flow<BodyState> = model.state

    override val asked: Flow<Boolean> = store.asked

    override fun setSex(sex: Sex) = store.update { it.copy(sex = sex) }

    /** Kept as the year of birth, so the age moves on by itself. */
    override fun setAge(age: Int) = store.update { it.copy(birthYear = LocalDate.now(clock).year - age) }

    override fun setHeight(cm: Int) = store.update { it.copy(heightCm = cm) }

    override fun setActivity(activity: Activity) = store.update { it.copy(activity = activity) }

    override fun setAdjustment(kcal: Int) =
        store.update { it.copy(adjustment = kcal.coerceIn(DailyGoal.MIN_ADJUSTMENT, DailyGoal.MAX_ADJUSTMENT)) }

    override suspend fun setWeight(kg: Double) = diary.setWeightForDay(LocalDate.now(clock), kg, clock.instant())

    override fun finishOnboarding() = store.markAsked()
}
