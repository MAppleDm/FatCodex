package dev.dietapp.ui.journal

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.TextAction
import dev.dietapp.data.local.Journal
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The diagnostics journal: what was sent to the model, what came back, and why something did not work. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class JournalViewModel @Inject constructor(private val journal: Journal) : ViewModel() {
    val text: StateFlow<String> = journal.version
        .debounce(200)
        .map { journal.read().takeLast(MAX_SHOWN) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun clear() = journal.clear()

    fun full(): String = journal.read()

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** "Журнал сохранён." / "Не удалось записать файл." after saving. */
    val message: StateFlow<String?> = _message

    fun onSaved(ok: Boolean) {
        _message.value = if (ok) Texts.JOURNAL_SAVED else Texts.FOODS_FILE_FAILED
    }

    companion object {
        /** fatcodex-journal-2026-10-02-0930.txt */
        fun fileName(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String =
            "fatcodex-journal-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".txt"

        /** A phone screen does not need more; copy and share take the whole file. */
        const val MAX_SHOWN = 60_000
    }
}

/** The screen with its two ways to get a file out: save it where the user picks, or attach it to a message. */
@Composable
fun JournalRoute(vm: JournalViewModel, onBack: () -> Unit) {
    val text by vm.text.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val full = withContext(Dispatchers.IO) { vm.full() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(full.toByteArray(Charsets.UTF_8)) }
                }
            }.isSuccess
            vm.onSaved(ok)
        }
    }
    JournalScreen(
        text = text,
        message = message,
        onBack = onBack,
        onSave = { save.launch(JournalViewModel.fileName()) },
        onShare = {
            scope.launch {
                val full = withContext(Dispatchers.IO) { vm.full() }
                runCatching { shareFile(context, full) }.onFailure { vm.onSaved(false) }
            }
        },
        onClear = vm::clear,
    )
}

@Composable
fun JournalScreen(
    text: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.JOURNAL, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp).weight(1f))
            TextAction(Texts.JOURNAL_SAVE, onSave, Modifier.testTag("journal-save"), color = AppTheme.colors.secondary)
            TextAction(Texts.JOURNAL_SHARE, onShare, Modifier.testTag("journal-share"), color = AppTheme.colors.secondary)
            TextAction(Texts.JOURNAL_CLEAR, onClear, Modifier.testTag("journal-clear"), color = AppTheme.colors.secondary)
        }
        Hairline()
        message?.let { Text(it, style = AppTheme.type.caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("journal-message")) }
        if (text.isBlank()) {
            Text(Texts.JOURNAL_EMPTY, style = AppTheme.type.secondary, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val vertical = rememberScrollState()
        // newest at the bottom: open there
        LaunchedEffect(text.length) { vertical.scrollTo(vertical.maxValue) }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Text(
                text,
                style = AppTheme.type.monoSecondary.copy(fontSize = 11.sp, lineHeight = 15.sp),
                modifier = Modifier.verticalScroll(vertical).padding(12.dp).testTag("journal"),
            )
        }
    }
}

/**
 * The journal as a .txt attachment (a messenger would cut long text into pieces): written to the app's cache and handed
 * out through the FileProvider declared in the manifest, readable only by the app the user picks.
 */
private fun shareFile(context: Context, text: String) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // only the latest one is ever needed
    val file = File(dir, JournalViewModel.fileName()).apply { writeText(text, Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, Texts.JOURNAL_SUBJECT)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(file.name, uri) // lets the share sheet itself read it, for the preview
    val chooser = Intent.createChooser(send, Texts.JOURNAL)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, dev.dietapp.MainActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
