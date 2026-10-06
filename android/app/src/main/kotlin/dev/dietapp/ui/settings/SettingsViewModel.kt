package dev.dietapp.ui.settings

import dev.dietapp.data.domain.Language
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.Texts
import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.local.LocalSettings
import dev.dietapp.data.repo.AuthRepository
import dev.dietapp.data.repo.DiaryRepository
import dev.dietapp.data.repo.FoodRepository
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val email: String? = null,
    /** The goal of the diary, worked out from "О себе" (what a day costs plus the person's correction). Never typed in. */
    val savedGoal: Int? = null,
    val history: List<DaySummary> = emptyList(),
    /** Running without a server: the diary lives only on this phone. */
    val local: Boolean = false,
    val hasKey: Boolean = false,
    val language: Language = Language.Ru,
    val keyInput: String = "",
    /** The first tap on "стереть всё" was made; the second one does it. */
    val eraseArmed: Boolean = false,
    /** How many foods the user's own base holds. */
    val foodCount: Int = 0,
    /** On the agent page: the system prompt is opened. */
    val promptOpen: Boolean = false,
    /** What the agent is told before every message. Read-only. */
    val agentPrompt: String = "",
    /** What saving the key said ("Сохранено." / why it was refused). Shown on the agent page, where the key is. */
    val keyMessage: String? = null,
    val keyMessageIsError: Boolean = false,
) {
    val canSaveKey get() = keyInput.isNotBlank()
}

private data class Edit(
    val keyInput: String = "",
    val eraseArmed: Boolean = false,
    val promptOpen: Boolean = false,
    val keyMessage: String? = null,
    val keyMessageIsError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val localSettings: LocalSettings,
    diary: DiaryRepository,
    foods: FoodRepository,
) : ViewModel() {
    private val edit = MutableStateFlow(Edit())

    val state: StateFlow<SettingsUiState> = combine(
        diary.observeProfile(), diary.observeDaySummaries(), edit, localSettings.capabilities, foods.foods,
    ) { profile, history, e, caps, foodList ->
        SettingsUiState(
            email = profile.email,
            savedGoal = profile.calorieGoal,
            history = history,
            local = caps.local,
            hasKey = caps.modelKey,
            language = caps.language,
            keyInput = e.keyInput,
            eraseArmed = e.eraseArmed,
            foodCount = foodList.size,
            promptOpen = e.promptOpen,
            agentPrompt = localSettings.agentPrompt,
            keyMessage = e.keyMessage,
            keyMessageIsError = e.keyMessageIsError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onKeyChange(text: String) =
        edit.update { it.copy(keyInput = text.filterNot(Char::isWhitespace), keyMessage = null, eraseArmed = false) }

    fun onSaveKey() {
        val key = edit.value.keyInput.takeIf { it.isNotBlank() } ?: return
        localSettings.saveKey(key).fold(
            onSuccess = { edit.update { it.copy(keyInput = "", keyMessage = Texts.SAVED, keyMessageIsError = false) } },
            onFailure = { e -> edit.update { it.copy(keyMessage = e.message, keyMessageIsError = true) } },
        )
    }

    fun onTogglePrompt() = edit.update { it.copy(promptOpen = !it.promptOpen, eraseArmed = false) }

    /** The screens are rebuilt in the other language at once (MainActivity keys the content on it). */
    fun onToggleLanguage() = localSettings.setLanguage(if (state.value.language == Language.Ru) Language.En else Language.Ru)

    fun onClearKey() {
        localSettings.clearKey()
        edit.update { it.copy(keyInput = "", keyMessage = null) }
    }

    /**
     * "выйти" with a server. Without one this erases the only copy of the diary, so it asks twice: the first tap
     * arms it (for a few seconds), the second one does it.
     */
    fun onLogout() {
        if (state.value.local && !edit.value.eraseArmed) {
            edit.update { it.copy(eraseArmed = true) }
            viewModelScope.launch {
                delay(CONFIRM_MS)
                edit.update { it.copy(eraseArmed = false) }
            }
            return
        }
        viewModelScope.launch {
            auth.logout()
            edit.value = Edit()
        }
    }

    private companion object {
        const val CONFIRM_MS = 4_000L
    }
}
