package dev.dietapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.local.NEEDS_KEY
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Totals
import dev.dietapp.ui.about.AboutScreen
import dev.dietapp.ui.history.HistoryScreen
import dev.dietapp.ui.agent.AgentScreen
import dev.dietapp.ui.login.LoginScreen
import dev.dietapp.ui.login.LoginStep
import dev.dietapp.ui.login.LoginUiState
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the person sees and can tap when the app runs without a server. */
@RunWith(AndroidJUnit4::class)
class LocalModeScreensTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun login(state: LoginUiState, onLocal: () -> Unit = {}) = compose.setContent {
        AppTheme(darkTheme = false) { LoginScreen(state, {}, {}, {}, {}, onLocal, {}) }
    }

    private fun settings(
        state: SettingsUiState,
        onLogout: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onOpenHistory: () -> Unit = {},
        onOpenMe: () -> Unit = {},
        onToggleLanguage: () -> Unit = {},
    ) = compose.setContent {
        AppTheme(darkTheme = false) {
            SettingsScreen(
                state, onBack = {}, onLogout = onLogout,
                onOpenAbout = onOpenAbout, onOpenHistory = onOpenHistory, onOpenMe = onOpenMe, onToggleLanguage = onToggleLanguage,
            )
        }
    }

    // ---------- login ----------

    @Test fun `the email step offers to go on without a server`() {
        var chosen = 0
        login(LoginUiState(loading = false, step = LoginStep.Email), onLocal = { chosen++ })
        compose.onNodeWithText(Texts.NO_SERVER_HINT).assertIsDisplayed()
        compose.onNodeWithTag("local").assertIsDisplayed().performClick()
        assertEquals(1, chosen)
    }

    @Test fun `the key step asks for the key and says it cannot be done without`() {
        login(LoginUiState(loading = false, serverEnabled = false, step = LoginStep.Key))
        compose.onNodeWithTag("key").assertIsDisplayed()
        compose.onNodeWithText(Texts.KEY_STEP_HINT).assertIsDisplayed()
        compose.onNodeWithTag("local").assertDoesNotExist()
        compose.onNodeWithTag("skip").assertDoesNotExist()
    }

    @Test fun `nothing is skipped and no goal is typed at sign-in`() {
        login(LoginUiState(loading = false, step = LoginStep.Email))
        compose.onNodeWithTag("skip").assertDoesNotExist()
        compose.onNodeWithTag("goal").assertDoesNotExist()
    }

    @Test fun `later steps do not repeat the offer`() {
        login(LoginUiState(loading = false, step = LoginStep.Code, email = "me@example.com"))
        compose.onNodeWithTag("local").assertDoesNotExist()
    }

    // ---------- settings ----------

    @Test fun `without a server the settings have no erase and no small print, both are on the about page`() {
        settings(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithTag("logout").assertDoesNotExist()
        compose.onNodeWithText(Texts.ERASE).assertDoesNotExist()
        compose.onNodeWithText(Texts.LOCAL_MODE).assertDoesNotExist()
    }

    @Test fun `the agent is not in the settings, it is on the about page`() {
        settings(SettingsUiState(savedGoal = 1900, local = true, hasKey = true))
        compose.onNodeWithTag("agent").assertDoesNotExist()
        compose.onNodeWithTag("key").assertDoesNotExist()
        compose.onNodeWithTag("agent-web").assertDoesNotExist()
        compose.onNodeWithText(Texts.AGENT_PROVIDER_DEEPSEEK).assertDoesNotExist()
    }

    @Test fun `what a person comes for is on top, the language and about are at the bottom`() {
        settings(SettingsUiState(savedGoal = 1900, local = true))
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        val topEnd = compose.onNodeWithTag("settings-top").fetchSemanticsNode().boundsInRoot.bottom
        compose.onNodeWithTag("goal").assertDoesNotExist() // the goal is not typed in anywhere: it is on the "about me" page
        assertTrue("about me comes first", top("me-entry") < top("foods-entry"))
        assertTrue("then the food base and the history", top("foods-entry") < top("history-entry") && top("history-entry") < topEnd)
        assertTrue("the language is under all of it", top("language") >= topEnd)
        assertTrue("about is the last", top("about-entry") > top("language"))
    }

    @Test fun `about me is the first row, with a server and without, and leads to its page`() {
        var opened = 0
        settings(SettingsUiState(savedGoal = 1900, local = true), onOpenMe = { opened++ })
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        compose.onNodeWithTag("me-entry").assertIsDisplayed().assertTextContains(Texts.ME, substring = true)
        assertTrue("above the food base", top("me-entry") < top("foods-entry"))
        compose.onNodeWithTag("me-entry").performClick()
        assertEquals(1, opened)
    }

    @Test fun `with a server about me is there too`() {
        settings(SettingsUiState(email = "me@example.com", savedGoal = 1900))
        compose.onNodeWithTag("me-entry").assertIsDisplayed()
    }

    @Test fun `the history is a row that leads to its own page, no days are listed in settings`() {
        var opened = 0
        settings(
            SettingsUiState(
                savedGoal = 1900, local = true,
                history = listOf(DaySummary(java.time.LocalDate.parse("2026-09-29"), Totals(1420.0, 90.0, 50.0, 140.0))),
            ),
            onOpenHistory = { opened++ },
        )
        compose.onNodeWithTag("history-entry").assertIsDisplayed().assertTextContains(Texts.HISTORY, substring = true)
        compose.onNodeWithTag("day-2026-09-29").assertDoesNotExist()
        compose.onNodeWithTag("history-entry").performClick()
        assertEquals(1, opened)
    }

    @Test fun `the language switches from the bottom group`() {
        var switched = 0
        settings(SettingsUiState(savedGoal = 1900, local = true), onToggleLanguage = { switched++ })
        compose.onNodeWithTag("language").performClick()
        assertEquals(1, switched)
    }

    @Test fun `with a server the language is at the bottom too, above about`() {
        settings(SettingsUiState(email = "me@example.com", savedGoal = 1900))
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("history-entry") < top("language") && top("language") < top("about-entry"))
        assertTrue("signing out is the last", top("logout") > top("about-entry"))
    }

    // ---------- the history page ----------

    private fun history(days: List<DaySummary>, goal: Int? = 1900, onPickDay: (java.time.LocalDate) -> Unit = {}, onBack: () -> Unit = {}) =
        compose.setContent { AppTheme(darkTheme = false) { HistoryScreen(days, goal, onBack, onPickDay) } }

    private fun day(date: String, kcal: Double) = DaySummary(java.time.LocalDate.parse(date), Totals(kcal, 90.0, 50.0, 140.0))

    @Test fun `the history page lists the days with what was eaten against the goal`() {
        history(listOf(day("2026-09-29", 1420.0), day("2026-09-28", 1550.0)))
        compose.onNodeWithText(Texts.HISTORY).assertIsDisplayed()
        compose.onNodeWithTag("day-2026-09-29").assertIsDisplayed().assertTextContains("1", substring = true)
        compose.onNodeWithTag("day-2026-09-28").assertIsDisplayed()
        compose.onNodeWithTag("history-empty").assertDoesNotExist()
    }

    @Test fun `without a goal the history shows only what was eaten`() {
        history(listOf(day("2026-09-29", 1420.0)), goal = null)
        compose.onNodeWithTag("day-2026-09-29").assertIsDisplayed()
        compose.onNodeWithText("/", substring = true).assertDoesNotExist() // "1 420 / 1 900" needs a goal
    }

    @Test fun `a tap on a day picks it`() {
        val picked = mutableListOf<java.time.LocalDate>()
        history(listOf(day("2026-09-29", 1420.0), day("2026-09-28", 1550.0)), onPickDay = { picked += it })
        compose.onNodeWithTag("day-2026-09-28").performClick()
        assertEquals(listOf(java.time.LocalDate.parse("2026-09-28")), picked)
    }

    @Test fun `the history page is where the export is`() {
        var export = 0
        compose.setContent {
            AppTheme(darkTheme = false) {
                HistoryScreen(listOf(day("2026-09-29", 1420.0)), 1900, onBack = {}, onPickDay = {}, onOpenExport = { export++ })
            }
        }
        compose.onNodeWithTag("history-export").assertIsDisplayed().assertTextContains(Texts.HISTORY_EXPORT)
        compose.onNodeWithTag("history-export").performClick()
        assertEquals(1, export)
        compose.onNodeWithTag("history-journal").assertDoesNotExist()
    }

    @Test fun `an empty history still offers the export, which then says there is nothing in it`() {
        history(emptyList())
        compose.onNodeWithTag("history-export").assertIsDisplayed()
    }

    @Test fun `an empty history says so`() {
        history(emptyList())
        compose.onNodeWithTag("history-empty").assertIsDisplayed()
        compose.onNodeWithText(Texts.NO_HISTORY).assertIsDisplayed()
    }

    @Test fun `back leaves the history page`() {
        var back = 0
        history(emptyList(), onBack = { back++ })
        compose.onNodeWithContentDescription(Texts.CD_BACK).performClick()
        assertEquals(1, back)
    }

    @Test fun `the journal and the export are not rows of the settings, they are on the history page`() {
        settings(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithTag("journal-entry").assertDoesNotExist()
        compose.onNodeWithTag("export-entry").assertDoesNotExist()
        compose.onNodeWithTag("about-entry").assertIsDisplayed()
    }

    @Test fun `with a server the settings have the language and about, and signing out`() {
        settings(SettingsUiState(email = "me@example.com", savedGoal = 1900))
        compose.onNodeWithTag("language").assertIsDisplayed()
        compose.onNodeWithTag("about-entry").assertIsDisplayed()
        compose.onNodeWithTag("logout").assertIsDisplayed()
    }

    @Test fun `about leads to its page`() {
        var about = 0
        settings(SettingsUiState(savedGoal = 1900, local = true), onOpenAbout = { about++ })
        compose.onNodeWithTag("about-entry").performClick()
        assertEquals(1, about)
    }

    @Test fun `there is no choice of mode in settings`() {
        settings(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithTag("record-mode").assertDoesNotExist()
        compose.onNodeWithTag("mode-info").assertDoesNotExist()
        compose.onNodeWithTag("foods-entry").assertIsDisplayed()
    }

    // ---------- the about page ----------

    private fun about(
        local: Boolean, onOpenUrl: (String) -> Unit = {}, onBack: () -> Unit = {}, eraseArmed: Boolean = false, onErase: () -> Unit = {},
        onOpenJournal: () -> Unit = {},
    ) = compose.setContent {
        AppTheme(darkTheme = false) {
            AboutScreen(
                local = local, version = "0.2.0", onBack = onBack, onOpenUrl = onOpenUrl, eraseArmed = eraseArmed, onErase = onErase,
                onOpenJournal = onOpenJournal,
            )
        }
    }

    @Test fun `without a server the about page says where the diary lives and what leaves the phone`() {
        about(local = true)
        compose.onNodeWithText("FatCodex").assertIsDisplayed()
        compose.onNodeWithTag("about-version").assertTextContains("0.2.0", substring = true)
        compose.onNodeWithText(Texts.LOCAL_MODE).assertIsDisplayed()
        compose.onNodeWithText(Texts.ABOUT_LOCAL_COPY).assertIsDisplayed()
        compose.onNodeWithText(Texts.ABOUT_LOCAL_SENT).assertIsDisplayed()
        compose.onNodeWithText(Texts.ABOUT_SERVER_COPY).assertDoesNotExist()
    }

    @Test fun `the agent is a row of the about page that leads to its own page, for the phone-only mode`() {
        var opened = 0
        compose.setContent {
            AppTheme(darkTheme = false) {
                AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {}, onOpenAgent = { opened++ })
            }
        }
        compose.onNodeWithTag("about-agent").performScrollTo().assertIsDisplayed().assertTextContains(Texts.AGENT, substring = true)
        // nothing of the agent's insides here, they are on its page
        compose.onNodeWithTag("key").assertDoesNotExist()
        compose.onNodeWithTag("agent-web").assertDoesNotExist()
        compose.onNodeWithTag("about-agent").performClick()
        assertEquals(1, opened)
    }

    @Test fun `with a server the about page has no agent row`() {
        about(local = false)
        compose.onNodeWithTag("about-agent").assertDoesNotExist()
    }

    @Test fun `the journal is on the about page, for the phone-only mode`() {
        var opened = 0
        about(local = true, onOpenJournal = { opened++ })
        compose.onNodeWithTag("about-journal").performScrollTo().assertIsDisplayed().assertTextContains(Texts.JOURNAL, substring = true)
        compose.onNodeWithText(Texts.ABOUT_JOURNAL_HINT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("about-journal").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun `with a server the about page has no journal`() {
        about(local = false)
        compose.onNodeWithTag("about-journal").assertDoesNotExist()
    }

    @Test fun `with a server the about page does not claim the diary is only on the phone`() {
        about(local = false)
        compose.onNodeWithText(Texts.ABOUT_SERVER_COPY).assertIsDisplayed()
        compose.onNodeWithText(Texts.LOCAL_MODE).assertDoesNotExist()
        compose.onNodeWithText(Texts.ABOUT_LOCAL_COPY).assertDoesNotExist()
    }

    @Test fun `the links open the project page and the licence`() {
        val opened = mutableListOf<String>()
        about(local = true, onOpenUrl = { opened += it })
        compose.onNodeWithTag("about-github").performScrollTo().performClick()
        compose.onNodeWithTag("about-license").performScrollTo().performClick()
        assertEquals(listOf("https://github.com/MAppleDm/FatCodex", "https://github.com/MAppleDm/FatCodex/blob/main/LICENSE"), opened)
    }

    @Test fun `back leaves the about page`() {
        var back = 0
        about(local = true, onBack = { back++ })
        compose.onNodeWithContentDescription(Texts.CD_BACK).performClick()
        assertEquals(1, back)
    }

    @Test fun `web search has no switch in settings`() {
        settings(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithTag("web-search").assertDoesNotExist()
    }

    // ---------- the agent page ----------

    private fun agent(
        state: SettingsUiState,
        onKey: (String) -> Unit = {},
        onSaveKey: () -> Unit = {},
        onClearKey: () -> Unit = {},
        onTogglePrompt: () -> Unit = {},
        onBack: () -> Unit = {},
    ) = compose.setContent {
        AppTheme(darkTheme = false) { AgentScreen(state, onBack, onKey, onSaveKey, onClearKey, onTogglePrompt) }
    }

    @Test fun `the agent page names the provider and asks for the key`() {
        agent(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithText(Texts.AGENT).assertIsDisplayed()
        compose.onNodeWithTag("agent-provider").assertIsDisplayed()
        compose.onNodeWithText(Texts.AGENT_PROVIDER_DEEPSEEK).assertIsDisplayed()
        compose.onNodeWithTag("key").assertIsDisplayed()
        compose.onNodeWithText(Texts.MODEL_KEY_HINT).assertIsDisplayed()
    }

    @Test fun `a typed key can be saved`() {
        var saved = 0
        agent(SettingsUiState(savedGoal = 1900, local = true, keyInput = "sk-1234567890"), onSaveKey = { saved++ })
        compose.onNodeWithTag("key-save").performClick()
        assertEquals(1, saved)
    }

    @Test fun `a stored key is never shown, only that there is one, and it can be removed`() {
        var cleared = 0
        agent(SettingsUiState(savedGoal = 1900, local = true, hasKey = true), onClearKey = { cleared++ })
        compose.onNodeWithText(Texts.MODEL_KEY_SET).assertIsDisplayed()
        compose.onNodeWithTag("key").assertDoesNotExist()
        compose.onNodeWithTag("key-clear").performClick()
        assertEquals(1, cleared)
    }

    @Test fun `why a key was refused is said on the page where the key is`() {
        agent(SettingsUiState(savedGoal = 1900, local = true, keyInput = "x", keyMessage = "Это не похоже на ключ DeepSeek.", keyMessageIsError = true))
        compose.onNodeWithTag("key-message").assertIsDisplayed()
        compose.onNodeWithText("Это не похоже на ключ DeepSeek.").assertIsDisplayed()
    }

    @Test fun `web search is shown as always on, with no switch`() {
        agent(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithTag("agent-web").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Texts.AGENT_WEB_ON).assertIsDisplayed()
        compose.onNodeWithTag("web-search").assertDoesNotExist()
    }

    @Test fun `the prompt is closed until it is opened, and then it can be read`() {
        var toggled = 0
        agent(SettingsUiState(savedGoal = 1900, local = true, agentPrompt = "Never estimate calories."), onTogglePrompt = { toggled++ })
        compose.onNodeWithTag("agent-prompt-text").assertDoesNotExist()
        compose.onNodeWithTag("agent-prompt").performScrollTo().performClick()
        assertEquals(1, toggled)
    }

    @Test fun `an opened prompt is the whole text, selectable but with nothing to type into`() {
        agent(SettingsUiState(savedGoal = 1900, local = true, promptOpen = true, agentPrompt = "Never estimate calories."))
        compose.onNodeWithTag("agent-prompt-text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Never estimate calories.").assertIsDisplayed()
        compose.onNodeWithText(Texts.AGENT_PROMPT_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test fun `back leaves the agent page`() {
        var back = 0
        agent(SettingsUiState(savedGoal = 1900, local = true), onBack = { back++ })
        compose.onNodeWithContentDescription(Texts.CD_BACK).performClick()
        assertEquals(1, back)
    }

    @Test fun `erase is a red block at the bottom of the about page that says what it deletes`() {
        about(local = true)
        compose.onNodeWithTag("about-erase-block").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Texts.ABOUT_DANGER).assertIsDisplayed()
        compose.onNodeWithText(Texts.ABOUT_ERASE_DETAILS).assertIsDisplayed()
        compose.onNodeWithTag("about-erase").assertIsDisplayed().assertTextContains(Texts.ERASE)
        compose.onNodeWithText(Texts.ERASE_CONFIRM).assertDoesNotExist()
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("the last thing on the page, under the links", top("about-erase-block") > top("about-license"))
    }

    @Test fun `erasing needs two taps, the first only arms the button and the second erases`() {
        var taps = 0
        compose.setContent {
            val armed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            AppTheme(darkTheme = false) {
                // what the view model does: the first tap arms, the second one erases
                AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {}, eraseArmed = armed.value, onErase = {
                    if (armed.value) taps++ else armed.value = true
                })
            }
        }
        compose.onNodeWithTag("about-erase").performScrollTo().performClick()
        assertEquals("the first tap erases nothing", 0, taps)
        compose.onNodeWithTag("about-erase").assertTextContains(Texts.ERASE_CONFIRM)
        compose.onNodeWithTag("about-erase").performClick()
        assertEquals(1, taps)
    }

    @Test fun `the armed button asks again in words`() {
        about(local = true, eraseArmed = true)
        compose.onNodeWithTag("about-erase").performScrollTo().assertTextContains(Texts.ERASE_CONFIRM)
    }

    @Test fun `with a server there is no erase on the about page, signing out stays in settings`() {
        about(local = false)
        compose.onNodeWithTag("about-erase-block").assertDoesNotExist()
        compose.onNodeWithTag("about-erase").assertDoesNotExist()
    }

    @Test fun `with a server the settings stay as they were`() {
        settings(SettingsUiState(email = "me@example.com", savedGoal = 1900))
        compose.onNodeWithText("me@example.com").assertIsDisplayed()
        compose.onNodeWithText(Texts.LOG_OUT).assertIsDisplayed()
        compose.onNodeWithTag("key").assertDoesNotExist()
        compose.onNodeWithText(Texts.LOCAL_MODE).assertDoesNotExist()
    }

    // ---------- the camera without a key ----------

    @Test fun `the camera button explains that a key is needed instead of opening`() {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(local = true, modelKey = false), TEST_CLOCK)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.uiState.collectAsState()
                MainScreen(state, vm, vm.effects)
            }
        }
        compose.onNodeWithContentDescription("Камера").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("notice").assertIsDisplayed()
        compose.onNodeWithText(NEEDS_KEY).assertIsDisplayed()
        compose.onNodeWithContentDescription(Texts.CD_SHUTTER).assertDoesNotExist()
    }
}
