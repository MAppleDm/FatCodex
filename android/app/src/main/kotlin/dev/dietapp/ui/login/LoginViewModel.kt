package dev.dietapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.data.local.LocalSettings
import dev.dietapp.data.repo.AuthRepository
import dev.dietapp.data.repo.BodyRepository
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

enum class LoginStep { Email, Code, Key, About }

data class LoginUiState(
    /** Session and profile are still being read: render nothing rather than flash the login screen. */
    val loading: Boolean = true,
    /** Signed in, the questions about the person answered and so the goal known: show the diary. */
    val done: Boolean = false,
    /** This build can sign in to a server at all; if not, the only way in is the local mode. */
    val serverEnabled: Boolean = true,
    val step: LoginStep = LoginStep.Email,
    val email: String = "",
    val code: String = "",
    val key: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

private data class Form(
    val email: String = "",
    val code: String = "",
    val key: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The whole onboarding: email, then the code from the mail, then the questions about the person ([LoginStep.About], shown
 * by their own screen), which end with the goal worked out from the answers. The goal is never typed in.
 * The step is derived from the facts (code sent? signed in? questions finished? goal known?), not stored.
 * Without a server (a build with none configured, or the person has none) the email steps are replaced by one:
 * the person's own DeepSeek key. There is no way round it: the agent is the only thing that reads what is written, so
 * the key is asked until the questions are over.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    diary: DiaryRepository,
    private val localSettings: LocalSettings,
    body: BodyRepository,
    @Named("serverEnabled") private val serverEnabled: Boolean,
) : ViewModel() {
    private val form = MutableStateFlow(Form())

    val state: StateFlow<LoginUiState> = combine(
        form, auth.loggedIn, diary.observeProfile(), localSettings.capabilities,
        body.asked,
    ) { f, loggedIn, profile, caps, asked ->
        // the goal comes from the answers, so "the questions are over" and "the goal is known" go together
        val ready = asked && profile.calorieGoal != null
        LoginUiState(
            // without a server the signed-out moment lasts a frame: render nothing instead of the email step
            loading = !serverEnabled && !loggedIn,
            done = loggedIn && ready,
            serverEnabled = serverEnabled,
            step = when {
                loggedIn && caps.local && !caps.modelKey && !ready -> LoginStep.Key
                loggedIn -> LoginStep.About
                f.codeSent -> LoginStep.Code
                else -> LoginStep.Email
            },
            email = f.email, code = f.code, key = f.key, busy = f.busy, error = f.error,
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

    fun onChangeEmail() = form.update { it.copy(codeSent = false, code = "", error = null) }

    /** "без сервера": use the app on this phone alone; the next step is the key. */
    fun onWithoutServer() {
        if (form.value.busy) return
        viewModelScope.launch { auth.useWithoutServer() }
    }

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
            LoginStep.About -> Unit // answered on its own screen, with its own buttons
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
