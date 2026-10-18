package dev.dietapp.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.LockIcon
import dev.dietapp.coreui.tap

/**
 * What the app shows while it is closed: nothing of the diary, only a way to open it. The system's prompt comes up by itself;
 * the button asks for it again after it was dismissed.
 */
@Composable
fun LockScreen(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().testTag("lock-screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockIcon(c.foreground, Modifier.size(40.dp))
        Text("FatCodex", style = AppTheme.type.body, modifier = Modifier.padding(top = 16.dp))
        Text(Texts.LOCKED, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 4.dp))
        Text(
            Texts.UNLOCK, style = AppTheme.type.body,
            modifier = Modifier.padding(top = 32.dp).border(1.dp, c.foreground).tap(onClick = onOpen)
                .padding(horizontal = 36.dp, vertical = 12.dp).testTag("unlock"),
        )
    }
}
