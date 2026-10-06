package dev.dietapp.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.tap

const val GITHUB_URL = "https://github.com/MAppleDm/FatCodex"
const val LICENSE_URL = "https://github.com/MAppleDm/FatCodex/blob/main/LICENSE"

/**
 * What the app is, which version this is, where the diary lives and what leaves the phone, and where to find the code.
 * The things that used to sit as small print in settings ("Без сервера…", "единственная копия") are here.
 */
@Composable
fun AboutScreen(
    local: Boolean,
    version: String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The first tap on "Стереть всё" was made; the second one does it. */
    eraseArmed: Boolean = false,
    onErase: () -> Unit = {},
    /** The agent and its journal are about the model, so only the phone-only mode has them. */
    onOpenAgent: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.ABOUT, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("about-page")) {
            Column(Modifier.padding(16.dp)) {
                Text("FatCodex", style = AppTheme.type.body)
                Text(Texts.ABOUT_TAGLINE, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 4.dp))
            }
            StatRow(Texts.ABOUT_VERSION, version, Modifier.testTag("about-version"))
            Hairline()

            Text(Texts.ABOUT_DATA, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("about-data")) {
                if (local) {
                    Text(Texts.LOCAL_MODE, style = AppTheme.type.body)
                    Text(Texts.ABOUT_LOCAL_COPY, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 4.dp))
                    Text(Texts.ABOUT_LOCAL_SENT, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(Texts.ABOUT_SERVER_COPY, style = AppTheme.type.secondary)
                    Text(Texts.ABOUT_SERVER_SENT, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Hairline(Modifier.padding(top = 12.dp))

            if (local) {
                Text(Texts.ABOUT_MODEL, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                StatRow(Texts.AGENT, "›", Modifier.testTag("about-agent"), onClick = onOpenAgent)
                StatRow(Texts.JOURNAL, "›", Modifier.testTag("about-journal"), onClick = onOpenJournal)
                Text(Texts.ABOUT_JOURNAL_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                Hairline(Modifier.padding(top = 4.dp))
            }

            Text(Texts.ABOUT_LINKS, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            StatRow(Texts.ABOUT_GITHUB, "›", Modifier.testTag("about-github"), onClick = { onOpenUrl(GITHUB_URL) })
            Text(
                GITHUB_URL.removePrefix("https://"), style = AppTheme.type.caption,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            StatRow(Texts.ABOUT_LICENSE, Texts.ABOUT_LICENSE_NAME, Modifier.testTag("about-license"), onClick = { onOpenUrl(LICENSE_URL) })

            // without a server this phone holds the only copy; with one, "выйти" in settings is enough
            if (local) EraseBlock(eraseArmed, onErase)
        }
    }
}

/**
 * The one button that cannot be taken back, set apart in red. Two taps: the first arms it (the button turns solid and
 * asks again, and the view model disarms it after a few seconds), the second erases.
 */
@Composable
private fun EraseBlock(armed: Boolean, onErase: () -> Unit) {
    val red = AppTheme.colors.error
    Column(
        Modifier.padding(16.dp).padding(top = 12.dp).fillMaxWidth()
            .border(1.dp, red.copy(alpha = 0.6f)).background(red.copy(alpha = 0.07f)).padding(16.dp)
            .testTag("about-erase-block"),
    ) {
        Text(Texts.ABOUT_DANGER, style = AppTheme.type.caption.copy(color = red))
        Text(Texts.ABOUT_ERASE_DETAILS, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier.padding(top = 12.dp).fillMaxWidth()
                .border(1.dp, red).background(if (armed) red else Color.Transparent)
                .tap(onClick = onErase).padding(vertical = 12.dp)
                .testTag("about-erase"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (armed) Texts.ERASE_CONFIRM else Texts.ERASE,
                style = AppTheme.type.body.copy(color = if (armed) AppTheme.colors.background else red),
            )
        }
    }
}
