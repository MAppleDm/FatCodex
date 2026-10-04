package dev.dietapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.data.local.LocalSettings
import dev.dietapp.data.repo.AuthRepository
import dev.dietapp.data.repo.DiaryRepository
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginStep { Email, Code, Key, Goal }

data class LoginUiState(
    /** Session and profile are still being read: render nothing rather than flash the login screen. */
    val loading: Boolean = true,
    /** Signed in and a goal is set: show the diary. */
    val done: Boolean = false,
    /** This build can sign in to a server at all; if not, the only way in is the local mode. */
    val serverEnabled: Boolean = true,
    val step: LoginStep = LoginStep.Email,
    val email: String = "",
    val code: String = "",
    val key: String = "",
    val goal: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

private data class Form(
    val email: String = "",
    val code: String = "",
    val key: String = "",
    val goal: String = "",
    val codeSent: Boolean = false,
    val keySkipped: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The whole onboarding on one screen: email, then the code from the mail, then the daily goal.
 * The step is derived from the facts (code sent? signed in? goal known?), not stored.
 * Without a server (a build with none configured, or the person has none) the email steps are replaced by one:
 * the person's own DeepSeek key, which makes photos and free-form phrases work. It can be skipped (the built-in
 * dictionary still works) and is asked only until a goal has been set.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    diary: DiaryRepository,
    private val localSettings: LocalSettings,
    @Named("serverEnabled") private val serverEnabled: Boolean,
) : ViewModel() {
    private val form = MutableStateFlow(Form())

    val state: StateFlow<LoginUiState> = combine(
        form, auth.loggedIn, diary.observeProfile(), localSettings.capabilities,
    ) { f, loggedIn, profile, caps ->
        val hasGoal = profile.calorieGoal != null
        LoginUiState(
            // without a server the signed-out moment lasts a frame: render nothing instead of the email step
            loading = !serverEnabled && !loggedIn,
            done = loggedIn && hasGoal,
            serverEnabled = serverEnabled,
            step = when {
                loggedIn && caps.local && !caps.modelKey && !hasGoal && !f.keySkipped -> LoginStep.Key
                loggedIn -> LoginStep.Goal
                f.codeSent -> LoginStep.Code
                else -> LoginStep.Email
            },
            email = f.email, code = f.code, key = f.key, goal = f.goal, busy = f.busy, error = f.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoginUiState())

    init {
        if (!serverEnabled) {
            // nobody to sign in to: (re)enter the local mode whenever we are signed out, e.g. after "стереть всё"
            viewModelScope.launch { auth.loggedIn.collect { if (!it) auth.useWithoutServer() } }
        }
    }

    fun onEmail(text: String) = form.update { it.copy(email = text, error = null) }
    fun onCode(text: String) = form.update { it.copy(code = text.filter(Char::isDigit).take(6), error = null) }
    fun onKey(text: String) = form.update { it.copy(key = text.filterNot(Char::isWhitespace), error = null) }
    fun onGoal(text: String) = form.update { it.copy(goal = text.filter(Char::isDigit).take(5), error = null) }

    fun onChangeEmail() = form.update { it.copy(codeSent = false, code = "", error = null) }

    /** "без сервера": use the app on this phone alone; the next step is the goal. */
    fun onWithoutServer() {
        if (form.value.busy) return
        viewModelScope.launch { auth.useWithoutServer() }
    }

    /** "пропустить": no key for now, the built-in dictionary does the parsing. It can be added in settings. */
    fun onSkipKey() = form.update { it.copy(keySkipped = true, key = "", error = null) }

    fun onSubmit() {
        val f = form.value
        if (f.busy) return
        when (state.value.step) {
            LoginStep.Email -> submit({ auth.requestCode(f.email) }) { form.update { it.copy(codeSent = true) } }
            LoginStep.Code -> submit({ auth.verify(f.email, f.code) }) { form.update { it.copy(code = "") } }
            LoginStep.Key -> localSettings.saveKey(f.key).fold(
                onSuccess = { form.update { it.copy(key = "") } },
                onFailure = { e -> form.update { it.copy(error = e.message) } },
            )
            LoginStep.Goal -> {
                val goal = f.goal.toIntOrNull()
                if (goal == null) {
                    form.update { it.copy(error = dev.dietapp.Texts.GOAL_NOT_A_NUMBER) }
                } else {
                    submit({ auth.setGoal(goal) }) { }
                }
            }
        }
    }

    private fun submit(call: suspend () -> Result<Unit>, onSuccess: () -> Unit) {
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            call().fold(
                onSuccess = {
                    form.update { it.copy(busy = false) }
                    onSuccess()
                },
                onFailure = { e -> form.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }
}
