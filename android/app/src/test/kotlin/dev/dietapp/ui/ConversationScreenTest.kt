package dev.dietapp.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.FakeDiary
import dev.dietapp.FakeFoods
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.TODAY
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.domain.Message
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.entry
import dev.dietapp.ui.foods.FoodsRoute
import dev.dietapp.ui.foods.FoodsViewModel
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainViewModel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun `the user's message and the app's answer are separate lines that never overlap`() {
        val diary = FakeDiary()
        val at = Instant.parse("2026-09-30T05:00:00Z")
        diary.messages.value = listOf(Message("m1", TODAY, "казеиновый протеин 30 грамм с водой", false, at))
        diary.entries.value = listOf(entry("e1", name = "казеиновый протеин", grams = 30.0, kcal = 108.0, at = "2026-09-30T05:00:00Z").copy(mealId = "m1"))
        diary.notes.value = listOf(Note(1, TODAY, NoteKind.Info, "Добавил в базу: казеиновый протеин", null, at.plusSeconds(4), "m1"))
        val vm = MainViewModel(diary, FakeSpeech(), FakeLocalSettings(local = true, modelKey = true), TEST_CLOCK)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.uiState.collectAsState()
                MainScreen(state, vm, vm.effects)
            }
        }
        compose.waitForIdle()

        val message = compose.onNodeWithTag("message").assertIsDisplayed().getUnclippedBoundsInRoot()
        val entryRow = compose.onNodeWithTag("entry").assertIsDisplayed().getUnclippedBoundsInRoot()
        val note = compose.onNodeWithTag("note").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("message above its entry: $message / $entryRow", message.bottom <= entryRow.top)
        assertTrue("entry above the note: $entryRow / $note", entryRow.bottom <= note.top)
        val text = compose.onNodeWithText("казеиновый протеин 30 грамм с водой", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val answer = compose.onNodeWithText("казеиновый протеин", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the message is set off on the right: $text vs $answer", text.left >= Dp(56f) && text.left > answer.left)
    }

    /** The text field inside an underlined field tagged [tag]. */
    private fun type(tag: String, text: String) =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performTextInput(text)

    @Test fun `the food base screen adds a food`() {
        val repo = FakeFoods()
        val vm = FoodsViewModel(repo)
        compose.setContent { AppTheme(darkTheme = false) { FoodsRoute(vm, onBack = {}) } }

        compose.onNodeWithText(Texts.FOODS_EMPTY).assertIsDisplayed()
        compose.onNodeWithTag("add").performClick()
        type("food-name", "казеиновый протеин")
        type("food-kcal", "360")
        type("food-protein", "80")
        type("food-fat", "1,5")
        type("food-carbs", "8")
        compose.onNodeWithTag("food-save").performClick()
        compose.waitForIdle()

        assertEquals("казеиновый протеин", repo.saved.single().first.name)
        assertEquals(1, compose.onAllNodesWithTag("food").fetchSemanticsNodes().size)
        compose.onNodeWithText("~", substring = true).assertDoesNotExist() // the person's own numbers, not an estimate
    }

    @Test fun `the proposal panel lists the options and one tap confirms`() {
        val diary = FakeDiary()
        val choices = listOf(
            dev.dietapp.data.domain.FoodChoice("Сосиски Рубленые [Ремит]", dev.dietapp.data.domain.Per100(190.0, 13.0, 15.0, 1.0), "health-diet.ru", "https://health-diet.ru/x"),
            dev.dietapp.data.domain.FoodChoice("сосиски", dev.dietapp.data.domain.Per100(266.0, 11.0, 24.0, 1.5), null, null, estimated = true),
        )
        diary.notes.value = listOf(Note(7, TODAY, NoteKind.Proposal, "Нашёл сосиски Ремит. Занести в базу?", null,
            Instant.parse("2026-09-30T05:00:00Z"), choices = choices))
        val vm = MainViewModel(diary, FakeSpeech(), FakeLocalSettings(local = true, modelKey = true), TEST_CLOCK)
        compose.setContent {
            AppTheme(darkTheme = false) {
                val state by vm.uiState.collectAsState()
                MainScreen(state, vm, vm.effects)
            }
        }
        compose.onNodeWithTag("proposal").assertIsDisplayed()
        compose.onNodeWithText("health-diet.ru", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("~266 ккал", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("choice")[0].performClick()
        compose.waitForIdle()
        assertEquals(7L, diary.accepted.single().first)
        compose.onNodeWithTag("proposal").assertDoesNotExist()
    }
}
