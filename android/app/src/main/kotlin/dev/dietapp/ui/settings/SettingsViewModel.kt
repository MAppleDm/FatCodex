package dev.dietapp.ui.settings

import dev.dietapp.data.domain.Language
import dev.dietapp.data.local.RecordMode
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
    val savedGoal: Int? = null,
    /** What is typed in the goal field; null means "untouched, show the saved goal". */
    val goalInput: String? = null,
    val history: List<DaySummary> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** Running without a server: the diary lives only on this phone. */
    val local: Boolean = false,
    val hasKey: Boolean = false,
    val webSearch: Boolean = true,
    val recordMode: RecordMode = RecordMode.Standard,
    val language: Language = Language.Ru,
    val keyInput: String = "",
    /** The first tap on "стереть всё" was made; the second one does it. */
    val eraseArmed: Boolean = false,
    /** How many foods the user's own base holds. */
    val foodCount: Int = 0,
) {
    val goalText get() = goalInput ?: savedGoal?.toString().orEmpty()
    val canSave get() = goalInput != null && goalInput.toIntOrNull() != null && goalInput.toIntOrNull() != savedGoal && !busy
    val canSaveKey get() = keyInput.isNotBlank()
}

private data class Edit(
    val goalInput: String? = null,
    val keyInput: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val eraseArmed: Boolean = false,
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
            goalInput = e.goalInput,
            history = history,
            busy = e.busy,
            message = e.message,
            messageIsError = e.isError,
            local = caps.local,
            hasKey = caps.modelKey,
            webSearch = caps.webSearch,
            recordMode = caps.recordMode,
            language = caps.language,
            keyInput = e.keyInput,
            eraseArmed = e.eraseArmed,
            foodCount = foodList.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onGoalChange(text: String) =
        edit.update { it.copy(goalInput = text.filter(Char::isDigit).take(5), message = null, eraseArmed = false) }

    fun onSaveGoal() {
        val goal = state.value.goalInput?.toIntOrNull() ?: return
        edit.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            auth.setGoal(goal).fold(
                onSuccess = { edit.update { e -> Edit(keyInput = e.keyInput, message = Texts.SAVED) } },
                onFailure = { e -> edit.update { it.copy(busy = false, message = e.message, isError = true) } },
            )
        }
    }

    fun onKeyChange(text: String) =
        edit.update { it.copy(keyInput = text.filterNot(Char::isWhitespace), message = null, eraseArmed = false) }

    fun onSaveKey() {
        val key = edit.value.keyInput.takeIf { it.isNotBlank() } ?: return
        localSettings.saveKey(key).fold(
            onSuccess = { edit.update { it.copy(keyInput = "", message = Texts.SAVED, isError = false) } },
            onFailure = { e -> edit.update { it.copy(message = e.message, isError = true) } },
        )
    }

    fun onToggleWebSearch() = localSettings.setWebSearch(!state.value.webSearch)
    fun onToggleRecordMode() =
        localSettings.setRecordMode(if (state.value.recordMode == RecordMode.Standard) RecordMode.Precise else RecordMode.Standard)

    /** The screens are rebuilt in the other language at once (MainActivity keys the content on it). */
    fun onToggleLanguage() = localSettings.setLanguage(if (state.value.language == Language.Ru) Language.En else Language.Ru)

    fun onClearKey() {
        localSettings.clearKey()
        edit.update { it.copy(keyInput = "", message = null) }
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
