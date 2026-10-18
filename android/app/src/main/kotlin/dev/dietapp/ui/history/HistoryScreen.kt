package dev.dietapp.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppColors
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.formatInt
import dev.dietapp.coreui.tap
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Lang
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

private val DescriptionFormat get() = DateTimeFormatter.ofPattern(if (Lang.en) "EEEE, MMMM d" else "d MMMM, EEEE", Lang.current.locale)
private val MonthFormat get() = DateTimeFormatter.ofPattern("LLL", Lang.current.locale)

/** At least this many weeks are shown, so that yesterday can be picked on a Monday and a new diary does not look bare. */
private const val MIN_WEEKS = 4

private val CellShape = RoundedCornerShape(4.dp)

/**
 * Every day on a calendar of weeks, the oldest at the top and today at the bottom (it opens at the bottom, as the chat does),
 * each day a square. The deeper the fill, the more was eaten that day
 * against the goal (against the fullest day when there is no goal): a view of how the diary looks, not a verdict, so there is no
 * good and no bad colour and a day over the goal is the same as one at it. Today has an orange ring. A tap opens that day in the
 * diary, empty days too: that is how yesterday's dinner is written down. In the corner, the way to take it all out of the
 * app as a file.
 */
@Composable
fun HistoryScreen(
    days: List<DaySummary>,
    goal: Int?,
    onBack: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onOpenExport: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    Column(modifier.fillMaxSize().background(AppTheme.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp)) {
            IconAction(onBack, Texts.CD_BACK) { BackIcon(it) }
            Text(Texts.HISTORY, style = AppTheme.type.body, modifier = Modifier.padding(start = 4.dp).weight(1f))
            TextAction(Texts.HISTORY_EXPORT, onOpenExport, Modifier.testTag("history-export"), color = AppTheme.colors.secondary)
        }
        Hairline()
        if (days.isEmpty()) {
            Text(Texts.NO_HISTORY, style = AppTheme.type.secondary, modifier = Modifier.padding(16.dp).testTag("history-empty"))
            return@Column
        }
        val byDay = remember(days) { days.associateBy { it.day } }
        val reference = goal?.takeIf { it > 0 }?.toDouble() ?: days.maxOf { it.totals.kcal }.coerceAtLeast(1.0)
        val weeks = remember(days, today) { weeksOldestFirst(days.minOf { it.day }, today) }
        // opens on the latest weeks; a calendar shorter than the screen simply starts at the top
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = weeks.lastIndex)

        WeekdayHeader()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history"), state = listState) {
            items(weeks, key = { it.toString() }) { monday -> WeekRow(monday, today, byDay, reference, onPickDay) }
        }
        Hairline()
        Legend(reference)
    }
}

/** The Mondays of the weeks from the one of [first] (or the last [MIN_WEEKS]) to the one of [today], the oldest first. */
internal fun weeksOldestFirst(first: LocalDate, today: LocalDate): List<LocalDate> {
    val monday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
    val newest = today.with(monday)
    val oldest = minOf(first, today.minusWeeks((MIN_WEEKS - 1).toLong())).with(monday)
    return generateSequence(newest) { it.minusWeeks(1) }.takeWhile { !it.isBefore(oldest) }.toList().asReversed()
}

/** How deep the fill of a day with [kcal] is, from 0 (the least) to 1 (the goal or more). */
internal fun heatFraction(kcal: Double, reference: Double): Float = (kcal / reference).coerceIn(0.0, 1.0).toFloat()

/**
 * Black on a light fill, white on a dark one, switching where both read equally well (relative luminance 0.18): every fill of the
 * calendar, in both themes, gives its date at least 4.5 to 1.
 */
internal fun textOn(fill: Color): Color = if (fill.luminance() > 0.18f) Color.Black else Color.White

private fun fillOf(summary: DaySummary?, reference: Double, c: AppColors): Color =
    if (summary == null) c.heatEmpty else lerp(c.heatLow, c.heatHigh, heatFraction(summary.totals.kcal, reference))

@Composable
private fun WeekdayHeader() {
    val names = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT_STANDALONE, Lang.current.locale).removeSuffix(".") }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp + MONTH_COLUMN, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        names.forEach {
            Text(
                it, style = AppTheme.type.caption.copy(color = AppTheme.colors.tertiary, fontSize = 11.sp),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
            )
        }
    }
}

private val MONTH_COLUMN = 36.dp

@Composable
private fun WeekRow(monday: LocalDate, today: LocalDate, byDay: Map<LocalDate, DaySummary>, reference: Double, onPick: (LocalDate) -> Unit) {
    val dates = (0L..6L).map { monday.plusDays(it) }
    // the month is named on the week that holds its first day, if that day is there (not one that has not come yet)
    val first = dates.firstOrNull { it.dayOfMonth == 1 && !it.isAfter(today) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            first?.format(MonthFormat)?.removeSuffix(".").orEmpty(),
            style = AppTheme.type.caption.copy(color = AppTheme.colors.secondary), maxLines = 1, modifier = Modifier.width(MONTH_COLUMN),
        )
        dates.forEach { date ->
            Box(Modifier.weight(1f).padding(2.dp).aspectRatio(1f)) {
                if (!date.isAfter(today)) DayCell(date, byDay[date], reference, date == today, onPick)
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, summary: DaySummary?, reference: Double, isToday: Boolean, onPick: (LocalDate) -> Unit) {
    val c = AppTheme.colors
    val fill = fillOf(summary, reference, c)
    val textColor = if (summary == null) c.secondary else textOn(fill)
    val description = date.format(DescriptionFormat) + ", " +
        (summary?.let { "${formatInt(it.totals.kcal.roundToInt())} ${Texts.KCAL}" } ?: Texts.HISTORY_NOTHING)
    Box(
        Modifier.fillMaxSize()
            .clip(CellShape)
            .background(fill)
            .then(if (isToday) Modifier.border(1.5.dp, c.time, CellShape) else Modifier)
            .tap { onPick(date) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("day-$date"),
    ) {
        Text(
            date.dayOfMonth.toString(), style = AppTheme.type.monoSecondary.copy(fontSize = 11.sp, lineHeight = 14.sp, color = textColor),
            modifier = Modifier.padding(start = 5.dp, top = 3.dp),
        )
    }
}

/** "0 [five fills from none to the goal] 1 900 ккал": what the fills stand for. */
@Composable
private fun Legend(reference: Double) {
    val c = AppTheme.colors
    val steps = listOf(c.heatEmpty, lerp(c.heatLow, c.heatHigh, 0f), lerp(c.heatLow, c.heatHigh, 0.33f), lerp(c.heatLow, c.heatHigh, 0.66f), c.heatHigh)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}.testTag("history-legend"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("0", style = AppTheme.type.monoSecondary.copy(fontSize = 12.sp))
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            steps.forEach { Box(Modifier.size(14.dp).clip(CellShape).background(it)) }
        }
        Spacer(Modifier.width(8.dp))
        Text("${formatInt(reference.roundToInt())} ${Texts.KCAL}", style = AppTheme.type.monoSecondary.copy(fontSize = 12.sp))
    }
}
