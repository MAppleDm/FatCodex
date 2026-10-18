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
import dev.dietapp.ui.font.FontChoice
import dev.dietapp.ui.font.FontKind
import dev.dietapp.ui.lock.LockSupport
import dev.dietapp.ui.update.RELEASES_URL
import dev.dietapp.ui.update.UpdateBlock
import dev.dietapp.ui.update.UpdateState

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
    /** The font of the app: which one is in use, the way to change it, and whether the last file picked was no font. */
    font: FontChoice = FontChoice(),
    fontFailed: Boolean = false,
    onFont: (FontKind) -> Unit = {},
    onPickFontFile: () -> Unit = {},
    /** The lock of the app: whether it is on, whether this phone can check anyone, and the way to turn it on or off. */
    lockOn: Boolean = false,
    lockSupport: LockSupport = LockSupport.Ready,
    onToggleLock: () -> Unit = {},
    /** The sound of a recorded food: on or off. */
    soundOn: Boolean = true,
    onToggleSound: () -> Unit = {},
    update: UpdateState = UpdateState(),
    onCheckUpdates: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onCancelUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
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
            UpdateBlock(update, onCheckUpdates, onDownloadUpdate, onCancelUpdate, onInstallUpdate, { onOpenUrl(RELEASES_URL) })

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

            SoundBlock(soundOn, onToggleSound)
            LockBlock(lockOn, lockSupport, onToggleLock)
            FontBlock(font, fontFailed, onFont, onPickFontFile)
            Hairline(Modifier.padding(top = 4.dp))

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

/** The row that turns the sound of a recorded food on and off. */
@Composable
private fun SoundBlock(on: Boolean, onToggle: () -> Unit) {
    Text(Texts.SOUND_SECTION, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    StatRow(Texts.SOUND, if (on) Texts.SOUND_ON else Texts.SOUND_OFF, Modifier.testTag("about-sound"), onClick = onToggle)
    Text(Texts.SOUND_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    Hairline(Modifier.padding(top = 4.dp))
}

/** The row that turns the app's lock on and off, and what it needs when it cannot be turned on. */
@Composable
private fun LockBlock(on: Boolean, support: LockSupport, onToggle: () -> Unit) {
    Text(Texts.LOCK_SECTION, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    StatRow(Texts.LOCK, if (on) Texts.LOCK_ON else Texts.LOCK_OFF, Modifier.testTag("about-lock"), onClick = onToggle)
    val hint = when {
        on && support != LockSupport.Ready -> Texts.LOCK_BROKEN
        on || support == LockSupport.Ready -> Texts.LOCK_HINT
        support == LockSupport.NotSet -> Texts.LOCK_NOT_SET
        else -> Texts.LOCK_UNAVAILABLE
    }
    Text(hint, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("lock-hint"))
    Hairline(Modifier.padding(top = 4.dp))
}

/** Inter, the system font, a font from a file picked earlier, and the way to pick one. The one in use has a dot. */
@Composable
private fun FontBlock(font: FontChoice, failed: Boolean, onFont: (FontKind) -> Unit, onPickFile: () -> Unit) {
    Text(Texts.FONT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    fun dot(kind: FontKind) = if (font.kind == kind) "●" else ""
    StatRow(Texts.FONT_INTER, dot(FontKind.Inter), Modifier.testTag("font-inter"), onClick = { onFont(FontKind.Inter) })
    StatRow(Texts.FONT_SYSTEM, dot(FontKind.System), Modifier.testTag("font-system"), onClick = { onFont(FontKind.System) })
    font.fileName?.let { name ->
        StatRow(name, dot(FontKind.File), Modifier.testTag("font-file"), onClick = { onFont(FontKind.File) })
    }
    StatRow(Texts.FONT_PICK, "›", Modifier.testTag("font-pick"), onClick = onPickFile)
    if (failed) {
        Text(
            Texts.FONT_FAILED, style = AppTheme.type.caption.copy(color = AppTheme.colors.error),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp).testTag("font-failed"),
        )
    }
    Text(Texts.FONT_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
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
