package dev.dietapp.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.BodyProfile
import dev.dietapp.data.domain.Sex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the person said about themselves ("О себе"), and whether the first-run questions have been dealt with. It lives in
 * the same preferences file as the chosen mode (`app`), so it travels with the diary in Android's backup, and it is
 * wiped with everything else by "Стереть всё".
 */
@Singleton
class BodyStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read())
    private val _asked = MutableStateFlow(prefs.getBoolean(ASKED, false))

    val profile: StateFlow<BodyProfile> = _profile.asStateFlow()

    /** The person has been through the first-run questions up to the result: they are not asked again. */
    val asked: StateFlow<Boolean> = _asked.asStateFlow()

    fun update(change: (BodyProfile) -> BodyProfile) {
        val next = change(_profile.value)
        prefs.edit {
            putOrRemove(SEX, next.sex?.name)
            if (next.birthYear != null) putInt(BIRTH_YEAR, next.birthYear) else remove(BIRTH_YEAR)
            if (next.heightCm != null) putInt(HEIGHT, next.heightCm) else remove(HEIGHT)
            putOrRemove(ACTIVITY, next.activity?.name)
            if (next.adjustment != 0) putInt(ADJUSTMENT, next.adjustment) else remove(ADJUSTMENT)
        }
        _profile.value = next
    }

    fun markAsked() {
        prefs.edit { putBoolean(ASKED, true) }
        _asked.value = true
    }

    fun clear() {
        prefs.edit { listOf(SEX, BIRTH_YEAR, HEIGHT, ACTIVITY, ADJUSTMENT, ASKED).forEach(::remove) }
        _profile.value = BodyProfile()
        _asked.value = false
    }

    private fun read() = BodyProfile(
        sex = prefs.getString(SEX, null)?.let { runCatching { Sex.valueOf(it) }.getOrNull() },
        birthYear = if (prefs.contains(BIRTH_YEAR)) prefs.getInt(BIRTH_YEAR, 0) else null,
        heightCm = if (prefs.contains(HEIGHT)) prefs.getInt(HEIGHT, 0) else null,
        activity = prefs.getString(ACTIVITY, null)?.let { runCatching { Activity.valueOf(it) }.getOrNull() },
        adjustment = prefs.getInt(ADJUSTMENT, 0),
    )

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value != null) putString(key, value) else remove(key)
    }

    private companion object {
        const val SEX = "body_sex"
        const val BIRTH_YEAR = "body_birth_year"
        const val HEIGHT = "body_height_cm"
        const val ACTIVITY = "body_activity"
        const val ADJUSTMENT = "body_adjustment"

        // a new key: the flag of the first version said "asked or skipped", this one says "got to the result"
        const val ASKED = "body_onboarded"
    }
}
