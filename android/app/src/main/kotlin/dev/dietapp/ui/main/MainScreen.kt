package dev.dietapp.ui.main

import dev.dietapp.data.domain.Lang
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.DayLine
import dev.dietapp.coreui.DaySummary
import dev.dietapp.coreui.DetailLine
import dev.dietapp.coreui.EntryEditor
import dev.dietapp.coreui.EntryRow
import dev.dietapp.coreui.FeedNote
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.InputBar
import dev.dietapp.coreui.MoreIcon
import dev.dietapp.coreui.NoteTone
import dev.dietapp.coreui.MessageRow
import dev.dietapp.coreui.ProgressLine
import dev.dietapp.coreui.Sparkline
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.formatInt
import dev.dietapp.coreui.formatKg
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.OutboxState
import dev.dietapp.data.net.OFFLINE_MESSAGE
import dev.dietapp.ui.camera.CameraCapture
import dev.dietapp.voice.VoiceController
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.dietapp.coreui.tap
import androidx.compose.ui.text.style.TextOverflow
import dev.dietapp.coreui.UnderlineField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import dev.dietapp.coreui.formatGrams

private val DayFormat get() = DateTimeFormatter.ofPattern(if (Lang.en) "MMMM d" else "d MMMM", Lang.current.locale)

private class VoiceHolder { var controller: VoiceController? = null }

/**
 * The one screen: day summary on top, the feed in the middle, the input at the bottom.
 * [effects] carries the haptic confirmations; everything else is plain state.
 */
@Composable
fun MainScreen(state: MainUiState, actions: MainActions, effects: Flow<UiEffect>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(effects) { effects.collect { haptic.performHapticFeedback(HapticFeedbackType.Confirm) } }

    // ---- permissions and the two kinds of media input ----
    val voiceHolder = remember { VoiceHolder() }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceHolder.controller?.start() else actions.onVoiceProblem(Texts.VOICE_DENIED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) actions.onOpenCamera() else actions.onVoiceProblem(Texts.CAMERA_DENIED)
    }
    val serverSpeech by rememberUpdatedState(!state.capabilities.local)
    val voice = remember {
        VoiceController(context, actions, serverSpeech = { serverSpeech }) { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            .also { voiceHolder.controller = it }
    }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }

    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    Box(modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.Top) {
                DaySummary(
                    kcal = state.totals.kcal.roundToInt(),
                    goal = state.goal,
                    protein = state.totals.protein.roundToInt(),
                    fat = state.totals.fat.roundToInt(),
                    carbs = state.totals.carbs.roundToInt(),
                    expanded = state.summaryExpanded,
                    onToggle = actions::onToggleSummary,
                    modifier = Modifier.weight(1f).testTag("summary"),
                ) { SummaryDetails(state) }
                IconAction(actions::onOpenSettings, Texts.CD_SETTINGS, Modifier.padding(top = 4.dp, end = 4.dp)) { MoreIcon(it) }
            }
            Hairline()

            if (!state.isToday) {
                DayLine(state.day.format(DayFormat), Texts.BACK_TO_TODAY, actions::onBackToToday)
            }

            Feed(state, actions, Modifier.weight(1f))

            state.proposal?.let { ProposalPanel(it, actions) }
            state.baseChange?.let { BaseChangePanel(it, actions) }
            state.question?.let { QuestionPanel(it.text, actions) }
            state.confirm?.let { ConfirmPanel(it, actions) }
            state.notice?.let { FeedNote(it, NoteTone.Info, Modifier.testTag("notice")) }
            InputBar(
                value = state.draft,
                onValueChange = actions::onDraftChange,
                onSend = actions::onSend,
                onMic = {
                    if (granted(Manifest.permission.RECORD_AUDIO)) voice.toggle(state.voice) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onCamera = {
                    // no key (local mode): onOpenCamera explains instead, so do not ask for a permission first
                    if (state.photoNeedsKey || granted(Manifest.permission.CAMERA)) actions.onOpenCamera()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                listening = state.voice == VoicePhase.Listening || state.voice == VoicePhase.Recording,
                placeholder = if (state.voice == VoicePhase.Transcribing) "…" else Texts.INPUT_PLACEHOLDER,
                modifier = Modifier.navigationBarsPadding(),
            )
        }

        if (state.cameraOpen) {
            CameraCapture(onPhoto = actions::onPhoto, onFailed = actions::onCameraFailed, onClose = actions::onCloseCamera)
        }
    }
}

