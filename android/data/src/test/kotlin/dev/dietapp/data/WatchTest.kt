package dev.dietapp.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.domain.DayFacts
import dev.dietapp.data.domain.Workout
import dev.dietapp.data.domain.WorkoutKind
import dev.dietapp.data.repo.WatchState
import dev.dietapp.data.watch.HealthConnectSource
import dev.dietapp.data.watch.Session
import dev.dietapp.data.watch.WatchAvailability
import dev.dietapp.data.watch.WatchMath
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What is done with the numbers a watch leaves in Health Connect, apart from the SDK. */
class WatchMathTest {
    private fun at(h: Int, m: Int) = Instant.parse("2026-10-07T%02d:%02d:00Z".format(h, m))

    @Test fun `a workout recorded twice by two apps counts once`() {
        val sessions = listOf(
            Session(WorkoutKind.Running, at(7, 0), at(7, 45)),
            Session(WorkoutKind.Running, at(7, 3), at(7, 44)),
        )
        assertEquals(listOf(Workout(WorkoutKind.Running, 45)), WatchMath.workouts(sessions))
    }

    @Test fun `two different workouts in a day are two, in the order they happened`() {
        val sessions = listOf(
            Session(WorkoutKind.Yoga, at(19, 0), at(19, 30)),
            Session(WorkoutKind.Running, at(7, 0), at(7, 45)),
            Session(WorkoutKind.Running, at(18, 0), at(18, 20)),
        )
        assertEquals(
            listOf(Workout(WorkoutKind.Running, 45), Workout(WorkoutKind.Running, 20), Workout(WorkoutKind.Yoga, 30)),
            WatchMath.workouts(sessions),
        )
    }

    @Test fun `a few minutes is not a workout`() {
        assertTrue(WatchMath.workouts(listOf(Session(WorkoutKind.Walking, at(12, 0), at(12, 6)))).isEmpty())
        assertEquals(1, WatchMath.workouts(listOf(Session(WorkoutKind.Walking, at(12, 0), at(12, WatchMath.MIN_WORKOUT_MINUTES)))).size)
    }

    @Test fun `the watch's own codes are told as what a person did`() {
        assertEquals(WorkoutKind.Running, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals(WorkoutKind.Running, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
        assertEquals(WorkoutKind.Cycling, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY))
        assertEquals(WorkoutKind.Strength, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING))
        assertEquals(WorkoutKind.Swimming, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL))
        assertEquals(WorkoutKind.Yoga, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_PILATES))
        assertEquals(WorkoutKind.Hiking, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
        assertEquals(WorkoutKind.Other, HealthConnectSource.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON))
    }
}

/** The watch as the diary sees it: connected by the person, read when the app opens, never overwriting with nothing. */
@RunWith(AndroidJUnit4::class)
class WatchRepositoryTest {
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-09-30") // the test clock

    @After fun tearDown() = env.close()

    private fun allowed() { env.watchSource.granted = env.watchSource.permissions }

    @Test fun `nothing is read until the person connects it`() = runTest {
        env.watch.refresh()
        assertTrue(env.watchSource.reads.isEmpty())
        assertFalse(env.watch.state.first().connected)
    }

    @Test fun `a refusal in the permission dialog connects nothing`() = runTest {
        assertFalse(env.watch.connect(emptySet()))
        assertFalse(env.watchStore.enabled.value)
        assertTrue(env.watchSource.reads.isEmpty())
    }

