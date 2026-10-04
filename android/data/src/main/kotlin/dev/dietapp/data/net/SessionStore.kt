package dev.dietapp.data.net

import android.content.Context
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The access token and the sync cursor, in app-private storage (backups are disabled in the manifest).
 * This is a login to your own server, not a bank: plain SharedPreferences is deliberate.
 */
@Singleton
class SessionStore @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _loggedIn = MutableStateFlow(prefs.getString(TOKEN, null) != null)

    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()
    val token: String? get() = prefs.getString(TOKEN, null)
    val email: String? get() = prefs.getString(EMAIL, null)

    /** `next_since` of the last completed sync; null means "pull everything". */
    var since: String?
        get() = prefs.getString(SINCE, null)
        set(value) = prefs.edit { if (value == null) remove(SINCE) else putString(SINCE, value) }

    fun save(token: String, email: String) {
        prefs.edit {
            putString(TOKEN, token)
            putString(EMAIL, email)
            remove(SINCE)
        }
        _loggedIn.value = true
    }

    fun clear() {
        prefs.edit { clear() }
        _loggedIn.value = false
    }

    private companion object {
        const val TOKEN = "token"
        const val EMAIL = "email"
        const val SINCE = "since"
    }
}
