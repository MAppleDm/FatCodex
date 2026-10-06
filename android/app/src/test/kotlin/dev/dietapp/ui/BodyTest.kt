package dev.dietapp.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeAuth
import dev.dietapp.FakeBody
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.formatInt
import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Sex
import dev.dietapp.ui.body.BodyUiState
import dev.dietapp.ui.body.BodyViewModel
import dev.dietapp.ui.body.BodyWizardScreen
import dev.dietapp.ui.body.MeScreen
import dev.dietapp.ui.login.LoginStep
import dev.dietapp.ui.login.LoginUiState
import dev.dietapp.ui.login.LoginViewModel
import dev.dietapp.ui.login.LoginScreen
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
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
class BodyViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val repo = FakeBody(asked = false)

    private fun TestScope.viewModel(): Pair<BodyViewModel, StateFlow<BodyUiState>> {
        val vm = BodyViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test fun `the questions start with sex, and nothing is known`() = runTest {
        val (_, state) = viewModel()
        assertEquals(BodyUiState.SEX, state.value.step)
        assertNull(state.value.sex); assertNull(state.value.age); assertNull(state.value.estimate)
    }

    @Test fun `sex and activity must be picked before going on, the numbers have a typical value`() = runTest {
        val (vm, state) = viewModel()
        assertFalse(state.value.canNext)
        vm.onNext()
        assertEquals("nothing picked: still on the first question", BodyUiState.SEX, state.value.step)
        vm.onSex(Sex.Female)
        assertTrue(state.value.canNext)
        vm.onNext()
        assertEquals(BodyUiState.AGE, state.value.step)
        assertTrue("a number needs no touch to go on", state.value.canNext)
    }

    @Test fun `a number left as shown is taken as shown`() = runTest {
        val (vm, state) = viewModel()
        vm.onSex(Sex.Male)
        vm.onNext(); vm.onNext(); vm.onNext(); vm.onNext() // sex, then age, height and weight, all left alone
        assertEquals(30, state.value.age)
        assertEquals(170, state.value.heightCm)
        assertEquals(listOf(70.0), repo.weights)
        assertEquals(BodyUiState.ACTIVITY, state.value.step)
    }

    @Test fun `all five answered gives the result with the energy worked out`() = runTest {
        val (vm, state) = viewModel()
        vm.onSex(Sex.Male); vm.onNext()
        vm.onAge(30); vm.onNext()
        vm.onHeight(180); vm.onNext()
        vm.onWeight(80.0); vm.onNext()
        assertFalse("the activity has to be picked", state.value.canNext)
        vm.onActivity(Activity.Moderate)
        vm.onNext()
        assertEquals(BodyUiState.RESULT, state.value.step)
        assertEquals(2760, state.value.estimate!!.tdee)
        assertEquals(1780, state.value.estimate!!.bmr)
    }

    @Test fun `back goes to the question before, not before the first`() = runTest {
        val (vm, state) = viewModel()
        vm.onBack()
        assertEquals(BodyUiState.SEX, state.value.step)
        vm.onSex(Sex.Male); vm.onNext()
        vm.onBack()
        assertEquals(BodyUiState.SEX, state.value.step)
        assertEquals("what was answered stays", Sex.Male, state.value.sex)
    }

    @Test fun `the weight shows at once and becomes a weigh-in when the person stops`() = runTest {
        val (vm, state) = viewModel()
        vm.onWeight(71.0)
        vm.onWeight(71.5)
        vm.onWeight(72.0)
        assertEquals("shown at once", 72.0, state.value.weightKg!!, 0.0)
        assertTrue("not saved while still dialling", repo.weights.isEmpty())
        advanceTimeBy(700)
        assertEquals("only where the person stopped", listOf(72.0), repo.weights)
    }

    @Test fun `numbers are kept inside what is plausible`() = runTest {
        val (vm, state) = viewModel()
        vm.onAge(5); assertEquals(14, state.value.age)
        vm.onAge(300); assertEquals(100, state.value.age)
        vm.onHeight(50); assertEquals(120, state.value.heightCm)
        vm.onHeight(400); assertEquals(220, state.value.heightCm)
        vm.onWeight(900.0); assertEquals(250.0, state.value.weightKg!!, 0.0)
    }

    @Test fun `the energy follows the weight being dialled before it is saved`() = runTest {
        val (vm, state) = viewModel()
        vm.onSex(Sex.Male); vm.onAge(30); vm.onHeight(180); vm.onActivity(Activity.Moderate); vm.onWeight(80.0)
        advanceTimeBy(700)
        assertEquals(2760, state.value.estimate!!.tdee)
        vm.onWeight(90.0)
        assertEquals("(900 + 1125 - 150 + 5) * 1.55 = 2914", 2910, state.value.estimate!!.tdee)
    }

    @Test fun `there is no goal until everything is known, then it is what a day costs`() = runTest {
        val (vm, state) = viewModel()
        assertNull(state.value.goal)
        vm.onSex(Sex.Male); vm.onAge(30); vm.onHeight(180); vm.onActivity(Activity.Moderate)
        assertNull("no weight yet", state.value.goal)
        vm.onWeight(80.0)
        assertEquals(2760, state.value.goal)
        assertEquals(0, state.value.adjustment)
        assertFalse(state.value.goalLimited)
    }

    @Test fun `the correction moves the goal away from what a day costs`() = runTest {
        val (vm, state) = viewModel()
        vm.onSex(Sex.Male); vm.onAge(30); vm.onHeight(180); vm.onActivity(Activity.Moderate); vm.onWeight(80.0)
        vm.onAdjustment(-500)
        assertEquals(-500, state.value.adjustment)
        assertEquals(2260, state.value.goal)
        vm.onAdjustment(300)
        assertEquals(3060, state.value.goal)
    }

    @Test fun `the goal follows the weight being dialled, with the correction kept`() = runTest {
        val (vm, state) = viewModel()
        vm.onSex(Sex.Male); vm.onAge(30); vm.onHeight(180); vm.onActivity(Activity.Moderate); vm.onWeight(80.0)
        advanceTimeBy(700)
        vm.onAdjustment(-500)
        vm.onWeight(90.0)
        assertEquals("2910 - 500", 2410, state.value.goal)
    }

    @Test fun `a correction that would go below the lowest goal is held there and says so`() = runTest {
        val (vm, state) = viewModel()
        vm.onSex(Sex.Female); vm.onAge(60); vm.onHeight(150); vm.onActivity(Activity.Sedentary); vm.onWeight(45.0)
        advanceTimeBy(700)
        assertEquals(1200, state.value.goal)
        assertTrue(state.value.goalLimited)
    }

    @Test fun `finishing saves a weight that was still being dialled`() = runTest {
        val (vm, _) = viewModel()
        vm.onWeight(66.5)
        vm.onFinish()
        assertEquals(listOf(66.5), repo.weights)
        assertTrue(repo.askedFlow.value)
    }

    @Test fun `finishing is what ends the questions, nothing else does`() = runTest {
        val (vm, _) = viewModel()
        vm.onSex(Sex.Male); vm.onAge(30); vm.onHeight(180); vm.onActivity(Activity.Moderate); vm.onWeight(80.0)
        assertFalse("all answered, the result not seen yet", repo.askedFlow.value)
        vm.onFinish()
        assertTrue(repo.askedFlow.value)
    }
}

