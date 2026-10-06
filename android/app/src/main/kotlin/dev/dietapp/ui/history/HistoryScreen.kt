package dev.dietapp.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.BackIcon
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.StatRow
import dev.dietapp.coreui.TextAction
import dev.dietapp.coreui.formatInt
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Lang
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DayFormat get() = DateTimeFormatter.ofPattern(if (Lang.en) "EEE, MMM d" else "d MMM, EEE", Lang.current.locale)

/**
 * Everything about what has been eaten, in one place: the days with food, newest first, each with what was eaten
 * against the goal (a tap opens that day in the diary), and in the corner the way to take it out of the app: the
 * export as a file.
 */
@Composable
fun HistoryScreen(
    days: List<DaySummary>,
    goal: Int?,
    onBack: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onOpenExport: () -> Unit = {},
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history")) {
            items(days, key = { it.day.toString() }) { day ->
                val kcal = formatInt(day.totals.kcal.roundToInt())
                StatRow(
                    label = day.day.format(DayFormat),
                    value = goal?.let { "$kcal / ${formatInt(it)}" } ?: kcal,
                    modifier = Modifier.testTag("day-${day.day}"),
                    onClick = { onPickDay(day.day) },
                )
            }
        }
    }
}
