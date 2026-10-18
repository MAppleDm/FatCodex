package dev.dietapp.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.domain.Lang
import dev.dietapp.data.domain.Language
import dev.dietapp.ui.about.AboutScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class UpdateScreenTest {
    @get:Rule(order = 0) val main = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val release = UpdateRelease("v0.3.0", "Проверка и скачивание обновлений из GitHub.\nПродолжение загрузки после закрытия приложения.",
        UpdateApk("FatCodex.apk", "https://github.com/MAppleDm/FatCodex/releases/download/v0.3.0/app.apk", 3_000_000))

    @Test fun `check download cancel and install actions are reachable from the about page`() {
        var state by mutableStateOf(UpdateState())
        var checked = 0
        var downloaded = 0
        var canceled = 0
        var installed = 0
        compose.setContent {
            AppTheme(darkTheme = false) {
                AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {}, update = state,
                    onCheckUpdates = { checked++ }, onDownloadUpdate = { downloaded++ }, onCancelUpdate = { canceled++ }, onInstallUpdate = { installed++ })
            }
        }
        compose.onNodeWithTag("update-check").performScrollTo().performClick()
        assertEquals(1, checked)
        compose.runOnIdle { state = UpdateState(UpdatePhase.Available, release) }
        compose.onNodeWithTag("update-download").performScrollTo().performClick()
        assertEquals(1, downloaded)
        compose.onNodeWithTag("update-notes").performScrollTo().assertTextContains("GitHub", substring = true)
        screenshot("update-available-light")
        compose.runOnIdle { state = UpdateState(UpdatePhase.Downloading, release, 1_500_000, 3_000_000) }
        compose.onNodeWithTag("update-bytes").performScrollTo().assertTextContains("50%", substring = true)
        compose.onNodeWithTag("update-cancel").performScrollTo().performClick()
        assertEquals(1, canceled)
        compose.runOnIdle { state = UpdateState(UpdatePhase.Ready, release) }
        compose.onNodeWithTag("update-install").performScrollTo().performClick()
        assertEquals(1, installed)
        screenshot("update-ready-light")
    }

    @Test fun `missing APK offers releases page and no download action`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            AppTheme {
                AboutScreen(local = false, version = "0.2.0", onBack = {}, onOpenUrl = opened::add,
                    update = UpdateState(UpdatePhase.Available, release.copy(apk = null)))
            }
        }
        compose.onNodeWithTag("update-download").assertDoesNotExist()
        compose.onNodeWithTag("update-releases").performScrollTo().performClick()
        assertEquals(listOf(RELEASES_URL), opened)
    }

    @Test fun `English paused download explains that Android will resume it`() {
        Lang.current = Language.En
        compose.setContent {
            AppTheme(darkTheme = true) {
                AboutScreen(local = false, version = "0.2.0", onBack = {}, onOpenUrl = {},
                    update = UpdateState(UpdatePhase.Downloading, release, 1_500_000, 3_000_000, paused = true))
            }
        }
        compose.onNodeWithTag("update-status").performScrollTo().assertTextContains("resume automatically", substring = true)
        screenshot("update-paused-dark")
    }

    @Test fun `a signing mismatch is explained beside a retryable download`() {
        compose.setContent {
            AppTheme(darkTheme = true) {
                AboutScreen(local = true, version = "0.2.0", onBack = {}, onOpenUrl = {},
                    update = UpdateState(UpdatePhase.Available, release, problem = UpdateProblem.Signature))
            }
        }
        compose.onNodeWithTag("update-error").performScrollTo().assertTextEquals(Texts.updateProblem(UpdateProblem.Signature))
        compose.onNodeWithTag("update-install").assertDoesNotExist()
        compose.onNodeWithTag("update-download").performScrollTo().assertIsDisplayed()
        screenshot("update-signature-dark")
    }

    private fun screenshot(name: String) {
        val path = System.getProperty("screenshots.dir") ?: return
        compose.waitForIdle()
        val directory = File(path).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
