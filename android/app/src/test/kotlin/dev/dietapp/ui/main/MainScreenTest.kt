package dev.dietapp.ui.main

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.TODAY
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.formatInt
import dev.dietapp.entry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one UI test the brief asks for, run on the JVM with Robolectric (no emulator):
 * type into the single input -> a diary entry appears -> the day summary at the top is updated.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val diary = FakeDiary()
    private val speech = FakeSpeech()
    private val goal = formatInt(1900)

    private fun show(): MainViewModel {
        val vm = MainViewModel(diary, speech, FakeLocalSettings(), TEST_CLOCK)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.uiState.collectAsState()
                MainScreen(state, vm, vm.effects)
            }
        }
        return vm
    }

    @Test fun `typing a meal adds an entry and updates the day summary`() {
        // the "server": turn the queued message into an entry, as the gateway would
        diary.onSend = { sent ->
            if (sent.text == "гречка 200 г") diary.entries.value = listOf(entry("e1", name = "гречка", grams = 200.0, kcal = 184.0))
        }
        show()

        compose.onNodeWithTag("summary").assertTextContains("0 / $goal ккал", substring = true)

        compose.onNodeWithContentDescription(Texts.INPUT_PLACEHOLDER).performTextInput("гречка 200 г")
        compose.onNodeWithContentDescription("Отправить").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("entry").assertIsDisplayed().assertTextContains("гречка", substring = true)
        compose.onNodeWithTag("entry").assertTextContains("200 г", substring = true)
        compose.onNodeWithTag("entry").assertTextContains("184", substring = true)
        compose.onNodeWithTag("summary").assertTextContains("184 / $goal ккал · Б 7 · Ж 1 · У 40")
        // the input is empty again, so the send button has turned back into the microphone
        compose.onNodeWithContentDescription("Микрофон").assertIsDisplayed()
    }

    @Test fun `the summary opens on tap and shows what is left`() {
        diary.entries.value = listOf(entry("e1", kcal = 1420.0))
        show()
        compose.onNodeWithTag("summary").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertTextContains("Осталось", substring = true)
        compose.onNodeWithTag("summary").assertTextContains("480 ккал", substring = true)
    }

    @Test fun `a tap opens a food, a tap on its row folds it back and keeps the change`() {
        diary.entries.value = listOf(entry("e1", name = "гречка", grams = 200.0))
        show()
        compose.onNodeWithTag("entry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor").assertIsDisplayed()
        compose.onNodeWithTag("entry-facts", useUnmergedTree = true).assertTextContains("184 ккал", substring = true)
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("200")).performTextReplacement("150")
        compose.onNodeWithTag("entry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor").assertDoesNotExist()
        org.junit.Assert.assertEquals(Triple("e1", 150.0, "гречка"), diary.updates.single())
    }

    @Test fun `an open question is asked above the input`() {
        diary.notes.value = listOf(
            dev.dietapp.data.domain.Note(1, TODAY, dev.dietapp.data.domain.NoteKind.Question, "Какой суп?", null,
                java.time.Instant.parse("2026-09-30T06:00:00Z")),
        )
        show()
        compose.onNodeWithTag("question-panel").assertIsDisplayed()
        compose.onNodeWithText("Какой суп?").assertIsDisplayed()
        compose.onNodeWithTag("question-skip").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(1L), diary.skippedQuestions)
        compose.onNodeWithTag("question-panel").assertDoesNotExist()
    }

    @Test fun `a change to the food base is asked with yes and no, and yes goes to the diary`() {
        diary.notes.value = listOf(
            dev.dietapp.data.domain.Note(7, TODAY, dev.dietapp.data.domain.NoteKind.BaseChange,
                "Удалить «казеин» из базы?\n360 ккал · Б 80 · Ж 1,5 · У 8 на 100 г", null, java.time.Instant.parse("2026-09-30T06:00:00Z")),
        )
        show()
        compose.onNodeWithTag("base-change").assertIsDisplayed()
        compose.onNodeWithText("Удалить «казеин» из базы?").assertIsDisplayed()
        compose.onNodeWithText("360 ккал · Б 80 · Ж 1,5 · У 8 на 100 г").assertIsDisplayed()
        compose.onNodeWithTag("base-change-no").assertIsDisplayed()
        compose.onNodeWithTag("base-change-yes").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(7L to true), diary.baseChangeAnswers)
        compose.onNodeWithTag("base-change").assertDoesNotExist()
    }

    @Test fun `an empty day explains what can be typed`() {
        show()
        compose.onNodeWithText(Texts.EMPTY_HINT).assertIsDisplayed()
    }
}
