package dev.dietapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dietapp.coreui.AppTheme
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
import androidx.compose.ui.platform.LocalUriHandler
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

/** One activity, one root: sign-in and the questions about the person until the goal is known, then the diary. */
@Composable
fun AppRoot(
    main: MainViewModel, login: LoginViewModel, settings: SettingsViewModel, foods: FoodsViewModel, journal: JournalViewModel,
    export: ExportViewModel,
    body: BodyViewModel,
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
            else -> Diary(main, settings, foods, journal, export, body)
        }
    }
}

@Composable
private fun Diary(
    main: MainViewModel, settings: SettingsViewModel, foods: FoodsViewModel, journal: JournalViewModel, export: ExportViewModel,
    body: BodyViewModel,
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
            onOpenHistory = main::onOpenHistory,
            onToggleLanguage = settings::onToggleLanguage,
            onOpenAbout = main::onOpenAbout,
            onLogout = {
                settings.onLogout()
                main.onCloseSettings()
            },
        )
        Screen.Foods -> FoodsRoute(foods, onBack = main::onCloseFoods)
        Screen.Journal -> JournalRoute(journal, onBack = main::onCloseJournal)
        Screen.Export -> ExportRoute(export, onBack = main::onCloseExport)
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
        )
        Screen.About -> {
            val links = LocalUriHandler.current
            AboutScreen(
                local = settingsState.local,
                version = BuildConfig.VERSION_NAME,
                onBack = main::onCloseAbout,
                onOpenUrl = { runCatching { links.openUri(it) } },
                onOpenAgent = main::onOpenAgent,
                onOpenJournal = main::onOpenJournal,
                eraseArmed = settingsState.eraseArmed,
                onErase = {
                    settings.onLogout()
                    main.onCloseSettings()
                },
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
