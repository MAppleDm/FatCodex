package dev.dietapp.ui.foods

import dev.dietapp.FakeFoods
import dev.dietapp.MainDispatcherRule
import dev.dietapp.Texts
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.FoodHit
import dev.dietapp.data.local.ImportResult
import dev.dietapp.failure
import java.time.Instant
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
class FoodsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val repo = FakeFoods()
    private val casein = Food(7, "казеиновый протеин", listOf("казеин"), Per100(360.0, 80.0, 1.5, 8.0), true, "типичная этикетка", Instant.EPOCH)
    private val bar = Food(8, "батончик", emptyList(), Per100(316.0, 30.0, 10.0, 26.0), false, null, Instant.EPOCH)

    private fun TestScope.viewModel(): Pair<FoodsViewModel, StateFlow<FoodsUiState>> {
        val vm = FoodsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test fun `lists the user's foods and filters them by any of their names`() = runTest {
        repo.foods.value = listOf(bar, casein)
        val (vm, state) = viewModel()
        assertEquals(2, state.value.total)
        vm.onQuery("Казеин")
        assertEquals(listOf("казеиновый протеин"), state.value.mine.map { it.name })
        vm.onQuery("шоколад")
        assertTrue(state.value.mine.isEmpty())
        assertEquals(2, state.value.total)
    }

    @Test fun `the built-in base is searched too, the user's own hits are not repeated`() = runTest {
        repo.hits = listOf(
            FoodHit("my:7", "казеиновый протеин", casein.per100, FoodHit.Source.Mine, true),
            FoodHit("usda:14063", "Beverages, protein powder whey based", Per100(352.0, 78.1, 1.6, 6.3), FoodHit.Source.Usda, false),
        )
        val (vm, state) = viewModel()
        vm.onQuery("протеин")
        advanceTimeBy(200)
        assertEquals(listOf("usda:14063"), state.value.builtIn.map { it.id })
        vm.onQuery("")
        advanceTimeBy(200)
        assertTrue(state.value.builtIn.isEmpty())
    }

    @Test fun `a new food starts from what was searched`() = runTest {
        val (vm, state) = viewModel()
        vm.onQuery("мой хлеб")
        vm.onNew()
        val edit = state.value.editing!!
        assertNull(edit.id)
        assertEquals("мой хлеб", edit.name)
        assertFalse("no numbers yet", edit.canSave)
        vm.onEditChange(edit.copy(kcal = "231", protein = "9", fat = "3", carbs = "41,5", aliases = "хлеб домашний, мой"))
        vm.onSave()
        val (input, id) = repo.saved.single()
        assertNull(id)
        assertEquals(41.5, input.per100.carbs, 0.0)
        assertEquals(listOf("хлеб домашний", "мой"), input.aliases)
        assertFalse(input.estimated)
        assertNull(state.value.editing)
        assertEquals(Texts.SAVED, state.value.message)
    }

    @Test fun `the model's estimate stays an estimate until its numbers are changed`() = runTest {
        repo.foods.value = listOf(casein)
        val (vm, state) = viewModel()
        vm.onEdit(casein)
        assertEquals("1.5", state.value.editing!!.fat)
        vm.onEditChange(state.value.editing!!.copy(aliases = "казеин, мицеллярный казеин"))
        vm.onSave()
        assertTrue("only the names changed", repo.saved.last().first.estimated)
        assertEquals(7L, repo.saved.last().second)

        vm.onEdit(casein)
        vm.onEditChange(state.value.editing!!.copy(kcal = "372"))
        vm.onSave()
        assertFalse("the person corrected the numbers", repo.saved.last().first.estimated)
    }

    @Test fun `a refused save keeps the editor open with the reason`() = runTest {
        repo.saveResult = failure("Белков, жиров и углеводов вместе больше 100 г на 100 г.")
        val (vm, state) = viewModel()
        vm.onNew()
        vm.onEditChange(state.value.editing!!.copy(name = "x", kcal = "400", protein = "60", fat = "30", carbs = "30"))
        vm.onSave()
        assertTrue(state.value.messageIsError)
        assertEquals("x", state.value.editing!!.name)
    }

    @Test fun `a built-in food can be copied into the user's base`() = runTest {
        val (vm, state) = viewModel()
        vm.onCopy(FoodHit("usda:14063", "Beverages, protein powder whey based", Per100(352.0, 78.1, 1.6, 6.3), FoodHit.Source.Usda, false))
        val edit = state.value.editing!!
        assertNull(edit.id)
        assertEquals("78.1", edit.protein)
        assertEquals("USDA: Beverages, protein powder whey based", edit.note)
    }

    @Test fun `deleting from the editor`() = runTest {
        repo.foods.value = listOf(casein, bar)
        val (vm, state) = viewModel()
        vm.onEdit(casein)
        vm.onDelete()
        assertEquals(listOf("батончик"), state.value.mine.map { it.name })
        assertNull(state.value.editing)
    }

    @Test fun `importing reports what happened, skipped rows included`() = runTest {
        repo.importResult = Result.success(ImportResult(2, 1, listOf("пустое: Нужно название.")))
        val (vm, state) = viewModel()
        vm.onImport("{...}")
        assertEquals("{...}", repo.imported)
        assertEquals("Добавлено: 2, обновлено: 1. Пропущено: пустое: Нужно название.", state.value.message)
        assertTrue(state.value.messageIsError)
    }

    @Test fun `a file that is not a food base is refused with the reason`() = runTest {
        repo.importResult = Result.failure(dev.dietapp.data.net.AppError("Это не файл базы продуктов (нужен JSON, как при экспорте)."))
        val (vm, state) = viewModel()
        vm.onImport("garbage")
        assertTrue(state.value.message!!.startsWith("Это не файл базы продуктов"))
    }

    @Test fun `the card of an existing food knows when and how it came`() = runTest {
        val web = casein.copy(createdAt = Instant.parse("2026-10-02T06:30:00Z"), updatedAt = Instant.parse("2026-10-02T07:00:00Z"),
            origin = "web", url = "https://health-diet.ru/x")
        repo.foods.value = listOf(web)
        val (vm, state) = viewModel()
        vm.onEdit(web)
        val e = state.value.editing!!
        assertEquals(Instant.parse("2026-10-02T06:30:00Z"), e.createdAt)
        assertEquals("web", e.origin)
        assertEquals("https://health-diet.ru/x", e.url)
        assertEquals("типичная этикетка", e.note)
    }
}
