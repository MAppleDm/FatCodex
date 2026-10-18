package dev.dietapp.data.watch

import dev.dietapp.data.domain.DayFacts
import java.time.LocalDate
import java.time.ZoneId

/** Can this phone give what a watch recorded? */
enum class WatchAvailability {
    /** Not asked yet. */
    Unknown,
    Ready,

    /** Health Connect is not on the phone (Android 13 and older need its app). */
    NotInstalled,

    /** Health Connect is there but too old for this app. */
    UpdateNeeded,
}

/**
 * Where the watch's numbers come from. Health Connect on a phone (a watch writes to it through its own app: Samsung Health,
 * Google Health, the Pixel Watch app...). Behind an interface so everything around it is tested without a watch.
 */
interface WatchSource {
    /** The permissions to ask for, one per kind of data that is read. */
    val permissions: Set<String>

    suspend fun availability(): WatchAvailability

    /** The permissions the person has given so far (a subset of [permissions]). */
    suspend fun granted(): Set<String>

    /**
     * What the watch says about [day]: null when nothing may be read (no permission, not available). A kind of data without
     * a permission or without a record is simply missing from the result.
     */
    suspend fun read(day: LocalDate, zone: ZoneId): DayFacts?
}
