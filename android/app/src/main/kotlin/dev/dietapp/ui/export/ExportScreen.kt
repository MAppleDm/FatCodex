package dev.dietapp.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.TextAction
import dev.dietapp.data.export.ExportFile
import dev.dietapp.data.export.ExportFormat
import dev.dietapp.data.export.ExportPeriod
import dev.dietapp.data.repo.ExportRepository
import dev.dietapp.ui.shareTextFile
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportUiState(
    val period: ExportPeriod = ExportPeriod.Month,
    /** "Файл сохранён." / why there is nothing to export. */
    val message: String? = null,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class ExportViewModel @Inject constructor(private val exports: ExportRepository) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state

    fun onPeriod(period: ExportPeriod) = _state.update { it.copy(period = period, message = null) }

    /** The file for the chosen period; null, with the reason shown, when there is nothing in it. */
    suspend fun build(format: ExportFormat): ExportFile? = exports.export(format, _state.value.period).fold(
        onSuccess = { it },
        onFailure = { e ->
            _state.update { it.copy(message = e.message ?: Texts.FOODS_FILE_FAILED, messageIsError = true) }
            null
        },
    )

    fun onSaved(ok: Boolean) =
        _state.update { it.copy(message = if (ok) Texts.EXPORT_SAVED else Texts.FOODS_FILE_FAILED, messageIsError = !ok) }

    fun onShareFailed() = onSaved(false)
}

/** The screen with its two ways to get a file out: save it where the user picks, or attach it to a message. */
@Composable
fun ExportRoute(vm: ExportViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // the file is built before the system asks where to put it, so an empty period never leaves an empty file behind
    @Composable
    fun saver(format: ExportFormat): () -> Unit {
        val pending = remember { arrayOfNulls<ExportFile>(1) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
            val file = pending[0]
            pending[0] = null
            if (uri == null || file == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file.text.toByteArray(Charsets.UTF_8)) }
                    }
                }.isSuccess
                vm.onSaved(ok)
            }
        }
        return {
            scope.launch {
                vm.build(format)?.let {
                    pending[0] = it
                    launcher.launch(it.name)
                }
            }
        }
    }

    val savers = ExportFormat.entries.associateWith { saver(it) }
    ExportScreen(
        state = state,
        onBack = onBack,
        onPeriod = vm::onPeriod,
        onSave = { savers.getValue(it)() },
        onShare = { format ->
            scope.launch {
                val file = vm.build(format) ?: return@launch
                runCatching { shareTextFile(context, file.name, file.mime, file.text, Texts.EXPORT_SUBJECT, Texts.EXPORT) }
                    .onFailure { vm.onShareFailed() }
            }
        },
    )
}

@Composable
fun ExportScreen(
    state: ExportUiState,
    onBack: () -> Unit,
    onPeriod: (ExportPeriod) -> Unit,
    onSave: (ExportFormat) -> Unit,
    onShare: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.EXPORT, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp))
        }
        Hairline()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(Texts.EXPORT_HINT, style = AppTheme.type.caption, modifier = Modifier.padding(16.dp))
            Text(Texts.EXPORT_PERIOD, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            ExportPeriod.entries.forEach { period ->
                StatRow(
                    Texts.exportPeriod(period), if (period == state.period) "●" else "", Modifier.testTag("period-${period.tag}"),
                    onClick = { onPeriod(period) },
                )
            }
            Hairline(Modifier.padding(top = 8.dp))
            Text(Texts.EXPORT_FORMAT, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            ExportFormat.entries.forEach { format ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Texts.exportFormat(format), style = AppTheme.type.body)
                        Text(Texts.exportFormatHint(format), style = AppTheme.type.caption)
                    }
                    TextAction(Texts.EXPORT_SAVE, { onSave(format) }, Modifier.testTag("export-save-${format.name.lowercase()}"), color = AppTheme.colors.secondary)
                    TextAction(Texts.EXPORT_SHARE, { onShare(format) }, Modifier.testTag("export-share-${format.name.lowercase()}"), color = AppTheme.colors.secondary)
                }
            }
            state.message?.let {
                Text(
                    it,
                    style = AppTheme.type.caption.copy(color = if (state.messageIsError) AppTheme.colors.error else AppTheme.colors.secondary),
                    modifier = Modifier.padding(16.dp).testTag("export-message"),
                )
            }
        }
    }
}
