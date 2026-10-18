package dev.dietapp.ui.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeWatch
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.repo.WatchState
import dev.dietapp.data.watch.WatchAvailability
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val repo = FakeWatch()

    private fun TestScope.viewModel(): Pair<WatchViewModel, StateFlow<WatchState>> {
        val vm = WatchViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test fun `it shows what the repository says`() = runTest {
        val (_, state) = viewModel()
        assertFalse(state.value.connected)
        repo.current.value = WatchState(WatchAvailability.Ready, permitted = true, connected = true, lastReadAt = Instant.parse("2026-10-07T18:14:00Z"))
        assertTrue(state.value.connected && state.value.permitted)
    }

    @Test fun `opening the page looks at the phone again`() = runTest {
        val (vm, _) = viewModel()
        vm.onOpened()
        assertEquals(1, repo.phoneLooks)
    }

    @Test fun `what the person allowed in the dialog is passed on, and nothing if nothing was allowed`() = runTest {
        val (vm, state) = viewModel()
        vm.onGranted(emptySet())
        assertFalse(state.value.connected)
        vm.onGranted(setOf("p.sleep"))
        assertEquals(listOf(emptySet(), setOf("p.sleep")), repo.answers)
        assertTrue(state.value.connected)
    }

    @Test fun `disconnecting goes through the repository`() = runTest {
        val (vm, _) = viewModel()
        vm.onDisconnect()
        assertEquals(1, repo.disconnects)
    }

    @Test fun `coming to the front reads the watch quietly`() = runTest {
        val (vm, _) = viewModel()
        vm.onForeground()
        assertEquals(1, repo.refreshes)
    }

    @Test fun `the dialog asks for what the repository reads`() = runTest {
        val (vm, _) = viewModel()
        assertEquals(repo.permissions, vm.permissions)
    }
}

@RunWith(AndroidJUnit4::class)
class WatchScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private var connects = 0
    private var disconnects = 0
    private var installs = 0

    private fun show(state: WatchState) = compose.setContent {
        AppTheme(darkTheme = false) {
            WatchScreen(state, onBack = {}, onConnect = { connects++ }, onDisconnect = { disconnects++ }, onInstall = { installs++ })
        }
    }

    @Test fun `before it is connected the page explains and offers to connect`() {
        show(WatchState(WatchAvailability.Ready))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_OFF)
        compose.onNodeWithTag("watch-connect").assertIsDisplayed().performClick()
        assertEquals(1, connects)
        compose.onNodeWithTag("watch-disconnect").assertDoesNotExist()
        compose.onNodeWithTag("watch-install").assertDoesNotExist()
    }

    @Test fun `the page says what is read, and that Health Connect has no wellbeing scores`() {
        show(WatchState(WatchAvailability.Ready))
        compose.onNodeWithTag("watch-page").assertIsDisplayed()
        compose.onNodeWithText(Texts.WATCH_ABOUT).assertIsDisplayed()
        compose.onNodeWithText(Texts.WATCH_WHAT).assertIsDisplayed()
    }

    @Test fun `connected, it says so and when it was last read, and can be disconnected`() {
        show(WatchState(WatchAvailability.Ready, permitted = true, connected = true, lastReadAt = Instant.parse("2026-10-07T18:14:00Z")))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_ON)
        compose.onNodeWithTag("watch-read").assertIsDisplayed()
        compose.onNodeWithTag("watch-connect").assertDoesNotExist()
        compose.onNodeWithTag("watch-disconnect").performClick()
        assertEquals(1, disconnects)
    }

    @Test fun `a connection that lacks the calories says so and offers to allow them`() {
        show(WatchState(WatchAvailability.Ready, permitted = true, connected = true, complete = false))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_PARTIAL)
        compose.onNodeWithTag("watch-connect").assertIsDisplayed().assertTextContains(Texts.WATCH_ALLOW)
        compose.onNodeWithTag("watch-connect").performClick()
        assertEquals(1, connects)
        compose.onNodeWithTag("watch-disconnect").assertIsDisplayed()
    }

    @Test fun `a whole connection has nothing to allow`() {
        show(WatchState(WatchAvailability.Ready, permitted = true, connected = true))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_ON)
        compose.onNodeWithTag("watch-connect").assertDoesNotExist()
    }

    @Test fun `when the permissions were taken back it says so and asks again`() {
        show(WatchState(WatchAvailability.Ready, permitted = false, connected = true))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_NO_PERMISSION)
        compose.onNodeWithTag("watch-connect").assertIsDisplayed().performClick()
        assertEquals(1, connects)
        compose.onNodeWithTag("watch-disconnect").assertIsDisplayed()
    }

    @Test fun `without Health Connect the only action is to get it`() {
        show(WatchState(WatchAvailability.NotInstalled))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_NOT_INSTALLED)
        compose.onNodeWithTag("watch-connect").assertDoesNotExist()
        compose.onNodeWithTag("watch-install").assertTextContains(Texts.WATCH_INSTALL).performClick()
        assertEquals(1, installs)
    }

    @Test fun `with an old Health Connect the action is to update it`() {
        show(WatchState(WatchAvailability.UpdateNeeded))
        compose.onNodeWithTag("watch-status").assertTextContains(Texts.WATCH_UPDATE_NEEDED)
        compose.onNodeWithTag("watch-install").assertTextContains(Texts.WATCH_UPDATE)
    }
}
