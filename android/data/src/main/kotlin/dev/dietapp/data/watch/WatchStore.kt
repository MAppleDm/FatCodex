package dev.dietapp.data.watch

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the person has connected the watch, and when it was last read. In the same preferences file as the chosen mode
 * (`app`), so it goes with Android's backup and is wiped by "Стереть всё".
 */
@Singleton
class WatchStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    private val _readAt = MutableStateFlow(prefs.getLong(READ_AT, 0L).takeIf { it > 0 })

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Epoch millis of the last read, null if never. */
    val readAt: StateFlow<Long?> = _readAt.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(ENABLED, value) }
        _enabled.value = value
    }

    fun setReadAt(ms: Long) {
        prefs.edit { putLong(READ_AT, ms) }
        _readAt.value = ms
    }

    fun clear() {
        // "watch_reading" was written by a version that read calories; it goes with the rest
        prefs.edit { remove(ENABLED); remove(READ_AT); remove("watch_reading") }
        _enabled.value = false
        _readAt.value = null
    }

    private companion object {
        const val ENABLED = "watch_enabled"
        const val READ_AT = "watch_read_at"
    }
}