/** The questions as the person meets them, on a screen, with the real view model behind them. */
@RunWith(AndroidJUnit4::class)
class BodyWizardScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val repo = FakeBody(asked = false)

    private fun show(): BodyViewModel {
        val vm = BodyViewModel(repo)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.state.collectAsState()
                BodyWizardScreen(state, vm)
            }
        }
        return vm
    }

    private fun next() {
        compose.onNodeWithTag("wizard-next").performClick()
        compose.waitForIdle()
    }

    @Test fun `it starts with sex, two boxes, nothing picked, and a progress of one in five`() {
        show()
        compose.onNodeWithText(Texts.WIZARD_SEX_Q).assertIsDisplayed()
        compose.onNodeWithTag("sex-male").assertIsDisplayed()
        compose.onNodeWithTag("sex-female").assertIsDisplayed()
        compose.onNodeWithTag("wizard-progress").assertTextContains(Texts.wizardProgress(1, 5))
        compose.onNodeWithTag("wizard-back").assertDoesNotExist()
    }

    @Test fun `next does nothing until sex is picked, then the picked box is marked`() {
        show()
        next()
        compose.onNodeWithText(Texts.WIZARD_SEX_Q).assertIsDisplayed()
        compose.onNodeWithTag("sex-female").performClick()
        compose.onNodeWithTag("sex-female").assertIsSelected()
        next()
        compose.onNodeWithText(Texts.WIZARD_AGE_Q).assertIsDisplayed()
    }

    @Test fun `the age dial shows a typical value and moves by one with plus and minus`() {
        show()
        compose.onNodeWithTag("sex-male").performClick(); next()
        compose.onNodeWithTag("age-value").assertTextContains("30 ${Texts.ME_YEARS}")
        compose.onNodeWithTag("age-plus").performClick()
        compose.onNodeWithTag("age-value").assertTextContains("31 ${Texts.ME_YEARS}")
        compose.onNodeWithTag("age-minus").performClick(); compose.onNodeWithTag("age-minus").performClick()
        compose.onNodeWithTag("age-value").assertTextContains("29 ${Texts.ME_YEARS}")
        compose.onNodeWithTag("age-slider").assertIsDisplayed()
    }

    @Test fun `the age cannot be dialled below the lowest or above the highest`() {
        val vm = show()
        compose.onNodeWithTag("sex-male").performClick(); next()
        vm.onAge(14)
        compose.waitForIdle()
        compose.onNodeWithTag("age-minus").performClick()
        compose.onNodeWithTag("age-value").assertTextContains("14 ${Texts.ME_YEARS}")
    }

    @Test fun `five questions then the result, with the energy worked out from the answers`() {
        show()
        compose.onNodeWithTag("sex-male").performClick(); next()
        compose.onNodeWithTag("age-plus").performClick(); next()                               // 31
        compose.onNodeWithText(Texts.WIZARD_HEIGHT_Q).assertIsDisplayed()
        compose.onNodeWithTag("height-value").assertTextContains("170 ${Texts.ME_CM}"); next()
        compose.onNodeWithText(Texts.WIZARD_WEIGHT_Q).assertIsDisplayed()
        compose.onNodeWithTag("weight-value").assertTextContains("70 ${Texts.KG}")
        compose.onNodeWithTag("weight-plus").performClick()                                    // 70,5
        compose.onNodeWithTag("weight-value").assertTextContains("70,5 ${Texts.KG}"); next()
        compose.onNodeWithText(Texts.WIZARD_ACTIVITY_Q).assertIsDisplayed()
        Activity.entries.forEach { compose.onNodeWithTag("activity-${it.name.lowercase()}").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("activity-moderate").performScrollTo().performClick()
        compose.onNodeWithTag("activity-moderate").assertIsSelected()
        next()

        compose.onNodeWithText(Texts.WIZARD_RESULT_Q).assertIsDisplayed()
        // (10 * 70.5 + 6.25 * 170 - 5 * 31 + 5) * 1.55 = 2507
        compose.onNodeWithTag("energy-tdee").assertTextContains("≈ ${formatInt(2510)}")
        compose.onNodeWithTag("energy-rest").assertTextContains(formatInt(1620), substring = true)
        // without a correction the goal is what a day costs
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(2510))
        compose.onNodeWithTag("wizard-done").performClick()
        compose.waitForIdle()
        assertTrue(repo.askedFlow.value)
        assertEquals("the weight became a weigh-in", listOf(70.5), repo.weights)
    }

    @Test fun `the result lets the person correct the goal, minus or plus, and shows the sum`() {
        show()
        compose.onNodeWithTag("sex-male").performClick(); next()
        next(); next(); next()                                                                 // 30 years, 170 cm, 70 kg as shown
        compose.onNodeWithTag("activity-moderate").performScrollTo().performClick(); next()
        // (700 + 1062.5 - 150 + 5) * 1.55 = 2507.6 -> 2510
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(2510))
        compose.onNodeWithTag("adjust-value").assertTextContains("0 ${Texts.KCAL}")
        compose.onNodeWithTag("adjust-minus").performScrollTo().performClick() // below the fold on a short screen
        compose.onNodeWithTag("adjust-minus").performClick()
        compose.onNodeWithTag("adjust-minus").performClick()
        compose.onNodeWithTag("adjust-value").assertTextContains("${Texts.signedKcal(-150)} ${Texts.KCAL}")
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(2360))
        compose.onNodeWithTag("goal-sum").assertTextContains(Texts.meGoalSum(2510, -150))
        compose.onNodeWithTag("adjust-plus").performScrollTo().performClick()
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(2410))
        assertEquals(-100, repo.current.value.adjustment)
    }

    @Test fun `no question can be skipped, the goal is worked out from all of them`() {
        show()
        compose.onNodeWithTag("wizard-skip").assertDoesNotExist()
        compose.onNodeWithTag("sex-male").performClick(); next()
        compose.onNodeWithTag("wizard-skip").assertDoesNotExist()
        assertFalse(repo.askedFlow.value)
    }

    @Test fun `back returns to the question before`() {
        show()
        compose.onNodeWithTag("sex-male").performClick(); next()
        compose.onNodeWithTag("wizard-back").performClick()
        compose.onNodeWithText(Texts.WIZARD_SEX_Q).assertIsDisplayed()
        compose.onNodeWithTag("sex-male").assertIsSelected()
    }
}

