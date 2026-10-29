package dev.dietapp.ui.main

import dev.dietapp.data.domain.Lang
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.dietapp.Texts
import dev.dietapp.coreui.AppBubble
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.SwipeToDelete
import dev.dietapp.coreui.AppAppearance
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
import dev.dietapp.data.domain.Entry
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.OutboxState
import dev.dietapp.data.net.OFFLINE_MESSAGE
import dev.dietapp.ui.camera.CameraCapture
import dev.dietapp.ui.sound.LocalAddedSound
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
    val sound = LocalAddedSound.current
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                UiEffect.Confirm -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                UiEffect.Added -> sound.play()
            }
        }
    }

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
            val coal = AppTheme.appearance == AppAppearance.Coal
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = if (coal) 12.dp else 0.dp, vertical = if (coal) 8.dp else 0.dp)
                    .clip(RoundedCornerShape(if (coal) 16.dp else 0.dp))
                    .background(AppTheme.colors.summary),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("FatCodex", style = AppTheme.type.secondary.copy(color = AppTheme.colors.foreground), modifier = Modifier.weight(1f))
                    IconAction(actions::onOpenSettings, Texts.CD_SETTINGS) { MoreIcon(it) }
                }
                DaySummary(
                    kcal = state.totals.kcal.roundToInt(),
                    goal = state.goal,
                    protein = state.totals.protein.roundToInt(),
                    fat = state.totals.fat.roundToInt(),
                    carbs = state.totals.carbs.roundToInt(),
                    expanded = state.summaryExpanded,
                    onToggle = actions::onToggleSummary,
                    modifier = Modifier.testTag("summary"),
                    date = state.day.format(DayFormat),
                    // another day, or the first load, shows its numbers at once; only a change of this day's total is counted
                    resetKey = state.day to state.loaded,
                ) { shownKcal -> SummaryDetails(state, shownKcal) }
            }
            if (!coal) Hairline()

            if (!state.isToday) {
                DayLine(Texts.BACK_TO_TODAY, actions::onBackToToday)
            }

            Feed(state, actions, Modifier.weight(1f))

            // what the app asks is in the chat, where it asked it; above the input only "записать?" and the undo of a swipe
            state.confirm?.let { ConfirmPanel(it, actions) }
            state.undo?.let { UndoBar(it, actions) }
            state.notice?.let { Text(it, style = AppTheme.type.secondary, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("notice")) }
            InputBar(
                value = state.draft,
                onValueChange = actions::onDraftChange,
                onSend = actions::onSend,
                onMic = {
                    if (granted(Manifest.permission.RECORD_AUDIO)) voice.toggle(state.voice) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onCamera = {
                    // no key (local mode): onOpenCamera explains instead, so do not ask for a permission first
                    if (state.needsKey || granted(Manifest.permission.CAMERA)) actions.onOpenCamera()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                listening = state.voice == VoicePhase.Listening || state.voice == VoicePhase.Recording,
                placeholder = if (state.voice == VoicePhase.Transcribing) "…" else Texts.INPUT_PLACEHOLDER,
                modifier = Modifier.navigationBarsPadding(),
            )
        }

        if (state.cameraOpen) {
            CameraCapture(
                onPhoto = actions::onPhoto, onFailed = actions::onCameraFailed, onGalleryFailed = actions::onGalleryFailed,
                onClose = actions::onCloseCamera,
            )
        }
    }
}

