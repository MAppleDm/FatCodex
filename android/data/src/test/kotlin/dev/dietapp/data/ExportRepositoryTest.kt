package dev.dietapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.db.WeightRow
import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.BodyProfile
import dev.dietapp.data.domain.Lang
import dev.dietapp.data.domain.Workout
import dev.dietapp.data.domain.WorkoutKind
import dev.dietapp.data.domain.Language
import dev.dietapp.data.domain.Sex
import dev.dietapp.data.export.ExportFormat
import dev.dietapp.data.export.ExportPeriod
import dev.dietapp.data.net.AppError
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The diary leaving the phone as a file: real Room, the clock stopped on 2026-09-30. */
@RunWith(AndroidJUnit4::class)
class ExportRepositoryTest {
    private lateinit var env: TestEnv

    @Before fun setUp() {
        env = TestEnv()
        Lang.current = Language.Ru
    }

    @After fun tearDown() {
        Lang.current = Language.Ru
        env.close()
    }

    private fun at(day: String, h: Int, m: Int) = Instant.parse("${day}T%02d:%02d:00Z".format(h, m)).toEpochMilli()

    private suspend fun seed() {
        // the goal is what a day costs plus the person's correction: a 30-year-old man of 178 cm, 82.6 kg (the latest weigh-in
        // below), sitting all day, burns 2150 kcal; less 250 is 1900
        env.bodyStore.update { BodyProfile(Sex.Male, 1996, 178, Activity.Sedentary, adjustment = -250) }
        env.db.entries().upsert(
            listOf(
                entryRow("e1", "гречка", 200.0, day = "2026-09-30").copy(mealId = "m1", eatenAtMs = at("2026-09-30", 8, 15), position = 0),
                entryRow("e2", "сосиски, \"Ремит\"", 50.0, status = "uncertain", day = "2026-09-30")
                    .copy(mealId = "m1", eatenAtMs = at("2026-09-30", 8, 15), position = 1),
                entryRow("e3", "абырвалг", 30.0, status = "unmatched", matched = false, day = "2026-09-30")
                    .copy(mealId = "m2", eatenAtMs = at("2026-09-30", 13, 0)),
                entryRow("e4", "удалённое", 100.0, day = "2026-09-30", deleted = true),
                entryRow("e5", "ждёт", 100.0, day = "2026-09-30").copy(pending = true),
                entryRow("e6", "овсянка", 60.0, day = "2026-09-20").copy(eatenAtMs = at("2026-09-20", 7, 0)),
                entryRow("e7", "старое", 100.0, day = "2026-06-01").copy(eatenAtMs = at("2026-06-01", 7, 0)),
            ),
        )
        env.db.weights().upsert(
            listOf(
                WeightRow("w1", "2026-09-25", 81.0, 1, deleted = false, dirty = false),
                WeightRow("w2", "2026-09-30", 82.6, 2, deleted = false, dirty = false),
                WeightRow("w3", "2026-09-29", 99.0, 3, deleted = true, dirty = false),
            ),
        )
    }

    private suspend fun file(format: ExportFormat, period: ExportPeriod = ExportPeriod.Month) =
        env.exports.export(format, period).getOrThrow()

    private fun jsonDays(text: String) =
        Json.parseToJsonElement(text).jsonObject["days"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonPrimitive.content }

    @Test fun `markdown has a section per day with a table, the weight and honest marks`() = runTest {
        seed()
        val md = file(ExportFormat.Markdown).text
        assertTrue(md, md.startsWith("# FatCodex: дневник питания\n"))
        assertTrue(md, md.contains("Период: 2026-09-20 … 2026-09-30 · дней с едой: 2 · цель 1900 ккал в день"))
        assertTrue(md, md.contains(" · 230 ккал · Б "))
        assertTrue(md, md.contains("| 08:15 | гречка | 200 | 184 | 6.8 | 1.2 | 39.9 |"))
        assertTrue("an approximate entry is marked", md.contains("| 08:15 | сосиски, \"Ремит\" | 50 | ~46 |"))
        assertTrue("an entry without numbers is a dash, and says so", md.contains("| 13:00 | абырвалг | 30 | — | — | — | — |") && md.contains("Без цифр: 1"))
        assertTrue(md, md.contains("Вес: 82.6 кг (тренд 81.8)"))
        assertTrue("oldest day first", md.indexOf("## 2026-09-20") < md.indexOf("## 2026-09-30"))
        for (hidden in listOf("удалённое", "ждёт", "старое", "99")) assertFalse("$hidden is not in the diary", md.contains(hidden))
    }

