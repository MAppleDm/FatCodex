package dev.dietapp.data.repo

import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.WeightRow
import dev.dietapp.data.db.toRow
import dev.dietapp.data.domain.DayFacts
import dev.dietapp.data.local.DiagLog
import dev.dietapp.data.watch.WatchAvailability
import dev.dietapp.data.watch.WatchSource
import dev.dietapp.data.watch.WatchStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

@Singleton
class WatchRepositoryImpl @Inject constructor(
    private val source: WatchSource,
    private val db: AppDatabase,
    private val store: WatchStore,
    private val clock: Clock,
    private val journal: DiagLog,
) : WatchRepository {
    private data class Phone(val availability: WatchAvailability = WatchAvailability.Unknown, val permitted: Boolean = false, val complete: Boolean = false)

    private val phone = MutableStateFlow(Phone())

    override val state: Flow<WatchState> = combine(phone, store.enabled, store.readAt) { p, enabled, readAt ->
        WatchState(p.availability, p.permitted, enabled, readAt?.let(Instant::ofEpochMilli), complete = p.complete)
    }

    override val permissions: Set<String> get() = source.permissions

    override suspend fun refreshPhone() {
        val availability = source.availability()
        val granted = if (availability == WatchAvailability.Ready) source.granted() else emptySet()
        phone.value = Phone(availability, permitted = granted.isNotEmpty(), complete = granted.containsAll(source.permissions))
    }

    override suspend fun connect(granted: Set<String>): Boolean {
        if (granted.none { it in source.permissions }) return false
        store.setEnabled(true)
        refreshPhone()
        read(BACKFILL_DAYS)
        return true
    }

    override fun disconnect() = store.setEnabled(false)

    override suspend fun refresh() {
        if (!store.enabled.value) return
        read(RECENT_DAYS)
    }

    /** The last [days] days, today first. A day the watch has nothing for keeps what it had: the diary is a record. */
    private suspend fun read(days: Int) {
        if (source.availability() != WatchAvailability.Ready) {
            refreshPhone()
            return
        }
        val today = LocalDate.now(clock)
        for (back in 0 until days) {
            val day = today.minusDays(back.toLong())
            val facts = runCatching { source.read(day, clock.zone) }.getOrNull() ?: continue
            if (facts.isEmpty) continue
            // the weigh-in goes to the diary's weights, the rest is the day's facts
            if (!facts.copy(weightKg = null).isEmpty) db.dayFacts().upsert(facts.toRow(clock.millis()))
            facts.weightKg?.let { importWeight(day, it) }
            if (back == 0) journal.log("watch", describe(facts))
        }
        store.setReadAt(clock.millis())
        refreshPhone()
    }

    /**
     * Scales put a weigh-in into Health Connect: it becomes the day's weigh-in in the diary, unless the person wrote one that day
     * themselves (theirs wins) or removed the one that came from the scales (it does not come back).
     */
    private suspend fun importWeight(day: LocalDate, kg: Double) {
        val id = "scales-$day"
        val mine = db.weights().get(id)
        if (mine?.deleted == true) return
        val latest = db.weights().latestForDay(day.toString())
        if (latest != null && latest.id != id) return
        if (mine?.kg == kg) return
        db.weights().upsert(WeightRow(id, day.toString(), kg, clock.millis(), deleted = false, dirty = false))
    }

    /** One line for the journal: what came from Health Connect for today, so that a device that says less can be seen. */
    private fun describe(f: DayFacts): String = listOf(
        "day ${f.day}",
        "sleep ${f.sleepMinutes ?: "-"} min",
        "steps ${f.steps ?: "-"}",
        "workouts ${f.workouts.size}",
        "weight ${f.weightKg ?: "-"} kg",
    ).joinToString(" · ")

    private companion object {
        /** What is read each time the app opens: today, and the two days before it (a night's sleep arrives late). */
        const val RECENT_DAYS = 3

        /** What is read when the watch is connected: Health Connect keeps about a month before the permission was given. */
        const val BACKFILL_DAYS = 30
    }
}
