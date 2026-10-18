package dev.dietapp.data.watch

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.data.domain.DayFacts
import dev.dietapp.data.domain.Workout
import dev.dietapp.data.domain.WorkoutKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Reads a day from Health Connect: sleep, steps, workouts and the morning weigh-in of smart scales. Each kind is read on its
 * own and only if the person allowed it; one that fails (revoked in the middle, nothing recorded) is missing, not an error.
 * This class is the only place that knows the SDK. Calories burned are not read: what the watches put there was not right.
 */
@Singleton
class HealthConnectSource @Inject constructor(@ApplicationContext private val context: Context) : WatchSource {

    override val permissions: Set<String> = setOf(SLEEP, STEPS, EXERCISE, WEIGHT).map { HealthPermission.getReadPermission(it) }.toSet()

    override suspend fun availability(): WatchAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> WatchAvailability.Ready
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> WatchAvailability.UpdateNeeded
        else -> WatchAvailability.NotInstalled
    }

    private fun client() = HealthConnectClient.getOrCreate(context)

    override suspend fun granted(): Set<String> =
        runCatching { client().permissionController.getGrantedPermissions() }.getOrDefault(emptySet()).filter { it in permissions }.toSet()

    override suspend fun read(day: LocalDate, zone: ZoneId): DayFacts? {
        if (availability() != WatchAvailability.Ready) return null
        val allowed = granted()
        if (allowed.isEmpty()) return null
        fun may(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in allowed

        val client = client()
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        // the night that ends on this day: from the evening before to the evening of the day, so a nap counts too
        val nightFrom = day.minusDays(1).atTime(NIGHT_STARTS).atZone(zone).toInstant()
        val nightTo = day.atTime(NIGHT_STARTS).atZone(zone).toInstant()

        val steps = if (may(STEPS)) quiet {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))[StepsRecord.COUNT_TOTAL]?.toInt()
        } else null
        val sleep = if (may(SLEEP)) quiet {
            client.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), TimeRangeFilter.between(nightFrom, nightTo)))[SleepSessionRecord.SLEEP_DURATION_TOTAL]
                ?.toMinutes()?.toInt()
        } else null
        // the first weigh-in of the day: the morning one, before food and water
        val weight = if (may(WEIGHT)) quiet {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end))).records
                .minByOrNull { it.time }?.let { Math.round(it.weight.inKilograms * 10) / 10.0 }
        } else null
        val workouts = if (may(EXERCISE)) quiet {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end))).records
                .map { Session(kindOf(it.exerciseType), it.startTime, it.endTime) }
        } else null

        return DayFacts(
            day = day,
            sleepMinutes = sleep?.takeIf { it > 0 },
            steps = steps?.takeIf { it > 0 },
            workouts = WatchMath.workouts(workouts.orEmpty()),
            weightKg = weight?.takeIf { it in 20.0..400.0 },
        )
    }

    /** One kind of data failing (permission revoked meanwhile, a provider hiccup) leaves it out and nothing else. */
    private inline fun <T> quiet(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    internal companion object {
        val SLEEP = SleepSessionRecord::class
        val STEPS = StepsRecord::class
        val EXERCISE = ExerciseSessionRecord::class
        val WEIGHT = WeightRecord::class
        private val NIGHT_STARTS = java.time.LocalTime.of(18, 0)

        /** Health Connect's code for what was done, in words a person uses. */
        fun kindOf(type: Int): WorkoutKind = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> WorkoutKind.Running
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WorkoutKind.Walking
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> WorkoutKind.Cycling
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> WorkoutKind.Strength
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> WorkoutKind.Swimming
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES, ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> WorkoutKind.Yoga
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> WorkoutKind.Hiking
            else -> WorkoutKind.Other
        }
    }
}

/** A session as the watch recorded it, before it becomes a [Workout]. */
internal data class Session(val kind: WorkoutKind, val start: Instant, val end: Instant)

/** The small arithmetic of reading a day, apart from the SDK so that it can be tested. */
internal object WatchMath {
    /** The shortest stretch that counts as a workout: a watch notes every short walk. */
    const val MIN_WORKOUT_MINUTES = 10

    /** Two sessions of one kind that start within this many minutes are the same workout recorded by two apps. */
    private const val SAME_START_MINUTES = 10L

    fun workouts(sessions: List<Session>): List<Workout> {
        val kept = ArrayList<Session>()
        for (s in sessions.sortedBy { it.start }) {
            val twin = kept.any { it.kind == s.kind && Math.abs(Duration.between(it.start, s.start).toMinutes()) <= SAME_START_MINUTES }
            if (!twin) kept += s
        }
        return kept.map { Workout(it.kind, Duration.between(it.start, it.end).toMinutes().toInt()) }.filter { it.minutes >= MIN_WORKOUT_MINUTES }
    }
}
