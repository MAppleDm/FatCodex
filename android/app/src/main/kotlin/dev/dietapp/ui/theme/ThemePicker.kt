package dev.dietapp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppAppearance
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.TextAction

/** A choice applies at once; closing the picker leaves the current appearance alone. */
@Composable
fun ThemePicker(selected: AppAppearance, onSelect: (AppAppearance) -> Unit, onDismiss: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        title = { Text(Texts.THEME, style = AppTheme.type.body) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                AppAppearance.entries.forEach { appearance ->
                    val palette = appearance.colors(systemDark)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .testTag("theme-${appearance.name.lowercase()}")
                            .selectable(selected = appearance == selected, role = Role.RadioButton, onClick = { onSelect(appearance) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(selected = appearance == selected, onClick = null, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Texts.themeName(appearance), style = AppTheme.type.body)
                            Text(Texts.themeDescription(appearance), style = AppTheme.type.caption)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf(palette.background, palette.recorded, palette.time).forEach { color ->
                                Box(Modifier.size(10.dp).background(color, CircleShape).border(1.dp, palette.border, CircleShape))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextAction(Texts.THEME_CLOSE, onDismiss, Modifier.testTag("theme-close")) },
    )
}
