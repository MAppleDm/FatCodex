package dev.dietapp.ui.body

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.TextAction
import dev.dietapp.data.domain.Energy

/**
 * The first-run questions, one at a time, each with the control that suits it: two boxes for sex, a dial for the age, the
 * height and the weight, five described levels for the activity. Then what a day costs, worked out from the answers, and
 * the goal that follows from it, which the person can correct. The goal is not typed anywhere, so none of the questions
 * can be skipped; "О себе" in settings is where to change the answers later.
 */
@Composable
fun BodyWizardScreen(state: BodyUiState, actions: BodyActions, modifier: Modifier = Modifier) {
    val onResult = state.step == BodyUiState.RESULT
    BackHandler(enabled = state.step > 0, onBack = actions::onBack)
    Column(
        modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Progress(state.step)
                if (!onResult) {
                    Text(
                        Texts.wizardProgress(state.step + 1, BodyUiState.QUESTIONS), style = AppTheme.type.caption,
                        modifier = Modifier.padding(top = 6.dp).testTag("wizard-progress"),
                    )
                }
            }
        }

        // each question opens at its top, whatever the one before was scrolled to
        val scroll = rememberScrollState()
        LaunchedEffect(state.step) { scroll.scrollTo(0) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(16.dp))
            when (state.step) {
                BodyUiState.SEX -> Question(Texts.WIZARD_SEX_Q, Texts.WIZARD_SEX_WHY) { SexChoice(state.sex, actions::onSex) }
                BodyUiState.AGE -> Question(Texts.WIZARD_AGE_Q, Texts.WIZARD_AGE_WHY) {
                    NumberStepper(
                        state.age?.toDouble(), Energy.DEFAULT_AGE.toDouble(), Energy.MIN_AGE.toDouble(), Energy.MAX_AGE.toDouble(), 1.0,
                        Texts.ME_YEARS, { actions.onAge(it.toInt()) }, tag = "age", dashWhenEmpty = false,
                    )
                }
                BodyUiState.HEIGHT -> Question(Texts.WIZARD_HEIGHT_Q, Texts.WIZARD_HEIGHT_WHY) {
                    NumberStepper(
                        state.heightCm?.toDouble(), Energy.DEFAULT_HEIGHT.toDouble(), Energy.MIN_HEIGHT.toDouble(), Energy.MAX_HEIGHT.toDouble(), 1.0,
                        Texts.ME_CM, { actions.onHeight(it.toInt()) }, tag = "height", dashWhenEmpty = false,
                    )
                }
                BodyUiState.WEIGHT -> Question(Texts.WIZARD_WEIGHT_Q, Texts.WIZARD_WEIGHT_WHY) {
                    NumberStepper(
                        state.weightKg, Energy.DEFAULT_WEIGHT, Energy.MIN_WEIGHT, Energy.MAX_WEIGHT, 0.5,
                        Texts.KG, actions::onWeight, tag = "weight", decimals = 1, dashWhenEmpty = false,
                    )
                }
                BodyUiState.ACTIVITY -> Question(Texts.WIZARD_ACTIVITY_Q, Texts.WIZARD_ACTIVITY_WHY) {
                    ActivityChoice(state.activity, actions::onActivity)
                }
                else -> Question(Texts.WIZARD_RESULT_Q, Texts.WIZARD_RESULT_HINT) {
                    Column {
                        EnergyBlock(state.estimate)
                        if (state.estimate != null) {
                            Spacer(Modifier.height(24.dp))
                            GoalBlock(state, actions::onAdjustment)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (state.step > 0) {
                TextAction(Texts.WIZARD_BACK, actions::onBack, Modifier.testTag("wizard-back"), color = AppTheme.colors.secondary)
            } else {
                Spacer(Modifier.height(1.dp))
            }
            if (onResult) {
                TextAction(Texts.WIZARD_DONE, actions::onFinish, Modifier.testTag("wizard-done"))
            } else {
                TextAction(
                    Texts.NEXT, actions::onNext, Modifier.testTag("wizard-next"),
                    color = if (state.canNext) AppTheme.colors.foreground else AppTheme.colors.tertiary,
                )
            }
        }
    }
}

@Composable
private fun Question(title: String, why: String, control: @Composable () -> Unit) {
    Text(title, style = AppTheme.type.body, modifier = Modifier.testTag("wizard-title"))
    Text(why, style = AppTheme.type.caption, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
    control()
}

/** A thin bar of five pieces; the ones up to the question being asked are filled. The result fills all of them. */
@Composable
private fun Progress(step: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(BodyUiState.QUESTIONS) { i ->
            Box(
                Modifier.weight(1f).height(2.dp)
                    .background(if (i <= step) AppTheme.colors.foreground else AppTheme.colors.border),
            )
        }
    }
}
