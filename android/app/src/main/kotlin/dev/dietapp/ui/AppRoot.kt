package dev.dietapp.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dietapp.coreui.AppTheme
import dev.dietapp.ui.font.FontViewModel
import dev.dietapp.ui.foods.FoodsRoute
import dev.dietapp.ui.foods.FoodsViewModel
import dev.dietapp.ui.journal.JournalRoute
import dev.dietapp.ui.journal.JournalViewModel
import dev.dietapp.ui.login.LoginScreen
import dev.dietapp.ui.login.LoginStep
import dev.dietapp.ui.login.LoginViewModel
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.main.Screen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import dev.dietapp.Texts
import dev.dietapp.ui.lock.Biometrics
import dev.dietapp.ui.lock.LockSupport
import dev.dietapp.ui.lock.PromptResult
import dev.dietapp.ui.lock.LockViewModel
import dev.dietapp.ui.sound.SoundViewModel
import dev.dietapp.ui.lock.fragmentActivity
import dev.dietapp.BuildConfig
import dev.dietapp.ui.about.AboutScreen
import dev.dietapp.ui.agent.AgentScreen
import dev.dietapp.ui.body.BodyViewModel
import dev.dietapp.ui.body.BodyWizardScreen
import dev.dietapp.ui.body.MeScreen
import dev.dietapp.ui.export.ExportRoute
import dev.dietapp.ui.history.HistoryScreen
import dev.dietapp.ui.export.ExportViewModel
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsViewModel
import dev.dietapp.ui.watch.WatchRoute
import dev.dietapp.ui.watch.WatchViewModel
import dev.dietapp.ui.update.UpdateInstaller
import dev.dietapp.ui.update.UpdateProblem
import dev.dietapp.ui.update.UpdateViewModel

/** One activity, one root: sign-in and the questions about the person until the goal is known, then the diary. */
@Composable
fun AppRoot(
    main: MainViewModel, login: LoginViewModel, settings: SettingsViewModel, foods: FoodsViewModel, journal: JournalViewModel,
    export: ExportViewModel,
    body: BodyViewModel,
    watch: WatchViewModel,
    fonts: FontViewModel,
    lock: LockViewModel,
    sound: SoundViewModel,
    updates: UpdateViewModel,
) {
    val loginState by login.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        when {
            loginState.loading -> Unit // a frame of background instead of a flash of the wrong screen
            !loginState.done && loginState.step == LoginStep.About -> {
                val bodyState by body.state.collectAsStateWithLifecycle()
                BodyWizardScreen(bodyState, body)
            }
            !loginState.done -> LoginScreen(
                state = loginState,
                onEmail = login::onEmail,
                onCode = login::onCode,
                onSubmit = login::onSubmit,
                onChangeEmail = login::onChangeEmail,
                onWithoutServer = login::onWithoutServer,
                onKey = login::onKey,
            )
            else -> Diary(main, settings, foods, journal, export, body, watch, fonts, lock, sound, updates)
        }
    }
}

