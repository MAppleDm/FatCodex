package dev.dietapp.ui.theme

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.coreui.AppAppearance
import dev.dietapp.coreui.AppTheme
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `a new installation and an unknown stored theme follow the phone`() {
        assertEquals(AppAppearance.System, ThemeStore(context).choice.value)
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", "unknown").commit()
        assertEquals(AppAppearance.System, ThemeStore(context).choice.value)
    }

    @Test fun `every choice is remembered after a new start, including following the system again`() {
        val store = ThemeStore(context)
        for (appearance in listOf(AppAppearance.Paper, AppAppearance.Coal, AppAppearance.Matcha, AppAppearance.System)) {
            store.use(appearance)
            assertEquals(appearance, store.choice.value)
            assertEquals(appearance, ThemeStore(context).choice.value)
        }
    }

    @Test fun `only the system option changes when the phone changes appearance`() {
        assertEquals(AppAppearance.Paper, AppAppearance.System.resolve(systemDark = false))
        assertEquals(AppAppearance.Coal, AppAppearance.System.resolve(systemDark = true))
        for (appearance in listOf(AppAppearance.Paper, AppAppearance.Coal, AppAppearance.Matcha)) {
            assertEquals(appearance, appearance.resolve(systemDark = false))
            assertEquals(appearance, appearance.resolve(systemDark = true))
        }
        assertTrue(AppAppearance.Coal.colors().isDark)
        assertFalse(AppAppearance.Matcha.colors(systemDark = true).isDark)
    }

    @Test fun `choosing a theme applies it immediately and the picker remembers the selected option`() {
        val store = ThemeStore(context)
        compose.setContent {
            val appearance by store.choice.collectAsState()
            AppTheme(appearance = appearance) {
                SettingsScreen(SettingsUiState(local = true), {}, {}, appearance = appearance, onAppearance = store::use)
            }
        }
        for (appearance in listOf(AppAppearance.Matcha, AppAppearance.Coal, AppAppearance.Paper, AppAppearance.System)) {
            compose.onNodeWithTag("theme-entry").performClick()
            compose.onNodeWithTag("theme-${appearance.name.lowercase()}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("theme-entry").assertTextContains(Texts.themeName(appearance), substring = true)
            assertEquals(appearance, ThemeStore(context).choice.value)
            compose.onNodeWithTag("theme-entry").performClick()
            compose.onNodeWithTag("theme-${appearance.name.lowercase()}").assertIsSelected()
            compose.onNodeWithTag("theme-close").performClick()
            assertEquals(appearance, store.choice.value)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }

    @Test fun `text and animated totals stay readable on each surface in all three themes`() {
        for (appearance in listOf(AppAppearance.Paper, AppAppearance.Coal, AppAppearance.Matcha)) {
            val c = appearance.colors()
            for (surface in listOf(c.background, c.surface, c.summary)) {
                for (text in listOf(c.foreground, c.secondary, c.time)) {
                    val ratio = contrast(text, surface)
                    assertTrue("$appearance: $text on $surface has contrast $ratio", ratio >= 4.5)
                }
            }
            for (surface in listOf(c.background, c.surface)) {
                for (text in listOf(c.recorded, c.tertiary, c.error)) {
                    assertTrue("$appearance: $text on $surface", contrast(text, surface) >= 4.5)
                }
            }
        }
    }
}
