package dev.dietapp.ui.history

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.dietapp.coreui.AppColors
import dev.dietapp.coreui.DarkColors
import dev.dietapp.coreui.LightColors
import dev.dietapp.coreui.MatchaColors
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calendar of the history: which weeks it has, how deep a day is filled, and that every colour of it can be read. */
class HistoryTest {
    private fun date(s: String) = LocalDate.parse(s)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---------- the weeks ----------

    @Test fun `the weeks run from the one of the first day to the one of today, Monday to Sunday, the oldest first`() {
        assertEquals("a Wednesday", DayOfWeek.WEDNESDAY, date("2026-09-30").dayOfWeek)
        val weeks = weeksOldestFirst(first = date("2026-08-01"), today = date("2026-09-30"))
        assertEquals("the Saturday of the first day belongs to the week that began on a Monday", date("2026-07-27"), weeks.first())
        assertEquals("the week of today is the last, at the bottom", date("2026-09-28"), weeks.last())
        assertEquals(10, weeks.size)
        assertTrue(weeks.all { it.dayOfWeek == DayOfWeek.MONDAY })
        assertEquals(weeks.sorted(), weeks)
    }

    @Test fun `a young diary still shows four weeks, and on a Monday yesterday is in the week before`() {
        val monday = date("2026-09-28")
        val weeks = weeksOldestFirst(first = monday, today = monday)
        assertEquals(listOf("2026-09-07", "2026-09-14", "2026-09-21", "2026-09-28").map(::date), weeks)
        assertTrue("yesterday, a Sunday, is in the week above the last", date("2026-09-27") in weeks[2]..weeks[2].plusDays(6))
    }

    @Test fun `data that is older than the four weeks stretches the calendar back to it`() {
        val weeks = weeksOldestFirst(first = date("2026-01-05"), today = date("2026-09-30"))
        assertEquals(date("2026-01-05"), weeks.first())
        assertEquals(date("2026-09-28"), weeks.last())
    }

    // ---------- how deep a day is ----------

    @Test fun `a day is as deep as it is close to the goal, and a day over the goal is the same as one at it`() {
        assertEquals(0f, heatFraction(0.0, 1900.0), 0f)
        assertEquals(0.5f, heatFraction(950.0, 1900.0), 0.001f)
        assertEquals(1f, heatFraction(1900.0, 1900.0), 0f)
        assertEquals("no verdict on eating more", 1f, heatFraction(3200.0, 1900.0), 0f)
    }

    // ---------- the colours ----------

    private fun ramp(c: AppColors, steps: Int = 20) = (0..steps).map { lerp(c.heatLow, c.heatHigh, it / steps.toFloat()) }

    @Test fun `the date in every filled square reads well enough, in all three themes`() {
        for ((name, c) in listOf("paper" to LightColors, "coal" to DarkColors, "matcha" to MatchaColors)) {
            for (fill in ramp(c)) {
                val ratio = contrast(textOn(fill), fill)
                assertTrue("$name $fill: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test fun `the date in an empty square is quiet but can be read`() {
        for ((name, c) in listOf("paper" to LightColors, "coal" to DarkColors, "matcha" to MatchaColors)) {
            val ratio = contrast(c.secondary, c.heatEmpty)
            assertTrue("$name: $ratio", ratio >= 4.0)
        }
    }

    @Test fun `more food is always deeper, lighter fill in the dark theme and darker in the light one`() {
        val light = ramp(LightColors).map { it.luminance() }
        val dark = ramp(DarkColors).map { it.luminance() }
        assertEquals(light.sortedDescending(), light)
        assertEquals(dark.sorted(), dark)
        assertTrue(light.first() - light.last() > 0.5)
        assertTrue(dark.last() - dark.first() > 0.4)
    }

    @Test fun `a day with the least food is told apart from a day with none`() {
        for ((name, c) in listOf("paper" to LightColors, "coal" to DarkColors, "matcha" to MatchaColors)) {
            assertTrue(name, kotlin.math.abs(c.heatEmpty.luminance() - c.heatLow.luminance()) > 0.02)
            assertTrue("$name: an empty day is told apart from the background", c.heatEmpty != c.background)
        }
    }
}
