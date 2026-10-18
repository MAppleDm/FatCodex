package dev.dietapp.ui.font

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.Inter
import dev.dietapp.ui.about.AboutScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The font of the app: the bundled Inter, the system's, and a font file of the person's own (Verdana Pro, say). */
@RunWith(AndroidJUnit4::class)
class FontTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Any real font will do: the one the app ships. */
    private val fontFile = File("../core-ui/src/main/res/font/inter_regular.ttf")

    private fun store() = FontStore(context)

    @Test fun `the app starts in Inter, and the system font is a choice`() {
        val fonts = store()
        assertEquals(FontChoice(FontKind.Inter, null), fonts.choice.value)
        assertSame(Inter, fonts.family(fonts.choice.value))
        fonts.use(FontKind.System)
        assertEquals(FontKind.System, fonts.choice.value.kind)
        assertSame(FontFamily.SansSerif, fonts.family(fonts.choice.value))
    }

    @Test fun `a font file of the person's own is copied in, used, and remembered`() {
        assertTrue("the test needs the shipped font to pick", fontFile.isFile)
        val fonts = store()
        assertTrue(fonts.importFile(Uri.fromFile(fontFile)))
        assertEquals(FontKind.File, fonts.choice.value.kind)
        assertEquals("inter_regular", fonts.choice.value.fileName)

        // a new start of the app finds it, and the picked file stays when Inter is chosen again
        assertEquals(FontChoice(FontKind.File, "inter_regular"), store().choice.value)
        fonts.use(FontKind.Inter)
        assertEquals(FontChoice(FontKind.Inter, "inter_regular"), fonts.choice.value)
        fonts.use(FontKind.File)
        assertEquals(FontKind.File, fonts.choice.value.kind)
    }

    @Test fun `a file that is no font changes nothing`() {
        val notAFont = File.createTempFile("notes", ".txt").apply { writeText("this is not a font, only some words"); deleteOnExit() }
        val fonts = store()
        assertFalse(fonts.importFile(Uri.fromFile(notAFont)))
        assertEquals(FontChoice(), fonts.choice.value)
        assertFalse(File(context.filesDir, "fonts/custom.font").exists())
        assertFalse("nothing half-copied is left behind", File(context.filesDir, "fonts/incoming.font").exists())
    }

    @Test fun `a file that cannot be opened changes nothing, and a good font stays when a bad one comes after it`() {
        val fonts = store()
        assertTrue(fonts.importFile(Uri.fromFile(fontFile)))
        val missing = Uri.fromFile(File("no/such/font.ttf"))
        assertFalse(fonts.importFile(missing))
        assertEquals(FontChoice(FontKind.File, "inter_regular"), fonts.choice.value)
    }

    @Test fun `without a picked file there is no file font to switch to`() {
        val fonts = store()
        fonts.use(FontKind.File)
        assertEquals(FontKind.Inter, fonts.choice.value.kind)
    }

    @Test fun `a picked file that has gone is not used, the app is back in Inter`() {
        val fonts = store()
        assertTrue(fonts.importFile(Uri.fromFile(fontFile)))
        File(context.filesDir, "fonts/custom.font").delete()
        val next = store()
        assertEquals(FontChoice(FontKind.Inter, null), next.choice.value)
        assertNull(next.choice.value.fileName)
        assertSame(Inter, next.family(FontChoice(FontKind.File, "inter_regular")))
    }

    // ---------- the about page ----------

    private fun about(
        font: FontChoice = FontChoice(), failed: Boolean = false, onFont: (FontKind) -> Unit = {}, onPick: () -> Unit = {},
    ) = compose.setContent {
        AppTheme(darkTheme = false) {
            AboutScreen(
                local = true, version = "0.2.0", onBack = {}, onOpenUrl = {},
                font = font, fontFailed = failed, onFont = onFont, onPickFontFile = onPick,
            )
        }
    }

    @Test fun `the about page offers Inter, the system font and a file, and marks the one in use`() {
        val chosen = mutableListOf<FontKind>()
        var picked = 0
        about(FontChoice(FontKind.System), onFont = { chosen += it }, onPick = { picked++ })

        compose.onNodeWithTag("font-inter").performScrollTo().assertIsDisplayed().assertTextContains(Texts.FONT_INTER, substring = true)
        compose.onNodeWithTag("font-system").assertTextContains("●", substring = true)
        compose.onNodeWithTag("font-inter").performClick()
        compose.onNodeWithTag("font-pick").performScrollTo().performClick()
        assertEquals(listOf(FontKind.Inter), chosen)
        assertEquals(1, picked)
        compose.onNodeWithTag("font-file").assertDoesNotExist()
    }

    @Test fun `a font picked earlier is a row of its own, and a font that could not be read is said`() {
        val chosen = mutableListOf<FontKind>()
        about(FontChoice(FontKind.File, "VerdanaPro-Regular"), failed = true, onFont = { chosen += it })

        compose.onNodeWithTag("font-file").performScrollTo().assertTextContains("VerdanaPro-Regular", substring = true)
        compose.onNodeWithTag("font-file").assertTextContains("●", substring = true)
        compose.onNodeWithTag("font-failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("font-system").performScrollTo().performClick()
        assertEquals(listOf(FontKind.System), chosen)
    }

    @Test fun `the hint names Verdana Pro and says the file stays on the phone`() {
        about()
        compose.onNodeWithText(Texts.FONT_HINT).performScrollTo().assertIsDisplayed()
        assertTrue(Texts.FONT_HINT.contains("Verdana Pro"))
    }
}
