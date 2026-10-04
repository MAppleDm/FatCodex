package dev.dietapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.dietapp.data.local.PHOTO_NEEDS_KEY
import dev.dietapp.ui.login.LoginScreen
import dev.dietapp.ui.login.LoginStep
import dev.dietapp.ui.login.LoginUiState
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the person sees and can tap when the app runs without a server. */
@RunWith(AndroidJUnit4::class)
class LocalModeScreensTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun login(state: LoginUiState, onLocal: () -> Unit = {}, onSkip: () -> Unit = {}) = compose.setContent {
        AppTheme(darkTheme = false) { LoginScreen(state, {}, {}, {}, {}, {}, onLocal, {}, onSkip) }
    }

    private fun settings(
        state: SettingsUiState,
        onKey: (String) -> Unit = {},
        onSaveKey: () -> Unit = {},
        onClearKey: () -> Unit = {},
        onLogout: () -> Unit = {},
    ) = compose.setContent {
        AppTheme(darkTheme = false) { SettingsScreen(state, {}, {}, {}, onKey, onSaveKey, onClearKey, {}, onLogout) }
    }

    // ---------- login ----------

    @Test fun `the email step offers to go on without a server`() {
        var chosen = 0
        login(LoginUiState(loading = false, step = LoginStep.Email), onLocal = { chosen++ })
        compose.onNodeWithText(Texts.NO_SERVER_HINT).assertIsDisplayed()
        compose.onNodeWithTag("local").assertIsDisplayed().performClick()
        assertEquals(1, chosen)
    }

    @Test fun `the key step asks for the key, says what it is for, and can be skipped`() {
        var skipped = 0
        login(LoginUiState(loading = false, serverEnabled = false, step = LoginStep.Key), onSkip = { skipped++ })
        compose.onNodeWithTag("key").assertIsDisplayed()
        compose.onNodeWithText(Texts.KEY_STEP_HINT).assertIsDisplayed()
        compose.onNodeWithTag("local").assertDoesNotExist()
        compose.onNodeWithTag("skip").assertIsDisplayed().performClick()
        assertEquals(1, skipped)
    }

    @Test fun `only the key step has a skip`() {
        login(LoginUiState(loading = false, step = LoginStep.Goal))
        compose.onNodeWithTag("skip").assertDoesNotExist()
        compose.onNodeWithTag("goal").assertIsDisplayed()
    }

    @Test fun `later steps do not repeat the offer`() {
        login(LoginUiState(loading = false, step = LoginStep.Code, email = "me@example.com"))
        compose.onNodeWithTag("local").assertDoesNotExist()
    }

    // ---------- settings ----------

    @Test fun `local settings say where the diary lives and offer the key`() {
        settings(SettingsUiState(savedGoal = 1900, local = true))
        compose.onNodeWithText(Texts.LOCAL_MODE).assertIsDisplayed()
        compose.onNodeWithTag("key").assertIsDisplayed()
        compose.onNodeWithText(Texts.MODEL_KEY_HINT).assertIsDisplayed()
        compose.onNodeWithTag("logout").assertIsDisplayed()
        compose.onNodeWithText(Texts.ERASE).assertIsDisplayed()
    }

    @Test fun `a typed key can be saved`() {
        var saved = 0
        settings(SettingsUiState(savedGoal = 1900, local = true, keyInput = "sk-1234567890"), onSaveKey = { saved++ })
        compose.onNodeWithTag("key-save").performClick()
        assertEquals(1, saved)
    }

    @Test fun `a stored key is never shown, only that there is one, and it can be removed`() {
        var cleared = 0
        settings(SettingsUiState(savedGoal = 1900, local = true, hasKey = true), onClearKey = { cleared++ })
        compose.onNodeWithText(Texts.MODEL_KEY_SET).assertIsDisplayed()
        compose.onNodeWithTag("key").assertDoesNotExist()
        compose.onNodeWithTag("key-clear").performClick()
        assertEquals(1, cleared)
    }

    @Test fun `erasing says what it will do and asks for a second tap`() {
        var taps = 0
        settings(SettingsUiState(savedGoal = 1900, local = true, eraseArmed = true), onLogout = { taps++ })
        compose.onNodeWithText(Texts.ERASE_HINT).assertIsDisplayed()
        compose.onNodeWithText(Texts.ERASE_CONFIRM).assertIsDisplayed().performClick()
        assertEquals(1, taps)
    }

    @Test fun `with a server the settings stay as they were`() {
        settings(SettingsUiState(email = "me@example.com", savedGoal = 1900))
        compose.onNodeWithText("me@example.com").assertIsDisplayed()
        compose.onNodeWithText(Texts.LOG_OUT).assertIsDisplayed()
        compose.onNodeWithTag("key").assertDoesNotExist()
        compose.onNodeWithText(Texts.LOCAL_MODE).assertDoesNotExist()
    }

    // ---------- the camera without a key ----------

    @Test fun `the camera button explains that photos need a key instead of opening`() {
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
        compose.onNodeWithText(PHOTO_NEEDS_KEY).assertIsDisplayed()
        compose.onNodeWithContentDescription(Texts.CD_SHUTTER).assertDoesNotExist()
    }
}
