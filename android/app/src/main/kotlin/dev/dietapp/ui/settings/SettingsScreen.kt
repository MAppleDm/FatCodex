package dev.dietapp.ui.settings

import dev.dietapp.data.domain.Lang
import dev.dietapp.data.local.RecordMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.UnderlineField
import dev.dietapp.coreui.formatInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val HistoryFormat get() = DateTimeFormatter.ofPattern(if (Lang.en) "EEE, MMM d" else "d MMM, EEE", Lang.current.locale)

/** Goal and history, reached from the corner icon. Nothing else lives here. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onGoalChange: (String) -> Unit,
    onSaveGoal: () -> Unit,
    onKeyChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
    onLogout: () -> Unit,
    onOpenFoods: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onToggleWebSearch: () -> Unit = {},
    onToggleRecordMode: () -> Unit = {},
    onToggleLanguage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.SETTINGS, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()

        // everything between the header and the footer scrolls as one list: on a short screen the footer stays visible
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history"), verticalArrangement = Arrangement.Top) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (state.local) Text(Texts.LOCAL_MODE, style = AppTheme.type.secondary)
                    else state.email?.let { Text(it, style = AppTheme.type.secondary) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UnderlineField(
                            value = state.goalText, onValueChange = onGoalChange, modifier = Modifier.width(120.dp).testTag("goal"),
                            placeholder = Texts.GOAL, keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                            onImeAction = { if (state.canSave) onSaveGoal() }, mono = true,
                        )
                        Text(" ${Texts.KCAL}", style = AppTheme.type.monoSecondary)
                        Spacer(Modifier.weight(1f))
                        TextAction(Texts.SAVE, onSaveGoal, color = if (state.canSave) AppTheme.colors.foreground else AppTheme.colors.tertiary)
                    }
                    state.message?.let {
                        Text(
                            it,
                            style = AppTheme.type.caption.copy(color = if (state.messageIsError) AppTheme.colors.error else AppTheme.colors.secondary),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (state.local) {
                item {
                    Hairline()
                    ModelKey(state, onKeyChange, onSaveKey, onClearKey)
                    Hairline()
                    StatRow(Texts.FOODS, formatInt(state.foodCount), Modifier.testTag("foods-entry"), onClick = onOpenFoods)
                    StatRow(Texts.WEB_SEARCH, if (state.webSearch) Texts.ON else Texts.OFF, Modifier.testTag("web-search"), onClick = onToggleWebSearch)
                    Text(Texts.WEB_SEARCH_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    val precise = state.recordMode == RecordMode.Precise
                    StatRow(Texts.RECORD_MODE, if (precise) Texts.MODE_PRECISE else Texts.MODE_STANDARD, Modifier.testTag("record-mode"),
                        onClick = onToggleRecordMode)
                    Text(if (precise) Texts.MODE_PRECISE_HINT else Texts.MODE_STANDARD_HINT, style = AppTheme.type.caption,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    StatRow(Texts.JOURNAL, "›", Modifier.testTag("journal-entry"), onClick = onOpenJournal)
                }
            }
            item {
                Hairline()
                StatRow(Texts.LANGUAGE, Texts.LANGUAGE_NAME, Modifier.testTag("language"), onClick = onToggleLanguage)
                Hairline()
                Text(Texts.HISTORY, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                if (state.history.isEmpty()) {
                    Text(Texts.NO_HISTORY, style = AppTheme.type.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            items(state.history, key = { it.day.toString() }) { day ->
                val kcal = formatInt(day.totals.kcal.roundToInt())
                StatRow(
                    label = day.day.format(HistoryFormat),
                    value = state.savedGoal?.let { "$kcal / ${formatInt(it)}" } ?: kcal,
                    onClick = { onPickDay(day.day) },
                )
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.local) Text(Texts.ERASE_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 8.dp).weight(1f))
            else Spacer(Modifier.weight(1f))
            val label = when {
                !state.local -> Texts.LOG_OUT
                state.eraseArmed -> Texts.ERASE_CONFIRM
                else -> Texts.ERASE
            }
            TextAction(
                label, onLogout, Modifier.testTag("logout"),
                color = if (state.eraseArmed) AppTheme.colors.error else AppTheme.colors.secondary,
            )
        }
    }
}

/** The optional DeepSeek key of the local mode: one line, plus one sentence on what it changes. */
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
        Text(Texts.MODEL_KEY_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
    }
}
