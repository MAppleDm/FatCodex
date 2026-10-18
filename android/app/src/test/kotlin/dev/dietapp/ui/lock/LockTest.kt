package dev.dietapp.ui.lock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.ui.about.AboutScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The lock of the app: when it closes, what the closed app shows, and the row of the about page that turns it on. */
@RunWith(AndroidJUnit4::class)
class LockTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var clock = 0L

    private fun lock() = AppLock(context) { clock }

    // ---------- when it closes ----------

    @Test fun `the lock is off and the app open until the person turns it on`() {
        val lock = lock()
        assertFalse(lock.enabled.value)
        assertFalse(lock.locked.value)
    }

    @Test fun `turning it on keeps the app open now, a new start of the app is closed`() {
        val lock = lock()
        lock.setEnabled(true)
        assertTrue(lock.enabled.value)
        assertFalse("the person has just proved who they are", lock.locked.value)

        val next = lock()
        assertTrue("remembered", next.enabled.value)
        assertTrue("a start of the app is closed", next.locked.value)
        next.unlock()
        assertFalse(next.locked.value)
    }

    @Test fun `turning it off opens the app for good`() {
        lock().setEnabled(true)
        val lock = lock()
        assertTrue(lock.locked.value)
        lock.setEnabled(false)
        assertFalse(lock.locked.value)
        assertFalse(lock().locked.value)
    }

    @Test fun `a short look away does not close the app, a minute away does`() {
        val lock = lock().also { it.setEnabled(true) }

        clock = 1_000
        lock.onBackground()
        clock = 1_000 + AppLock.GRACE_MS - 1
        lock.onForeground()
        assertFalse("a moment in the camera or the file picker", lock.locked.value)

        clock = 100_000
        lock.onBackground()
        clock = 100_000 + AppLock.GRACE_MS
        lock.onForeground()
        assertTrue("a minute is enough", lock.locked.value)

        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test fun `coming back without having left changes nothing, and without the lock nothing ever closes`() {
        val lock = lock()
        lock.onForeground()
        assertFalse(lock.locked.value)

        clock = 0
        lock.onBackground()
        clock = 10 * AppLock.GRACE_MS
        lock.onForeground()
        assertFalse("the lock is off", lock.locked.value)
    }

    @Test fun `a long stay away closes the app once, not again on the next return`() {
        val lock = lock().also { it.setEnabled(true) }
        clock = 0
        lock.onBackground()
        clock = 5 * AppLock.GRACE_MS
        lock.onForeground()
        lock.unlock()
        lock.onForeground() // the activity can start again without having stopped
        assertFalse(lock.locked.value)
    }

    @Test fun `a prompt that cannot check anyone is told apart from one that was only closed`() {
        assertEquals(PromptResult.Closed, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(PromptResult.Closed, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertEquals(PromptResult.Closed, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(PromptResult.Closed, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_CANCELED))
        // the screen lock was removed after the app's lock was turned on: it must not keep the person out
        assertEquals(PromptResult.CannotCheck, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL))
        assertEquals(PromptResult.CannotCheck, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertEquals(PromptResult.CannotCheck, PromptResult.ofError(androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT))
    }

    // ---------- what the closed app shows ----------

    @Test fun `the closed app shows no diary, only a way to open it`() {
        var opened = 0
        compose.setContent { AppTheme(darkTheme = false) { LockScreen(onOpen = { opened++ }) } }
        compose.onNodeWithTag("lock-screen").assertIsDisplayed()
        compose.onNodeWithText("FatCodex").assertIsDisplayed()
        compose.onNodeWithText(Texts.LOCKED).assertIsDisplayed()
        compose.onNodeWithTag("unlock").assertTextContains(Texts.UNLOCK)
        compose.onNodeWithTag("unlock").performClick()
        assertEquals(1, opened)
    }

    // ---------- the about page ----------

    private fun about(on: Boolean = false, support: LockSupport = LockSupport.Ready, onToggle: () -> Unit = {}) = compose.setContent {
        AppTheme(darkTheme = false) {
            AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {}, lockOn = on, lockSupport = support, onToggleLock = onToggle)
        }
    }

    @Test fun `the about page has the lock, off, with what it does`() {
        var toggled = 0
        about(onToggle = { toggled++ })
        compose.onNodeWithTag("about-lock").performScrollTo().assertTextContains(Texts.LOCK_OFF, substring = true)
        compose.onNodeWithTag("lock-hint").assertTextContains(Texts.LOCK_HINT)
        compose.onNodeWithTag("about-lock").performClick()
        assertEquals(1, toggled)
    }

    @Test fun `a lock that is on says so, with what it does`() {
        about(on = true, support = LockSupport.Ready)
        compose.onNodeWithTag("about-lock").performScrollTo().assertTextContains(Texts.LOCK_ON, substring = true)
        compose.onNodeWithTag("lock-hint").assertTextContains(Texts.LOCK_HINT)
    }

    @Test fun `a lock that is on while the phone's own lock has gone says it does not check, and how to turn it off`() {
        about(on = true, support = LockSupport.NotSet)
        compose.onNodeWithTag("about-lock").performScrollTo().assertTextContains(Texts.LOCK_ON, substring = true)
        compose.onNodeWithTag("lock-hint").assertTextContains(Texts.LOCK_BROKEN)
    }

    @Test fun `a phone with no screen lock says what to do first`() {
        about(support = LockSupport.NotSet)
        compose.onNodeWithTag("lock-hint").performScrollTo().assertTextContains(Texts.LOCK_NOT_SET)
    }

    @Test fun `a phone that cannot check anyone says so`() {
        about(support = LockSupport.Unavailable)
        compose.onNodeWithTag("lock-hint").performScrollTo().assertTextContains(Texts.LOCK_UNAVAILABLE)
    }
}
