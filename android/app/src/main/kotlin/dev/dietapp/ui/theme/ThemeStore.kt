package dev.dietapp.ui.theme

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.coreui.AppAppearance
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A local appearance preference, available before the first screen is drawn. */
@Singleton
class ThemeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        runCatching { AppAppearance.valueOf(prefs.getString("theme", null).orEmpty()) }.getOrDefault(AppAppearance.System),
    )
    val choice: StateFlow<AppAppearance> = state.asStateFlow()

    fun use(appearance: AppAppearance) {
        prefs.edit { putString("theme", appearance.name) }
        state.value = appearance
    }
}
