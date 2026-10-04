package dev.dietapp.ui.foods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.Texts
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.FoodBase
import dev.dietapp.data.local.FoodHit
import dev.dietapp.data.local.FoodInput
import dev.dietapp.data.repo.FoodRepository
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A food being edited: everything stays text while the person types. [id] null means a new food. */
data class FoodEdit(
    val id: Long?,
    val name: String = "",
    val aliases: String = "",
    val kcal: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
    val note: String = "",
    /** The values were the model's estimate; saving changed numbers makes them the person's own. */
    val estimated: Boolean = false,
    val original: Per100? = null,
    // shown on the card of a food that already exists, not edited
    val createdAt: java.time.Instant? = null,
    val updatedAt: java.time.Instant? = null,
    val origin: String? = null,
    val url: String? = null,
) {
    val per100: Per100?
        get() {
            val v = listOf(kcal, protein, fat, carbs).map { it.replace(',', '.').toDoubleOrNull() ?: return null }
            return Per100(v[0], v[1], v[2], v[3])
        }
    val canSave get() = name.isNotBlank() && per100 != null
}

data class FoodsUiState(
    val query: String = "",
    /** The user's own foods (filtered by [query]). */
    val mine: List<Food> = emptyList(),
    /** Matches from the built-in table and the USDA catalog: read-only, can be copied into the user's base. */
    val builtIn: List<FoodHit> = emptyList(),
    val total: Int = 0,
    val editing: FoodEdit? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

private data class Local(val query: String = "", val editing: FoodEdit? = null, val message: String? = null, val isError: Boolean = false)

/**
 * The "База продуктов" screen: the same table the model fills from the chat, open for the person to look at and
 * change. Also moves the whole base in and out as a JSON file.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class FoodsViewModel @Inject constructor(private val repo: FoodRepository) : ViewModel(), FoodsActions {
    private val local = MutableStateFlow(Local())

    private val searched = local.map { it.query.trim() }.distinctUntilChanged().debounce(150).mapLatest { q ->
        if (q.isEmpty()) emptyList() else repo.search(q)
    }

    val state: StateFlow<FoodsUiState> = combine(local, repo.foods, flowOfEmptyThen(searched)) { l, foods, hits ->
        val q = l.query.trim()
        val keys = FoodBase.keyOf(q).split(' ').filter { it.isNotEmpty() }
        FoodsUiState(
            query = l.query,
            mine = if (q.isEmpty()) foods else foods.filter { f ->
                val words = FoodBase.keyOf((listOf(f.name) + f.aliases).joinToString(" "))
                keys.all { words.contains(it) }
            },
            builtIn = hits.filter { it.source != FoodHit.Source.Mine },
            total = foods.size,
            editing = l.editing,
            message = l.message,
            messageIsError = l.isError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodsUiState())

    override fun onQuery(text: String) = local.update { it.copy(query = text, message = null) }

    override fun onNew() = local.update { it.copy(editing = FoodEdit(id = null, name = it.query.trim()), message = null) }

    override fun onEdit(food: Food) = local.update {
        it.copy(
            editing = FoodEdit(
                id = food.id, name = food.name, aliases = food.aliases.joinToString(", "),
                kcal = num(food.per100.kcal), protein = num(food.per100.protein), fat = num(food.per100.fat), carbs = num(food.per100.carbs),
                note = food.note.orEmpty(), estimated = food.estimated, original = food.per100,
                createdAt = food.createdAt, updatedAt = food.updatedAt, origin = food.origin, url = food.url,
            ),
            message = null,
        )
    }

    /** A built-in food as the start of the person's own (to rename it, correct it, give it other names). */
    override fun onCopy(hit: FoodHit) = local.update {
        it.copy(
            editing = FoodEdit(
                id = null, name = hit.name,
                kcal = num(hit.per100.kcal), protein = num(hit.per100.protein), fat = num(hit.per100.fat), carbs = num(hit.per100.carbs),
                note = if (hit.source == FoodHit.Source.Usda) "USDA: ${hit.name}" else "",
            ),
            message = null,
        )
    }

    override fun onEditChange(edit: FoodEdit) = local.update { it.copy(editing = edit, message = null) }
    override fun onCancel() = local.update { it.copy(editing = null, message = null) }

    override fun onSave() {
        val edit = local.value.editing ?: return
        val per100 = edit.per100 ?: return
        val input = FoodInput(
            name = edit.name,
            per100 = per100,
            aliases = edit.aliases.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() },
            // the person looked at the numbers and changed them: they are no longer the model's guess
            estimated = edit.estimated && per100 == edit.original,
            note = edit.note,
        )
        viewModelScope.launch {
            repo.save(input, edit.id).fold(
                onSuccess = { local.update { it.copy(editing = null, message = Texts.SAVED, isError = false) } },
                onFailure = { e -> local.update { it.copy(message = e.message, isError = true) } },
            )
        }
    }

    override fun onDelete() {
        val id = local.value.editing?.id ?: return
        viewModelScope.launch {
            repo.delete(id).fold(
                onSuccess = { local.update { it.copy(editing = null, message = null) } },
                onFailure = { e -> local.update { it.copy(message = e.message, isError = true) } },
            )
        }
    }

    suspend fun exportJson(): String = repo.exportJson()

    fun onExported() = local.update { it.copy(message = Texts.FOODS_EXPORTED, isError = false) }

    fun onImport(text: String) {
        viewModelScope.launch {
            repo.importJson(text).fold(
                onSuccess = { r ->
                    val skipped = if (r.skipped.isEmpty()) "" else " ${Texts.FOODS_SKIPPED} ${r.skipped.joinToString("; ")}"
                    local.update { it.copy(message = "${Texts.FOODS_IMPORTED} ${r.added}, ${Texts.FOODS_UPDATED} ${r.updated}.$skipped", isError = r.skipped.isNotEmpty()) }
                },
                onFailure = { e -> local.update { it.copy(message = e.message, isError = true) } },
            )
        }
    }

    fun onFileFailed() = local.update { it.copy(message = Texts.FOODS_FILE_FAILED, isError = true) }

    private companion object {
        fun num(v: Double): String =
            if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

        fun <T> flowOfEmptyThen(flow: kotlinx.coroutines.flow.Flow<List<T>>) =
            kotlinx.coroutines.flow.merge(flowOf(emptyList()), flow)
    }
}
