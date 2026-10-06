package dev.dietapp.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.DailyGoal
import dev.dietapp.data.domain.Energy
import dev.dietapp.data.domain.EnergyEstimate
import dev.dietapp.data.domain.Sex
import dev.dietapp.data.repo.BodyRepository
import dev.dietapp.data.repo.BodyState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the person said about themselves, as the first-run questions and the "О себе" page show it, with what a day costs
 * and the goal that follows. [step] is the question being asked (0 to 4), or [RESULT] once all five are answered.
 */
data class BodyUiState(
    val sex: Sex? = null,
    val age: Int? = null,
    val heightCm: Int? = null,
    val weightKg: Double? = null,
    val activity: Activity? = null,
    val estimate: EnergyEstimate? = null,
    /** The person's own correction of the goal, in kcal: minus to lose weight, plus to gain. */
    val adjustment: Int = 0,
    /** What a day costs plus the correction: the goal of the diary. Null while the estimate is. */
    val goal: Int? = null,
    /** The correction would have gone below the lowest goal the app allows, so the goal is held there. */
    val goalLimited: Boolean = false,
    val step: Int = 0,
) {
    /** Sex and activity have no sensible default, so they must be picked; the numbers start from a typical value. */
    val canNext: Boolean get() = when (step) {
        SEX -> sex != null
        ACTIVITY -> activity != null
        else -> true
    }

    companion object {
        const val SEX = 0
        const val AGE = 1
        const val HEIGHT = 2
        const val WEIGHT = 3
        const val ACTIVITY = 4
        const val RESULT = 5

        /** The questions, not counting the result. */
        const val QUESTIONS = 5
    }
}

/** What the screens can do with it. The view model implements it; the screens only know this interface. */
interface BodyActions {
    fun onSex(sex: Sex)
    fun onAge(age: Int)
    fun onHeight(cm: Int)
    fun onWeight(kg: Double)
    fun onActivity(activity: Activity)

    /** Moves the goal away from what a day costs, in kcal. */
    fun onAdjustment(kcal: Int)
    fun onNext()
    fun onBack()
    fun onFinish()
}

@HiltViewModel
class BodyViewModel @Inject constructor(private val body: BodyRepository) : ViewModel(), BodyActions {
    private val step = MutableStateFlow(0)

    /** The weight being dialled in. It shows at once and becomes a weigh-in when the person stops. */
    private val weightDraft = MutableStateFlow<Double?>(null)
    private var weightJob: Job? = null

    val state: StateFlow<BodyUiState> = combine(body.state, step, weightDraft) { b, s, draft ->
        val weight = draft ?: b.weightKg
        val estimate = if (draft != null) estimateOf(b, weight) else b.estimate
        BodyUiState(
            b.sex, b.age, b.heightCm, weight, b.activity, estimate,
            adjustment = b.adjustment, goal = DailyGoal.of(estimate, b.adjustment), goalLimited = DailyGoal.limited(estimate, b.adjustment),
            step = s,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyUiState())

    private fun estimateOf(b: BodyState, weight: Double?): EnergyEstimate? {
        val sex = b.sex ?: return null
        val age = b.age ?: return null
        val height = b.heightCm ?: return null
        val activity = b.activity ?: return null
        return Energy.estimate(sex, age, height, weight ?: return null, activity)
    }

    override fun onSex(sex: Sex) = body.setSex(sex)
    override fun onAge(age: Int) = body.setAge(age.coerceIn(Energy.MIN_AGE, Energy.MAX_AGE))
    override fun onHeight(cm: Int) = body.setHeight(cm.coerceIn(Energy.MIN_HEIGHT, Energy.MAX_HEIGHT))
    override fun onActivity(activity: Activity) = body.setActivity(activity)
    override fun onAdjustment(kcal: Int) = body.setAdjustment(kcal)

    override fun onWeight(kg: Double) {
        val clean = kg.coerceIn(Energy.MIN_WEIGHT, Energy.MAX_WEIGHT)
        weightDraft.value = clean
        weightJob?.cancel()
        weightJob = viewModelScope.launch {
            delay(WEIGHT_SETTLE_MS)
            commitWeight(clean)
        }
    }

    /** "Далее": a number the person did not touch is taken as it was shown; the question moves on. */
    override fun onNext() {
        val s = state.value
        if (!s.canNext || s.step >= BodyUiState.RESULT) return
        when (s.step) {
            BodyUiState.AGE -> if (s.age == null) body.setAge(Energy.DEFAULT_AGE)
            BodyUiState.HEIGHT -> if (s.heightCm == null) body.setHeight(Energy.DEFAULT_HEIGHT)
            BodyUiState.WEIGHT -> viewModelScope.launch { commitWeight(s.weightKg ?: Energy.DEFAULT_WEIGHT) }
        }
        step.value = s.step + 1
    }

    override fun onBack() {
        step.value = (step.value - 1).coerceAtLeast(0)
    }

    override fun onFinish() = finish()

    private fun finish() {
        // a weight still being dialled in is saved before the questions are over
        weightDraft.value?.let { kg -> viewModelScope.launch { commitWeight(kg) } }
        body.finishOnboarding()
    }

    private suspend fun commitWeight(kg: Double) {
        weightJob?.cancel()
        body.setWeight(kg)
        weightDraft.value = null
    }

    private companion object {
        const val WEIGHT_SETTLE_MS = 600L
    }
}
