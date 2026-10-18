package dev.dietapp.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.StatRow
import java.util.Locale

@Composable
fun UpdateBlock(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onReleases: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("about-updates")) {
        Text(Texts.UPDATES, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        val status = when (state.phase) {
            UpdatePhase.Idle -> Texts.UPDATE_HINT
            UpdatePhase.Checking -> Texts.UPDATE_CHECKING
            UpdatePhase.Current -> Texts.UPDATE_CURRENT
            UpdatePhase.NoReleases -> Texts.UPDATE_NO_RELEASES
            UpdatePhase.Available -> Texts.updateAvailable(state.release?.tag.orEmpty())
            UpdatePhase.Downloading -> if (state.paused) Texts.UPDATE_WAITING else Texts.UPDATE_DOWNLOADING
            UpdatePhase.Verifying -> Texts.UPDATE_VERIFYING
            UpdatePhase.Ready -> Texts.UPDATE_READY
        }
        Text(status, style = AppTheme.type.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("update-status"))
        state.problem?.let { problem ->
            Text(Texts.updateProblem(problem), style = AppTheme.type.caption.copy(color = AppTheme.colors.error),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("update-error"))
        }
        when (state.phase) {
            UpdatePhase.Idle, UpdatePhase.Current, UpdatePhase.NoReleases ->
                StatRow(Texts.UPDATE_CHECK, "›", Modifier.testTag("update-check"), onClick = onCheck)
            UpdatePhase.Available -> {
                val release = state.release
                if (release?.apk != null) {
                    StatRow(Texts.UPDATE_DOWNLOAD, updateSize(release.apk.size), Modifier.testTag("update-download"), onClick = onDownload)
                } else {
                    Text(Texts.UPDATE_NO_APK, style = AppTheme.type.caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (!release?.notes.isNullOrBlank()) {
                    Text(release.notes, style = AppTheme.type.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("update-notes"))
                }
                StatRow(Texts.UPDATE_RELEASE_PAGE, "↗", Modifier.testTag("update-releases"), onClick = onReleases)
                StatRow(Texts.UPDATE_CHECK, "›", Modifier.testTag("update-check"), onClick = onCheck)
            }
            UpdatePhase.Downloading -> {
                val progress = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { progress }, color = AppTheme.colors.foreground, trackColor = AppTheme.colors.border,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("update-progress"))
                Text("${(progress * 100).toInt()}% · ${updateSize(state.bytes)} / ${updateSize(state.total)}",
                    style = AppTheme.type.caption, modifier = Modifier.padding(horizontal = 16.dp).testTag("update-bytes"))
                StatRow(Texts.CANCEL, "×", Modifier.testTag("update-cancel"), onClick = onCancel)
            }
            UpdatePhase.Ready -> {
                StatRow(Texts.UPDATE_INSTALL, "›", Modifier.testTag("update-install"), onClick = onInstall)
                Text(Texts.UPDATE_INSTALL_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                StatRow(Texts.UPDATE_REMOVE, "×", Modifier.testTag("update-cancel"), onClick = onCancel)
            }
            else -> Unit
        }
        Hairline(Modifier.padding(top = 8.dp))
    }
}

internal fun updateSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
