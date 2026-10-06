package dev.dietapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.export.ExportFile
import dev.dietapp.data.export.ExportFormat
import dev.dietapp.data.export.ExportPeriod
import dev.dietapp.data.net.AppError
import dev.dietapp.data.repo.ExportRepository
import dev.dietapp.ui.export.ExportScreen
import dev.dietapp.ui.export.ExportUiState
import dev.dietapp.ui.export.ExportViewModel
import dev.dietapp.ui.history.HistoryScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.main.Screen
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsUiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private class FakeExports : ExportRepository {
    val asked = mutableListOf<Pair<ExportFormat, ExportPeriod>>()
    var result: Result<ExportFile> = Result.success(ExportFile("fatcodex-diary-30d-2026-09-30.md", "text/markdown", "# diary"))

    override suspend fun export(format: ExportFormat, period: ExportPeriod): Result<ExportFile> {
        asked += format to period
        return result
    }
}

class ExportViewModelTest {
    private val exports = FakeExports()
    private val vm = ExportViewModel(exports)

    @Test fun `the last 30 days are chosen to start with`() {
        assertEquals(ExportPeriod.Month, vm.state.value.period)
    }

    @Test fun `a file is built for the chosen period`() = runTest {
        vm.onPeriod(ExportPeriod.All)
        val file = vm.build(ExportFormat.Json)!!
        assertEquals("# diary", file.text)
        assertEquals(listOf(ExportFormat.Json to ExportPeriod.All), exports.asked)
    }

    @Test fun `nothing to export shows why and builds no file`() = runTest {
        exports.result = Result.failure(AppError("В этом периоде записей нет.", "empty"))
        assertNull(vm.build(ExportFormat.Markdown))
        assertEquals("В этом периоде записей нет.", vm.state.value.message)
        assertTrue(vm.state.value.messageIsError)
    }

    @Test fun `choosing another period clears the old message`() = runTest {
        exports.result = Result.failure(AppError("В этом периоде записей нет.", "empty"))
        vm.build(ExportFormat.Markdown)
        vm.onPeriod(ExportPeriod.Quarter)
        assertNull(vm.state.value.message)
        assertEquals(ExportPeriod.Quarter, vm.state.value.period)
    }

    @Test fun `saving says whether it worked`() {
        vm.onSaved(true)
        assertEquals(Texts.EXPORT_SAVED, vm.state.value.message)
        assertFalse(vm.state.value.messageIsError)
        vm.onSaved(false)
        assertEquals(Texts.FOODS_FILE_FAILED, vm.state.value.message)
        assertTrue(vm.state.value.messageIsError)
    }
}

@RunWith(AndroidJUnit4::class)
class ExportScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show(
        state: ExportUiState = ExportUiState(),
        onPeriod: (ExportPeriod) -> Unit = {},
        onSave: (ExportFormat) -> Unit = {},
        onShare: (ExportFormat) -> Unit = {},
    ) = compose.setContent {
        AppTheme(darkTheme = false) { ExportScreen(state, {}, onPeriod, onSave, onShare) }
    }

    @Test fun `it says what the file is for and offers every period and format`() {
        show()
        compose.onNodeWithText(Texts.EXPORT_HINT).assertIsDisplayed()
        ExportPeriod.entries.forEach { compose.onNodeWithTag("period-${it.tag}").performScrollTo().assertIsDisplayed() }
        ExportFormat.entries.forEach {
            compose.onNodeWithTag("export-save-${it.name.lowercase()}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("export-share-${it.name.lowercase()}").assertIsDisplayed()
        }
    }

    @Test fun `a tap on a period picks it`() {
        val picked = mutableListOf<ExportPeriod>()
        show(onPeriod = { picked += it })
        compose.onNodeWithTag("period-all").performClick()
        compose.onNodeWithTag("period-7d").performClick()
        assertEquals(listOf(ExportPeriod.All, ExportPeriod.Week), picked)
    }

    @Test fun `save and send name the format they are for`() {
        val saved = mutableListOf<ExportFormat>()
        val shared = mutableListOf<ExportFormat>()
        show(onSave = { saved += it }, onShare = { shared += it })
        compose.onNodeWithTag("export-save-csvfood").performScrollTo().performClick()
        compose.onNodeWithTag("export-share-markdown").performScrollTo().performClick()
        assertEquals(listOf(ExportFormat.CsvFood), saved)
        assertEquals(listOf(ExportFormat.Markdown), shared)
    }

    @Test fun `a message is shown under the formats`() {
        show(ExportUiState(message = "В этом периоде записей нет.", messageIsError = true))
        compose.onNodeWithTag("export-message").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("В этом периоде записей нет.").assertIsDisplayed()
    }

    @Test fun `the history page leads to the export, with a server and without`() {
        var opened = 0
        compose.setContent {
            AppTheme(darkTheme = false) {
                HistoryScreen(emptyList(), 1900, onBack = {}, onPickDay = {}, onOpenExport = { opened++ })
            }
        }
        compose.onNodeWithTag("history-export").performClick()
        assertEquals(1, opened)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExportNavigationTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `the main view model opens and closes the export screen, back to the history`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenHistory()
        vm.onOpenExport()
        assertEquals(Screen.Export, vm.uiState.value.screen)
        vm.onCloseExport()
        assertEquals("the export is reached from the history, so back goes there", Screen.History, vm.uiState.value.screen)
    }

    @Test fun `the journal opens from the about page and back leads to the about page`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(local = true), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenAbout()
        vm.onOpenJournal()
        assertEquals(Screen.Journal, vm.uiState.value.screen)
        vm.onCloseJournal()
        assertEquals(Screen.About, vm.uiState.value.screen)
    }

    @Test fun `the history page opens from settings and back leads to settings`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(local = true), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenHistory()
        assertEquals(Screen.History, vm.uiState.value.screen)
        vm.onCloseHistory()
        assertEquals(Screen.Settings, vm.uiState.value.screen)
    }

    @Test fun `picking a day in the history leaves the settings and shows that day`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(local = true), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenHistory()
        vm.onSelectDay(java.time.LocalDate.parse("2026-09-28"))
        assertEquals(Screen.Main, vm.uiState.value.screen)
        assertEquals(java.time.LocalDate.parse("2026-09-28"), vm.uiState.value.day)
    }

    @Test fun `the about page opens from settings and back leads to settings`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(local = true), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenAbout()
        assertEquals(Screen.About, vm.uiState.value.screen)
        vm.onCloseAbout()
        assertEquals(Screen.Settings, vm.uiState.value.screen)
    }

    @Test fun `the agent page opens from the about page and back leads to the about page`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(local = true), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenAbout()
        vm.onOpenAgent()
        assertEquals(Screen.Agent, vm.uiState.value.screen)
        vm.onCloseAgent()
        assertEquals(Screen.About, vm.uiState.value.screen)
    }
}