@Composable
private fun SummaryDetails(state: MainUiState) {
    val t = state.totals
    val goal = state.goal
    val kcal = t.kcal.roundToInt()
    if (goal != null) {
        ProgressLine(kcal.toFloat() / goal)
        Spacer(Modifier.height(8.dp))
        val left = goal - kcal
        // neutral wording either way: going over a goal is information, not a failure
        DetailLine(if (left >= 0) Texts.LEFT else Texts.OVER, "${formatInt(abs(left))} ${Texts.KCAL}")
    }
    DetailLine(Texts.PROTEIN, "${formatInt(t.protein)} ${Texts.GRAMS}")
    DetailLine(Texts.FAT, "${formatInt(t.fat)} ${Texts.GRAMS}")
    DetailLine(Texts.CARBS, "${formatInt(t.carbs)} ${Texts.GRAMS}")
    state.weight?.let { w ->
        Spacer(Modifier.height(8.dp))
        DetailLine(Texts.WEIGHT, "${formatKg(w.latestKg)} ${Texts.KG} · ${Texts.TREND} ${formatKg(w.trendKg)}")
        Sparkline(w.raw, w.trend, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Feed(state: MainUiState, actions: MainActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    // follow new lines at the bottom, but do not yank the list around while a row is being edited
    LaunchedEffect(state.feed.size, state.feed.lastOrNull()?.key) {
        if (state.feed.isNotEmpty() && state.editing == null) listState.scrollToItem(state.feed.lastIndex)
    }
    if (state.feed.isEmpty() && state.isToday) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomStart) {
            Text(Texts.EMPTY_HINT, style = AppTheme.type.secondary.copy(color = AppTheme.colors.tertiary))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag("feed"), state = listState, verticalArrangement = Arrangement.Top) {
        items(state.feed, key = { it.key }) { item ->
            // No fade-out: a removed row that fades while the list scrolls to the bottom can stay behind as a ghost,
            // drawn over the row that took its place (seen on a device: the answered question over its answer).
            val animate = Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(120), fadeOutSpec = null)
            when (item) {
                is FeedItem.EntryItem -> EntryLine(item, state.editing, actions, animate)
                is FeedItem.WeightItem -> Column(animate) {
                    StatRow(
                        label = Texts.WEIGHT_LABEL,
                        value = "${formatKg(item.weight.kg)} ${Texts.KG}",
                        extra = item.trendKg?.let { "${Texts.TREND} ${formatKg(it)}" },
                        onClick = { actions.onWeightTap(item.weight.id) },
                    )
                    if (item.actionsOpen) {
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            Spacer(Modifier.weight(1f))
                            TextAction(Texts.DELETE, { actions.onWeightDelete(item.weight.id) }, color = AppTheme.colors.error)
                        }
                    }
                }
                is FeedItem.MessageItem -> Column(animate) {
                    val m = item.message
                    val p = item.pending
                    val failed = p?.state == OutboxState.Failed
                    MessageRow(
                        text = listOfNotNull(Texts.PHOTO.takeIf { m.hasImage }, m.text).joinToString(" · "),
                        status = when {
                            p == null -> null
                            failed -> Texts.STATUS_FAILED
                            p.error == OFFLINE_MESSAGE -> Texts.STATUS_OFFLINE
                            p.attempts > 0 -> Texts.STATUS_RETRY
                            else -> Texts.STATUS_WAITING
                        },
                        isError = failed,
                        onClick = { actions.onMessageTap(m.id) },
                        modifier = Modifier.testTag("message"),
                    )
                    if (failed && p.error != null) {
                        FeedNote("${p.error} ${Texts.TAP_TO_REMOVE}", NoteTone.Error, onClick = { actions.onMessageTap(m.id) })
                    }
                }
                // the user's answer to a question sits on their side of the chat, like a message
                is FeedItem.NoteItem -> if (item.note.kind == NoteKind.Answer) {
                    MessageRow(item.note.text, status = null, isError = false, modifier = animate.testTag("answer"), newTurn = false) {}
                } else {
                    FeedNote(
                        text = item.note.text,
                        tone = when (item.note.kind) {
                            NoteKind.Question, NoteKind.Proposal, NoteKind.BaseChange -> NoteTone.Question
                            NoteKind.Error -> NoteTone.Error
                            NoteKind.Info, NoteKind.Answer -> NoteTone.Info
                        },
                        modifier = animate.testTag(
                            when (item.note.kind) {
                                NoteKind.Question -> "question"
                                NoteKind.Proposal -> "proposal-note"
                                else -> "note"
                            },
                        ),
                        onClick = { actions.onNoteTap(item.note.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryLine(item: FeedItem.EntryItem, editing: EditState?, actions: MainActions, modifier: Modifier) {
    val e = item.entry
    if (editing?.entryId != e.id) {
        EntryRow(
            name = e.name,
            grams = e.grams,
            kcal = e.kcal,
            approximate = e.status == EntryStatus.Uncertain,
            pending = e.pending,
            note = item.summary,
            onClick = { actions.onEditStart(e.id) },
            modifier = modifier.testTag("entry"),
        )
        return
    }
    // the numbers follow the grams being typed, from the values per 100 g
    val grams = editing.grams.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: e.grams
    val p = e.per100
    val kcal = if (p != null) p.kcal * grams / 100 else e.kcal
    val approximate = e.status == EntryStatus.Uncertain
    EntryEditor(
        header = {
            EntryRow(
                name = editing.name.ifBlank { e.name }, grams = grams, kcal = kcal, approximate = approximate, pending = e.pending,
                onClick = { actions.onEditStart(e.id) }, modifier = Modifier.testTag("entry"),
            )
        },
        facts = when {
            p != null -> facts(p.kcal * grams / 100, p.protein * grams / 100, p.fat * grams / 100, p.carbs * grams / 100, approximate)
            e.kcal != null -> facts(e.kcal!!, e.protein ?: 0.0, e.fat ?: 0.0, e.carbs ?: 0.0, approximate)
            else -> null
        },
        source = when {
            p != null -> listOfNotNull(
                "${Texts.ENTRY_PER100}: ${num(p.kcal)} ${Texts.KCAL} · ${Texts.P} ${num(p.protein)} · ${Texts.F} ${num(p.fat)} · ${Texts.C} ${num(p.carbs)}",
                e.foodName?.takeIf { !it.equals(e.name, ignoreCase = true) },
            ).joinToString(" · ")
            e.kcal == null -> Texts.ENTRY_UNKNOWN
            else -> null
        },
        history = { if (item.history.isNotEmpty()) EntryHistory(item.history) },
        name = editing.name,
        grams = editing.grams,
        onName = actions::onEditName,
        onGrams = actions::onEditGrams,
        onDone = actions::onEditCommit,
        onDelete = actions::onEditDelete,
        modifier = modifier.testTag("editor"),
    )
}

/**
 * The question that pops up above the input when the model found values for a food: one tap on an option puts it into
 * the food base and fills in the entry; "свой вариант" lets the user type their own; "пропустить" leaves it.
 */
@Composable
private fun ProposalPanel(p: ProposalUi, actions: MainActions) {
    val t = AppTheme.type
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("proposal")) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
            Box(Modifier.width(1.dp).height(20.dp).background(AppTheme.colors.foreground))
            Spacer(Modifier.width(10.dp))
            Text(p.question, style = t.body)
        }
        val custom = p.custom
        if (custom == null) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                p.choices.forEachIndexed { i, c ->
                    Row(
                        Modifier.fillMaxWidth().tap(enabled = !p.busy) { actions.onProposalPick(i) }
                            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("choice"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = t.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val source = c.source ?: c.url?.let(::hostOf) ?: if (c.estimated) Texts.PROPOSAL_TYPICAL else null
                            Text(
                                listOfNotNull("${Texts.P} ${num(c.per100.protein)} · ${Texts.F} ${num(c.per100.fat)} · ${Texts.C} ${num(c.per100.carbs)}", source)
                                    .joinToString(" · "),
                                style = t.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("${if (c.estimated) "~" else ""}${formatInt(c.per100.kcal)} ${Texts.KCAL}", style = t.mono)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction(Texts.PROPOSAL_OWN, actions::onProposalCustom, Modifier.testTag("proposal-own"))
                Spacer(Modifier.weight(1f))
                TextAction(if (p.busy) "…" else Texts.PROPOSAL_SKIP, actions::onProposalSkip, Modifier.testTag("proposal-skip"),
                    color = AppTheme.colors.secondary)
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                UnderlineField(custom.name, { actions.onProposalDraft(custom.copy(name = it)) }, Modifier.testTag("own-name"),
                    placeholder = Texts.FOODS_NAME, imeAction = ImeAction.Next)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OwnNumber(Texts.KCAL, custom.kcal, "own-kcal", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(kcal = it)) }
                    OwnNumber(Texts.P, custom.protein, "own-protein", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(protein = it)) }
                    OwnNumber(Texts.F, custom.fat, "own-fat", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(fat = it)) }
                    OwnNumber(Texts.C, custom.carbs, "own-carbs", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(carbs = it)) }
                }
                Text(Texts.FOODS_PER100, style = t.caption, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextAction(Texts.CANCEL, actions::onProposalBack, Modifier.testTag("own-back"), color = AppTheme.colors.secondary)
                TextAction(
                    if (p.busy) "…" else Texts.PROPOSAL_SAVE, actions::onProposalSave, Modifier.testTag("own-save"),
                    color = if (custom.canSave) AppTheme.colors.foreground else AppTheme.colors.tertiary,
                )
            }
        }
    }
}

/**
 * What was asked about a food and what the user answered, inside the opened entry: like the folded steps of an
 * agent's turn, there when you look, out of the way otherwise.
 */
@Composable
private fun EntryHistory(lines: List<HistoryLine>) {
    val t = AppTheme.type
    val c = AppTheme.colors
    Column(Modifier.padding(top = 12.dp).testTag("entry-history")) {
        Text(Texts.ENTRY_HISTORY, style = t.caption.copy(color = c.tertiary))
        lines.forEach { l ->
            when (l.from) {
                HistoryLine.From.User -> Text(l.text, style = t.caption.copy(color = c.foreground, textAlign = TextAlign.End),
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 6.dp))
                HistoryLine.From.AppAsks -> Row(Modifier.padding(top = 6.dp)) {
                    Box(Modifier.width(1.dp).height(16.dp).background(c.secondary))
                    Spacer(Modifier.width(8.dp))
                    Text(l.text, style = t.caption)
                }
                HistoryLine.From.App -> Text(l.text, style = t.caption, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * The agent wants to delete or change one of the user's foods and may not without a yes: the question says what and
 * how the values would change, "да" makes the change, "нет" leaves the food as it is.
 */
@Composable
private fun BaseChangePanel(c: BaseChangeUi, actions: MainActions) {
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("base-change")) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
            Box(Modifier.width(1.dp).height(20.dp).background(AppTheme.colors.foreground))
            Spacer(Modifier.width(10.dp))
            Column {
                val lines = c.question.lines()
                Text(lines.first(), style = AppTheme.type.body)
                lines.drop(1).forEach { Text(it, style = AppTheme.type.caption) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(Texts.BASE_CHANGE_NO, { actions.onBaseChange(false) }, Modifier.testTag("base-change-no"), color = AppTheme.colors.secondary)
            Spacer(Modifier.weight(1f))
            TextAction(if (c.busy) "…" else Texts.BASE_CHANGE_YES, { actions.onBaseChange(true) }, Modifier.testTag("base-change-yes"))
        }
    }
}

/** The app's open question, above the input: the next message is its answer, or "пропустить" closes it. */
@Composable
private fun QuestionPanel(text: String, actions: MainActions) {
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("question-panel")) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            Box(Modifier.width(1.dp).height(20.dp).background(AppTheme.colors.foreground))
            Spacer(Modifier.width(10.dp))
            Text(text, style = AppTheme.type.body)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.QUESTION_HINT, style = AppTheme.type.caption, modifier = Modifier.weight(1f))
            TextAction(Texts.SKIP, actions::onQuestionSkip, Modifier.testTag("question-skip"), color = AppTheme.colors.secondary)
        }
    }
}

/**
 * "Записать?" above the input: the day's food that waits for the user, with what each item holds. Tapping an item
 * opens it in the feed for correcting; "записать" counts it, "не записывать" takes it back.
 */
@Composable
private fun ConfirmPanel(c: ConfirmUi, actions: MainActions) {
    val t = AppTheme.type
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("confirm")) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
            Box(Modifier.width(1.dp).height(20.dp).background(AppTheme.colors.foreground))
            Spacer(Modifier.width(10.dp))
            Text(Texts.CONFIRM_QUESTION, style = t.body)
        }
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            c.entries.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().tap { actions.onEditStart(e.id) }.padding(horizontal = 16.dp, vertical = 6.dp).testTag("confirm-item"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = t.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (e.kcal == null) Texts.ENTRY_UNKNOWN
                            else "${Texts.P} ${num1(e.protein)} · ${Texts.F} ${num1(e.fat)} · ${Texts.C} ${num1(e.carbs)}",
                            style = t.caption,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("${formatGrams(e.grams)} ${Texts.GRAMS}", style = t.monoSecondary)
                    Text(
                        e.kcal?.let { (if (e.status == EntryStatus.Uncertain) "~" else "") + formatInt(it) } ?: "—",
                        style = t.mono, textAlign = TextAlign.End, modifier = Modifier.width(60.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(Texts.CONFIRM_DROP, { actions.onConfirm(false) }, Modifier.testTag("confirm-drop"), color = AppTheme.colors.secondary)
            Spacer(Modifier.weight(1f))
            TextAction(if (c.busy) "…" else Texts.CONFIRM_RECORD, { actions.onConfirm(true) }, Modifier.testTag("confirm-record"))
        }
    }
}

/** "316 ккал · Б 11,6 · Ж 1,8 · У 61,8" */
private fun facts(kcal: Double, protein: Double, fat: Double, carbs: Double, approximate: Boolean): String =
    "${if (approximate) "~" else ""}${formatInt(kcal)} ${Texts.KCAL} · ${Texts.P} ${num1(protein)} · ${Texts.F} ${num1(fat)} · ${Texts.C} ${num1(carbs)}"

/** One decimal at most, none when it is whole: "11,6", "2". */
private fun num1(v: Double?): String = num(Math.round((v ?: 0.0) * 10) / 10.0)

@Composable
private fun OwnNumber(label: String, value: String, tag: String, modifier: Modifier, onChange: (String) -> Unit) {
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

private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