    private suspend fun seedDishAndWatch() {
        val parts = listOf(
            dev.dietapp.data.domain.DishPart("свёкла", 40.0, dev.dietapp.data.domain.Per100(43.0, 1.6, 0.2, 9.6)),
            dev.dietapp.data.domain.DishPart("говядина", 50.0, dev.dietapp.data.domain.Per100(250.0, 26.0, 15.0, 0.0)),
        )
        val sum = dev.dietapp.data.domain.Dish.totals(parts)
        env.db.entries().upsert(
            entryRow("dish", "борщ", 90.0, day = "2026-09-30").copy(
                kcal = sum.kcal, protein = sum.protein, fat = sum.fat, carbs = sum.carbs, eatenAtMs = at("2026-09-30", 9, 0),
                parts = dev.dietapp.data.local.DishCodec.encode(parts),
            ),
        )
        env.db.dayFacts().upsert(
            dev.dietapp.data.db.DayFactsRow("2026-09-30", 440, 8412, dev.dietapp.data.local.WorkoutCodec.encode(listOf(Workout(WorkoutKind.Running, 45))), 1),
        )
        // a day with the watch's words and no food at all
        env.db.dayFacts().upsert(dev.dietapp.data.db.DayFactsRow("2026-09-28", null, 4000, null, 1))
    }