@Composable
private fun SummaryDetails(state: MainUiState, kcal: Int) {
    // the macros are already the second line of the header; here only what the header does not say.
    // [kcal] is the header's count, so the line and "Осталось" move with the number above them.
    state.goal?.let { goal ->
        ProgressLine(kcal.toFloat() / goal)
        Spacer(Modifier.height(8.dp))
        val left = goal - kcal
        // neutral wording either way: going over a goal is information, not a failure
        DetailLine(if (left >= 0) Texts.LEFT else Texts.OVER, "${formatInt(abs(left))} ${Texts.KCAL}")
    }
    state.weight?.let { w ->
        Spacer(Modifier.height(10.dp))
        // one weigh-in has no trend and nothing to draw yet
        val many = w.raw.size > 1
        DetailLine(Texts.WEIGHT, "${formatKg(w.latestKg)} ${Texts.KG}" + if (many) " · ${Texts.TREND} ${formatKg(w.trendKg)}" else "")
        if (many) Sparkline(w.raw, w.trend, Modifier.padding(top = 4.dp))
    }
    state.facts?.takeIf { !it.isEmpty }?.let { f ->
        Spacer(Modifier.height(10.dp))
        Text(Texts.WATCH, style = AppTheme.type.caption, modifier = Modifier.testTag("facts").padding(bottom = 2.dp))
        f.sleepMinutes?.let { DetailLine(Texts.FACT_SLEEP, Texts.sleepText(it)) }
        f.steps?.let { DetailLine(Texts.FACT_STEPS, formatInt(it)) }
        // several sessions of one kind are one line: "Тренировка ×4  120 мин"
        f.workouts.groupBy { it.kind }.forEach { (kind, sessions) ->
            DetailLine(Texts.workoutName(kind, sessions.size), "${sessions.sumOf { it.minutes }} ${Texts.MIN}")
        }
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
                is FeedItem.EntryItem -> EntryLine(item, state, actions, animate)
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
                    val step = p?.agent
                    val thumb = remember(item.thumb) {
                        item.thumb?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    }
                    MessageRow(
                        // with its picture in the chat a photo needs no word for it
                        text = listOfNotNull(Texts.PHOTO.takeIf { m.hasImage && thumb == null }, m.text).joinToString(" · "),
                        thumbnail = thumb,
                        thumbnailDescription = Texts.PHOTO,
                        status = when {
                            p == null -> null
                            failed -> null // the red line under the message says it
                            step != null -> null // being read right now: the line under the message says what the agent does
                            p.error == OFFLINE_MESSAGE -> Texts.STATUS_OFFLINE
                            p.attempts > 0 -> Texts.STATUS_RETRY
                            else -> Texts.STATUS_WAITING
                        },
                        isError = failed,
                        progress = step?.let(Texts::agentStep),
                        onClick = { actions.onMessageTap(m.id) },
                        modifier = Modifier.testTag("message"),
                    )
                    if (failed) {
                        // the agent could not read it: one line of why, and the way to have it read again. A short reason and the
                        // buttons share a line; a long one (a refused key) takes its own and the buttons go below it.
                        FlowRow(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            p?.error?.let {
                                Text(
                                    it, style = AppTheme.type.secondary.copy(color = AppTheme.colors.error),
                                    modifier = Modifier.padding(end = 8.dp).testTag("failed-note"),
                                )
                            }
                            TextAction(Texts.RETRY, { actions.onMessageRetry(m.id) }, Modifier.testTag("retry"), color = AppTheme.colors.error)
                            TextAction(Texts.REMOVE, { actions.onMessageDiscard(m.id) }, Modifier.testTag("discard"), color = AppTheme.colors.secondary)
                        }
                    }
                }
                is FeedItem.NoteItem -> when {
                    // the user's pick sits on their side of the chat, like a message
                    item.note.kind == NoteKind.Answer ->
                        MessageRow(item.note.text, status = null, isError = false, modifier = animate.testTag("answer"), newTurn = false) {}
                    item.proposal != null -> ProposalCard(item.proposal, actions, animate)
                    item.baseChange != null -> BaseChangeCard(item.baseChange, actions, animate)
                    item.open -> QuestionCard(item.note.text, actions, animate)
                    else -> FeedNote(
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
private fun EntryLine(item: FeedItem.EntryItem, state: MainUiState, actions: MainActions, modifier: Modifier) {
    val e = item.entry
    val editing = state.editing
    if (editing?.entryId != e.id) {
        val open = e.parts.isNotEmpty() && e.id in state.expandedParts
        // a swipe to the right deletes the food; "вернуть" above the input brings it back for a few seconds
        SwipeToDelete(Texts.DELETE, { actions.onEntrySwipe(e.id) }, modifier) { Column {
            EntryRow(
                name = e.name,
                grams = e.grams,
                kcal = e.kcal,
                approximate = e.status == EntryStatus.Uncertain,
                pending = e.pending,
                time = Texts.clock(e.eatenAt),
                expanded = if (e.parts.isEmpty()) null else open,
                onToggle = { actions.onPartsToggle(e.id) },
                onClick = { actions.onEditStart(e.id) },
                modifier = Modifier.testTag("entry"),
            )
            if (open) PartsList(e, state.partEdit?.takeIf { it.entryId == e.id }, actions)
        } }
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
 * The ingredients of a dish that was eaten, under its row: what and how much of each. A tap on one lets its grams be changed
 * (more meat, no sour cream); the dish weighs what its ingredients now weigh and its numbers follow.
 */
@Composable
private fun PartsList(e: Entry, edit: PartEdit?, actions: MainActions) {
    val t = AppTheme.type
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, bottom = 6.dp).testTag("parts")) {
        e.parts.forEachIndexed { i, p ->
            if (edit?.index == i) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = t.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    UnderlineField(
                        edit.grams, actions::onPartGrams, Modifier.width(76.dp).testTag("part-grams"), placeholder = Texts.GRAMS,
                        keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done, onImeAction = actions::onPartCommit, mono = true,
                    )
                    Text(" ${Texts.GRAMS}", style = t.monoSecondary)
                    TextAction(Texts.PART_DONE, actions::onPartCommit, Modifier.testTag("part-done"))
                }
            } else {
                Row(Modifier.fillMaxWidth().tap { actions.onPartEditStart(e.id, i) }.padding(vertical = 5.dp).testTag("part"), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = t.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text("${formatGrams(p.grams)} ${Texts.GRAMS}", style = t.monoSecondary)
                    Text(formatInt(p.kcal), style = t.monoSecondary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                }
            }
        }
    }
}

/**
 * Values the agent found for a food, in the chat where it offered them: one tap on an option puts it into the food base
 * and fills in the entry; "свой вариант" lets the user type their own; "пропустить" leaves it. Several can be open at once
 * (a receipt), each answered on its own. Once answered only the question stays, with the pick after it.
 */
@Composable
private fun ProposalCard(p: ProposalUi, actions: MainActions, modifier: Modifier) {
    val t = AppTheme.type
    val c = AppTheme.colors
    AppBubble(modifier.testTag("proposal"), wide = true) {
        Text(p.question, style = t.body)
        val custom = p.custom
        if (custom == null) {
            p.choices.forEachIndexed { i, choice ->
                Hairline(Modifier.padding(top = if (i == 0) 8.dp else 0.dp))
                Row(
                    Modifier.fillMaxWidth().tap(enabled = !p.busy) { actions.onProposalPick(p.noteId, i) }
                        .padding(vertical = 8.dp).testTag("choice"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(choice.name, style = t.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val source = choice.source ?: choice.url?.let(::hostOf) ?: if (choice.estimated) Texts.PROPOSAL_TYPICAL else null
                        Text(
                            listOfNotNull("${Texts.P} ${num(choice.per100.protein)} · ${Texts.F} ${num(choice.per100.fat)} · ${Texts.C} ${num(choice.per100.carbs)}", source)
                                .joinToString(" · "),
                            style = t.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (choice.parts.isNotEmpty()) {
                            Text(Texts.dishOf(choice.parts), style = t.caption, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("choice-parts"))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${if (choice.estimated) "~" else ""}${formatInt(choice.per100.kcal)}", style = t.mono.copy(color = c.recorded))
                        Text("${Texts.KCAL}/100 ${Texts.GRAMS}", style = t.caption)
                    }
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextAction(Texts.PROPOSAL_OWN, { actions.onProposalCustom(p.noteId) }, Modifier.testTag("proposal-own"))
                Spacer(Modifier.weight(1f))
                TextAction(if (p.busy) "…" else Texts.PROPOSAL_SKIP, { actions.onProposalSkip(p.noteId) }, Modifier.testTag("proposal-skip"), color = c.secondary)
            }
        } else {
            UnderlineField(custom.name, { actions.onProposalDraft(custom.copy(name = it)) }, Modifier.testTag("own-name"),
                placeholder = Texts.FOODS_NAME, imeAction = ImeAction.Next)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                OwnNumber(Texts.KCAL, custom.kcal, "own-kcal", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(kcal = it)) }
                OwnNumber(Texts.P, custom.protein, "own-protein", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(protein = it)) }
                OwnNumber(Texts.F, custom.fat, "own-fat", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(fat = it)) }
                OwnNumber(Texts.C, custom.carbs, "own-carbs", Modifier.weight(1f)) { actions.onProposalDraft(custom.copy(carbs = it)) }
            }
            Text(Texts.FOODS_PER100, style = t.caption, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction(Texts.CANCEL, actions::onProposalBack, Modifier.testTag("own-back"), color = c.secondary)
                TextAction(
                    if (p.busy) "…" else Texts.PROPOSAL_SAVE, actions::onProposalSave, Modifier.testTag("own-save"),
                    color = if (custom.canSave) c.foreground else c.tertiary,
                )
            }
        }
    }
}

/**
 * The agent wants to delete or change one of the user's foods and may not without a yes: the question says what and
 * how the values would change, "да" makes the change, "нет" leaves the food as it is.
 */
@Composable
private fun BaseChangeCard(b: BaseChangeUi, actions: MainActions, modifier: Modifier) {
    AppBubble(modifier.testTag("base-change"), wide = true) {
        val lines = b.question.lines()
        Text(lines.first(), style = AppTheme.type.body)
        lines.drop(1).forEach { Text(it, style = AppTheme.type.caption) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(Texts.BASE_CHANGE_NO, { actions.onBaseChange(b.noteId, false) }, Modifier.testTag("base-change-no"), color = AppTheme.colors.secondary)
            Spacer(Modifier.weight(1f))
            TextAction(if (b.busy) "…" else Texts.BASE_CHANGE_YES, { actions.onBaseChange(b.noteId, true) }, Modifier.testTag("base-change-yes"))
        }
    }
}

/** The app's open question, in the chat: the next message is its answer, or "пропустить" closes it. */
@Composable
private fun QuestionCard(text: String, actions: MainActions, modifier: Modifier) {
    Column(modifier.testTag("question-open")) {
        FeedNote(text, NoteTone.Question, Modifier.testTag("question"))
        Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.QUESTION_HINT, style = AppTheme.type.caption, modifier = Modifier.weight(1f))
            TextAction(Texts.SKIP, actions::onQuestionSkip, Modifier.testTag("question-skip"), color = AppTheme.colors.secondary)
        }
    }
}

