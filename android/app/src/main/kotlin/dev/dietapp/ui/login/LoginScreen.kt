package dev.dietapp.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.UnderlineField

/** Sign-in and the one-time goal, on a single screen that reveals itself step by step. */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmail: (String) -> Unit,
    onCode: (String) -> Unit,
    onGoal: (String) -> Unit,
    onSubmit: () -> Unit,
    onChangeEmail: () -> Unit,
    onWithoutServer: () -> Unit,
    onKey: (String) -> Unit,
    onSkipKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = !state.busy && when (state.step) {
        LoginStep.Email -> state.email.contains('@')
        LoginStep.Code -> state.code.length == 6
        LoginStep.Key -> state.key.isNotBlank()
        LoginStep.Goal -> state.goal.isNotEmpty()
    }
    Column(
        modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (state.step) {
            LoginStep.Email -> {
                UnderlineField(
                    state.email, onEmail, Modifier.testTag("email"), placeholder = Texts.EMAIL,
                    keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, onImeAction = { if (canSubmit) onSubmit() },
                )
                Hint(Texts.NO_SERVER_HINT)
            }
            LoginStep.Code -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(state.email, style = AppTheme.type.secondary)
                    TextAction(Texts.CHANGE_EMAIL, onChangeEmail)
                }
                UnderlineField(
                    state.code, onCode, Modifier.testTag("code"), placeholder = Texts.CODE,
                    keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, mono = true,
                    onImeAction = { if (canSubmit) onSubmit() },
                )
                Hint(Texts.CODE_HINT)
            }
            LoginStep.Key -> {
                UnderlineField(
                    state.key, onKey, Modifier.testTag("key"), placeholder = Texts.MODEL_KEY,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, secret = true,
                    onImeAction = { if (canSubmit) onSubmit() },
                )
                Hint(Texts.KEY_STEP_HINT)
            }
            LoginStep.Goal -> {
                UnderlineField(
                    state.goal, onGoal, Modifier.testTag("goal"), placeholder = Texts.GOAL,
                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, mono = true,
                    onImeAction = { if (canSubmit) onSubmit() },
                )
                Hint(Texts.GOAL_HINT)
            }
        }
        state.error?.let {
            Text(it, style = AppTheme.type.secondary.copy(color = AppTheme.colors.error), modifier = Modifier.padding(top = 12.dp).testTag("error"))
        }
        Spacer(Modifier.height(16.dp))
        val withoutServerOffered = state.step == LoginStep.Email && state.serverEnabled
        val skipOffered = state.step == LoginStep.Key
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (withoutServerOffered || skipOffered) Arrangement.SpaceBetween else Arrangement.End) {
            if (withoutServerOffered) TextAction(Texts.NO_SERVER, onWithoutServer, Modifier.testTag("local"), color = AppTheme.colors.secondary)
            if (skipOffered) TextAction(Texts.SKIP, onSkipKey, Modifier.testTag("skip"), color = AppTheme.colors.secondary)
            TextAction(if (state.busy) "…" else Texts.NEXT, onSubmit, Modifier.testTag("next"),
                color = if (canSubmit) AppTheme.colors.foreground else AppTheme.colors.tertiary)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = AppTheme.type.caption, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
}
