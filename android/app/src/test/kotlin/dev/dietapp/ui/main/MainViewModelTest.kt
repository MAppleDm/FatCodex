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
import dev.dietapp.data.local.NEEDS_KEY
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

    @Test fun `an open proposal is asked in the chat, on its own line, with its options`() = runTest {
        val (_, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        val line = state.value.feed.filterIsInstance<FeedItem.NoteItem>().single()
        val p = line.proposal!!
        assertEquals(5L, p.noteId)
        assertEquals(listOf("Сосиски Рубленые [Ремит]", "сосиски"), p.choices.map { it.name })
        assertNull(p.custom)
        assertEquals(p, state.value.proposal)
    }

    @Test fun `several open proposals are each answered on their own`() = runTest {
        val (vm, _) = viewModel()
        diary.notes.value = listOf(proposalNote(), proposalNote(id = 6).copy(createdAt = Instant.parse("2026-09-30T06:00:01Z")))
        vm.onProposalPick(5, 1)
        assertEquals(5L to found[1], diary.accepted.single())
    }

    @Test fun `picking an option confirms it and the options go away`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalPick(5, 0)
        assertEquals(5L to found[0], diary.accepted.single())
        assertNull(state.value.proposal)
        val line = state.value.feed.filterIsInstance<FeedItem.NoteItem>().single { it.note.id == 5L }
        assertNull("the answered question stays in the chat, without its options", line.proposal)
    }

    @Test fun `own values start from the first option and are sent as the user's`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalCustom(5)
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
        vm.onProposalCustom(5)
        vm.onProposalBack()
        assertNull(state.value.proposal!!.custom)
        assertTrue(diary.dismissedNotes.isEmpty())
    }

    @Test fun `skipping closes the proposal but keeps it in the chat`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalSkip(5)
        assertEquals(listOf(5L), diary.skipped)
        assertTrue(diary.dismissedNotes.isEmpty())
        assertNull(state.value.proposal)
        assertTrue(diary.accepted.isEmpty())
    }

    // ---------- a change to the food base that waits for a yes ----------

    private fun baseChangeNote(id: Long = 8, resolved: Boolean = false) = Note(
        id, TODAY, NoteKind.BaseChange, "Удалить «казеин» из базы?\n360 ккал · Б 80 · Ж 1,5 · У 8 на 100 г", null,
        Instant.parse("2026-09-30T06:00:00Z"), "m1", resolved = resolved,
    )

    @Test fun `a change the agent may not make alone is asked in the chat`() = runTest {
        val (_, state) = viewModel()
        diary.notes.value = listOf(baseChangeNote())
        val ask = state.value.feed.filterIsInstance<FeedItem.NoteItem>().single().baseChange!!
        assertEquals(8L, ask.noteId)
        assertTrue(ask.question.startsWith("Удалить «казеин» из базы?"))
    }

    @Test fun `yes and no are sent to the diary, and the buttons go away`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(baseChangeNote())
        vm.onBaseChange(8, true)
        assertEquals(listOf(8L to true), diary.baseChangeAnswers)
        assertNull(state.value.baseChange)

        diary.notes.value = listOf(baseChangeNote(id = 9))
        vm.onBaseChange(9, false)
        assertEquals(9L to false, diary.baseChangeAnswers.last())
    }

    @Test fun `a failure is shown as a notice and the question stays`() = runTest {
        val (vm, state) = viewModel()
        diary.notes.value = listOf(baseChangeNote())
        diary.baseChangeResult = Result.failure(dev.dietapp.data.net.AppError("Не вышло.", "x"))
        vm.onBaseChange(8, true)
        assertEquals("Не вышло.", state.value.notice)
        assertTrue(state.value.baseChange != null)
        assertFalse(state.value.baseChange!!.busy)
    }

    @Test fun `a change, a proposal and the confirmation can all be open at once, each where it belongs`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = waiting() + entry("e3", "кофе", kcal = 20.0).copy(mealId = "m1", pending = true)
        diary.notes.value = listOf(baseChangeNote(), proposalNote())
        assertTrue(state.value.baseChange != null)
        assertTrue(state.value.proposal != null)
        assertEquals("the food the proposal is about waits for the pick, not for записать", listOf("e3"), state.value.confirm!!.entries.map { it.id })
    }

    @Test fun `an answered change stays in the chat as a line`() = runTest {
        val (_, state) = viewModel()
        diary.notes.value = listOf(baseChangeNote(resolved = true))
        assertNull(state.value.baseChange)
        val line = state.value.feed.filterIsInstance<FeedItem.NoteItem>().single()
        assertNull(line.baseChange)
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

    @Test fun `the food a proposal is about is not offered for recording before the pick`() = runTest {
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

    @Test fun `the whole conversation about a food stays in the chat, in the order it happened`() = runTest {
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
        )
        assertEquals(
            listOf("m:m1", "e:e1", "n:1", "m:m2", "n:2", "n:3", "n:4"),
            state.value.feed.map { it.key },
        )
        assertTrue("nothing is still asked", state.value.feed.filterIsInstance<FeedItem.NoteItem>().all { it.proposal == null && !it.open })
    }

    @Test fun `an open question is asked in the chat, and its answer shows after it`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("e1", kcal = null).copy(mealId = "m1", pending = true))
        diary.notes.value = listOf(Note(1, TODAY, NoteKind.Question, "Что это точнее?", "e1", Instant.parse("2026-09-30T06:00:01Z"), "m1"))
        assertEquals(1L, state.value.question!!.id)
        assertTrue(state.value.feed.filterIsInstance<FeedItem.NoteItem>().single().open)

        diary.messages.value = listOf(dev.dietapp.data.domain.Message("m2", TODAY, "роллы", false, Instant.parse("2026-09-30T06:00:05Z"), aboutEntryId = "e1"))
        diary.outbox.value = listOf(outboxMessage("m2"))
        assertTrue(state.value.feed.any { it is FeedItem.MessageItem && it.message.id == "m2" })

        vm.onQuestionSkip()
        assertEquals(listOf(1L), diary.skippedQuestions)
    }

    @Test fun `a food the open question is about is not offered for recording until it is answered`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(
            entry("e1", "гречка", kcal = null).copy(mealId = "m1", pending = true),
            entry("e2", "кофе", kcal = 20.0, status = EntryStatus.Uncertain).copy(mealId = "m1", pending = true),
        )
        val question = Note(1, TODAY, NoteKind.Question, "Не нашёл «гречка». Что это точнее?", "e1", Instant.parse("2026-09-30T06:00:01Z"), "m1")
        diary.notes.value = listOf(question)
        assertEquals(1L, state.value.question!!.id)
        assertEquals("only the other food waits for записать", listOf("e2"), state.value.confirm!!.entries.map { it.id })

        // the question is skipped: now the food without numbers is offered too, and the person decides
        diary.notes.value = listOf(question.copy(resolved = true))
        assertNull(state.value.question)
        assertEquals(listOf("e1", "e2"), state.value.confirm!!.entries.map { it.id })
    }

    @Test fun `a question about the only waiting food leaves no confirmation at all`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = listOf(entry("e1", "гречка", kcal = null).copy(mealId = "m1", pending = true))
        diary.notes.value = listOf(Note(1, TODAY, NoteKind.Question, "Что это точнее?", "e1", Instant.parse("2026-09-30T06:00:01Z"), "m1"))
        assertTrue(state.value.question != null)
        assertNull(state.value.confirm)
    }

    @Test fun `an open proposal sits under the message it answers`() = runTest {
        val (_, state) = viewModel()
        diary.entries.value = listOf(entry("e1", kcal = null).copy(mealId = "m1", pending = true))
        diary.messages.value = listOf(dev.dietapp.data.domain.Message("m1", TODAY, "сосиски ремит", false, Instant.parse("2026-09-30T05:59:00Z")))
        diary.notes.value = listOf(proposalNote())
        assertEquals(listOf("m:m1", "e:e1", "n:5"), state.value.feed.map { it.key })
        assertTrue(state.value.proposal != null)
    }

    @Test fun `a failed confirmation says why and keeps the options`() = runTest {
        diary.acceptResult = Result.failure(AppError("Это предложение уже неактуально.", "stale"))
        val (vm, state) = viewModel()
        diary.notes.value = listOf(proposalNote())
        vm.onProposalPick(5, 1)
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

    // ---------- the sound of a food that is in for good ----------

    @Test fun `a food that goes in for good makes the sound, what was there already does not`() = runTest {
        diary.entries.value = listOf(entry("old", name = "овсянка"))
        val (vm, _) = viewModel()
        vm.effects.test {
            expectNoEvents() // opening the app is not an addition
            diary.entries.value = diary.entries.value + entry("new", name = "борщ")
            assertEquals(UiEffect.Added, awaitItem())
            expectNoEvents()
        }
    }

    @Test fun `changing or removing a food is not an addition`() = runTest {
        diary.entries.value = listOf(entry("a", name = "овсянка", grams = 60.0), entry("b", name = "борщ"))
        val (vm, _) = viewModel()
        vm.effects.test {
            diary.entries.value = listOf(entry("a", name = "овсянка", grams = 80.0, kcal = 300.0), entry("b", name = "борщ"))
            diary.entries.value = listOf(entry("a", name = "овсянка", grams = 80.0, kcal = 300.0))
            expectNoEvents()
        }
    }

    @Test fun `a food that waits for the person is silent until it is recorded`() = runTest {
        val (vm, _) = viewModel()
        vm.effects.test {
            diary.entries.value = listOf(entry("p", name = "борщ").copy(pending = true))
            expectNoEvents() // recognised, shown, not counted: "Записать?"
            diary.entries.value = listOf(entry("p", name = "борщ").copy(pending = false))
            assertEquals(UiEffect.Added, awaitItem())
        }
    }

    @Test fun `a food that has no numbers yet is silent until it gets them`() = runTest {
        val (vm, _) = viewModel()
        vm.effects.test {
            diary.entries.value = listOf(entry("s", name = "суши", kcal = null, status = EntryStatus.Unmatched))
            expectNoEvents() // the agent asked what it is
            diary.entries.value = listOf(entry("s", name = "суши", kcal = 250.0))
            assertEquals(UiEffect.Added, awaitItem())
        }
    }

    @Test fun `a weight that is added makes the sound too`() = runTest {
        val (vm, _) = viewModel()
        vm.effects.test {
            diary.weights.value = listOf(dev.dietapp.data.domain.Weight("w9", TODAY, 82.4, Instant.parse("2026-09-30T05:00:00Z")))
            assertEquals(UiEffect.Added, awaitItem())
        }
    }

    @Test fun `another day opened is only looked at, a food added to it counts`() = runTest {
        val yesterday = LocalDate.parse("2026-09-29")
        diary.entries.value = listOf(entry("t1"), entry("y1", day = yesterday))
        val (vm, _) = viewModel()
        vm.effects.test {
            vm.onSelectDay(yesterday)
            expectNoEvents() // the day's food was there
            diary.entries.value = diary.entries.value + entry("y2", day = yesterday, name = "ужин")
            assertEquals(UiEffect.Added, awaitItem())
            vm.onBackToToday()
            expectNoEvents()
        }
    }

    @Test fun `what is being typed comes back after the system closed the app, and a sent message does not`() = runTest {
        val handle = androidx.lifecycle.SavedStateHandle()
        val first = MainViewModel(diary, speech, localSettings, TEST_CLOCK, handle)
        first.onDraftChange("кофе 200 мл")
        // the process is gone; the system gives the new screen the state it kept
        val again = MainViewModel(diary, speech, localSettings, TEST_CLOCK, handle)
        assertEquals("кофе 200 мл", again.uiState.value.draft)
        again.onSend()
        assertEquals("", MainViewModel(diary, speech, localSettings, TEST_CLOCK, handle).uiState.value.draft)
    }

    @Test fun `sending a message while a food is open saves the change and folds it, so the feed follows the new message`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("e1", name = "гречка", grams = 200.0))
        vm.onEditStart("e1")
        vm.onEditGrams("150")
        vm.onDraftChange("кофе")
        vm.onSend()
        assertNull("folded", state.value.editing)
        assertEquals("the change was kept", 150.0, diary.updates.single().second!!, 0.0)
        assertEquals("кофе", diary.sent.single().text)
    }

    @Test fun `a photo taken while an ingredient is open saves its grams first`() = runTest {
        val (vm, state) = viewModel()
        val beet = dev.dietapp.data.domain.DishPart("свёкла", 40.0, dev.dietapp.data.domain.Per100(43.0, 1.6, 0.2, 9.6))
        val beef = dev.dietapp.data.domain.DishPart("говядина", 50.0, dev.dietapp.data.domain.Per100(250.0, 26.0, 15.0, 0.0))
        diary.entries.value = listOf(entry("d1", name = "борщ", grams = 90.0, kcal = 142.2).copy(parts = listOf(beet, beef)))
        vm.onPartsToggle("d1")
        vm.onPartEditStart("d1", 1)
        vm.onPartGrams("80")
        vm.onPhoto(byteArrayOf(1, 2, 3))
        assertNull(state.value.partEdit)
        assertEquals(listOf(Triple("d1", 1, 80.0)), diary.partChanges)
        assertTrue(diary.sent.single().image != null)
    }

    @Test fun `a weigh-in command is recorded as weight, not sent as food`() = runTest {
        val (vm, state) = viewModel()
        vm.effects.test {
            vm.onDraftChange("вес 82,4")
            vm.onSend()
            // the tick of the haptic confirmation and the sound of a record that is in: both, in whichever order they arrive
            assertEquals(setOf(UiEffect.Added, UiEffect.Confirm), setOf(awaitItem(), awaitItem()))
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
        assertTrue(state.value.needsKey)
        vm.onOpenCamera()
        assertFalse(state.value.cameraOpen)
        assertEquals(NEEDS_KEY, state.value.notice)
    }

    @Test fun `without a key a message is not sent, the notice says why and what was typed stays`() = runTest {
        localSettings.state.value = Capabilities(local = true, modelKey = false)
        val (vm, state) = viewModel()
        vm.onDraftChange("гречка 200 г")
        vm.onSend()
        assertTrue("nothing reads it, so nothing is queued", diary.sent.isEmpty())
        assertEquals(NEEDS_KEY, state.value.notice)
        assertEquals("гречка 200 г", state.value.draft)
    }

    @Test fun `without a key a weigh-in still goes through, the agent is not needed for it`() = runTest {
        localSettings.state.value = Capabilities(local = true, modelKey = false)
        val (vm, state) = viewModel()
        vm.onDraftChange("вес 82.4")
        vm.onSend()
        assertEquals(listOf(TODAY to 82.4), diary.addedWeights)
        assertEquals("", state.value.draft)
        assertTrue(diary.sent.isEmpty())
    }

    @Test fun `adding the key opens the camera again`() = runTest {
        localSettings.state.value = Capabilities(local = true, modelKey = false)
        val (vm, state) = viewModel()
        localSettings.state.value = Capabilities(local = true, modelKey = true)
        assertFalse(state.value.needsKey)
        vm.onOpenCamera()
        assertTrue(state.value.cameraOpen)
    }

    @Test fun `adding the key lets the message through`() = runTest {
        localSettings.state.value = Capabilities(local = true, modelKey = false)
        val (vm, state) = viewModel()
        vm.onDraftChange("гречка 200 г")
        vm.onSend()
        localSettings.state.value = Capabilities(local = true, modelKey = true)
        vm.onSend()
        assertEquals("гречка 200 г", diary.sent.single().text)
        assertEquals("", state.value.draft)
    }

    @Test fun `with a server the camera never needs a key`() = runTest {
        localSettings.state.value = Capabilities(local = false, modelKey = false)
        val (vm, state) = viewModel()
        assertFalse(state.value.needsKey)
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

    @Test fun `a picture from the gallery that cannot be opened closes the camera and says so`() = runTest {
        val (vm, state) = viewModel()
        vm.onOpenCamera()
        vm.onGalleryFailed()
        assertFalse(state.value.cameraOpen)
        assertEquals(Texts.GALLERY_FAILED, state.value.notice)
        assertTrue(diary.sent.isEmpty())
    }

    @Test fun `a picture from the gallery is sent like a snapshot, with the typed words as its caption`() = runTest {
        val (vm, state) = viewModel()
        vm.onDraftChange("чек вкусно и точка")
        vm.onOpenCamera()
        vm.onPhoto(byteArrayOf(5, 6, 7))
        val sent = diary.sent.single()
        assertEquals("чек вкусно и точка", sent.text)
        assertEquals(MessageSource.Photo, sent.source)
        assertFalse(state.value.cameraOpen)
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

    @Test fun `a swiped food leaves the chat and the totals at once, and is deleted when the undo time is over`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a", "гречка", kcal = 184.0), entry("b", "кофе", kcal = 20.0))
        vm.onEntrySwipe("a")
        assertEquals(listOf("b"), state.value.feed.filterIsInstance<FeedItem.EntryItem>().map { it.entry.id })
        assertEquals(20.0, state.value.totals.kcal, 0.0)
        assertEquals(UndoUi("a", "гречка"), state.value.undo)
        assertTrue("not yet: it can still come back", diary.deletedEntries.isEmpty())
        advanceTimeBy(5_001)
        assertEquals(listOf("a"), diary.deletedEntries)
        assertNull(state.value.undo)
    }

    @Test fun `undo brings a swiped food back and nothing is deleted`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a", "гречка", kcal = 184.0))
        vm.onEntrySwipe("a")
        vm.onUndo()
        assertEquals(listOf("a"), state.value.feed.filterIsInstance<FeedItem.EntryItem>().map { it.entry.id })
        assertNull(state.value.undo)
        advanceTimeBy(10_000)
        assertTrue(diary.deletedEntries.isEmpty())
    }

    @Test fun `a second swipe deletes the first food for good and offers to bring back the second`() = runTest {
        val (vm, state) = viewModel()
        diary.entries.value = listOf(entry("a", "гречка", kcal = 184.0), entry("b", "кофе", kcal = 20.0))
        vm.onEntrySwipe("a")
        vm.onEntrySwipe("b")
        assertEquals(listOf("a"), diary.deletedEntries)
        assertEquals("b", state.value.undo!!.entryId)
        assertTrue(state.value.feed.none { it is FeedItem.EntryItem })
    }

    @Test fun `editing an unknown entry does nothing`() = runTest {
        val (vm, state) = viewModel()
        vm.onEditStart("ghost")
        assertNull(state.value.editing)
    }

    // ---------- weights, waiting messages, notes ----------

    // ---------- the ingredients of a dish ----------

    private val beet = dev.dietapp.data.domain.DishPart("свёкла", 40.0, dev.dietapp.data.domain.Per100(43.0, 1.6, 0.2, 9.6))
    private val beef = dev.dietapp.data.domain.DishPart("говядина", 50.0, dev.dietapp.data.domain.Per100(250.0, 26.0, 15.0, 0.0))
    private fun dish() = entry("d1", name = "борщ", grams = 90.0, kcal = 142.2).copy(parts = listOf(beet, beef))

    @Test fun `the chevron of a dish opens its ingredients, and again folds them`() = runTest {
        diary.entries.value = listOf(dish())
        val (vm, state) = viewModel()
        vm.onPartsToggle("d1")
        assertEquals(setOf("d1"), state.value.expandedParts)
        vm.onPartsToggle("d1")
        assertTrue(state.value.expandedParts.isEmpty())
    }

    @Test fun `a tap on an ingredient lets its grams be changed, and done saves them`() = runTest {
        diary.entries.value = listOf(dish())
        val (vm, state) = viewModel()
        vm.onPartsToggle("d1")
        vm.onPartEditStart("d1", 1)
        assertEquals(PartEdit("d1", 1, "50"), state.value.partEdit)
        vm.onPartGrams("7x5,5")
        assertEquals("only digits and a decimal mark", "75,5", state.value.partEdit!!.grams)
        vm.onPartCommit()
        assertEquals(listOf(Triple("d1", 1, 75.5)), diary.partChanges)
        assertNull(state.value.partEdit)
    }

    @Test fun `a number that is the same, empty or absurd saves nothing`() = runTest {
        diary.entries.value = listOf(dish())
        val (vm, state) = viewModel()
        for (typed in listOf("50", "", "99999")) {
            vm.onPartEditStart("d1", 1)
            vm.onPartGrams(typed)
            vm.onPartCommit()
            assertNull(state.value.partEdit)
        }
        assertTrue(diary.partChanges.isEmpty())
    }

    @Test fun `taking an ingredient out is a change to nothing`() = runTest {
        diary.entries.value = listOf(dish())
        val (vm, _) = viewModel()
        vm.onPartEditStart("d1", 0)
        vm.onPartGrams("0")
        vm.onPartCommit()
        assertEquals(listOf(Triple("d1", 0, 0.0)), diary.partChanges)
    }

    @Test fun `starting on another ingredient saves the first one`() = runTest {
        diary.entries.value = listOf(dish())
        val (vm, state) = viewModel()
        vm.onPartEditStart("d1", 0)
        vm.onPartGrams("60")
        vm.onPartEditStart("d1", 1)
        assertEquals(listOf(Triple("d1", 0, 60.0)), diary.partChanges)
        assertEquals(1, state.value.partEdit!!.index)
    }

    @Test fun `opening the food for editing closes the ingredient being changed`() = runTest {
        diary.entries.value = listOf(dish())
        val (vm, state) = viewModel()
        vm.onPartEditStart("d1", 0)
        vm.onEditStart("d1")
        assertNull(state.value.partEdit)
        assertEquals("d1", state.value.editing!!.entryId)
    }

    @Test fun `a plain food has no ingredients to change`() = runTest {
        diary.entries.value = listOf(entry("p1", name = "гречка"))
        val (vm, state) = viewModel()
        vm.onPartEditStart("p1", 0)
        assertNull(state.value.partEdit)
    }

    @Test fun `what the watch said about the day reaches the screen`() = runTest {
        val (_, state) = viewModel()
        assertNull(state.value.facts)
        diary.facts.value = dev.dietapp.data.domain.DayFacts(TODAY, sleepMinutes = 440, steps = 8412)
        assertEquals(440, state.value.facts!!.sleepMinutes)
    }

    @Test fun `the watch page opens from the settings and goes back to them`() = runTest {
        val (vm, state) = viewModel()
        vm.onOpenSettings()
        vm.onOpenWatch()
        assertEquals(Screen.Watch, state.value.screen)
        vm.onCloseWatch()
        assertEquals(Screen.Settings, state.value.screen)
    }

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

    @Test fun `tapping a waiting message nudges it, a failed one is left to its own buttons`() = runTest {
        val (vm, _) = viewModel()
        diary.outbox.value = listOf(
            outboxMessage("failed", state = OutboxState.Failed, error = "Не удалось обработать."),
            outboxMessage("waiting"),
        )
        vm.onMessageTap("failed")
        vm.onMessageTap("waiting")
        assertTrue("a tap never takes a message back", diary.discardedOutbox.isEmpty())
        assertTrue(diary.retriedOutbox.isEmpty())
        assertEquals(listOf(false), diary.syncRequests)
    }

    @Test fun `try again has the agent read the failed message once more`() = runTest {
        val (vm, _) = viewModel()
        diary.outbox.value = listOf(outboxMessage("failed", state = OutboxState.Failed, error = "Агент недоступен."))
        vm.onMessageRetry("failed")
        assertEquals(listOf("failed"), diary.retriedOutbox)
        assertTrue(diary.discardedOutbox.isEmpty())
    }

    @Test fun `remove takes a failed message back`() = runTest {
        val (vm, _) = viewModel()
        diary.outbox.value = listOf(outboxMessage("failed", state = OutboxState.Failed, error = "Агент недоступен."))
        vm.onMessageDiscard("failed")
        assertEquals(listOf("failed"), diary.discardedOutbox)
        assertTrue(diary.retriedOutbox.isEmpty())
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
