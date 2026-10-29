package dev.dietapp.ui.main

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
        compose.onNodeWithTag("summary").assertTextContains("184 / $goal ккал")
        compose.onNodeWithTag("summary").assertTextContains("Б 7 · Ж 1 · У 40") // its own line: a long figure cannot break it apart
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

    @Test fun `an open question is asked in the chat, with a way to skip it`() {
        diary.notes.value = listOf(
            dev.dietapp.data.domain.Note(1, TODAY, dev.dietapp.data.domain.NoteKind.Question, "Какой суп?", null,
                java.time.Instant.parse("2026-09-30T06:00:00Z")),
        )
        show()
        compose.onNodeWithTag("question-open").assertIsDisplayed()
        compose.onNodeWithText("Какой суп?").assertIsDisplayed()
        compose.onNodeWithTag("question-skip").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(1L), diary.skippedQuestions)
        compose.onNodeWithTag("question-open").assertDoesNotExist()
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

    @Test fun `a message the agent could not read stays, with why, a red try again and remove`() {
        diary.outbox.value = listOf(
            dev.dietapp.data.domain.OutboxMessage(
                "f1", "фиш бургер, мороженое", false, TODAY, java.time.Instant.parse("2026-09-30T06:00:00Z"),
                dev.dietapp.data.domain.OutboxState.Failed, "Нет связи с агентом.", 3,
            ),
        )
        show()
        compose.onNodeWithTag("message").assertTextContains("фиш бургер, мороженое", substring = true)
        compose.onNodeWithTag("failed-note").assertIsDisplayed()
        compose.onNodeWithText("Нет связи с агентом.").assertIsDisplayed()
        compose.onNodeWithTag("retry").assertIsDisplayed().assertTextContains(Texts.RETRY)
        compose.onNodeWithTag("discard").assertIsDisplayed()

        compose.onNodeWithTag("retry").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("f1"), diary.retriedOutbox)
        org.junit.Assert.assertTrue("trying again does not take it back", diary.discardedOutbox.isEmpty())

        compose.onNodeWithTag("discard").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("f1"), diary.discardedOutbox)
    }

    @Test fun `while the agent works the line under the message says what it does`() {
        fun waiting(agent: dev.dietapp.data.domain.AgentStatus?) = listOf(
            dev.dietapp.data.domain.OutboxMessage(
                "w1", "борщ", false, TODAY, java.time.Instant.parse("2026-09-30T06:00:00Z"),
                dev.dietapp.data.domain.OutboxState.Queued, null, 0, agent,
            ),
        )
        diary.outbox.value = waiting(null)
        show()
        compose.onNodeWithTag("message").assertTextContains(Texts.STATUS_WAITING, substring = true)

        val steps = dev.dietapp.data.domain.AgentStep.entries
        diary.outbox.value = waiting(dev.dietapp.data.domain.AgentStatus(dev.dietapp.data.domain.AgentStep.SearchWeb))
        compose.waitForIdle()
        compose.onNodeWithTag("message").assertTextContains("ищу в интернете", substring = true)

        diary.outbox.value = waiting(dev.dietapp.data.domain.AgentStatus(dev.dietapp.data.domain.AgentStep.ReadPage, "health-diet.ru"))
        compose.waitForIdle()
        compose.onNodeWithTag("message").assertTextContains("читаю health-diet.ru", substring = true)

        // every step has its words, none is empty
        for (step in steps) org.junit.Assert.assertTrue(Texts.agentStep(dev.dietapp.data.domain.AgentStatus(step)).isNotBlank())
    }

    @Test fun `a message that is only waiting has no try again`() {
        diary.outbox.value = listOf(
            dev.dietapp.data.domain.OutboxMessage(
                "w1", "гречка 200 г", false, TODAY, java.time.Instant.parse("2026-09-30T06:00:00Z"),
                dev.dietapp.data.domain.OutboxState.Queued, null, 0,
            ),
        )
        show()
        compose.onNodeWithTag("message").assertIsDisplayed()
        compose.onNodeWithTag("retry").assertDoesNotExist()
        compose.onNodeWithTag("failed-note").assertDoesNotExist()
    }

    private val beet = dev.dietapp.data.domain.DishPart("свёкла", 40.0, dev.dietapp.data.domain.Per100(43.0, 1.6, 0.2, 9.6))
    private val beef = dev.dietapp.data.domain.DishPart("говядина", 50.0, dev.dietapp.data.domain.Per100(250.0, 26.0, 15.0, 0.0))
    private fun dish() = entry("d1", name = "борщ", grams = 90.0, kcal = 142.2).copy(parts = listOf(beet, beef))

    @Test fun `a dish has a chevron that opens its ingredients, and a tap on one changes its grams`() {
        diary.entries.value = listOf(dish())
        show()
        compose.onNodeWithTag("parts").assertDoesNotExist()
        compose.onNodeWithTag("parts-toggle", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("parts").assertIsDisplayed()
        compose.onAllNodesWithTag("part").assertCountEquals(2)
        compose.onAllNodesWithTag("part")[0].assertTextContains("свёкла", substring = true)
        compose.onAllNodesWithTag("part")[1].assertTextContains("50 г", substring = true)

        compose.onAllNodesWithTag("part")[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("part-grams").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("50")).performTextReplacement("75")
        compose.onNodeWithTag("part-done").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(Triple("d1", 1, 75.0)), diary.partChanges)
        compose.onNodeWithTag("part-grams").assertDoesNotExist()
    }

    @Test fun `the chevron folds the ingredients again`() {
        diary.entries.value = listOf(dish())
        show()
        compose.onNodeWithTag("parts-toggle", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("parts").assertIsDisplayed()
        compose.onNodeWithTag("parts-toggle", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("parts").assertDoesNotExist()
    }

    @Test fun `a plain food has no chevron, and a tap on the dish itself still opens it for editing`() {
        diary.entries.value = listOf(entry("a1", name = "гречка"), dish())
        show()
        compose.onAllNodesWithTag("parts-toggle", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("entry")[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor").assertIsDisplayed()
    }

    @Test fun `the summary opens on what the watch said about the day`() {
        diary.facts.value = dev.dietapp.data.domain.DayFacts(
            TODAY, sleepMinutes = 440, steps = 8412,
            workouts = listOf(dev.dietapp.data.domain.Workout(dev.dietapp.data.domain.WorkoutKind.Running, 45)),
        )
        show()
        compose.onNodeWithTag("facts", useUnmergedTree = true).assertDoesNotExist() // folded: the day stays quiet
        compose.onNodeWithTag("summary").performClick()
        compose.waitForIdle()
        for (expected in listOf("7 ч 20 м", formatInt(8412), "Бег", "45 мин")) {
            compose.onNodeWithTag("summary").assertTextContains(expected, substring = true)
        }
    }

    @Test fun `several sessions of one kind are one line of the watch block`() {
        val other = dev.dietapp.data.domain.WorkoutKind.Other
        diary.facts.value = dev.dietapp.data.domain.DayFacts(
            TODAY, sleepMinutes = null, steps = null,
            workouts = listOf(30, 25, 40, 30).map { dev.dietapp.data.domain.Workout(other, it) },
        )
        show()
        compose.onNodeWithTag("summary").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertTextContains("Тренировка ×4", substring = true)
        compose.onNodeWithTag("summary").assertTextContains("125 мин", substring = true)
    }

    /** The calories of the header as they are on screen this very frame. */
    private fun shownKcal(): Int {
        val texts = compose.onNodeWithTag("summary").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        return texts.first().text.substringBefore("/").filter(Char::isDigit).toInt()
    }

    @Test fun `a food that is recorded is counted into the total, and the count ends on the real number`() {
        show()
        compose.mainClock.autoAdvance = false
        diary.entries.value = listOf(entry("e1", kcal = 1420.0))
        compose.mainClock.advanceTimeBy(160)
        val during = shownKcal()
        org.junit.Assert.assertTrue("on its way, not there yet and not still at 0: $during", during in 1..1419)
        compose.mainClock.advanceTimeBy(1_500)
        org.junit.Assert.assertEquals(1420, shownKcal())
    }

    @Test fun `a food that is removed counts the total down`() {
        diary.entries.value = listOf(entry("e1", kcal = 1420.0))
        show()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(1420, shownKcal())
        compose.mainClock.autoAdvance = false
        diary.entries.value = emptyList()
        compose.mainClock.advanceTimeBy(160)
        val during = shownKcal()
        org.junit.Assert.assertTrue("on its way down: $during", during in 1..1419)
        compose.mainClock.advanceTimeBy(1_500)
        org.junit.Assert.assertEquals(0, shownKcal())
    }

    @Test fun `what is already in the day when the app opens is not counted up from nothing`() {
        diary.entries.value = listOf(entry("e1", kcal = 1420.0))
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(60)
        org.junit.Assert.assertEquals(1420, shownKcal())
    }

    @Test fun `another day shows its own total at once, it does not count from the day before`() {
        val yesterday = java.time.LocalDate.parse("2026-09-28")
        diary.entries.value = listOf(entry("today", kcal = 1420.0), entry("old", day = yesterday, kcal = 500.0))
        val vm = show()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(1420, shownKcal())
        compose.mainClock.autoAdvance = false
        vm.onSelectDay(yesterday)
        compose.mainClock.advanceTimeBy(60)
        org.junit.Assert.assertEquals(500, shownKcal())
    }

    @Test fun `the date sits in the summary, folded and open`() {
        show()
        compose.onNodeWithTag("summary").assertTextContains("30 сентября", substring = true)
        compose.onNodeWithTag("summary").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertTextContains("30 сентября", substring = true)
        compose.onNodeWithText(Texts.BACK_TO_TODAY).assertDoesNotExist() // today: nowhere to go back to
    }

    @Test fun `another day shows its own date and the way back to today`() {
        val yesterday = java.time.LocalDate.parse("2026-09-28")
        diary.entries.value = listOf(entry("old", day = yesterday, kcal = 500.0))
        val vm = show()
        vm.onSelectDay(yesterday)
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertTextContains("28 сентября", substring = true)
        compose.onNodeWithText(Texts.BACK_TO_TODAY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertTextContains("30 сентября", substring = true)
    }

    @Test fun `nothing from the watch is added to the goal, the line is calories over the goal`() {
        diary.entries.value = listOf(entry("e1", kcal = 1420.0))
        diary.facts.value = dev.dietapp.data.domain.DayFacts(TODAY, steps = 14619)
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertTextContains("${formatInt(1420)} / $goal ккал")
    }

    @Test fun `a day the watch said nothing about has no watch lines`() {
        show()
        compose.onNodeWithTag("summary").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("facts", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `a recorded food has the time it was recorded under its numbers, a food still waiting has none`() {
        diary.entries.value = listOf(
            entry("e1", name = "гречка", at = "2026-09-30T05:15:00Z"),
            entry("e2", name = "борщ", at = "2026-09-30T09:40:00Z").copy(pending = true),
        )
        show()
        val times = compose.onAllNodesWithTag("entry-time", useUnmergedTree = true)
        times.assertCountEquals(1)
        times[0].assertTextEquals(Texts.clock(java.time.Instant.parse("2026-09-30T05:15:00Z")))
    }

    @Test fun `the user's pick stays in the chat as their own message`() {
        val at = { s: Int -> java.time.Instant.parse("2026-09-30T06:00:%02dZ".format(s)) }
        diary.entries.value = listOf(entry("e1", "колбаса любительская", kcal = 240.0).copy(mealId = "m1"))
        diary.messages.value = listOf(dev.dietapp.data.domain.Message("m1", TODAY, "бутерброд с колбасой", false, at(0)))
        diary.notes.value = listOf(
            dev.dietapp.data.domain.Note(3, TODAY, dev.dietapp.data.domain.NoteKind.Answer, "Колбаса любительская Мясновъ · health-diet.ru", "e1", at(5), "m1", resolved = true),
        )
        show()
        compose.onNodeWithTag("entry").assertIsDisplayed()
        compose.onNodeWithTag("answer").assertIsDisplayed().assertTextContains("Мясновъ", substring = true)
    }

    @Test fun `a food swiped to the right is offered back for a moment`() {
        diary.entries.value = listOf(entry("e1", "гречка", kcal = 184.0))
        show()
        compose.onNodeWithTag("swipe").performTouchInput { swipeRight(startX = left + 10f, endX = right) }
        compose.waitForIdle()
        compose.onNodeWithTag("entry").assertDoesNotExist()
        compose.onNodeWithTag("undo").assertIsDisplayed()
        compose.onNodeWithTag("undo-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("entry").assertIsDisplayed()
        org.junit.Assert.assertTrue(diary.deletedEntries.isEmpty())
    }

    @Test fun `a short drag puts the food back where it was`() {
        diary.entries.value = listOf(entry("e1", "гречка", kcal = 184.0))
        show()
        compose.onNodeWithTag("swipe").performTouchInput { swipeRight(startX = left + 10f, endX = left + 40f, durationMillis = 600) }
        compose.waitForIdle()
        compose.onNodeWithTag("entry").assertIsDisplayed()
        compose.onNodeWithTag("undo").assertDoesNotExist()
    }

    @Test fun `an empty day explains what can be typed`() {
        show()
        compose.onNodeWithText(Texts.EMPTY_HINT).assertIsDisplayed()
    }
}
