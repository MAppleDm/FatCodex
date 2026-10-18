package dev.dietapp.ui.sound

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.ui.about.AboutScreen
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.main.UiEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sound of a food that is in the diary for good: when it plays, and the row of the about page that turns it off. */
@RunWith(AndroidJUnit4::class)
class SoundTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class CountingSound : AddedSound {
        var played = 0
        override fun play() {
            played++
        }
    }

    // ---------- the choice ----------

    @Test fun `the sound is on until the person turns it off, and the choice is kept`() {
        val store = SoundStore(context)
        assertTrue(store.enabled.value)
        store.setEnabled(false)
        assertFalse(store.enabled.value)
        assertFalse("kept for the next start", SoundStore(context).enabled.value)
        store.setEnabled(true)
        assertTrue(SoundStore(context).enabled.value)
    }

    @Test fun `turning the sound on plays it once so it is heard, turning it off does not`() {
        val store = SoundStore(context)
        val sound = CountingSound()
        val model = SoundViewModel(store, sound)

        model.onToggle()
        assertFalse(model.enabled.value)
        assertEquals(0, sound.played)

        model.onToggle()
        assertTrue(model.enabled.value)
        assertEquals(1, sound.played)
    }

    // ---------- the screen ----------

    @Test fun `a food in for good plays the sound, a haptic confirmation alone does not`() {
        val sound = CountingSound()
        val effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 4)
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(), TEST_CLOCK)
        compose.setContent {
            AppTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAddedSound provides sound) {
                    val state by vm.uiState.collectAsState()
                    MainScreen(state, vm, effects)
                }
            }
        }
        compose.waitForIdle()

        effects.tryEmit(UiEffect.Confirm)
        compose.waitForIdle()
        assertEquals(0, sound.played)

        effects.tryEmit(UiEffect.Added)
        compose.waitForIdle()
        assertEquals(1, sound.played)
    }

    // ---------- the about page ----------

    @Test fun `the about page has the sound, with what it is for, and the row turns it on and off`() {
        var toggled = 0
        compose.setContent {
            AppTheme(darkTheme = false) {
                AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {}, soundOn = true, onToggleSound = { toggled++ })
            }
        }
        compose.onNodeWithTag("about-sound").performScrollTo().assertIsDisplayed().assertTextContains(Texts.SOUND_ON, substring = true)
        compose.onNodeWithTag("about-sound").performClick()
        assertEquals(1, toggled)
    }

    @Test fun `a sound that is off says so`() {
        compose.setContent {
            AppTheme(darkTheme = false) {
                AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {}, soundOn = false)
            }
        }
        compose.onNodeWithTag("about-sound").performScrollTo().assertTextContains(Texts.SOUND_OFF, substring = true)
    }
}