    @Test fun `connecting reads the last month and remembers it`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, sleepMinutes = 440, steps = 8412, workouts = listOf(Workout(WorkoutKind.Running, 45)))
        env.watchSource.facts[today.minusDays(29)] = DayFacts(today.minusDays(29), steps = 5000)
        assertTrue(env.watch.connect(env.watchSource.permissions))
        assertEquals(30, env.watchSource.reads.size)
        val state = env.watch.state.first()
        assertTrue(state.connected && state.permitted)
        assertEquals(Instant.parse("2026-09-30T05:15:00Z"), state.lastReadAt)
        val facts = env.diary.observeDay(today).first().facts!!
        assertEquals(440, facts.sleepMinutes)
        assertEquals(8412, facts.steps)
        assertEquals(listOf(Workout(WorkoutKind.Running, 45)), facts.workouts)
        assertEquals(5000, env.db.dayFacts().get(today.minusDays(29).toString())!!.steps)
    }

    @Test fun `what came in for today is one line of the journal`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, steps = 14619, weightKg = 119.4)
        env.watch.connect(env.watchSource.permissions)
        val journal = env.journal.read()
        assertTrue(journal, journal.contains("day 2026-09-30") && journal.contains("steps 14619") && journal.contains("weight 119.4 kg"))
        assertTrue("nothing about calories", !journal.contains("kcal"))
    }

    // ---------- smart scales ----------

    private suspend fun weightOf(day: LocalDate) = env.db.weights().latestForDay(day.toString())

    @Test fun `a weigh-in from the scales becomes the day's weight in the diary`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, weightKg = 119.4)
        env.watch.connect(env.watchSource.permissions)
        assertEquals(119.4, weightOf(today)!!.kg, 0.0)
        assertNull("a weigh-in is not a fact of the day", env.db.dayFacts().get(today.toString()))
    }

    @Test fun `a weight written by hand that day wins over the scales`() = runTest {
        allowed()
        env.db.weights().upsert(dev.dietapp.data.db.WeightRow("mine", today.toString(), 118.9, 1, deleted = false, dirty = false))
        env.watchSource.facts[today] = DayFacts(today, weightKg = 119.4)
        env.watch.connect(env.watchSource.permissions)
        assertEquals(118.9, weightOf(today)!!.kg, 0.0)
        assertEquals(1, env.db.weights().all().size)
    }

    @Test fun `a weigh-in from the scales that was removed does not come back, and a new reading of the scales is taken`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, weightKg = 119.4)
        env.watch.connect(env.watchSource.permissions)
        val row = weightOf(today)!!
        env.db.weights().upsert(row.copy(deleted = true))
        env.watch.refresh()
        assertNull(weightOf(today))

        // another day, the scales say something new: it comes in, and a corrected reading replaces it
        env.watchSource.facts[today.minusDays(1)] = DayFacts(today.minusDays(1), weightKg = 120.0)
        env.watch.refresh()
        assertEquals(120.0, weightOf(today.minusDays(1))!!.kg, 0.0)
        env.watchSource.facts[today.minusDays(1)] = DayFacts(today.minusDays(1), weightKg = 119.8)
        env.watch.refresh()
        assertEquals(119.8, weightOf(today.minusDays(1))!!.kg, 0.0)
    }

    @Test fun `a permission that was added later shows as missing, and is whole once it is given`() = runTest {
        // connected before the weight was read: the older permissions are there, the new one is not
        env.watchSource.granted = env.watchSource.permissions - "p.weight"
        assertTrue(env.watch.connect(env.watchSource.granted))
        val partial = env.watch.state.first()
        assertTrue(partial.connected && partial.permitted)
        assertFalse("one kind of data cannot be read yet", partial.complete)

        env.watchSource.granted = env.watchSource.permissions
        assertTrue(env.watch.connect(env.watchSource.permissions))
        assertTrue(env.watch.state.first().complete)
    }

    @Test fun `with every permission given the connection is whole`() = runTest {
        allowed()
        env.watch.connect(env.watchSource.permissions)
        assertTrue(env.watch.state.first().complete)
    }

    @Test fun `a day with nothing to say leaves no row`() = runTest {
        allowed()
        env.watch.connect(env.watchSource.permissions)
        assertNull(env.db.dayFacts().get(today.toString()))
        assertNull(env.diary.observeDay(today).first().facts)
    }

    @Test fun `opening the app reads today and the two days before, and what was known is kept when the watch has nothing`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, steps = 3000)
        env.watch.connect(env.watchSource.permissions)
        env.watchSource.reads.clear()
        env.watchSource.facts.clear() // the phone has lost the day meanwhile
        env.watch.refresh()
        assertEquals(listOf(today, today.minusDays(1), today.minusDays(2)), env.watchSource.reads)
        assertEquals("the diary is a record", 3000, env.db.dayFacts().get(today.toString())!!.steps)
    }

    @Test fun `a day is replaced when the watch says more later`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, steps = 3000)
        env.watch.connect(env.watchSource.permissions)
        env.watchSource.facts[today] = DayFacts(today, steps = 9000, sleepMinutes = 400)
        env.watch.refresh()
        assertEquals(9000, env.db.dayFacts().get(today.toString())!!.steps)
        assertEquals(400, env.db.dayFacts().get(today.toString())!!.sleepMinutes)
    }

    @Test fun `a provider that fails costs nothing but that read`() = runTest {
        allowed()
        env.watch.connect(env.watchSource.permissions)
        env.watchSource.failing = true
        env.watch.refresh() // must not throw
        assertTrue(env.watchStore.enabled.value)
    }

    @Test fun `without Health Connect nothing is read and the page can say why`() = runTest {
        allowed()
        env.watch.connect(env.watchSource.permissions)
        env.watchSource.reads.clear()
        env.watchSource.availability = WatchAvailability.NotInstalled
        env.watch.refresh()
        assertTrue(env.watchSource.reads.isEmpty())
        assertEquals(WatchAvailability.NotInstalled, env.watch.state.first().availability)
        env.watchSource.availability = WatchAvailability.UpdateNeeded
        env.watch.refreshPhone()
        assertEquals(WatchAvailability.UpdateNeeded, env.watch.state.first().availability)
        assertFalse(env.watch.state.first().permitted)
    }

    @Test fun `permissions taken back in Health Connect show as not permitted, and nothing is read`() = runTest {
        allowed()
        env.watch.connect(env.watchSource.permissions)
        env.watchSource.granted = emptySet()
        env.watch.refreshPhone()
        val state: WatchState = env.watch.state.first()
        assertTrue(state.connected)
        assertFalse(state.permitted)
        assertEquals(WatchAvailability.Ready, state.availability)
    }

    @Test fun `disconnecting stops the reading and keeps what was read`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, steps = 3000)
        env.watch.connect(env.watchSource.permissions)
        env.watch.disconnect()
        env.watchSource.reads.clear()
        env.watch.refresh()
        assertTrue(env.watchSource.reads.isEmpty())
        assertEquals(3000, env.db.dayFacts().get(today.toString())!!.steps)
        assertFalse(env.watch.state.first().connected)
    }

    @Test fun `erase all takes the watch with it`() = runTest {
        allowed()
        env.watchSource.facts[today] = DayFacts(today, steps = 3000)
        env.watch.connect(env.watchSource.permissions)
        env.auth.logout()
        assertNull(env.db.dayFacts().get(today.toString()))
        assertFalse(env.watchStore.enabled.value)
        assertNull(env.watchStore.readAt.value)
    }
}
