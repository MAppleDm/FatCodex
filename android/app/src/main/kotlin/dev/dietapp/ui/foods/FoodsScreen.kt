package dev.dietapp.ui.foods

import dev.dietapp.data.domain.Lang
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.UnderlineField
import dev.dietapp.coreui.formatInt
import dev.dietapp.coreui.tap
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.Per100
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the food base screen can do; the view model implements it. */
interface FoodsActions {
    fun onQuery(text: String)
    fun onNew()
    fun onEdit(food: Food)
    fun onEditChange(edit: FoodEdit)
    fun onSave()
    fun onDelete()
    fun onCancel()
}

/** The screen with its file pickers: export writes a JSON file the person chooses, import reads one. */
@Composable
fun FoodsRoute(vm: FoodsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = vm.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.isSuccess
            if (ok) vm.onExported() else vm.onFileFailed()
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrNull()
            if (text == null) vm.onFileFailed() else vm.onImport(text)
        }
    }
    FoodsScreen(
        state = state,
        actions = vm,
        onBack = onBack,
        onExport = { export.launch(EXPORT_NAME) },
        onImport = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    )
}

private const val EXPORT_NAME = "dietapp-foods.json"

/** The user's own foods (what the agent added with their yes and what they added themselves), searchable and editable. */
@Composable
fun FoodsScreen(
    state: FoodsUiState,
    actions: FoodsActions,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp)) {
            IconAction(if (state.editing != null) actions::onCancel else onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.FOODS, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp).weight(1f))
            if (state.editing == null) {
                TextAction(Texts.FOODS_IMPORT, onImport, Modifier.testTag("import"), color = AppTheme.colors.secondary)
                TextAction(Texts.FOODS_EXPORT, onExport, Modifier.testTag("export"), color = AppTheme.colors.secondary)
            }
        }
        Hairline()
        val editing = state.editing
        if (editing != null) {
            FoodEditor(editing, state, actions)
        } else {
            FoodList(state, actions)
        }
    }
}

@Composable
private fun Message(state: FoodsUiState) {
    state.message?.let {
        Text(
            it,
            style = AppTheme.type.caption.copy(color = if (state.messageIsError) AppTheme.colors.error else AppTheme.colors.secondary),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("foods-message"),
        )
    }
}

