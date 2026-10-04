package dev.dietapp.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.Texts
import dev.dietapp.coreui.formatGrams
import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.FoodChoice
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.OutboxState
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.WeightCommand
import dev.dietapp.data.local.Capabilities
import dev.dietapp.data.local.LocalSettings
import dev.dietapp.data.local.PHOTO_NEEDS_KEY
import dev.dietapp.data.repo.DiaryRepository
import dev.dietapp.data.repo.SpeechRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the main screen can do. The view model implements it; the screen only knows this interface. */
interface MainActions {
    fun onDraftChange(text: String)
    fun onSend()
    fun onToggleSummary()
    fun onEditStart(entryId: String)
    fun onEditName(text: String)
    fun onEditGrams(text: String)
    fun onEditCommit()
    fun onEditDelete()
    fun onWeightTap(weightId: String)
    fun onWeightDelete(weightId: String)
    fun onMessageTap(messageId: String)
    fun onNoteTap(noteId: Long)
    fun onBackToToday()
    fun onOpenSettings()
    fun onCloseSettings()
    fun onOpenCamera()
    fun onCloseCamera()
    fun onPhoto(jpeg: ByteArray)
    fun onCameraFailed()
    fun onVoiceListening()
    fun onVoiceText(text: String, final: Boolean)
    fun onVoiceRecording()
    fun onVoiceRecorded(audio: ByteArray, mime: String, filename: String)
    fun onVoiceStopped()
    fun onVoiceProblem(message: String)
    fun onProposalPick(index: Int)
    fun onProposalCustom()
    fun onProposalDraft(draft: ChoiceDraft)
    fun onProposalSave()
    fun onProposalSkip()
    fun onProposalBack()
    fun onConfirm(record: Boolean)
    fun onBaseChange(apply: Boolean)
    fun onQuestionSkip()
}

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
@HiltViewModel
class MainViewModel @Inject constructor(
    private val diary: DiaryRepository,
    private val speech: SpeechRepository,
    localSettings: LocalSettings,
    private val clock: Clock,
) : ViewModel(), MainActions {

    private val local = MutableStateFlow(today().let { Local(day = it, today = it) })
    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<MainUiState> = combine(
        local,
        local.map { it.day }.distinctUntilChanged().flatMapLatest { diary.observeDay(it) },
        diary.observeWeights(),
        diary.observeProfile(),
        localSettings.capabilities,
    ) { l, content, weights, profile, caps -> buildState(l, content, weights, profile, caps) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            buildState(local.value, DayContent.Empty, emptyList(), Profile(null, null)),
        )

    init {
        viewModelScope.launch { diary.confirmations.collect { _effects.tryEmit(UiEffect.Confirm) } }
    }

    private fun today(): LocalDate = LocalDate.now(clock)
    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
    private fun update(block: (Local) -> Local) = local.update(block)

    /** Call when the app comes to the foreground: new day? pick up what other devices did. */
    fun onForeground() {
        val today = today()
        update { if (it.today == today) it else it.copy(today = today, day = if (it.day == it.today) today else it.day) }
        diary.requestSync(pull = true)
    }

    // ---------- input ----------

    override fun onDraftChange(text: String) =
        update { it.copy(draft = text, draftFromVoice = it.draftFromVoice && text.isNotEmpty(), notice = null) }

    /** Text shared into the app: it goes into the input, it is not sent on its own. */
    fun onSharedText(text: String) {
        val clean = text.trim().take(MAX_SHARED)
        if (clean.isNotEmpty()) update { it.copy(draft = clean, draftFromVoice = false, screen = Screen.Main, notice = null) }
    }

    override fun onSend() {
        val l = local.value
        val text = l.draft.trim()
        if (text.isEmpty()) return
        update { it.copy(draft = "", draftFromVoice = false, notice = null, voiceBase = "") }
        viewModelScope.launch {
            val kg = WeightCommand.parse(text)
            if (kg != null) {
                diary.addWeight(l.day, kg, clock.instant())
                _effects.tryEmit(UiEffect.Confirm)
            } else {
                diary.sendMessage(text, null, l.day, now(), if (l.draftFromVoice) MessageSource.Voice else MessageSource.Text)
            }
        }
    }

    override fun onPhoto(jpeg: ByteArray) {
        val l = local.value
        val caption = l.draft.trim().takeIf { it.isNotEmpty() }
        update { it.copy(cameraOpen = false, draft = "", draftFromVoice = false, voiceBase = "") }
        viewModelScope.launch { diary.sendMessage(caption, jpeg, l.day, now(), MessageSource.Photo) }
    }

    override fun onOpenCamera() {
        if (uiState.value.photoNeedsKey) showNotice(PHOTO_NEEDS_KEY) else update { it.copy(cameraOpen = true, notice = null) }
    }
    override fun onCloseCamera() = update { it.copy(cameraOpen = false) }
    override fun onCameraFailed() {
        update { it.copy(cameraOpen = false) }
        showNotice(Texts.CAMERA_FAILED)
    }

    // ---------- voice ----------

    override fun onVoiceListening() = update { it.copy(voice = VoicePhase.Listening, voiceBase = it.draft, notice = null) }
    override fun onVoiceRecording() = update { it.copy(voice = VoicePhase.Recording, voiceBase = it.draft, notice = Texts.VOICE_RECORDING_HINT) }

    override fun onVoiceText(text: String, final: Boolean) = update {
        val joined = listOf(it.voiceBase.trim(), text.trim()).filter { part -> part.isNotEmpty() }.joinToString(" ")
        it.copy(
            draft = joined,
            draftFromVoice = joined.isNotEmpty(),
            voice = if (final) VoicePhase.Idle else it.voice,
            notice = null,
        )
    }

    override fun onVoiceStopped() = update { if (it.voice == VoicePhase.Transcribing) it else it.copy(voice = VoicePhase.Idle) }

    override fun onVoiceProblem(message: String) {
        update { it.copy(voice = VoicePhase.Idle) }
        showNotice(message)
    }

    override fun onVoiceRecorded(audio: ByteArray, mime: String, filename: String) {
        update { it.copy(voice = VoicePhase.Transcribing, notice = null) }
        viewModelScope.launch {
            speech.transcribe(audio, filename, mime, Locale.getDefault().language).fold(
                onSuccess = { text ->
                    if (text.isBlank()) onVoiceProblem(Texts.VOICE_NO_MATCH) else onVoiceText(text, final = true)
                },
                onFailure = { onVoiceProblem(it.message ?: Texts.VOICE_NO_MATCH) },
            )
        }
    }

    // ---------- summary and days ----------

    override fun onToggleSummary() = update { it.copy(summaryExpanded = !it.summaryExpanded) }

    fun onSelectDay(day: LocalDate) = update { it.copy(day = day, screen = Screen.Main, editing = null, weightActionsId = null) }

    override fun onBackToToday() = update { it.copy(day = it.today, editing = null, weightActionsId = null) }
    override fun onOpenSettings() = update { it.copy(screen = Screen.Settings, editing = null) }
    override fun onCloseSettings() = update { it.copy(screen = Screen.Main) }
    fun onOpenFoods() = update { it.copy(screen = Screen.Foods) }
    fun onCloseFoods() = update { it.copy(screen = Screen.Settings) }
    fun onOpenJournal() = update { it.copy(screen = Screen.Journal) }
    fun onCloseJournal() = update { it.copy(screen = Screen.Settings) }
    fun onOpenExport() = update { it.copy(screen = Screen.Export) }
    fun onCloseExport() = update { it.copy(screen = Screen.Settings) }

    // ---------- editing in place ----------

    /** A tap on a food opens it; a tap on the open one folds it back, keeping what was changed. */
    override fun onEditStart(entryId: String) {
        val open = local.value.editing
        if (open != null) onEditCommit()
        if (open?.entryId == entryId) return
        val entry = uiState.value.feed.filterIsInstance<FeedItem.EntryItem>().firstOrNull { it.entry.id == entryId }?.entry ?: return
        update {
            it.copy(editing = EditState(entryId, entry.name, formatGrams(entry.grams)), weightActionsId = null)
        }
    }

    override fun onEditName(text: String) = update { s -> s.copy(editing = s.editing?.copy(name = text)) }

    override fun onEditGrams(text: String) =
        update { s -> s.copy(editing = s.editing?.copy(grams = text.filter { c -> c.isDigit() || c == '.' || c == ',' })) }

    /** Folds the open food back, saving a changed name or amount. */
    override fun onEditCommit() {
        val edit = local.value.editing ?: return
        update { it.copy(editing = null) }
        val entry = uiState.value.feed.filterIsInstance<FeedItem.EntryItem>().firstOrNull { it.entry.id == edit.entryId }?.entry
        val grams = edit.grams.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 5000 }
        val changed = entry == null || (grams != null && grams != entry.grams) || (edit.name.isNotBlank() && edit.name.trim() != entry.name)
        if (!changed) return
        viewModelScope.launch {
            diary.updateEntry(edit.entryId, grams, edit.name)
            _effects.tryEmit(UiEffect.Confirm)
        }
    }

    override fun onEditDelete() {
        val edit = local.value.editing ?: return
        update { it.copy(editing = null) }
        viewModelScope.launch { diary.deleteEntry(edit.entryId) }
    }

    // ---------- weights, waiting messages, notes ----------

    override fun onWeightTap(weightId: String) =
        update { it.copy(weightActionsId = if (it.weightActionsId == weightId) null else weightId, editing = null) }

    override fun onWeightDelete(weightId: String) {
        update { it.copy(weightActionsId = null) }
        viewModelScope.launch { diary.deleteWeight(weightId) }
    }

    /** A failed message is taken back; a waiting one is nudged; a processed one has nothing to do. */
    override fun onMessageTap(messageId: String) {
        val pending = uiState.value.feed.filterIsInstance<FeedItem.MessageItem>().firstOrNull { it.message.id == messageId }?.pending ?: return
        viewModelScope.launch {
            if (pending.state == OutboxState.Failed) diary.discardOutbox(messageId) else diary.requestSync()
        }
    }

    override fun onNoteTap(noteId: Long) {
        val note = uiState.value.feed.filterIsInstance<FeedItem.NoteItem>().firstOrNull { it.note.id == noteId }?.note ?: return
        // the chat is a record: only an error report goes away when tapped
        if (note.kind == NoteKind.Error) viewModelScope.launch { diary.dismissNote(noteId) }
    }

    // ---------- confirming a food the model found ----------

    override fun onProposalPick(index: Int) {
        val p = uiState.value.proposal ?: return
        accept(p.noteId, p.choices.getOrNull(index) ?: return)
    }

    /** "свой вариант": start from the model's first option, the user corrects what they want. */
    override fun onProposalCustom() {
        val p = uiState.value.proposal ?: return
        val first = p.choices.first()
        fun n(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(Locale.ROOT, v)
        update {
            it.copy(
                customFor = p.noteId,
                custom = ChoiceDraft(first.name, n(first.per100.kcal), n(first.per100.protein), n(first.per100.fat), n(first.per100.carbs)),
            )
        }
    }

    override fun onProposalDraft(draft: ChoiceDraft) = update { it.copy(custom = draft) }

    /** Back from typing own values to the options. */
    override fun onProposalBack() = update { it.copy(customFor = null, custom = null) }

    override fun onProposalSave() {
        val p = uiState.value.proposal ?: return
        val draft = local.value.custom ?: return
        val per100 = draft.per100 ?: return
        accept(p.noteId, FoodChoice(draft.name.trim(), per100, source = Texts.PROPOSAL_OWN_SOURCE))
    }

    /** "пропустить": the entry stays without numbers; the food can still be added in the food base later. */
    override fun onProposalSkip() {
        val p = uiState.value.proposal ?: return
        update { it.copy(customFor = null, custom = null) }
        viewModelScope.launch { diary.skipProposal(p.noteId) }
    }

    /** The open question is not worth answering: it is closed, the next message is a message of its own. */
    override fun onQuestionSkip() {
        val q = uiState.value.question ?: return
        viewModelScope.launch { diary.skipQuestion(q.id) }
    }

    // ---------- a change to the food base that waits for a yes ----------

    /** [apply]: the agent's change to the food base is made; otherwise it is dropped. */
    override fun onBaseChange(apply: Boolean) {
        val c = uiState.value.baseChange ?: return
        if (local.value.baseChangeBusy != null) return
        update { it.copy(baseChangeBusy = c.noteId) }
        viewModelScope.launch {
            diary.answerBaseChange(c.noteId, apply).fold(
                onSuccess = { update { it.copy(baseChangeBusy = null) } },
                onFailure = { e ->
                    update { it.copy(baseChangeBusy = null) }
                    showNotice(e.message ?: Texts.BASE_CHANGE_FAILED)
                },
            )
        }
    }

    // ---------- "Записать?" ----------

    /** [record]: the waiting food is counted from now on; otherwise it is taken back. */
    override fun onConfirm(record: Boolean) {
        val c = uiState.value.confirm ?: return
        if (local.value.confirmBusy) return
        update { it.copy(confirmBusy = true, editing = null) }
        viewModelScope.launch {
            diary.recordPending(c.entries.map { it.id }, record)
            update { it.copy(confirmBusy = false) }
            if (record) _effects.tryEmit(UiEffect.Confirm)
        }
    }

    private fun accept(noteId: Long, choice: FoodChoice) {
        if (local.value.proposalBusy != null) return
        update { it.copy(proposalBusy = noteId) }
        viewModelScope.launch {
            diary.acceptProposal(noteId, choice).fold(
                onSuccess = {
                    update { it.copy(proposalBusy = null, customFor = null, custom = null) }
                    _effects.tryEmit(UiEffect.Confirm)
                },
                onFailure = { e ->
                    update { it.copy(proposalBusy = null) }
                    showNotice(e.message ?: Texts.CAMERA_FAILED)
                },
            )
        }
    }

    private fun showNotice(text: String) {
        update { it.copy(notice = text) }
        viewModelScope.launch {
            delay(NOTICE_MS)
            update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    private companion object {
        const val NOTICE_MS = 4_000L
        const val MAX_SHARED = 1_000
    }
}
