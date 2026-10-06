package dev.dietapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.formatInt

/**
 * What a person comes here for sits on top: "о себе" (where the goal is: what a day costs plus a correction), the food base,
 * the history. The language and "о приложении" sit at the very bottom, always in reach. The export is an action of the history page; the agent, the journal and
 * "стереть всё" (set apart in red) live on the about page.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenFoods: () -> Unit = {},
    onOpenMe: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onToggleLanguage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.SETTINGS, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()

        // the top scrolls if the screen is short; the bottom group below it stays where it is
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("settings-top")) {
            // who is signed in; where the diary lives is told on the "о приложении" page
            state.email?.takeIf { !state.local }?.let {
                Text(it, style = AppTheme.type.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                Hairline()
            }
            StatRow(Texts.ME, "›", Modifier.testTag("me-entry"), onClick = onOpenMe)
            if (state.local) StatRow(Texts.FOODS, formatInt(state.foodCount), Modifier.testTag("foods-entry"), onClick = onOpenFoods)
            StatRow(Texts.HISTORY, "›", Modifier.testTag("history-entry"), onClick = onOpenHistory)
        }

        // the bottom group: set once and left alone, then the page about the app
        Hairline()
        StatRow(Texts.LANGUAGE, Texts.LANGUAGE_NAME, Modifier.testTag("language"), onClick = onToggleLanguage)
        StatRow(Texts.ABOUT, "›", Modifier.testTag("about-entry"), onClick = onOpenAbout)
        if (!state.local) {
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextAction(Texts.LOG_OUT, onLogout, Modifier.testTag("logout"), color = AppTheme.colors.secondary)
            }
        }
    }
}