/** "Удалено: гречка · вернуть" for the few seconds a swiped food can still come back. */
@Composable
private fun UndoBar(u: UndoUi, actions: MainActions) {
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("undo")) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.deleted(u.name), style = AppTheme.type.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextAction(Texts.UNDO, actions::onUndo, Modifier.testTag("undo-action"))
        }
    }
}

/**
 * "Записать?" above the input, one line: the foods themselves are in the chat right above (grey, a tap opens one to
 * correct it), so here only what they come to. "записать" counts them, "не записывать" takes them back.
 */
@Composable
private fun ConfirmPanel(c: ConfirmUi, actions: MainActions) {
    val t = AppTheme.type
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.background).testTag("confirm")) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Texts.CONFIRM_QUESTION, style = t.body)
                val known = c.entries.mapNotNull { it.kcal }
                val approximate = c.entries.any { it.status == EntryStatus.Uncertain || it.kcal == null }
                Text(
                    listOfNotNull(
                        c.entries.joinToString(", ") { it.name },
                        known.takeIf { it.isNotEmpty() }?.let { "${if (approximate) "~" else ""}${formatInt(it.sum())} ${Texts.KCAL}" },
                    ).joinToString(" · "),
                    style = t.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("confirm-what"),
                )
            }
            TextAction(Texts.CONFIRM_DROP, { actions.onConfirm(false) }, Modifier.testTag("confirm-drop"), color = AppTheme.colors.secondary)
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
