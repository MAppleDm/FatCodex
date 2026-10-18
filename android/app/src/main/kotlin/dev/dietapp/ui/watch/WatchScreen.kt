package dev.dietapp.ui.watch

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.TextAction
import dev.dietapp.data.repo.WatchState
import dev.dietapp.data.watch.WatchAvailability

/** The screen with the system's permission dialog and the way to Health Connect's page in the store. */
@Composable
fun WatchRoute(vm: WatchViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.onOpened() }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted -> vm.onGranted(granted) }
    WatchScreen(
        state = state,
        onBack = onBack,
        onConnect = { request.launch(vm.permissions) },
        onDisconnect = vm::onDisconnect,
        onInstall = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/**
 * "Часы": what is read from the watch, whether it is connected, and the one button for that. Nothing here can be typed: the
 * numbers arrive by themselves and show up in the day's summary and in the export.
 */
@Composable
fun WatchScreen(state: WatchState, onBack: () -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.DEVICES, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("watch-page")) {
            Text(Texts.WATCH_ABOUT, style = AppTheme.type.secondary)
            Text(Texts.WATCH_WHAT, style = AppTheme.type.caption, modifier = Modifier.padding(top = 12.dp))

            val ready = state.availability == WatchAvailability.Ready || state.availability == WatchAvailability.Unknown
            val status = when {
                state.availability == WatchAvailability.NotInstalled -> Texts.WATCH_NOT_INSTALLED
                state.availability == WatchAvailability.UpdateNeeded -> Texts.WATCH_UPDATE_NEEDED
                state.connected && state.availability == WatchAvailability.Ready && !state.permitted -> Texts.WATCH_NO_PERMISSION
                state.connected && state.availability == WatchAvailability.Ready && !state.complete -> Texts.WATCH_PARTIAL
                state.connected -> Texts.WATCH_ON
                else -> Texts.WATCH_OFF
            }
            Text(status, style = AppTheme.type.body, modifier = Modifier.padding(top = 24.dp).testTag("watch-status"))
            if (state.connected) {
                state.lastReadAt?.let { Text(Texts.watchRead(it), style = AppTheme.type.caption, modifier = Modifier.testTag("watch-read")) }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.availability == WatchAvailability.NotInstalled -> TextAction(Texts.WATCH_INSTALL, onInstall, Modifier.testTag("watch-install"))
                    state.availability == WatchAvailability.UpdateNeeded -> TextAction(Texts.WATCH_UPDATE, onInstall, Modifier.testTag("watch-install"))
                    ready && (!state.connected || !state.permitted || !state.complete) ->
                        TextAction(if (state.connected && state.permitted) Texts.WATCH_ALLOW else Texts.WATCH_CONNECT, onConnect, Modifier.testTag("watch-connect"))
                }
                if (state.connected) TextAction(Texts.WATCH_DISCONNECT, onDisconnect, Modifier.testTag("watch-disconnect"), color = AppTheme.colors.secondary)
            }
            if (state.connected) Text(Texts.WATCH_KEPT, style = AppTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