@Composable
private fun FoodList(state: FoodsUiState, actions: FoodsActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
        UnderlineField(
            state.query, actions::onQuery, Modifier.weight(1f).testTag("foods-search"),
            placeholder = Texts.FOODS_SEARCH, imeAction = ImeAction.Search,
        )
        TextAction(Texts.FOODS_ADD, actions::onNew, Modifier.testTag("add"))
    }
    Message(state)
    LazyColumn(Modifier.fillMaxWidth().testTag("foods")) {
        item { Caption("${Texts.FOODS_MINE} · ${state.total}") }
        if (state.mine.isEmpty()) {
            item {
                Text(
                    if (state.total == 0) Texts.FOODS_EMPTY else Texts.FOODS_NOTHING_FOUND,
                    style = AppTheme.type.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(state.mine, key = { "my:${it.id}" }) { food ->
            FoodLine(food.name, food.per100, food.estimated, Modifier.testTag("food"), onClick = { actions.onEdit(food) })
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** "казеиновый протеин            360 ккал" over "Б 80 · Ж 1,5 · У 8 на 100 г · оценка". */
@Composable
private fun FoodLine(name: String, per100: Per100, estimated: Boolean, modifier: Modifier = Modifier, muted: Boolean = false, onClick: () -> Unit) {
    val t = AppTheme.type
    Column(modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = if (muted) t.body.copy(color = AppTheme.colors.secondary) else t.body,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${if (estimated) "~" else ""}${formatInt(per100.kcal)} ${Texts.KCAL}", style = t.mono)
        }
        val tail = if (estimated) " · ${Texts.FOODS_ESTIMATED}" else ""
        Text("${Texts.P} ${num(per100.protein)} · ${Texts.F} ${num(per100.fat)} · ${Texts.C} ${num(per100.carbs)} ${Texts.FOODS_PER100}$tail", style = t.caption)
    }
}

@Composable
private fun FoodEditor(edit: FoodEdit, state: FoodsUiState, actions: FoodsActions) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("food-editor")) {
        UnderlineField(edit.name, { actions.onEditChange(edit.copy(name = it)) }, Modifier.testTag("food-name"),
            placeholder = Texts.FOODS_NAME, imeAction = ImeAction.Next)
        UnderlineField(edit.aliases, { actions.onEditChange(edit.copy(aliases = it)) }, Modifier.testTag("food-aliases"),
            placeholder = Texts.FOODS_ALIASES, imeAction = ImeAction.Next)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            NumberField(Texts.KCAL, edit.kcal, "food-kcal", Modifier.weight(1f)) { actions.onEditChange(edit.copy(kcal = it)) }
            NumberField(Texts.P, edit.protein, "food-protein", Modifier.weight(1f)) { actions.onEditChange(edit.copy(protein = it)) }
            NumberField(Texts.F, edit.fat, "food-fat", Modifier.weight(1f)) { actions.onEditChange(edit.copy(fat = it)) }
            NumberField(Texts.C, edit.carbs, "food-carbs", Modifier.weight(1f)) { actions.onEditChange(edit.copy(carbs = it)) }
        }
        val tail = if (edit.estimated) " · ${Texts.FOODS_ESTIMATED_HINT}" else ""
        Text("${Texts.FOODS_PER100}$tail", style = AppTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
        // "откуда цифры" can be a sentence or two: it wraps into a block instead of running off the screen
        Text(Texts.FOODS_NOTE_LABEL, style = AppTheme.type.caption, modifier = Modifier.padding(top = 12.dp))
        UnderlineField(edit.note, { actions.onEditChange(edit.copy(note = it)) }, Modifier.testTag("food-note"),
            placeholder = Texts.FOODS_NOTE, imeAction = ImeAction.Default, singleLine = false)
        if (edit.id != null) FoodFacts(edit)
        Message(state)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextAction(Texts.CANCEL, actions::onCancel, color = AppTheme.colors.secondary)
            if (edit.id != null) TextAction(Texts.DELETE, actions::onDelete, Modifier.testTag("food-delete"), color = AppTheme.colors.error)
            TextAction(Texts.SAVE, actions::onSave, Modifier.testTag("food-save"),
                color = if (edit.canSave) AppTheme.colors.foreground else AppTheme.colors.tertiary)
        }
    }
}

/** What the card of an existing food knows besides its values: when it came, from whom, from where. */
@Composable
private fun FoodFacts(edit: FoodEdit) {
    val t = AppTheme.type
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("food-facts")) {
            edit.createdAt?.let { Text("${Texts.FOODS_ADDED} ${Stamp.format(it)}" + (edit.origin?.let { o -> " · ${originLabel(o)}" } ?: ""), style = t.caption) }
            if (edit.updatedAt != null && edit.updatedAt != edit.createdAt) {
                Text("${Texts.FOODS_CHANGED} ${Stamp.format(edit.updatedAt)}", style = t.caption)
            }
            edit.url?.let { Text("${Texts.FOODS_SOURCE} $it", style = t.caption) }
            edit.id?.let { Text("id my:$it", style = t.caption.copy(color = AppTheme.colors.tertiary)) }
        }
    }
}

private val Stamp: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Lang.current.locale).withZone(ZoneId.systemDefault())

private fun originLabel(origin: String) = when (origin) {
    "model" -> Texts.ORIGIN_MODEL
    "web" -> Texts.ORIGIN_WEB
    "import" -> Texts.ORIGIN_IMPORT
    else -> Texts.ORIGIN_USER
}

@Composable
private fun NumberField(label: String, value: String, tag: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, style = AppTheme.type.caption)
        UnderlineField(
            value, { text -> onChange(text.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) }, Modifier.testTag(tag),
            keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next, mono = true,
        )
    }
}

private fun num(v: Double): String =
    if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v).let { if (Lang.en) it else it.replace('.', ',') }
