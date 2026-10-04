package dev.dietapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.MainDispatcherRule
import dev.dietapp.TODAY
import dev.dietapp.coreui.AppTheme
import dev.dietapp.data.domain.DayContent
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.OutboxMessage
import dev.dietapp.data.domain.OutboxState
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Weight
import dev.dietapp.entry
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.dietapp.data.domain.Food
import dev.dietapp.data.domain.Message
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.local.FoodHit
import dev.dietapp.ui.foods.FoodEdit
import dev.dietapp.ui.foods.FoodsActions
import dev.dietapp.ui.foods.FoodsScreen
import dev.dietapp.ui.foods.FoodsUiState
import dev.dietapp.ui.login.LoginScreen
import dev.dietapp.ui.login.LoginStep
import dev.dietapp.ui.login.LoginUiState
import dev.dietapp.ui.main.EditState
import dev.dietapp.ui.main.MainActions
import dev.dietapp.ui.main.MainScreen
import dev.dietapp.ui.main.MainUiState
import dev.dietapp.ui.main.buildState
import dev.dietapp.ui.main.Local
import dev.dietapp.ui.settings.SettingsScreen
import dev.dietapp.ui.settings.SettingsUiState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens to PNG so the design can be looked at without a device:
 *   ./gradlew :app:testDebugUnitTest --tests "*ScreenshotTest" -Pscreenshots=<output dir>
 * Skipped in normal runs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class ScreenshotTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val outDir: File? = System.getProperty("screenshots.dir")?.takeIf { it.isNotBlank() }?.let(::File)

    private object Noop : MainActions {
        override fun onProposalPick(index: Int) = Unit
        override fun onProposalCustom() = Unit
        override fun onProposalDraft(draft: dev.dietapp.ui.main.ChoiceDraft) = Unit
        override fun onProposalSave() = Unit
        override fun onProposalSkip() = Unit
        override fun onProposalBack() = Unit
        override fun onConfirm(record: Boolean) = Unit
        override fun onQuestionSkip() = Unit
        override fun onDraftChange(text: String) = Unit
        override fun onSend() = Unit
        override fun onToggleSummary() = Unit
        override fun onEditStart(entryId: String) = Unit
        override fun onEditName(text: String) = Unit
        override fun onEditGrams(text: String) = Unit
        override fun onEditCommit() = Unit
        override fun onEditDelete() = Unit
        override fun onWeightTap(weightId: String) = Unit
        override fun onWeightDelete(weightId: String) = Unit
        override fun onMessageTap(messageId: String) = Unit
        override fun onNoteTap(noteId: Long) = Unit
        override fun onBackToToday() = Unit
        override fun onOpenSettings() = Unit
        override fun onCloseSettings() = Unit
        override fun onOpenCamera() = Unit
        override fun onCloseCamera() = Unit
        override fun onPhoto(jpeg: ByteArray) = Unit
        override fun onCameraFailed() = Unit
        override fun onVoiceListening() = Unit
        override fun onVoiceText(text: String, final: Boolean) = Unit
        override fun onVoiceRecording() = Unit
        override fun onVoiceRecorded(audio: ByteArray, mime: String, filename: String) = Unit
        override fun onVoiceStopped() = Unit
        override fun onVoiceProblem(message: String) = Unit
    }

    private object NoopFoods : FoodsActions {
        override fun onQuery(text: String) = Unit
        override fun onNew() = Unit
        override fun onEdit(food: Food) = Unit
        override fun onCopy(hit: FoodHit) = Unit
        override fun onEditChange(edit: FoodEdit) = Unit
        override fun onSave() = Unit
        override fun onDelete() = Unit
        override fun onCancel() = Unit
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val dir = outDir
        assumeTrue("set -Pscreenshots=<dir> to render screenshots", dir != null)
        compose.setContent { AppTheme(darkTheme = dark) { content() } }
        compose.waitForIdle()
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun sampleState(expanded: Boolean = false, editing: EditState? = null, withExtras: Boolean = true): MainUiState {
        val at = { h: Int, m: Int -> "2026-09-30T%02d:%02d:00Z".format(h, m) }
        val messages = listOf(
            Message("m1", TODAY, "овсянка 60 г и банан", false, Instant.parse(at(4, 55))),
            Message("m2", TODAY, "куриная грудка 150, гречка 200, масло", false, Instant.parse(at(9, 30))),
        ) + if (withExtras) listOf(
            Message("m3", TODAY, "казеиновый протеин 30 грамм с водой", false, Instant.parse(at(12, 40))),
            Message("m4", TODAY, "суши", false, Instant.parse(at(13, 10))),
            Message("p1", TODAY, "кофе с молоком", false, Instant.parse(at(14, 0))),
        ) else emptyList()
        val entries = listOf(
            entry("1", "овсянка на молоке", 60.0, 228.0, at = at(4, 55)).copy(mealId = "m1", protein = 8.1, fat = 4.2, carbs = 38.0),
            entry("2", "банан", 120.0, 107.0, at = at(4, 55)).copy(mealId = "m1", position = 1, protein = 1.3, fat = 0.4, carbs = 27.4),
            entry("3", "куриная грудка", 150.0, 248.0, at = at(9, 30)).copy(mealId = "m2", protein = 46.5, fat = 5.4, carbs = 0.0),
            entry("4", "гречка", 200.0, 184.0, at = at(9, 30)).copy(mealId = "m2", position = 1, protein = 6.8, fat = 1.2, carbs = 39.9),
            entry("5", "сливочное масло", 20.0, 143.0, at = at(9, 30), status = EntryStatus.Uncertain)
                .copy(mealId = "m2", position = 2, protein = 0.2, fat = 16.2, carbs = 0.0),
        ) + if (withExtras) listOf(
            entry("7", "казеиновый протеин", 30.0, 108.0, at = at(12, 40), status = EntryStatus.Uncertain)
                .copy(mealId = "m3", protein = 24.0, fat = 0.5, carbs = 2.4),
            entry("8", "вода", 250.0, 0.0, at = at(12, 40)).copy(mealId = "m3", position = 1, protein = 0.0, fat = 0.0, carbs = 0.0),
            entry("6", "суши", 300.0, null, at = at(13, 10), status = EntryStatus.Unmatched).copy(mealId = "m4"),
        ) else emptyList()
        val weights = listOf(83.1, 82.9, 83.0, 82.6, 82.4, 82.5, 82.1).mapIndexed { i, kg ->
            Weight("w$i", LocalDate.parse("2026-09-24").plusDays(i.toLong()), kg, Instant.parse("2026-09-${24 + i}T05:00:00Z"))
        }
        val content = DayContent(
            entries = entries,
            outbox = if (withExtras) listOf(
                OutboxMessage("p1", "кофе с молоком", false, TODAY, Instant.parse("2026-09-30T14:00:00Z"), OutboxState.Queued, "Нет связи с сервером.", 1),
            ) else emptyList(),
            notes = if (withExtras) listOf(
                Note(2, TODAY, NoteKind.Info, "Добавил в базу: казеиновый протеин — 360 ккал · Б 80 · Ж 1,5 · У 8 на 100 г (оценка)", null,
                    Instant.parse("2026-09-30T12:40:05Z"), "m3"),
                Note(1, TODAY, NoteKind.Question, "Не нашёл «суши» в базе продуктов. Что это точнее?", "6", Instant.parse("2026-09-30T13:11:00Z"), "m4"),
            ) else emptyList(),
            weights = weights.filter { it.day == TODAY },
            messages = messages,
        )
        return buildState(Local(day = TODAY, today = TODAY, summaryExpanded = expanded, editing = editing), content, weights, Profile("me@example.com", 1900))
    }

    @Test fun mainLight() = shot("main_light", dark = false) { MainScreen(sampleState(), Noop, emptyFlow()) }

    @Test fun mainDark() = shot("main_dark", dark = true) { MainScreen(sampleState(), Noop, emptyFlow()) }

    @Test fun mainExpandedLight() = shot("main_expanded_light", dark = false) {
        MainScreen(sampleState(expanded = true), Noop, emptyFlow())
    }

    @Test fun mainEditingDark() = shot("main_editing_dark", dark = true) {
        MainScreen(sampleState(editing = EditState("3", "куриная грудка", "150")), Noop, emptyFlow())
    }

    /**
     * A day as a chat: pasta recorded at once (green), sausages recorded after the user picked their values (the
     * question folded into the entry), borscht waiting for "записать" (grey, the panel above the input).
     */
    private fun chatContent(english: Boolean = false): DayContent {
        val t = { s: String -> Instant.parse("2026-09-30T12:$s:00Z") }
        fun l(ru: String, en: String) = if (english) en else ru
        val choices = listOf(
            dev.dietapp.data.domain.FoodChoice("Сосиски Рубленые [Ремит]", Per100(190.0, 13.0, 15.0, 1.0), "health-diet.ru",
                "https://health-diet.ru/base_of_food/sostav/232505.php"),
        )
        return DayContent(
            entries = listOf(
                entry("1", l("макароны вареные", "boiled pasta"), 200.0, 316.0, at = "2026-09-30T11:36:00Z")
                    .copy(mealId = "m1", protein = 11.6, fat = 1.8, carbs = 61.8, per100 = Per100(158.0, 5.8, 0.9, 30.9),
                        foodName = "Pasta, cooked, enriched, without added salt"),
                entry("2", l("сосиски ремит рубленые", "Remit sausages"), 50.0, 95.0, at = "2026-09-30T12:47:00Z")
                    .copy(mealId = "m2", protein = 6.5, fat = 7.5, carbs = 0.5, per100 = Per100(190.0, 13.0, 15.0, 1.0),
                        foodName = "Сосиски Рубленые [Ремит]"),
                entry("3", l("борщ", "borscht"), 300.0, 147.0, at = "2026-09-30T13:20:00Z", status = EntryStatus.Uncertain)
                    .copy(mealId = "m3", protein = 3.3, fat = 7.2, carbs = 15.6, per100 = Per100(49.0, 1.1, 2.4, 5.2), pending = true),
            ),
            outbox = emptyList(),
            notes = listOf(
                Note(1, TODAY, NoteKind.Proposal, l("Нашёл «Сосиски Рубленые [Ремит]» — 190 ккал, Б 13 Ж 15 У 1 на 100 г. Подходит?",
                    "Found “Сосиски Рубленые [Ремит]”: 190 kcal, P 13 F 15 C 1 per 100 g. Is that it?"), "2",
                    t("48"), "m2", resolved = true, choices = choices),
                Note(2, TODAY, NoteKind.Answer, "Сосиски Рубленые [Ремит] · health-diet.ru", "2", t("49"), "m2", resolved = true),
                Note(3, TODAY, NoteKind.Info, l("Добавил в базу: Сосиски Рубленые [Ремит] — 190 ккал · Б 13 · Ж 15 · У 1 на 100 г",
                    "Added to the base: Сосиски Рубленые [Ремит] — 190 kcal · P 13 · F 15 · C 1 per 100 g"), "2", t("50"), "m2", resolved = true),
            ),
            weights = emptyList(),
            messages = listOf(
                Message("m1", TODAY, l("Макароны вареные 200 грамм", "boiled pasta 200 g"), false, Instant.parse("2026-09-30T11:36:00Z")),
                Message("m2", TODAY, l("Сосиски ремит рубленые 50 грамм", "Remit sausages 50 g"), false, Instant.parse("2026-09-30T12:47:00Z")),
                Message("m3", TODAY, l("борщ тарелка", "a plate of borscht"), false, Instant.parse("2026-09-30T13:20:00Z")),
            ),
        )
    }

    @Test fun mainChatDark() = shot("main_chat_dark", dark = true) {
        MainScreen(buildState(Local(TODAY, TODAY), chatContent(), emptyList(), Profile(null, 2200)), Noop, emptyFlow())
    }

    /** The sausages opened: their numbers, and the question and the pick folded inside. */
    @Test fun mainChatOpenedDark() = shot("main_chat_opened_dark", dark = true) {
        MainScreen(buildState(Local(TODAY, TODAY, editing = EditState("2", "сосиски ремит рубленые", "50")), chatContent(),
            emptyList(), Profile(null, 2200)), Noop, emptyFlow())
    }

    @Test fun mainChatEnglishLight() {
        dev.dietapp.data.domain.Lang.current = dev.dietapp.data.domain.Language.En
        dev.dietapp.coreui.CoreTexts.english = true
        try {
            shot("main_chat_en_light", dark = false) {
                MainScreen(buildState(Local(TODAY, TODAY), chatContent(english = true), emptyList(), Profile(null, 2200)), Noop, emptyFlow())
            }
        } finally {
            dev.dietapp.data.domain.Lang.current = dev.dietapp.data.domain.Language.Ru
            dev.dietapp.coreui.CoreTexts.english = false
        }
    }

    /** An entry opened: its numbers for the portion and per 100 g. */
    @Test fun mainEntryFactsDark() = shot("main_entry_facts_dark", dark = true) {
        val content = DayContent(
            entries = listOf(
                entry("1", "макароны вареные", 200.0, 316.0, at = "2026-09-30T11:36:00Z").copy(mealId = "m1", protein = 11.6, fat = 1.8,
                    carbs = 61.8, per100 = Per100(158.0, 5.8, 0.9, 30.9), foodName = "Pasta, cooked, enriched, without added salt"),
            ),
            outbox = emptyList(), notes = emptyList(), weights = emptyList(),
            messages = listOf(Message("m1", TODAY, "Макароны вареные 200 грамм", false, Instant.parse("2026-09-30T11:36:00Z"))),
        )
        MainScreen(buildState(Local(TODAY, TODAY, editing = EditState("1", "макароны вареные", "200")), content, emptyList(),
            Profile(null, 2200)), Noop, emptyFlow())
    }

    @Test fun mainEmptyLight() = shot("main_empty_light", dark = false) {
        MainScreen(buildState(Local(TODAY, TODAY), DayContent.Empty, emptyList(), Profile("me@example.com", 1900)), Noop, emptyFlow())
    }

    @Test fun foodsLight() = shot("foods_light", dark = false) {
        val now = Instant.parse("2026-09-30T12:40:05Z")
        FoodsScreen(
            FoodsUiState(
                query = "протеин",
                mine = listOf(
                    Food(1, "казеиновый протеин", listOf("казеин"), Per100(360.0, 80.0, 1.5, 8.0), true, "типичная этикетка", now),
                    Food(2, "протеиновый батончик Bombbar", emptyList(), Per100(316.0, 30.0, 10.0, 26.0), false, "с упаковки", now),
                ),
                builtIn = listOf(
                    FoodHit("usda:14063", "Beverages, protein powder whey based", Per100(352.0, 78.1, 1.6, 6.3), FoodHit.Source.Usda, false),
                    FoodHit("usda:16122", "Soy protein isolate", Per100(335.0, 88.3, 3.4, 0.0), FoodHit.Source.Usda, false),
                ),
                total = 2,
            ),
            NoopFoods, {}, {}, {},
        )
    }

    @Test fun foodEditorDark() = shot("food_editor_dark", dark = true) {
        FoodsScreen(
            FoodsUiState(
                editing = FoodEdit(1, "казеиновый протеин", "казеин", "360", "80", "1.5", "8", "типичная этикетка", estimated = true),
                total = 2,
            ),
            NoopFoods, {}, {}, {},
        )
    }

    /** The launcher icon as a round launcher would show it. */
    @Test fun appIcon() = shot("app_icon", dark = false) {
        Box(Modifier.size(192.dp).clip(CircleShape).background(Color(0xFFFBF6EC)), contentAlignment = Alignment.Center) {
            Image(painterResource(dev.dietapp.R.mipmap.ic_launcher_foreground), null, Modifier.size(192.dp * 108 / 72))
        }
    }

    @Test fun loginLight() = shot("login_light", dark = false) {
        LoginScreen(LoginUiState(loading = false, step = LoginStep.Code, email = "me@example.com", code = "48", error = null),
            {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun loginEmailLight() = shot("login_email_light", dark = false) {
        LoginScreen(LoginUiState(loading = false, step = LoginStep.Email), {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun loginKeyLight() = shot("login_key_light", dark = false) {
        LoginScreen(LoginUiState(loading = false, serverEnabled = false, step = LoginStep.Key, key = "sk-3f9a2c61d0b84e7a"),
            {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun settingsDark() = shot("settings_dark", dark = true) {
        SettingsScreen(
            SettingsUiState(
                email = "me@example.com", savedGoal = 1900,
                history = listOf(29, 28, 27, 26).mapIndexed { i, d ->
                    dev.dietapp.data.domain.DaySummary(LocalDate.parse("2026-09-$d"), dev.dietapp.data.domain.Totals(1420.0 + i * 130, 90.0, 50.0, 140.0))
                },
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test fun settingsLocalLight() = shot("settings_local_light", dark = false) {
        SettingsScreen(
            SettingsUiState(
                savedGoal = 1900, local = true, hasKey = false,
                history = listOf(29, 28, 27).mapIndexed { i, d ->
                    dev.dietapp.data.domain.DaySummary(LocalDate.parse("2026-09-$d"), dev.dietapp.data.domain.Totals(1420.0 + i * 130, 90.0, 50.0, 140.0))
                },
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
