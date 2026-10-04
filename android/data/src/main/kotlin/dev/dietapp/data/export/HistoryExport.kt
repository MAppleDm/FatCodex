package dev.dietapp.data.export

import dev.dietapp.data.domain.Entry
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Lang
import dev.dietapp.data.domain.Lang.t
import dev.dietapp.data.domain.Totals
import dev.dietapp.data.domain.TrendPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The diary as a file for a person, a spreadsheet or another model to read. The app itself does not analyse the history:
 * it records, and this is how it gets out. Every format carries the same facts; they differ in who reads them best.
 */
enum class ExportFormat(val extension: String, val mime: String, private val kind: String) {
    /** For a person, and for a chat with a model: one section per day. */
    Markdown("md", "text/markdown", "diary"),

    /** Everything, with the meaning of each field: for a program or a model that works with structured data. */
    Json("json", "application/json", "diary"),

    /** One row per eaten food, for a spreadsheet or pandas. */
    CsvFood("csv", "text/csv", "food"),

    /** One row per weigh-in day, with the 7-day trend. */
    CsvWeight("csv", "text/csv", "weight");

    /** fatcodex-food-30d-2026-10-04.csv: what it is, how far back, and the day it was made. */
    fun fileName(period: ExportPeriod, today: LocalDate): String = "fatcodex-$kind-${period.tag}-$today.$extension"
}

/** How far back the file goes: the last [days] days, today included, or everything. */
enum class ExportPeriod(val days: Int?, val tag: String) {
    Week(7, "7d"),
    Month(30, "30d"),
    Quarter(90, "90d"),
    All(null, "all"),
}

/** A finished file, held in memory (a diary is a few hundred kilobytes at most). */
class ExportFile(val name: String, val mime: String, val text: String)

/**
 * What goes into a file. [entries] are the recorded food in diary order (deleted and still-unconfirmed food is not in
 * the diary, so it is not here); [weights] one point per weigh-in day with the trend already worked out.
 */
data class ExportData(
    val entries: List<Entry>,
    val weights: List<TrendPoint>,
    val goalKcal: Int?,
    val zone: ZoneId,
    val exportedAt: Instant,
) {
    val days: List<LocalDate> = (entries.map { it.day } + weights.map { it.day }).distinct().sorted()
}

object HistoryExport {

    fun render(format: ExportFormat, data: ExportData): String = when (format) {
        ExportFormat.Markdown -> markdown(data)
        ExportFormat.Json -> json(data)
        ExportFormat.CsvFood -> csvFood(data)
        ExportFormat.CsvWeight -> csvWeight(data)
    }

    // ---------- CSV ----------

    private val FOOD_HEADER = listOf(
        "date", "time", "meal_id", "name", "grams", "kcal", "protein_g", "fat_g", "carbs_g",
        "kcal_per_100g", "matched_food", "status", "source",
    )

    private fun csvFood(d: ExportData): String = buildString {
        append(row(FOOD_HEADER))
        for (e in d.entries) {
            append(
                row(
                    listOf(
                        e.day.toString(), time(e, d.zone), e.mealId, e.name, num(e.grams), num(e.kcal), num(e.protein), num(e.fat),
                        num(e.carbs), num(e.per100?.kcal), e.foodName, e.status.toWire(), e.source.toWire(),
                    ),
                ),
            )
        }
    }

    private fun csvWeight(d: ExportData): String = buildString {
        append(row(listOf("date", "kg", "trend_7d_kg")))
        for (w in d.weights) append(row(listOf(w.day.toString(), num(w.raw), num(w.trend))))
    }