/** "О себе": every answer with its control, the energy on top, changes saved as they are made. */
@RunWith(AndroidJUnit4::class)
class MeScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val repo = FakeBody()

    private fun show() {
        val vm = BodyViewModel(repo)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.state.collectAsState()
                MeScreen(state, vm, onBack = {})
            }
        }
    }

    @Test fun `before anything is said every number is a dash and the energy is asked for`() {
        show()
        compose.onNodeWithText(Texts.ME).assertIsDisplayed()
        compose.onNodeWithTag("energy-incomplete").assertIsDisplayed()
        compose.onNodeWithTag("age-value").performScrollTo().assertTextContains("—")
        compose.onNodeWithTag("height-value").performScrollTo().assertTextContains("—")
        compose.onNodeWithTag("weight-value").performScrollTo().assertTextContains("—")
    }

    @Test fun `each control saves as it is changed, and the energy appears when all is known`() {
        show()
        compose.onNodeWithTag("sex-male").performScrollTo().performClick()
        compose.onNodeWithTag("age-plus").performScrollTo().performClick()          // from the typical 30: 31
        compose.onNodeWithTag("height-plus").performScrollTo().performClick()       // 171
        compose.onNodeWithTag("weight-plus").performScrollTo().performClick()       // 70,5
        compose.onNodeWithTag("activity-light").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Sex.Male, repo.current.value.sex)
        assertEquals(31, repo.current.value.age)
        assertEquals(171, repo.current.value.heightCm)
        assertEquals(Activity.Light, repo.current.value.activity)
        // the weight waits a moment for the person to stop; the page already shows it
        compose.onNodeWithTag("weight-value").performScrollTo().assertTextContains("70,5 ${Texts.KG}")
        compose.onNodeWithTag("energy-tdee").performScrollTo().assertIsDisplayed()
    }

    @Test fun `what is already known is shown`() {
        repo.setSex(Sex.Female); repo.setAge(28); repo.setHeight(165); repo.setActivity(Activity.High)
        show()
        compose.onNodeWithTag("sex-female").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("age-value").performScrollTo().assertTextContains("28 ${Texts.ME_YEARS}")
        compose.onNodeWithTag("height-value").performScrollTo().assertTextContains("165 ${Texts.ME_CM}")
        compose.onNodeWithTag("activity-high").performScrollTo().assertIsSelected()
    }

    @Test fun `the page says how the figure is reached and that it is an estimate`() {
        repo.setSex(Sex.Male); repo.setAge(30); repo.setHeight(180); repo.setActivity(Activity.Moderate)
        kotlinx.coroutines.runBlocking { repo.setWeight(80.0) }
        show()
        compose.onNodeWithTag("energy-tdee").assertTextContains("≈ ${formatInt(2760)}")
        compose.onNodeWithTag("energy-rest").assertTextContains("1,55", substring = true)
        compose.onNodeWithText(Texts.ME_FORMULA).assertIsDisplayed()
    }

    private fun answered() {
        repo.setSex(Sex.Male); repo.setAge(30); repo.setHeight(180); repo.setActivity(Activity.Moderate)
        kotlinx.coroutines.runBlocking { repo.setWeight(80.0) }
    }

    @Test fun `the goal is what a day costs plus the correction, and there is no field to type it in`() {
        answered()
        show()
        compose.onNodeWithTag("goal-block").assertIsDisplayed()
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(2760))
        compose.onNodeWithTag("adjust-value").assertTextContains("0 ${Texts.KCAL}")
        compose.onNodeWithTag("goal").assertDoesNotExist()
    }

    @Test fun `the correction is made with minus and plus and saved at once`() {
        answered()
        show()
        compose.onNodeWithTag("adjust-minus").performScrollTo().performClick()
        compose.onNodeWithTag("adjust-minus").performClick()
        compose.waitForIdle()
        assertEquals(-100, repo.current.value.adjustment)
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(2660))
        compose.onNodeWithTag("goal-sum").assertTextContains(Texts.meGoalSum(2760, -100))
        compose.onNodeWithTag("adjust-plus").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(-50, repo.current.value.adjustment)
    }

    @Test fun `a correction that goes below the floor is held and the page says why`() {
        repo.setSex(Sex.Female); repo.setAge(60); repo.setHeight(150); repo.setActivity(Activity.Sedentary)
        kotlinx.coroutines.runBlocking { repo.setWeight(45.0) }
        show()
        compose.onNodeWithTag("goal-value").assertTextContains(formatInt(1200))
        compose.onNodeWithTag("goal-floor").performScrollTo().assertTextContains(Texts.meGoalFloor(1200))
    }

    @Test fun `before all is known there is no goal block, only the request to fill things in`() {
        show()
        compose.onNodeWithTag("goal-block").assertDoesNotExist()
        compose.onNodeWithText(Texts.ME_INCOMPLETE).assertIsDisplayed()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunFlowTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuth()
    private val diary = FakeDiary().also { it.profile.value = Profile(null, null) }
    private val localSettings = FakeLocalSettings()
    private val body = FakeBody(asked = false)

    private fun TestScope.viewModel(): Pair<LoginViewModel, StateFlow<LoginUiState>> {
        val vm = LoginViewModel(auth, diary, localSettings, body, serverEnabled = true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test fun `after signing in the questions about the person are all there is before the diary`() = runTest {
        auth.signIn()
        val (_, state) = viewModel()
        assertEquals(LoginStep.About, state.value.step)
        assertFalse(state.value.done)
    }

    @Test fun `the diary opens when the result has been seen, and not before`() = runTest {
        auth.signIn()
        val (_, state) = viewModel()
        body.setSex(Sex.Male); body.setAge(30); body.setHeight(180); body.setActivity(Activity.Moderate)
        kotlinx.coroutines.runBlocking { body.setWeight(80.0) }
        diary.profile.value = Profile(null, body.current.value.goal) // what the app does: the goal comes from the answers
        assertEquals(2760, body.current.value.goal)
        assertFalse("the last answer is given, the result is still to be seen", state.value.done)
        body.finishOnboarding()
        assertTrue(state.value.done)
    }

    @Test fun `the key comes first without a server, then the questions`() = runTest {
        auth.afterLocal = { localSettings.state.value = localSettings.state.value.copy(local = true) }
        val (vm, state) = viewModel()
        vm.onWithoutServer()
        assertEquals(LoginStep.Key, state.value.step)
        vm.onKey("sk-1234567890abcdef")
        vm.onSubmit()
        assertEquals(LoginStep.About, state.value.step)
        body.finishOnboarding()
        diary.profile.value = Profile(null, 2400)
        assertTrue(state.value.done)
    }

    @Test fun `someone who has answered and has a goal is not asked again`() = runTest {
        auth.signIn()
        body.finishOnboarding()
        diary.profile.value = Profile("me@example.com", 1900)
        val (_, state) = viewModel()
        assertTrue(state.value.done)
    }

    @Test fun `someone who updates with an old typed goal but no answers is asked the questions`() = runTest {
        auth.signIn()
        body.finishOnboarding() // asked before, in the first version, and skipped
        diary.profile.value = Profile("me@example.com", null) // the goal is no longer theirs to type: nothing known, nothing to show
        val (_, state) = viewModel()
        assertEquals(LoginStep.About, state.value.step)
        assertFalse(state.value.done)
    }
}
