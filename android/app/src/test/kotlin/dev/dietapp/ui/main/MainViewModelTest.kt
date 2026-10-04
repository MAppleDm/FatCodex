package dev.dietapp.ui.main

import app.cash.turbine.test
import dev.dietapp.FakeDiary
import dev.dietapp.FakeLocalSettings
import dev.dietapp.FakeSpeech
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TEST_CLOCK
import dev.dietapp.TODAY
import dev.dietapp.Texts
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.OutboxMessage
import dev.dietapp.data.domain.OutboxState
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.local.Capabilities
import dev.dietapp.data.local.PHOTO_NEEDS_KEY
import dev.dietapp.data.net.AppError
import dev.dietapp.entry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
class MainViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val diary = FakeDiary()
    private val speech = FakeSpeech()
    private val localSettings = FakeLocalSettings()

    private fun TestScope.viewModel(clock: Clock = TEST_CLOCK): Pair<MainViewModel, StateFlow<MainUiState>> {
        val vm = MainViewModel(diary, speech, localSettings, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} } // uiState is WhileSubscribed: keep it alive
        return vm to vm.uiState
    }

    private fun outboxMessage(id: String, text: String = "суп", state: OutboxState = OutboxState.Queued, error: String? = null) =
        OutboxMessage(id, text, false, TODAY, Instant.parse("2026-09-30T06:00:00Z"), state, error, if (error == null) 0 else 1)

    // ---------- the day at a glance ----------

    @Test fun `starts on today with an empty feed and the goal from the profile`() = runTest {
        val (_, state) = viewModel()
        assertEquals(TODAY, state.value.day)
        assertTrue(state.value.isToday && state.value.isEmpty)
        assertEquals(1900, state.value.goal)
        assertEquals(0.0, state.value.totals.kcal, 0.0)
    }

    @Test fun `totals add up the day's entries and ignore entries without numbers`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = listOf(
            entry("a", kcal = 247.5, grams = 150.0).copy(protein = 46.5, fat = 5.4, carbs = 0.0),
            entry("b", kcal = 184.0),
            entry("c", name = "суши", kcal = null, status = EntryStatus.Unmatched),
            entry("other-day", day = LocalDate.parse("2026-09-29"), kcal = 999.0),
        )
        assertEquals(431.5, state.value.totals.kcal, 1e-9)
        assertEquals(53.3, state.value.totals.protein, 1e-9)
        assertEquals(3, state.value.feed.size)
    }

    @Test fun `feed is chronological and mixes food, weigh-ins, waiting messages and notes`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = listOf(entry("late", at = "2026-09-30T05:00:00Z"), entry("early", at = "2026-09-30T04:00:00Z"))
        diary.outbox.value = listOf(outboxMessage("p"))                                     // 06:00
        diary.notes.value = listOf(Note(1, TODAY, NoteKind.Info, "Нет связи с моделью.", null, Instant.parse("2026-09-30T06:30:00Z")))
        diary.addWeight(TODAY, 82.4, Instant.parse("2026-09-30T04:30:00Z"))
        assertEquals(listOf("e:early", "w:w1", "e:late", "m:p", "n:1"), state.value.feed.map { it.key })
    }

    @Test fun `text shared from another app goes into the input, it is not sent`() = runTest {
        val (vm, state) = viewModel()
        vm.onOpenSettings()
        vm.onSharedText("  казеиновый протеин 30 грамм с водой \n")
        assertEquals("казеиновый протеин 30 грамм с водой", state.value.draft)
        assertEquals(Screen.Main, state.value.screen)
        assertTrue(diary.sent.isEmpty())
        vm.onSharedText("   ")
        assertEquals("blank shares change nothing", "казеиновый протеин 30 грамм с водой", state.value.draft)
    }

    // ---------- confirming a food the model found ----------

    private val found = listOf(
        dev.dietapp.data.domain.FoodChoice("Сосиски Рубленые [Ремит]", dev.dietapp.data.domain.Per100(190.0, 13.0, 15.0, 1.0), "health-diet.ru", "https://health-diet.ru/x"),
        dev.dietapp.data.domain.FoodChoice("сосиски", dev.dietapp.data.domain.Per100(266.0, 11.0, 24.0, 1.5), "типичные значения", estimated = true),
    )

    private fun proposalNote(id: Long = 5, resolved: Boolean = false) = Note(
        id, TODAY, NoteKind.Proposal, "Нашёл сосиски Ремит. Занести в базу?", "e1", Instant.parse("2026-09-30T06:00:00Z"), "m1",
        resolved = resolved, choices = found,
    )

    @Test fun `an open proposal pops up above the input with its options`() = runTest {
        val (_, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        val p = state.value.proposal!!
        assertEquals(5L, p.noteId)
        assertEquals(listOf("Сосиски Рубленые [Ремит]", "сосиски"), p.choices.map { it.name })
        assertNull(p.custom)
    }

    @Test fun `picking an option confirms it and the panel goes away`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalPick(0)
        assertEquals(5L to found[0], diary.accepted.single())
        assertNull(state.value.proposal)
        assertTrue("the answered question is not a line of its own", state.value.feed.none { it is FeedItem.NoteItem && it.note.id == 5L })
    }

    @Test fun `own values start from the first option and are sent as the user's`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalCustom()
        val draft = state.value.proposal!!.custom!!
        assertEquals(ChoiceDraft("Сосиски Рубленые [Ремит]", "190", "13", "15", "1"), draft)
        vm.onProposalDraft(draft.copy(kcal = "185", carbs = "1,5"))
        vm.onProposalSave()
        val (_, choice) = diary.accepted.single()
        assertEquals(185.0, choice.per100.kcal, 0.0)
        assertEquals(1.5, choice.per100.carbs, 0.0)
        assertFalse("typed by the user: not an estimate", choice.estimated)
        assertNull(choice.url)
    }

    @Test fun `cancel in own values goes back to the options, it does not drop the proposal`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalCustom()
        vm.onProposalBack()
        assertNull(state.value.proposal!!.custom)
        assertTrue(diary.dismissedNotes.isEmpty())
    }

    @Test fun `skipping closes the proposal but keeps it in the chat`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalSkip()
        assertEquals(listOf(5L), diary.skipped)
        assertTrue(diary.dismissedNotes.isEmpty())
        assertNull(state.value.proposal)
        assertTrue(diary.accepted.isEmpty())
    }

    // ---------- "Записать?" and the feed as a chat ----------

    private fun waiting() = listOf(
        entry("e1", "сосиски ремит рубленые", grams = 50.0, kcal = 95.0).copy(mealId = "m1", pending = true),
        entry("e2", "макароны", grams = 200.0, kcal = 316.0).copy(mealId = "m0"),
    )

    @Test fun `food waiting for the user is shown, not counted, and asked about above the input`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = waiting()
        assertEquals(316.0, state.value.totals.kcal, 0.0)
        assertEquals(listOf("e1"), state.value.confirm!!.entries.map { it.id })
        assertEquals(2, state.value.feed.count { it is FeedItem.EntryItem })
    }

    @Test fun `a proposal is answered before the confirmation`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = waiting()
        diary.notes.value = listOf(proposalNote())
        assertTrue(state.value.proposal != null)
        assertNull(state.value.confirm)
    }

    @Test fun `record counts the food`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = waiting()
        vm.onConfirm(true)
        assertEquals(listOf(listOf("e1") to true), diary.recorded)
        assertNull(state.value.confirm)
        assertEquals(411.0, state.value.totals.kcal, 0.0)
    }

    @Test fun `not recording takes the food back`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = waiting()
        vm.onConfirm(false)
        assertEquals(listOf(listOf("e1") to false), diary.recorded)
        assertEquals(listOf("e2"), state.value.feed.filterIsInstance<FeedItem.EntryItem>().map { it.entry.id })
    }

    @Test fun `the whole conversation about a food folds into its entry`() = runTest {
        val (_, state) = viewModel()
        val at = { s: Int -> Instant.parse("2026-09-30T06:00:%02dZ".format(s)) }
        diary.entries.value = listOf(entry("e1", "колбаса любительская", kcal = 240.0).copy(mealId = "m1"))
        diary.messages.value = listOf(
            dev.dietapp.data.domain.Message("m1", TODAY, "бутерброд с колбасой", false, at(0)),
            // the answer to "Не нашёл «колбаса любительская»…": a message about that entry
            dev.dietapp.data.domain.Message("m2", TODAY, "это из магазина Мясновъ", false, at(3), aboutEntryId = "e1"),
        )
        diary.notes.value = listOf(
            Note(1, TODAY, NoteKind.Question, "Не нашёл «колбаса любительская». Что это точнее?", "e1", at(1), "m1", resolved = true),
            proposalNote(resolved = true).copy(id = 2, text = "Нашёл «Мясновъ». Подходит?", targetEntryId = "e1", createdAt = at(4), messageId = "m2"),
            Note(3, TODAY, NoteKind.Answer, "Колбаса любительская Мясновъ · health-diet.ru", "e1", at(5), "m2", resolved = true),
            Note(4, TODAY, NoteKind.Info, "Исправил: колбаса любительская — 80 г", "e1", at(6), "m2", resolved = true),
            Note(5, TODAY, NoteKind.Info, "В базе: …", null, at(7), "m2", resolved = true),
        )
        val feed = state.value.feed
        assertEquals("the first message stays, the answer folds", listOf("m1"), feed.filterIsInstance<FeedItem.MessageItem>().map { it.message.id })
        assertEquals("a line about no food in particular stays", listOf(5L), feed.filterIsInstance<FeedItem.NoteItem>().map { it.note.id })
        val item = feed.filterIsInstance<FeedItem.EntryItem>().single()
        assertEquals(
            listOf(HistoryLine.From.AppAsks, HistoryLine.From.User, HistoryLine.From.AppAsks, HistoryLine.From.User, HistoryLine.From.App),
            item.history.map { it.from },
        )
        assertEquals("это из магазина Мясновъ", item.history[1].text)
        assertEquals("Колбаса любительская Мясновъ · health-diet.ru", item.summary)
    }

    @Test fun `an open question is asked in the panel, and its answer shows while it is processed`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("e1", kcal = null).copy(mealId = "m1", pending = true))
        diary.notes.value = listOf(Note(1, TODAY, NoteKind.Question, "Что это точнее?", "e1", Instant.parse("2026-09-30T06:00:01Z"), "m1"))
        assertEquals(1L, state.value.question!!.id)
        assertTrue(state.value.feed.none { it is FeedItem.NoteItem })

        diary.messages.value = listOf(dev.dietapp.data.domain.Message("m2", TODAY, "роллы", false, Instant.parse("2026-09-30T06:00:05Z"), aboutEntryId = "e1"))
        diary.outbox.value = listOf(outboxMessage("m2"))
        assertTrue(state.value.feed.any { it is FeedItem.MessageItem && it.message.id == "m2" })

        vm.onQuestionSkip()
        assertEquals(listOf(1L), diary.skippedQuestions)
    }

    @Test fun `an open proposal is asked in the panel, not in the feed`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = listOf(entry("e1", kcal = null).copy(mealId = "m1", pending = true))
        diary.notes.value = listOf(proposalNote())
        assertTrue(state.value.feed.none { it is FeedItem.NoteItem })
        assertTrue(state.value.proposal != null)
    }

    @Test fun `a failed confirmation says why and keeps the options`() = runTest {
        diary.acceptResult = Result.failure(AppError("Это предложение уже неактуально.", "stale"))
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalPick(1)
        assertEquals("Это предложение уже неактуально.", state.value.notice)
        assertFalse(state.value.proposal!!.busy)
    }

    // ---------- the feed as a conversation ----------

    private fun message(id: String, text: String, at: String) = dev.dietapp.data.domain.Message(id, TODAY, text, false, Instant.parse(at))

    private fun note(id: Long, text: String, at: String, messageId: String?, kind: NoteKind = NoteKind.Info) =
        Note(id, TODAY, kind, text, null, Instant.parse(at), messageId)

    @Test fun `each message is followed by its own answer, never mixed with the next one`() = runTest {
        val (_, state) = viewModel()
        diary.messages.value = listOf(
            message("m1", "казеиновый протеин 30 грамм с водой", "2026-09-30T05:00:00Z"),
            message("m2", "казеина было 40", "2026-09-30T05:10:00Z"),
        )
        // the entries come from m1; the correction made by m2 changes them but they stay under m1
        diary.entries.value = listOf(
            entry("water", name = "вода", at = "2026-09-30T05:00:00Z").copy(mealId = "m1", position = 1),
            entry("casein", name = "казеиновый протеин", at = "2026-09-30T05:00:00Z").copy(mealId = "m1", position = 0),
        )
        diary.notes.value = listOf(
            note(1, "Добавил в базу: казеиновый протеин", "2026-09-30T05:00:04Z", "m1"),
            note(2, "Исправил: казеиновый протеин — 40 г", "2026-09-30T05:10:03Z", "m2"),
        )
        assertEquals(
            listOf("m:m1", "e:casein", "e:water", "n:1", "m:m2", "n:2"),
            state.value.feed.map { it.key },
        )
    }

    @Test fun `an answer that takes long still sits under its message, not after later things`() = runTest {
        val (_, state) = viewModel()
        diary.messages.value = listOf(message("m1", "суп", "2026-09-30T05:00:00Z"))
        diary.addWeight(TODAY, 82.4, Instant.parse("2026-09-30T05:01:00Z"))
        diary.notes.value = listOf(note(1, "Не нашёл в сообщении еды.", "2026-09-30T05:02:00Z", "m1", NoteKind.Info))
        assertEquals(listOf("m:m1", "n:1", "w:w1"), state.value.feed.map { it.key })
    }

    @Test fun `a waiting message shows its status, a processed one does not`() = runTest {
        val (_, state) = viewModel()
        diary.messages.value = listOf(message("p", "суп", "2026-09-30T06:00:00Z"), message("done", "банан", "2026-09-30T05:00:00Z"))
        diary.outbox.value = listOf(outboxMessage("p"))
        val items = state.value.feed.filterIsInstance<FeedItem.MessageItem>()
        assertEquals(listOf("done", "p"), items.map { it.message.id })
        assertNull(items[0].pending)
        assertEquals(OutboxState.Queued, items[1].pending!!.state)
    }

    @Test fun `weight trend and sparkline come from all weigh-ins, not just today's`() = runTest {
        val (_, state) = viewModel()
        listOf("2026-09-28" to 83.0, "2026-09-29" to 82.0, "2026-09-30" to 81.0).forEach { (d, kg) ->
            diary.addWeight(LocalDate.parse(d), kg, Instant.parse("${d}T05:00:00Z"))
        }
        val w = state.value.weight!!
        assertEquals(81.0, w.latestKg, 0.0)
        assertEquals(82.0, w.trendKg, 1e-9)
        assertEquals(listOf(83.0, 82.0, 81.0), w.raw)
        assertEquals(listOf(83.0, 82.5, 82.0), w.trend)
        val weightRow = state.value.feed.filterIsInstance<FeedItem.WeightItem>().single()
        assertEquals(82.0, weightRow.trendKg!!, 1e-9)
    }

    @Test fun `no weigh-ins means no weight section`() = runTest {
        assertNull(viewModel().second.value.weight)
    }

    @Test fun `summary expands and collapses`() = runTest {
        val (vm, state) = viewModel()
        assertFalse(state.value.summaryExpanded)
        vm.onToggleSummary()
        assertTrue(state.value.summaryExpanded)
        vm.onToggleSummary()
        assertFalse(state.value.summaryExpanded)
    }

    // ---------- sending ----------

    @Test fun `sending queues the trimmed text for today and clears the input`() = runTest {
        val (vm, state) = viewModel()
        vm.onDraftChange("  гречка 200 г  ")
        vm.onSend()
        val sent = diary.sent.single()
        assertEquals("гречка 200 г", sent.text)
        assertEquals(TODAY, sent.day)
        assertEquals(10, sent.now.hour)
        assertEquals(MessageSource.Text, sent.source)
        assertEquals("", state.value.draft)
    }

    @Test fun `an empty input sends nothing`() = runTest {
        val (vm, _) = viewModel()
        vm.onDraftChange("   ")
        vm.onSend()
        assertTrue(diary.sent.isEmpty())
    }

    @Test fun `a weigh-in command is recorded as weight, not sent as food`() = runTest {
        val (vm, state) = viewModel()
        vm.effects.test {
            vm.onDraftChange("вес 82,4")
            vm.onSend()
            assertEquals(UiEffect.Confirm, awaitItem())
        }
        assertTrue(diary.sent.isEmpty())
        assertEquals(listOf(TODAY to 82.4), diary.addedWeights)
        assertEquals("", state.value.draft)
    }

    @Test fun `things that only look like a weigh-in are food messages`() = runTest {
        val (vm, _) = viewModel()
        vm.onDraftChange("вес 82.4 и гречка")
        vm.onSend()
        assertEquals(1, diary.sent.size)
        assertTrue(diary.addedWeights.isEmpty())
    }

    @Test fun `a photo is sent with the typed caption and closes the camera`() = runTest {
        val (vm, state) = viewModel()
        vm.onDraftChange("с сыром")
        vm.onOpenCamera()
        assertTrue(state.value.cameraOpen)
        vm.onPhoto(byteArrayOf(1, 2, 3))
        val sent = diary.sent.single()
        assertEquals("с сыром", sent.text)
        assertEquals(listOf<Byte>(1, 2, 3), sent.image!!.toList())
        assertEquals(MessageSource.Photo, sent.source)
        assertFalse(state.value.cameraOpen)
        assertEquals("", state.value.draft)
    }

    @Test fun `a photo without text has no caption`() = runTest {
        val (vm, _) = viewModel()
        vm.onPhoto(byteArrayOf(7))
        assertNull(diary.sent.single().text)
    }

    @Test fun `without a server and without a key the camera explains instead of opening`() = runTest {
        localSettings.state.value = Capabilities(local = true, modelKey = false)
        val (vm, state) = viewModel()
        assertTrue(state.value.photoNeedsKey)
        vm.onOpenCamera()
        assertFalse(state.value.cameraOpen)
        assertEquals(PHOTO_NEEDS_KEY, state.value.notice)
    }

    @Test fun `adding the key opens the camera again`() = runTest {
        localSettings.state.value = Capabilities(local = true, modelKey = false)
        val (vm, state) = viewModel()
        localSettings.state.value = Capabilities(local = true, modelKey = true)
        assertFalse(state.value.photoNeedsKey)
        vm.onOpenCamera()
        assertTrue(state.value.cameraOpen)
    }

    @Test fun `with a server the camera never needs a key`() = runTest {
        localSettings.state.value = Capabilities(local = false, modelKey = false)
        val (vm, state) = viewModel()
        assertFalse(state.value.photoNeedsKey)
        vm.onOpenCamera()
        assertTrue(state.value.cameraOpen)
    }

    @Test fun `a camera failure closes it and says so`() = runTest {
        val (vm, state) = viewModel()
        vm.onOpenCamera()
        vm.onCameraFailed()
        assertFalse(state.value.cameraOpen)
        assertEquals(Texts.CAMERA_FAILED, state.value.notice)
    }

    @Test fun `server confirmations become haptic ticks`() = runTest {
        val (vm, _) = viewModel()
        vm.effects.test {
            diary.confirmationTicks.tryEmit(Unit)
            assertEquals(UiEffect.Confirm, awaitItem())
        }
    }

    // ---------- voice ----------

    @Test fun `dictation fills the input live and marks the message as voice`() = runTest {
        val (vm, state) = viewModel()
        vm.onDraftChange("сегодня")
        vm.onVoiceListening()
        assertEquals(VoicePhase.Listening, state.value.voice)
        vm.onVoiceText("две вареные", final = false)
        assertEquals("сегодня две вареные", state.value.draft)
        vm.onVoiceText("две вареные яйца", final = true)
        assertEquals("сегодня две вареные яйца", state.value.draft)
        assertEquals(VoicePhase.Idle, state.value.voice)

        vm.onSend()
        assertEquals(MessageSource.Voice, diary.sent.single().source)
    }

    @Test fun `editing the dictated text by hand keeps it voice, clearing it does not`() = runTest {
        val (vm, _) = viewModel()
        vm.onVoiceText("гречка", final = true)
        vm.onDraftChange("")
        vm.onDraftChange("овсянка")
        vm.onSend()
        assertEquals(MessageSource.Text, diary.sent.single().source)
    }

    @Test fun `recorded audio is transcribed by the server and lands in the input`() = runTest {
        val (vm, state) = viewModel()
        vm.onVoiceRecording()
        assertEquals(VoicePhase.Recording, state.value.voice)
        vm.onVoiceRecorded(ByteArray(10), "audio/mp4", "voice.m4a")
        assertEquals("две вареные яйца", state.value.draft)
        assertEquals(VoicePhase.Idle, state.value.voice)
        assertEquals(10, speech.calls.single().first)
        assertEquals("audio/mp4", speech.calls.single().second)
    }

    @Test fun `a failed transcription says why and leaves the input alone`() = runTest {
        speech.result = Result.failure(AppError("Распознавание речи ещё не готово."))
        val (vm, state) = viewModel()
        vm.onDraftChange("уже набрано")
        vm.onVoiceRecorded(ByteArray(10), "audio/mp4", "voice.m4a")
        assertEquals("Распознавание речи ещё не готово.", state.value.notice)
        assertEquals("уже набрано", state.value.draft)
        assertEquals(VoicePhase.Idle, state.value.voice)
    }

    @Test fun `an empty transcription is reported as not heard`() = runTest {
        speech.result = Result.success("   ")
        val (vm, state) = viewModel()
        vm.onVoiceRecorded(ByteArray(10), "audio/mp4", "voice.m4a")
        assertEquals(Texts.VOICE_NO_MATCH, state.value.notice)
    }

    @Test fun `a notice disappears by itself, and typing dismisses it at once`() = runTest {
        val (vm, state) = viewModel()
        vm.onVoiceProblem(Texts.VOICE_AUDIO)
        assertEquals(Texts.VOICE_AUDIO, state.value.notice)
        advanceTimeBy(4_001)
        assertNull(state.value.notice)

        vm.onVoiceProblem(Texts.VOICE_AUDIO)
        vm.onDraftChange("а")
        assertNull(state.value.notice)
    }

    // ---------- editing in place ----------

    @Test fun `tapping a row opens the editor with its values`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a", name = "гречка", grams = 200.0))
        vm.onEditStart("a")
        assertEquals(EditState("a", "гречка", "200"), state.value.editing)
    }

    @Test fun `fractional grams keep their decimals in the editor`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a", grams = 12.5))
        vm.onEditStart("a")
        assertEquals("12.5", state.value.editing!!.grams)
    }

    @Test fun `the grams field only accepts digits and a separator`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a"))
        vm.onEditStart("a")
        vm.onEditGrams("1a5,5г")
        assertEquals("15,5", state.value.editing!!.grams)
    }

    @Test fun `committing sends the new grams and name and ticks`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a"))
        vm.onEditStart("a")
        vm.onEditName("рис")
        vm.onEditGrams("150,5")
        vm.effects.test {
            vm.onEditCommit()
            assertEquals(UiEffect.Confirm, awaitItem())
        }
        assertEquals(listOf(Triple("a", 150.5, "рис")), diary.updates)
        assertNull(state.value.editing)
    }

    @Test fun `invalid grams are ignored but the name still goes through`() = runTest {
        val (vm, _) = viewModel()
        diary.entries.value = listOf(entry("a"))
        vm.onEditStart("a")
        vm.onEditName("рис")
        vm.onEditGrams("0")
        vm.onEditCommit()
        assertEquals(listOf(Triple("a", null, "рис")), diary.updates)
        vm.onEditStart("a")
        vm.onEditGrams("99999")
        vm.onEditCommit()
        assertEquals("nothing valid changed: nothing is sent", 1, diary.updates.size)
    }

    @Test fun `a tap on the open food folds it back, keeping the change, and delete removes it`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a"), entry("b"))
        vm.onEditStart("a")
        vm.onEditStart("a")
        assertNull(state.value.editing)
        assertTrue("opened and folded without a change: nothing is sent", diary.updates.isEmpty())

        vm.onEditStart("a")
        vm.onEditGrams("150")
        vm.onEditStart("b")
        assertEquals("opening another food keeps the first one's change", listOf(Triple("a", 150.0, "гречка")), diary.updates)
        assertEquals("b", state.value.editing!!.entryId)

        vm.onEditStart("a")
        vm.onEditDelete()
        assertEquals(listOf("a"), diary.deletedEntries)
        assertNull(state.value.editing)
    }

    @Test fun `editing an unknown entry does nothing`() = runTest {
        val (vm, state) = viewModel()
        vm.onEditStart("ghost")
        assertNull(state.value.editing)
    }

    // ---------- weights, waiting messages, notes ----------

    @Test fun `tapping a weigh-in offers delete, tapping again hides it`() = runTest {
        val (vm, state) = viewModel()
        diary.addWeight(TODAY, 82.4, Instant.parse("2026-09-30T05:00:00Z"))
        vm.onWeightTap("w1")
        assertTrue(state.value.feed.filterIsInstance<FeedItem.WeightItem>().single().actionsOpen)
        vm.onWeightTap("w1")
        assertFalse(state.value.feed.filterIsInstance<FeedItem.WeightItem>().single().actionsOpen)
        vm.onWeightTap("w1")
        vm.onWeightDelete("w1")
        assertEquals(listOf("w1"), diary.deletedWeights)
    }

    @Test fun `tapping a failed message removes it, tapping a waiting one retries`() = runTest {
        val (vm, _) = viewModel()
        diary.outbox.value = listOf(
            outboxMessage("failed", state = OutboxState.Failed, error = "Не удалось обработать."),
            outboxMessage("waiting"),
        )
        vm.onMessageTap("failed")
        vm.onMessageTap("waiting")
        assertEquals(listOf("failed"), diary.discardedOutbox)
        assertEquals(listOf(false), diary.syncRequests)
    }

    @Test fun `the chat keeps its notes, only an error goes away when tapped`() = runTest {
        val (vm, _) = viewModel()
        diary.notes.value = listOf(
            Note(1, TODAY, NoteKind.Question, "Какой суп?", null, Instant.parse("2026-09-30T06:00:00Z")),
            Note(2, TODAY, NoteKind.Info, "Не нашёл в сообщении еды.", null, Instant.parse("2026-09-30T06:01:00Z")),
        )
        diary.notes.value += Note(3, TODAY, NoteKind.Error, "Не удалось.", null, Instant.parse("2026-09-30T06:02:00Z"))
        vm.onNoteTap(1)
        vm.onNoteTap(2)
        vm.onNoteTap(3)
        assertEquals(listOf(3L), diary.dismissedNotes)
    }

    // ---------- days, settings, lifecycle ----------

    @Test fun `picking a day from history shows it and sends new messages to that day`() = runTest {
        val (vm, state) = viewModel()
        val yesterday = LocalDate.parse("2026-09-28")
        diary.entries.value = listOf(entry("old", day = yesterday, kcal = 500.0))
        vm.onOpenSettings()
        assertEquals(Screen.Settings, state.value.screen)
        vm.onSelectDay(yesterday)

        assertEquals(Screen.Main, state.value.screen)
        assertEquals(yesterday, state.value.day)
        assertFalse(state.value.isToday)
        assertEquals(500.0, state.value.totals.kcal, 0.0)

        vm.onDraftChange("забыл записать ужин")
        vm.onSend()
        assertEquals(yesterday, diary.sent.single().day)

        vm.onBackToToday()
        assertEquals(TODAY, state.value.day)
    }

    @Test fun `settings open and close`() = runTest {
        val (vm, state) = viewModel()
        vm.onOpenSettings()
        assertEquals(Screen.Settings, state.value.screen)
        vm.onCloseSettings()
        assertEquals(Screen.Main, state.value.screen)
    }

    @Test fun `coming to the foreground pulls the server state`() = runTest {
        val (vm, _) = viewModel()
        vm.onForeground()
        assertEquals(listOf(true), diary.syncRequests)
    }

    @Test fun `after midnight the view moves to the new today, unless another day was chosen`() = runTest {
        val clock = MutableTestClock(Instant.parse("2026-09-30T07:00:00Z"))
        val (vm, state) = viewModel(clock)
        clock.now = Instant.parse("2026-10-01T07:00:00Z")
        vm.onForeground()
        assertEquals(LocalDate.parse("2026-10-01"), state.value.today)
        assertEquals(LocalDate.parse("2026-10-01"), state.value.day)

        vm.onSelectDay(LocalDate.parse("2026-09-20"))
        clock.now = Instant.parse("2026-10-02T07:00:00Z")
        vm.onForeground()
        assertEquals(LocalDate.parse("2026-10-02"), state.value.today)
        assertEquals(LocalDate.parse("2026-09-20"), state.value.day)
    }

    @Test fun `a profile without a goal shows the summary without a target`() = runTest {
        diary.profile.value = Profile("me@example.com", null)
        assertNull(viewModel().second.value.goal)
    }
}

class MutableTestClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.ofHours(3)
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}
