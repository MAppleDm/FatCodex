package dev.dietapp.ui

import dev.dietapp.coreui.countDurationMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long a number takes to count: never a blink for nothing, never a wait for a lot. */
class CountedTest {
    @Test fun `a small step is quick and a whole meal takes longer, but never more than a second`() {
        assertEquals("the shortest", 260, countDurationMs(0f))
        assertTrue(countDurationMs(5f) in 260..400)
        assertTrue(countDurationMs(184f) > countDurationMs(10f))
        assertTrue(countDurationMs(184f) in 500..800)
        assertEquals("the longest", 900, countDurationMs(1_000_000f))
    }

    @Test fun `going down takes as long as going up`() {
        assertEquals(countDurationMs(300f), countDurationMs(-300f))
    }

    @Test fun `a longer change never takes less time than a shorter one`() {
        var last = 0
        for (delta in listOf(1f, 2f, 5f, 20f, 100f, 400f, 2_000f, 10_000f)) {
            val ms = countDurationMs(delta)
            assertTrue("$delta: $ms after $last", ms >= last)
            last = ms
        }
    }
}
