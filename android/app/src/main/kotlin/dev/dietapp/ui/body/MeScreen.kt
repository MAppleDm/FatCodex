package dev.dietapp.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.data.domain.Energy

/**
 * "О себе": everything the person said about themselves, each with its own control and saved as it is changed, what a
 * day costs them worked out from it, and the goal for the day: that plus a correction of their own. The weight is the diary's latest weigh-in, so writing "вес 82.4" in the chat
 * updates this page, and dialling it here makes a weigh-in of today.
 */
@Composable
fun MeScreen(state: BodyUiState, actions: BodyActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.ME, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("me-page")) {
            Column(Modifier.padding(16.dp)) {
                EnergyBlock(state.estimate)
                if (state.estimate != null) {
                    Spacer(Modifier.height(24.dp))
                    GoalBlock(state, actions::onAdjustment)
                }
            }
            MeSection(Texts.ME_SEX) { SexChoice(state.sex, actions::onSex) }
            MeSection(Texts.ME_AGE) {
                NumberStepper(
                    state.age?.toDouble(), Energy.DEFAULT_AGE.toDouble(), Energy.MIN_AGE.toDouble(), Energy.MAX_AGE.toDouble(), 1.0,
                    Texts.ME_YEARS, { actions.onAge(it.toInt()) }, tag = "age",
                )
            }
            MeSection(Texts.ME_HEIGHT) {
                NumberStepper(
                    state.heightCm?.toDouble(), Energy.DEFAULT_HEIGHT.toDouble(), Energy.MIN_HEIGHT.toDouble(), Energy.MAX_HEIGHT.toDouble(), 1.0,
                    Texts.ME_CM, { actions.onHeight(it.toInt()) }, tag = "height",
                )
            }
            MeSection(Texts.ME_WEIGHT) {
                NumberStepper(
                    state.weightKg, Energy.DEFAULT_WEIGHT, Energy.MIN_WEIGHT, Energy.MAX_WEIGHT, 0.5,
                    Texts.KG, actions::onWeight, tag = "weight", decimals = 1,
                )
                Text(Texts.ME_WEIGHT_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
            }
            MeSection(Texts.ME_ACTIVITY) { ActivityChoice(state.activity, actions::onActivity) }
        }
    }
}
