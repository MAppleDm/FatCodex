package dev.dietapp.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.UnderlineField
import dev.dietapp.ui.settings.SettingsUiState

/**
 * Everything about the model that reads the messages, on a page of its own: which provider and key (DeepSeek's API is
 * the only kind so far), the web search (always on, shown so nothing is hidden) and the system prompt, which can be
 * read (it opens on a tap) but not changed yet.
 */
@Composable
fun AgentScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onKeyChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onTogglePrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.AGENT, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("agent-page")) {
            StatRow(Texts.AGENT_PROVIDER, Texts.AGENT_PROVIDER_DEEPSEEK, Modifier.testTag("agent-provider"))
            ModelKey(state, onKeyChange, onSaveKey, onClearKey)
            Hairline()
            StatRow(Texts.AGENT_WEB, Texts.AGENT_WEB_ON, Modifier.testTag("agent-web"))
            Text(Texts.AGENT_WEB_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            Hairline()
            StatRow(
                Texts.AGENT_PROMPT, "", Modifier.testTag("agent-prompt"),
                extra = if (state.promptOpen) "−" else "+", onClick = onTogglePrompt,
            )
            if (state.promptOpen) {
                Text(Texts.AGENT_PROMPT_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                Text(
                    state.agentPrompt, style = AppTheme.type.caption.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("agent-prompt-text"),
                )
            }
        }
    }
}

/** The DeepSeek key: one line, plus one sentence on what it changes. A stored key is never shown, only that there is one. */
@Composable
private fun ModelKey(state: SettingsUiState, onKeyChange: (String) -> Unit, onSave: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (state.hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Texts.MODEL_KEY_SET, style = AppTheme.type.secondary, modifier = Modifier.weight(1f))
                TextAction(Texts.REMOVE, onClear, Modifier.testTag("key-clear"), color = AppTheme.colors.secondary)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(
                    value = state.keyInput, onValueChange = onKeyChange, modifier = Modifier.weight(1f).testTag("key"),
                    placeholder = Texts.MODEL_KEY, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    onImeAction = { if (state.canSaveKey) onSave() }, secret = true,
                )
                TextAction(
                    Texts.SAVE, onSave, Modifier.testTag("key-save"),
                    color = if (state.canSaveKey) AppTheme.colors.foreground else AppTheme.colors.tertiary,
                )
            }
        }
        state.keyMessage?.let {
            Text(
                it,
                style = AppTheme.type.caption.copy(color = if (state.keyMessageIsError) AppTheme.colors.error else AppTheme.colors.secondary),
                modifier = Modifier.padding(top = 4.dp).testTag("key-message"),
            )
        }
        Text(Texts.MODEL_KEY_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
    }
}
