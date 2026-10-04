package dev.dietapp.ui

import dev.dietapp.FakeAuth
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TODAY
import dev.dietapp.Texts
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Totals
import dev.dietapp.failure
import dev.dietapp.ui.login.LoginStep
import dev.dietapp.ui.login.LoginUiState
import dev.dietapp.ui.login.LoginViewModel
import dev.dietapp.ui.settings.SettingsUiState
import dev.dietapp.ui.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuth()
    private val diary = FakeDiary().also { it.profile.value = Profile(null, null) }
    private val localSettings = FakeLocalSettings()

    init {
        // choosing "без сервера" is what makes the capabilities local
        auth.afterLocal = { localSettings.state.value = localSettings.state.value.copy(local = true) }
    }

    private fun TestScope.viewModel(serverEnabled: Boolean = true): Pair<LoginViewModel, StateFlow<LoginUiState>> {
        val vm = LoginViewModel(auth, diary, localSettings, serverEnabled)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test fun `starts on the email step`() = runTest {
        val (_, state) = viewModel()
        assertEquals(LoginStep.Email, state.value.step)
        assertFalse(state.value.done)
    }

    @Test fun `an existing session with a goal goes straight to the diary`() = runTest {
        auth.signIn()
        diary.profile.value = Profile("me@example.com", 1900)
        assertTrue(viewModel().second.value.done)
    }

    @Test fun `email, then code, then goal, each step on the same screen`() = runTest {
        diary.profile.value = Profile(null, null)
        auth.afterVerify = { auth.signIn() }
        auth.afterGoal = { diary.profile.value = Profile("me@example.com", it) }
        val (vm, state) = viewModel()

        vm.onEmail("me@example.com")
        vm.onSubmit()
        assertEquals(LoginStep.Code, state.value.step)
        assertEquals("code:me@example.com", auth.calls.last())

        vm.onCode("12a3456789")          // digits only, six of them
        assertEquals("123456", state.value.code)
        vm.onSubmit()
        assertEquals("verify:me@example.com:123456", auth.calls.last())
        assertEquals(LoginStep.Goal, state.value.step)
        assertFalse("not done until there is a goal", state.value.done)

        vm.onGoal("1900")
        vm.onSubmit()
        assertEquals("goal:1900", auth.calls.last())
        assertTrue(state.value.done)
    }

    @Test fun `a returning user with a goal is done right after the code`() = runTest {
        auth.afterVerify = { auth.signIn(); diary.profile.value = Profile("me@example.com", 2000) }
        val (vm, state) = viewModel()
        vm.onEmail("me@example.com"); vm.onSubmit()
        vm.onCode("123456"); vm.onSubmit()
        assertTrue(state.value.done)
    }

    @Test fun `server errors are shown as they are and editing clears them`() = runTest {
        auth.requestCodeResult = failure("Код уже отправлен. Подожди минуту и запроси снова.", "rate_limited")
        val (vm, state) = viewModel()
        vm.onEmail("me@example.com")
        vm.onSubmit()
        assertEquals("Код уже отправлен. Подожди минуту и запроси снова.", state.value.error)
        assertEquals(LoginStep.Email, state.value.step)
        assertFalse(state.value.busy)
        vm.onEmail("me@example.org")
        assertNull(state.value.error)
    }

    @Test fun `a wrong code stays on the code step`() = runTest {
        auth.verifyResult = failure("Код неверный или устарел. Запроси новый.", "invalid_code")
        val (vm, state) = viewModel()
        vm.onEmail("me@example.com"); vm.onSubmit()
        vm.onCode("000000"); vm.onSubmit()
        assertEquals(LoginStep.Code, state.value.step)
        assertEquals("Код неверный или устарел. Запроси новый.", state.value.error)
    }

    @Test fun `going back to change the address`() = runTest {
        val (vm, state) = viewModel()
        vm.onEmail("typo@example.com"); vm.onSubmit()
        vm.onCode("123")
        vm.onChangeEmail()
        assertEquals(LoginStep.Email, state.value.step)
        assertEquals("", state.value.code)
        assertEquals("typo@example.com", state.value.email)
    }

    @Test fun `a goal that is not a number is refused locally`() = runTest {
        auth.signIn()
        val (vm, state) = viewModel()
        vm.onSubmit()
        assertEquals("Введи число, например 1900.", state.value.error)
        assertTrue(auth.calls.isEmpty())
    }

    @Test fun `a goal below the floor shows the safety message`() = runTest {
        auth.signIn()
        auth.goalResult = failure("Цель ниже 1 200 ккал в день без наблюдения врача не рекомендуется. Выбери не меньше 1 200.", "goal_too_low")
        val (vm, state) = viewModel()
        vm.onGoal("900")
        vm.onSubmit()
        assertTrue(state.value.error!!.contains("врача"))
        assertFalse(state.value.done)
    }

    @Test fun `without a server the email steps give way to the key, then the goal`() = runTest {
        val (vm, state) = viewModel()
        assertTrue(state.value.serverEnabled)
        vm.onWithoutServer()
        assertEquals("local", auth.calls.last())
        assertEquals(LoginStep.Key, state.value.step)
        assertFalse(state.value.done)

        vm.onKey(" sk-1234 567890abcdef ")
        assertEquals("whitespace never belongs to a key", "sk-1234567890abcdef", state.value.key)
        vm.onSubmit()
        assertEquals(listOf("sk-1234567890abcdef"), localSettings.saved)
        assertEquals("", state.value.key)
        assertEquals(LoginStep.Goal, state.value.step)

        auth.afterGoal = { diary.profile.value = Profile(null, it) }
        vm.onGoal("1900"); vm.onSubmit()
        assertTrue(state.value.done)
    }

    @Test fun `something that cannot be a key is refused and the step stays`() = runTest {
        val (vm, state) = viewModel()
        vm.onWithoutServer()
        vm.onKey("abc")
        vm.onSubmit()
        assertEquals("Это не похоже на ключ DeepSeek.", state.value.error)
        assertEquals(LoginStep.Key, state.value.step)
        assertTrue(localSettings.saved.isEmpty())
        vm.onKey("sk-1234567890")
        assertNull("editing clears the error", state.value.error)
    }

    @Test fun `the key can be skipped, the dictionary still works`() = runTest {
        val (vm, state) = viewModel()
        vm.onWithoutServer()
        assertEquals(LoginStep.Key, state.value.step)
        vm.onSkipKey()
        assertEquals(LoginStep.Goal, state.value.step)
        assertTrue(localSettings.saved.isEmpty())
    }

    @Test fun `a key that is already there is not asked for again`() = runTest {
        localSettings.state.value = localSettings.state.value.copy(modelKey = true)
        val (vm, state) = viewModel()
        vm.onWithoutServer()
        assertEquals(LoginStep.Goal, state.value.step)
    }

    @Test fun `once a goal is set the key step never comes back`() = runTest {
        auth.afterLocal = { localSettings.state.value = localSettings.state.value.copy(local = true); diary.profile.value = Profile(null, 1900) }
        val (vm, state) = viewModel()
        vm.onWithoutServer()
        assertTrue(state.value.done)
    }

    @Test fun `with a server nobody is asked for a model key`() = runTest {
        auth.afterVerify = { auth.signIn() }
        val (vm, state) = viewModel()
        vm.onEmail("me@example.com"); vm.onSubmit()
        vm.onCode("123456"); vm.onSubmit()
        assertEquals(LoginStep.Goal, state.value.step)
    }

    @Test fun `a build without a server goes straight to the key step, never the email step`() = runTest {
        val (_, state) = viewModel(serverEnabled = false)
        assertFalse(state.value.serverEnabled)
        assertEquals("local", auth.calls.single())
        assertFalse("not even a frame of the email step", state.value.loading)
        assertEquals(LoginStep.Key, state.value.step)
    }

    @Test fun `a build without a server re-enters local mode after the data is erased`() = runTest {
        val (_, state) = viewModel(serverEnabled = false)
        auth.logout()
        assertEquals(listOf("local", "logout", "local"), auth.calls)
        assertEquals(LoginStep.Key, state.value.step)
    }

    @Test fun `a second tap while busy is ignored`() = runTest {
        val (vm, _) = viewModel()
        vm.onEmail("me@example.com")
        vm.onSubmit()
        vm.onSubmit() // now on the code step with an empty code: a second request must not go out as a code request
        assertEquals(1, auth.calls.count { it.startsWith("code:") })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuth()
    private val diary = FakeDiary()
    private val localSettings = FakeLocalSettings()
    private val foods = dev.dietapp.FakeFoods()

    private fun TestScope.viewModel(): Pair<SettingsViewModel, StateFlow<SettingsUiState>> {
        val vm = SettingsViewModel(auth, localSettings, diary, foods)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test fun `shows the account, the saved goal and the history`() = runTest {
        diary.summaries.value = listOf(DaySummary(TODAY, Totals(1420.0, 90.0, 50.0, 140.0)))
        val (_, state) = viewModel()
        assertEquals("me@example.com", state.value.email)
        assertEquals("1900", state.value.goalText)
        assertEquals(1, state.value.history.size)
        assertFalse(state.value.canSave)
    }

    @Test fun `saving is possible only for a changed, valid number`() = runTest {
        val (vm, state) = viewModel()
        vm.onGoalChange("1900")
        assertFalse(state.value.canSave)
        vm.onGoalChange("")
        assertFalse(state.value.canSave)
        vm.onGoalChange("2000x")
        assertEquals("2000", state.value.goalText)
        assertTrue(state.value.canSave)
    }

    @Test fun `a saved goal is confirmed`() = runTest {
        auth.afterGoal = { diary.profile.value = Profile("me@example.com", it) }
        val (vm, state) = viewModel()
        vm.onGoalChange("2100")
        vm.onSaveGoal()
        assertEquals("goal:2100", auth.calls.last())
        assertEquals(Texts.SAVED, state.value.message)
        assertFalse(state.value.messageIsError)
        assertEquals("2100", state.value.goalText)
        assertFalse(state.value.canSave)
    }

    @Test fun `a refused goal shows the reason and keeps the input`() = runTest {
        auth.goalResult = failure("Цель ниже 1 200 ккал в день без наблюдения врача не рекомендуется. Выбери не меньше 1 200.")
        val (vm, state) = viewModel()
        vm.onGoalChange("1000")
        vm.onSaveGoal()
        assertTrue(state.value.messageIsError)
        assertTrue(state.value.message!!.contains("1 200"))
        assertEquals("1000", state.value.goalText)
        assertEquals(1900, state.value.savedGoal)
    }

    @Test fun `logging out goes through the repository`() = runTest {
        val (vm, _) = viewModel()
        vm.onLogout()
        assertEquals("logout", auth.calls.last())
    }

    // ---------- local mode ----------

    private fun goLocal(key: Boolean = false) {
        localSettings.state.value = dev.dietapp.data.local.Capabilities(local = true, modelKey = key)
        diary.profile.value = Profile(null, 1900)
    }

    @Test fun `the model key section is for the local mode only`() = runTest {
        val (_, state) = viewModel()
        assertFalse(state.value.local)
        goLocal()
        assertTrue(state.value.local)
        assertFalse(state.value.hasKey)
    }

    @Test fun `a key is saved, masked away from the field and never echoed`() = runTest {
        goLocal()
        val (vm, state) = viewModel()
        vm.onKeyChange(" sk-1234 567890abcdef ")
        assertEquals("whitespace is dropped", "sk-1234567890abcdef", state.value.keyInput)
        assertTrue(state.value.canSaveKey)
        vm.onSaveKey()
        assertEquals(listOf("sk-1234567890abcdef"), localSettings.saved)
        assertTrue(state.value.hasKey)
        assertEquals("the field is emptied", "", state.value.keyInput)
        assertEquals(Texts.SAVED, state.value.message)
    }

    @Test fun `something that cannot be a key is refused with the reason`() = runTest {
        goLocal()
        val (vm, state) = viewModel()
        vm.onKeyChange("abc")
        vm.onSaveKey()
        assertTrue(localSettings.saved.isEmpty())
        assertTrue(state.value.messageIsError)
        assertEquals("abc", state.value.keyInput)
        assertFalse(state.value.hasKey)
    }

    @Test fun `the key can be removed again`() = runTest {
        goLocal(key = true)
        val (vm, state) = viewModel()
        assertTrue(state.value.hasKey)
        vm.onClearKey()
        assertEquals(1, localSettings.cleared)
        assertFalse(state.value.hasKey)
    }

    @Test fun `without a server erasing needs a second tap`() = runTest {
        goLocal()
        val (vm, state) = viewModel()
        vm.onLogout()
        assertTrue(state.value.eraseArmed)
        assertTrue("nothing erased yet", auth.calls.none { it == "logout" })
        vm.onLogout()
        assertEquals("logout", auth.calls.last())
        assertFalse(state.value.eraseArmed)
    }

    @Test fun `the first tap wears off by itself`() = runTest {
        goLocal()
        val (vm, state) = viewModel()
        vm.onLogout()
        assertTrue(state.value.eraseArmed)
        advanceTimeBy(4_001)
        assertFalse(state.value.eraseArmed)
        vm.onLogout() // arms again, does not erase
        assertTrue(auth.calls.none { it == "logout" })
    }

    @Test fun `settings show how many foods the user's base holds`() = runTest {
        goLocal()
        val (_, state) = viewModel()
        assertEquals(0, state.value.foodCount)
        foods.save(dev.dietapp.data.local.FoodInput("казеин", dev.dietapp.data.domain.Per100(360.0, 80.0, 1.5, 8.0)), null)
        assertEquals(1, state.value.foodCount)
    }

    @Test fun `web search can be switched off and on`() = runTest {
        goLocal()
        val (vm, state) = viewModel()
        assertTrue(state.value.webSearch)
        vm.onToggleWebSearch()
        assertFalse(state.value.webSearch)
        vm.onToggleWebSearch()
        assertTrue(state.value.webSearch)
    }

    @Test fun `touching anything else disarms the erase`() = runTest {
        goLocal()
        val (vm, state) = viewModel()
        vm.onLogout()
        vm.onGoalChange("2000")
        assertFalse(state.value.eraseArmed)
    }
}
