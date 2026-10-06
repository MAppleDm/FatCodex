package dev.dietapp.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.TODAY
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.domain.Message
import dev.dietapp.ui.main.FeedItem
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.main.Screen
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val AT = Instant.parse("2026-09-30T05:00:00Z")
private val PICTURE = byteArrayOf(1, 2, 3, 4)

private fun photo(id: String = "m1", text: String? = null) = Message(id, TODAY, text, hasImage = true, at = AT)

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailFeedTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val diary = FakeDiary()

    private fun TestScope.viewModel(): Pair<MainViewModel, StateFlow<dev.dietapp.ui.main.MainUiState>> {
        val vm = MainViewModel(diary, FakeSpeech(), FakeLocalSettings(), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm to vm.uiState
    }

    private fun StateFlow<dev.dietapp.ui.main.MainUiState>.messages() = value.feed.filterIsInstance<FeedItem.MessageItem>()

    @Test fun `a photo message carries its small picture`() = runTest {
        diary.thumbs["m1"] = PICTURE
        diary.messages.value = listOf(photo())
        val (_, state) = viewModel()
        assertArrayEquals(PICTURE, state.messages().single().thumb)
    }

    @Test fun `a photo whose picture this phone does not have has none`() = runTest {
        diary.messages.value = listOf(photo())
        val (_, state) = viewModel()
        assertNull(state.messages().single().thumb)
    }

    @Test fun `a message without a photo is never looked up`() = runTest {
        diary.messages.value = listOf(Message("m2", TODAY, "гречка 200 г", hasImage = false, at = AT))
        diary.thumbs["m2"] = PICTURE
        val (_, state) = viewModel()
        assertNull(state.messages().single().thumb)
    }

    @Test fun `a photo sent now gets its picture as soon as the message is in the chat`() = runTest {
        val (_, state) = viewModel()
        assertEquals(0, state.messages().size)
        diary.thumbs["m3"] = PICTURE
        diary.messages.value = listOf(photo("m3", "обед"))
        assertArrayEquals(PICTURE, state.messages().single().thumb)
    }
}

/** A real (blank) JPEG: the screen decodes it, so junk bytes will not do. */
private fun picture(): ByteArray = java.io.ByteArrayOutputStream().also {
    android.graphics.Bitmap.createBitmap(120, 90, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
}.toByteArray()

@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ThumbnailScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val diary = FakeDiary()

    private fun show() {
        val vm = MainViewModel(diary, FakeSpeech(), FakeLocalSettings(), TEST_CLOCK)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.uiState.collectAsState()
                MainScreen(state, vm, vm.effects)
            }
        }
    }

    @Test fun `a photo is a small picture in the chat, not the word photo`() {
        diary.thumbs["m1"] = picture()
        diary.messages.value = listOf(photo())
        show()
        compose.onNodeWithTag("message-thumb", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(Texts.PHOTO).assertDoesNotExist()
    }

    @Test fun `with words the picture is above them`() {
        diary.thumbs["m1"] = picture()
        diary.messages.value = listOf(photo(text = "обед в кафе"))
        show()
        compose.onNodeWithTag("message-thumb", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("обед в кафе").assertIsDisplayed()
        val thumbBottom = compose.onNodeWithTag("message-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        val textTop = compose.onNodeWithText("обед в кафе", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("the words are under the picture", textTop >= thumbBottom)
    }

    @Test fun `without the picture the chat falls back to the word`() {
        diary.messages.value = listOf(photo(text = "обед"))
        show()
        compose.onNodeWithTag("message-thumb", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("${Texts.PHOTO} · обед").assertIsDisplayed()
    }

    @Test fun `a plain message has no picture`() {
        diary.messages.value = listOf(Message("m2", TODAY, "гречка 200 г", hasImage = false, at = AT))
        show()
        compose.onNodeWithTag("message-thumb", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("гречка 200 г").assertIsDisplayed()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AboutMeNavigationTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun `about me opens from settings and back leads to settings`() = runTest {
        val vm = MainViewModel(FakeDiary(), FakeSpeech(), FakeLocalSettings(), TEST_CLOCK)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onOpenSettings()
        vm.onOpenMe()
        assertEquals(Screen.Me, vm.uiState.value.screen)
        vm.onCloseMe()
        assertEquals(Screen.Settings, vm.uiState.value.screen)
    }
}