    @Test fun `a dish carries its ingredients into the json and the markdown`() = runTest {
        seed(); seedDishAndWatch()
        val root = Json.parseToJsonElement(file(ExportFormat.Json).text).jsonObject
        val day = root["days"]!!.jsonArray.map { it.jsonObject }.first { it["date"]!!.jsonPrimitive.content == "2026-09-30" }
        val dish = day["entries"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "борщ" }
        val parts = dish["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("свёкла", "говядина"), parts.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(40.0, parts[0]["grams"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertTrue("a plain food has no parts", day["entries"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "гречка" }["parts"] == null)
        assertTrue(root["legend"]!!.jsonObject.containsKey("parts"))
        val md = file(ExportFormat.Markdown).text
        assertTrue(md, md.contains("> борщ: свёкла 40 г, говядина 50 г"))
    }

    @Test fun `what the watch said goes into every format that has days`() = runTest {
        seed(); seedDishAndWatch()
        val root = Json.parseToJsonElement(file(ExportFormat.Json).text).jsonObject
        val days = root["days"]!!.jsonArray.map { it.jsonObject }
        val watch = days.first { it["date"]!!.jsonPrimitive.content == "2026-09-30" }["watch"]!!.jsonObject
        assertEquals(440, watch["sleep_min"]!!.jsonPrimitive.content.toInt())
        assertEquals(8412, watch["steps"]!!.jsonPrimitive.content.toInt())
        assertTrue("no calories from the watch", watch["active_kcal"] == null && watch["total_kcal"] == null)
        assertTrue("nothing about the heart is exported", watch["resting_hr"] == null && watch["hrv_ms"] == null)
        assertEquals("running", watch["workouts"]!!.jsonArray.single().jsonObject["kind"]!!.jsonPrimitive.content)
        assertTrue("a day with only the watch is in the file", days.any { it["date"]!!.jsonPrimitive.content == "2026-09-28" })
        assertTrue(days.first { it["date"]!!.jsonPrimitive.content == "2026-09-20" }["watch"] == null)

        val md = file(ExportFormat.Markdown).text
        assertTrue(md, md.contains("Часы: сон 7 ч 20 м · шаги 8412 · тренировки: running 45 мин"))
        assertTrue(md, md.contains("## 2026-09-28"))
    }

    @Test fun `the watch has its own csv, one row a day`() = runTest {
        seed(); seedDishAndWatch()
        val lines = file(ExportFormat.CsvWatch).text.trim().lines()
        assertEquals("date,sleep_min,steps,workout_min,workouts", lines[0])
        assertEquals("2026-09-28,,4000,,", lines[1])
        assertEquals("2026-09-30,440,8412,45,running 45", lines[2])
        assertEquals("fatcodex-watch-30d-2026-09-30.csv", file(ExportFormat.CsvWatch).name)
    }

    @Test fun `a period with only the watch in it is not empty`() = runTest {
        env.db.dayFacts().upsert(dev.dietapp.data.db.DayFactsRow("2026-09-29", 400, null, null, 1))
        assertTrue(env.exports.export(ExportFormat.CsvWatch, ExportPeriod.Week).isSuccess)
    }

    @Test fun `markdown speaks the language of the app`() = runTest {
        seed()
        Lang.current = Language.En
        val md = file(ExportFormat.Markdown).text
        assertTrue(md, md.startsWith("# FatCodex: food diary\n"))
        assertTrue(md, md.contains("Weight: 82.6 kg (trend 81.8)"))
        assertTrue(md, md.contains("Without numbers: 1"))
    }

    @Test fun `a markdown table cell cannot be broken by the food's name`() = runTest {
        env.db.entries().upsert(entryRow("e1", "суп | борщ\nс хлебом", 300.0, day = "2026-09-30").copy(eatenAtMs = at("2026-09-30", 12, 0)))
        val md = file(ExportFormat.Markdown).text
        assertTrue(md, md.contains("| 12:00 | суп \\| борщ с хлебом | 300 |"))
    }

    @Test fun `csv has one row per recorded food, quoted where it must be`() = runTest {
        seed()
        val lines = file(ExportFormat.CsvFood, ExportPeriod.Week).text.trimEnd().lines()
        assertEquals("date,time,meal_id,name,grams,kcal,protein_g,fat_g,carbs_g,kcal_per_100g,matched_food,status,source", lines[0])
        assertEquals("three foods in the last week; none deleted, unconfirmed or older", 4, lines.size)
        assertEquals("2026-09-30,08:15,m1,гречка,200,184,6.8,1.2,39.9,92,,ok,text", lines[1])
        assertEquals("2026-09-30,08:15,m1,\"сосиски, \"\"Ремит\"\"\",50,46,1.7,0.3,10,92,,uncertain,text", lines[2])
        assertEquals("a food without numbers has empty cells, not zeros", "2026-09-30,13:00,m2,абырвалг,30,,,,,,,unmatched,text", lines[3])
    }

    @Test fun `weight csv has the trend and leaves out deleted weigh-ins`() = runTest {
        seed()
        assertEquals(
            "date,kg,trend_7d_kg\n2026-09-25,81,81\n2026-09-30,82.6,81.8\n",
            file(ExportFormat.CsvWeight).text,
        )
    }

    @Test fun `json carries totals, the legend and nulls for what is unknown`() = runTest {
        seed()
        val root = Json.parseToJsonElement(file(ExportFormat.Json).text).jsonObject
        assertEquals("fatcodex-diary", root["format"]!!.jsonPrimitive.content)
        assertEquals(1900, root["goal_kcal"]!!.jsonPrimitive.content.toInt())
        assertTrue(root["legend"]!!.jsonObject["status"]!!.jsonObject.keys.containsAll(listOf("ok", "uncertain", "unmatched")))
        val days = root["days"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-09-20", "2026-09-25", "2026-09-30"), days.map { it["date"]!!.jsonPrimitive.content })
        val last = days.last()
        assertEquals(230.0, last["totals"]!!.jsonObject["kcal"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1, last["entries_without_numbers"]!!.jsonPrimitive.content.toInt())
        assertEquals(82.6, last["weight_kg"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val entries = last["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("e1", "e2", "e3"), entries.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(JsonNull, entries[2]["kcal"])
        assertEquals(JsonNull, entries[2]["per100"])
        assertEquals(92.0, (entries[0]["per100"] as JsonObject)["kcal"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("uncertain", entries[1]["status"]!!.jsonPrimitive.content)
    }

    @Test fun `the period is counted back from today, and all means all`() = runTest {
        seed()
        assertEquals(listOf("2026-09-25", "2026-09-30"), jsonDays(file(ExportFormat.Json, ExportPeriod.Week).text))
        assertEquals(listOf("2026-09-20", "2026-09-25", "2026-09-30"), jsonDays(file(ExportFormat.Json, ExportPeriod.Month).text))
        assertEquals("90 days back from the 30th of September reach the 3rd of July", 3, jsonDays(file(ExportFormat.Json, ExportPeriod.Quarter).text).size)
        assertEquals(listOf("2026-06-01", "2026-09-20", "2026-09-25", "2026-09-30"), jsonDays(file(ExportFormat.Json, ExportPeriod.All).text))
    }

    @Test fun `the file is named for what it is, how far back it goes and the day`() = runTest {
        seed()
        assertEquals("fatcodex-diary-30d-2026-09-30.md", file(ExportFormat.Markdown).name)
        assertEquals("fatcodex-diary-all-2026-09-30.json", file(ExportFormat.Json, ExportPeriod.All).name)
        assertEquals("fatcodex-food-7d-2026-09-30.csv", file(ExportFormat.CsvFood, ExportPeriod.Week).name)
        assertEquals("fatcodex-weight-90d-2026-09-30.csv", file(ExportFormat.CsvWeight, ExportPeriod.Quarter).name)
        assertEquals("text/markdown", file(ExportFormat.Markdown).mime)
    }

    @Test fun `nothing to export is an error to show, not an empty file`() = runTest {
        val empty = env.exports.export(ExportFormat.Markdown, ExportPeriod.All).exceptionOrNull() as AppError
        assertEquals("empty", empty.code)
        // what was deleted, or is still waiting for "записать", is not in the diary
        env.db.entries().upsert(listOf(entryRow("e1", "было", 100.0, deleted = true), entryRow("e2", "ждёт", 100.0).copy(pending = true)))
        env.db.weights().upsert(WeightRow("w1", "2026-09-25", 81.0, 1, deleted = true, dirty = false))
        assertEquals("empty", (env.exports.export(ExportFormat.CsvFood, ExportPeriod.All).exceptionOrNull() as AppError).code)
    }
}