    /** RFC 4180: a cell with a comma, a quote or a line break is quoted, quotes are doubled. */
    private fun row(cells: List<String?>): String = cells.joinToString(",", postfix = "\n") { cell ->
        val v = cell.orEmpty()
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    // ---------- JSON ----------

    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun json(d: ExportData): String {
        val trendByDay = d.weights.associateBy { it.day }
        val root = buildJsonObject {
            put("format", "fatcodex-diary")
            put("version", 1)
            put("exported_at", d.exportedAt.toString())
            d.days.firstOrNull()?.let { put("from", it.toString()) }
            d.days.lastOrNull()?.let { put("to", it.toString()) }
            put("goal_kcal", d.goalKcal)
            putJsonObject("legend") {
                put("units", "grams for amounts and macros, kcal for energy; per100 values are per 100 g of the food")
                putJsonObject("status") {
                    put("ok", "numbers taken from a food database")
                    put("uncertain", "approximate: typical values for this kind of food, or the amount was a guess")
                    put("unmatched", "the food was not found: no numbers, not counted in the day's totals")
                }
                put("weight_trend_kg", "mean of the daily weights over the last 7 calendar days")
                put("source", "text, voice or photo: how the entry was made")
            }
            put("days", buildJsonArray {
                for (day in d.days) {
                    val entries = d.entries.filter { it.day == day }
                    val totals = Totals.of(entries)
                    add(buildJsonObject {
                        put("date", day.toString())
                        putJsonObject("totals") {
                            put("kcal", round1(totals.kcal))
                            put("protein_g", round1(totals.protein))
                            put("fat_g", round1(totals.fat))
                            put("carbs_g", round1(totals.carbs))
                        }
                        put("entries_without_numbers", entries.count { it.kcal == null })
                        trendByDay[day]?.let {
                            put("weight_kg", round1(it.raw))
                            put("weight_trend_kg", round1(it.trend))
                        }
                        put("entries", buildJsonArray {
                            for (e in entries) {
                                add(buildJsonObject {
                                    put("id", e.id)
                                    put("time", time(e, d.zone))
                                    put("meal_id", e.mealId)
                                    put("name", e.name)
                                    put("grams", round1(e.grams))
                                    put("kcal", e.kcal?.let(::round1))
                                    put("protein_g", e.protein?.let(::round1))
                                    put("fat_g", e.fat?.let(::round1))
                                    put("carbs_g", e.carbs?.let(::round1))
                                    put("per100", e.per100?.let { p ->
                                        buildJsonObject {
                                            put("kcal", round1(p.kcal))
                                            put("protein_g", round1(p.protein))
                                            put("fat_g", round1(p.fat))
                                            put("carbs_g", round1(p.carbs))
                                        }
                                    } ?: JsonNull)
                                    put("matched_food", e.foodName)
                                    put("status", e.status.toWire())
                                    put("source", e.source.toWire())
                                })
                            }
                        })
                    })
                }
            })
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: JsonElement?) {
        put(key, value ?: JsonNull)
    }

    // ---------- Markdown ----------

    private fun markdown(d: ExportData): String = buildString {
        val weekday = { day: LocalDate -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Lang.current.locale) }
        val trendByDay = d.weights.associateBy { it.day }
        appendLine(t("# FatCodex: дневник питания", "# FatCodex: food diary"))
        appendLine()
        val range = if (d.days.isEmpty()) "—" else "${d.days.first()} … ${d.days.last()}"
        val logged = d.entries.map { it.day }.distinct().size
        val goal = d.goalKcal?.let { t(" · цель ${it} ккал в день", " · goal $it kcal a day") }.orEmpty()
        appendLine(t("Период: $range · дней с едой: $logged$goal", "Period: $range · days with food: $logged$goal"))
        appendLine(t("Выгружено: ${DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(d.exportedAt.atZone(d.zone).toLocalDateTime().withNano(0))}",
            "Exported: ${DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(d.exportedAt.atZone(d.zone).toLocalDateTime().withNano(0))}"))
        appendLine()
        appendLine(
            t("Единицы: граммы и ккал; Б, Ж, У: белки, жиры, углеводы в граммах. «~»: приблизительно (типичные значения), " +
                "«—»: калорийность неизвестна (продукта нет в базе), такая запись не входит в итог дня.",
                "Units: grams and kcal; P, F, C: protein, fat, carbs in grams. “~”: approximate (typical values), " +
                    "“—”: energy unknown (the food is not in the base), such an entry is not counted in the day's total."),
        )

        val p = t("Б", "P")
        val f = t("Ж", "F")
        val c = t("У", "C")
        for (day in d.days) {
            val entries = d.entries.filter { it.day == day }
            val totals = Totals.of(entries)
            appendLine()
            val sum = if (entries.isEmpty()) "" else " · ${num(totals.kcal)} ${t("ккал", "kcal")} · $p ${num(totals.protein)} · $f ${num(totals.fat)} · $c ${num(totals.carbs)}"
            appendLine("## $day, ${weekday(day)}$sum")
            trendByDay[day]?.let {
                appendLine()
                appendLine(t("Вес: ${num(it.raw)} кг (тренд ${num(it.trend)})", "Weight: ${num(it.raw)} kg (trend ${num(it.trend)})"))
            }
            if (entries.isEmpty()) continue
            appendLine()
            appendLine("| ${t("Время", "Time")} | ${t("Продукт", "Food")} | ${t("г", "g")} | ${t("ккал", "kcal")} | $p | $f | $c |")
            appendLine("|---|---|---:|---:|---:|---:|---:|")
            for (e in entries) {
                val approx = if (e.status == EntryStatus.Uncertain) "~" else ""
                val cell = { v: Double? -> if (v == null) "—" else approx + num(v) }
                appendLine("| ${time(e, d.zone)} | ${cellText(e.name)} | ${num(e.grams)} | ${cell(e.kcal)} | ${cell(e.protein)} | ${cell(e.fat)} | ${cell(e.carbs)} |")
            }
            val missing = entries.count { it.kcal == null }
            if (missing > 0) {
                appendLine()
                appendLine(t("Без цифр: $missing (не вошли в итог дня)", "Without numbers: $missing (not in the day's total)"))
            }
        }
    }

    /** A table cell: no pipes, no line breaks. */
    private fun cellText(text: String) = text.replace("|", "\\|").replace(Regex("\\s+"), " ").trim()

    // ---------- numbers ----------

    private fun time(e: Entry, zone: ZoneId): String = e.eatenAt.atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    private fun round1(v: Double): Double = Math.round(v * 10) / 10.0

    /** A dot as the decimal mark whatever the phone's language; one decimal at most; "184", not "184.0". */
    private fun num(v: Double?): String =
        if (v == null) "" else String.format(Locale.ROOT, "%.1f", v).let { if (it.endsWith(".0")) it.dropLast(2) else it }
}
