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
import dev.dietapp.ui.login.LoginViewModel
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.main.Screen
import dev.dietapp.ui.export.ExportRoute
import dev.dietapp.ui.export.ExportViewModel
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsViewModel

/** One activity, one root: sign-in until there is a session and a goal, then the diary. */
@Composable
fun AppRoot(
    main: MainViewModel, login: LoginViewModel, settings: SettingsViewModel, foods: FoodsViewModel, journal: JournalViewModel,
    export: ExportViewModel,
) {
    val loginState by login.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        when {
            loginState.loading -> Unit // a frame of background instead of a flash of the wrong screen
            !loginState.done -> LoginScreen(
                state = loginState,
                onEmail = login::onEmail,
                onCode = login::onCode,
                onGoal = login::onGoal,
                onSubmit = login::onSubmit,
                onChangeEmail = login::onChangeEmail,
                onWithoutServer = login::onWithoutServer,
                onKey = login::onKey,
                onSkipKey = login::onSkipKey,
            )
            else -> Diary(main, settings, foods, journal, export)
        }
    }
}

@Composable
private fun Diary(main: MainViewModel, settings: SettingsViewModel, foods: FoodsViewModel, journal: JournalViewModel, export: ExportViewModel) {
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
            else -> main.onCloseSettings()
        }
    }

    when (state.screen) {
        Screen.Main -> MainScreen(state, main, main.effects)
        Screen.Settings -> SettingsScreen(
            state = settingsState,
            onBack = main::onCloseSettings,
            onGoalChange = settings::onGoalChange,
            onSaveGoal = settings::onSaveGoal,
            onKeyChange = settings::onKeyChange,
            onSaveKey = settings::onSaveKey,
            onClearKey = settings::onClearKey,
            onPickDay = main::onSelectDay,
            onOpenFoods = main::onOpenFoods,
            onOpenJournal = main::onOpenJournal,
            onToggleRecordMode = settings::onToggleRecordMode,
            onToggleLanguage = settings::onToggleLanguage,
            onToggleAgent = settings::onToggleAgent,
            onTogglePrompt = settings::onTogglePrompt,
            onOpenExport = main::onOpenExport,
            onLogout = {
                settings.onLogout()
                main.onCloseSettings()
            },
        )
        Screen.Foods -> FoodsRoute(foods, onBack = main::onCloseFoods)
        Screen.Journal -> JournalRoute(journal, onBack = main::onCloseJournal)
        Screen.Export -> ExportRoute(export, onBack = main::onCloseExport)
    }
}