@Composable
private fun Diary(
    main: MainViewModel, settings: SettingsViewModel, foods: FoodsViewModel, journal: JournalViewModel, export: ExportViewModel,
    body: BodyViewModel, watch: WatchViewModel, fonts: FontViewModel, lock: LockViewModel,
    sound: SoundViewModel,
    updates: UpdateViewModel,
) {
    val state by main.uiState.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()

    val foodsState by foods.state.collectAsStateWithLifecycle()

    // Back closes the innermost thing first: camera, an open editor, the food base, then settings.
    BackHandler(enabled = state.cameraOpen || state.editing != null || state.screen != Screen.Main) {
        when {
            state.cameraOpen -> main.onCloseCamera()
            state.editing != null -> main.onEditCommit()
            state.screen == Screen.Foods -> if (foodsState.editing != null) foods.onCancel() else main.onCloseFoods()
            state.screen == Screen.Journal -> main.onCloseJournal()
            state.screen == Screen.Export -> main.onCloseExport()
            state.screen == Screen.Agent -> main.onCloseAgent()
            state.screen == Screen.About -> main.onCloseAbout()
            state.screen == Screen.History -> main.onCloseHistory()
            state.screen == Screen.Me -> main.onCloseMe()
            state.screen == Screen.Watch -> main.onCloseWatch()
            else -> main.onCloseSettings()
        }
    }

    when (state.screen) {
        Screen.Main -> MainScreen(state, main, main.effects)
        Screen.Settings -> SettingsScreen(
            state = settingsState,
            onBack = main::onCloseSettings,
            onOpenFoods = main::onOpenFoods,
            onOpenMe = main::onOpenMe,
            onOpenWatch = main::onOpenWatch,
            onOpenHistory = main::onOpenHistory,
            onToggleLanguage = settings::onToggleLanguage,
            onOpenAbout = main::onOpenAbout,
            onLogout = { if (settings.onEraseTap()) main.onCloseSettings() },
        )
        Screen.Foods -> FoodsRoute(foods, onBack = main::onCloseFoods)
        Screen.Journal -> JournalRoute(journal, onBack = main::onCloseJournal)
        Screen.Export -> ExportRoute(export, onBack = main::onCloseExport)
        Screen.Watch -> WatchRoute(watch, onBack = main::onCloseWatch)
        Screen.Me -> {
            val bodyState by body.state.collectAsStateWithLifecycle()
            MeScreen(bodyState, body, onBack = main::onCloseMe)
        }
        Screen.History -> HistoryScreen(
            days = settingsState.history,
            goal = settingsState.savedGoal,
            onBack = main::onCloseHistory,
            onPickDay = main::onSelectDay,
            onOpenExport = main::onOpenExport,
            today = state.today,
        )
        Screen.About -> {
            val links = LocalUriHandler.current
            val font by fonts.choice.collectAsStateWithLifecycle()
            val fontFailed by fonts.failed.collectAsStateWithLifecycle()
            val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(fonts::onFile) }
            val context = LocalContext.current
            val update by updates.state.collectAsStateWithLifecycle()
            val installUpdate = {
                updates.onInstall { file ->
                    runCatching { context.startActivity(UpdateInstaller.intent(context, file)) }
                        .onFailure { updates.onInstallProblem(UpdateProblem.Installer) }
                }
            }
            val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (context.packageManager.canRequestPackageInstalls()) installUpdate()
                else updates.onInstallProblem(UpdateProblem.Permission)
            }
            val soundOn by sound.enabled.collectAsStateWithLifecycle()
            val lockOn by lock.enabled.collectAsStateWithLifecycle()
            val lockSupport = Biometrics.support(context)
            AboutScreen(
                local = settingsState.local,
                version = BuildConfig.VERSION_NAME,
                onBack = main::onCloseAbout,
                onOpenUrl = { runCatching { links.openUri(it) } },
                onOpenAgent = main::onOpenAgent,
                onOpenJournal = main::onOpenJournal,
                font = font,
                fontFailed = fontFailed,
                onFont = fonts::onKind,
                // font files have no reliable mime type, so every file is offered and the content decides
                onPickFontFile = { pickFont.launch(arrayOf("*/*")) },
                soundOn = soundOn,
                onToggleSound = sound::onToggle,
                update = update,
                onCheckUpdates = updates::onCheck,
                onDownloadUpdate = updates::onDownload,
                onCancelUpdate = updates::onCancel,
                onInstallUpdate = {
                    if (context.packageManager.canRequestPackageInstalls()) installUpdate()
                    else runCatching { allowInstall.launch(UpdateInstaller.permissionIntent(context)) }
                        .onFailure { updates.onInstallProblem(UpdateProblem.Installer) }
                },
                lockOn = lockOn,
                lockSupport = lockSupport,
                // turning the lock on or off is asked of the person with the same prompt that opens it
                onToggleLock = {
                    val activity = context.fragmentActivity()
                    when {
                        activity == null -> Unit
                        // the phone cannot check anyone any more: a lock that is on can only be turned off
                        lockSupport != LockSupport.Ready -> if (lockOn) lock.setEnabled(false)
                        else -> Biometrics.prompt(activity, if (lockOn) Texts.LOCK_TITLE_OFF else Texts.LOCK_TITLE_ON) { result ->
                            if (result == PromptResult.Passed) lock.setEnabled(!lockOn)
                        }
                    }
                },
                eraseArmed = settingsState.eraseArmed,
                // the first tap only arms the button: the page stays, so that the second tap can be made
                onErase = { if (settings.onEraseTap()) main.onCloseSettings() },
            )
        }
        Screen.Agent -> AgentScreen(
            state = settingsState,
            onBack = main::onCloseAgent,
            onKeyChange = settings::onKeyChange,
            onSaveKey = settings::onSaveKey,
            onClearKey = settings::onClearKey,
            onTogglePrompt = settings::onTogglePrompt,
        )
    }
}
