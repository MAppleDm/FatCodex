package dev.dietapp.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.data.domain.Lang
import dev.dietapp.data.domain.Language
import dev.dietapp.data.local.parse.PromptAssets
import dev.dietapp.data.net.SessionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** Where the diary is processed: on the user's own server, or entirely on the phone. */
enum class AppMode { Server, Local }

/**
 * The chosen mode, in its own preferences file (`app`), which Android may back up along with the database.
 * A person who was already signed in before local mode existed is a server user: the mode is inferred from the token.
 */
@Singleton
class ModeStore @Inject constructor(@ApplicationContext context: Context, session: SessionStore) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(read(session))

    /** Null until the user has chosen (or signed in). */
    val mode: StateFlow<AppMode?> = _mode.asStateFlow()

    val isLocal: Boolean get() = _mode.value == AppMode.Local

    private val _language = MutableStateFlow(
        (Language.of(prefs.getString(LANGUAGE, null)) ?: Language.forDevice()).also { Lang.current = it },
    )

    /** The language of the screens and of what the app writes in the chat. Follows the phone until chosen. */
    val language: StateFlow<Language> = _language.asStateFlow()

    fun setLanguage(language: Language) {
        prefs.edit { putString(LANGUAGE, language.code) }
        Lang.current = language
        _language.value = language
    }

    fun set(mode: AppMode?) {
        prefs.edit { if (mode == null) remove(KEY) else putString(KEY, mode.name) }
        _mode.value = mode
    }

    private fun read(session: SessionStore): AppMode? {
        prefs.getString(KEY, null)?.let { return runCatching { AppMode.valueOf(it) }.getOrNull() }
        return if (session.token != null) AppMode.Server.also { prefs.edit { putString(KEY, it.name) } } else null
    }

    private companion object {
        const val KEY = "mode"
        const val LANGUAGE = "language"
    }
}

/**
 * The user's own DeepSeek key, entered in settings. It never ships in the APK. It is kept encrypted by [cipher] (a key
 * in the Android Keystore) in its own preferences file (`secrets`), which is also excluded from Android backups, so it
 * does not travel to another phone or to the cloud. The plain text lives only in memory, while the app runs.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context, private val cipher: KeyCipher) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private var key: String? = load()
    private val _hasKey = MutableStateFlow(key != null)

    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()
    val deepseekKey: String? get() = key

    /** Throws when the phone cannot encrypt it; then nothing is stored (never a plain copy). */
    fun saveKey(key: String) {
        val clean = key.trim()
        val token = try {
            cipher.encrypt(clean)
        } catch (e: java.security.GeneralSecurityException) {
            throw dev.dietapp.data.net.AppError(
                Lang.t("Не удалось надёжно сохранить ключ на этом телефоне.", "Could not store the key securely on this phone."),
                "key_store",
            )
        }
        prefs.edit { putString(ENCRYPTED, token); remove(PLAIN) }
        this.key = clean
        _hasKey.value = true
    }

    fun clear() {
        prefs.edit { clear() }
        key = null
        _hasKey.value = false
    }

    private fun load(): String? {
        prefs.getString(ENCRYPTED, null)?.let { token ->
            cipher.decrypt(token)?.let { return it }
            // unreadable (its Keystore key is gone): the key has to be entered again, a dead copy helps nobody
            prefs.edit { remove(ENCRYPTED) }
            return null
        }
        // a key saved by an older version, in plain text: encrypt it now and forget the plain copy
        val old = prefs.getString(PLAIN, null)?.takeIf { it.isNotBlank() } ?: return null
        runCatching { cipher.encrypt(old) }.onSuccess { token -> prefs.edit { putString(ENCRYPTED, token); remove(PLAIN) } }
        return old
    }

    private companion object {
        const val ENCRYPTED = "deepseek_key_enc"
        const val PLAIN = "deepseek_key"
    }
}

/** What this install can do right now, for the UI to enable or explain things. */
data class Capabilities(
    val local: Boolean = false,
    /** A model key is set: the agent can read what is written. Without it nothing but a weigh-in can be recorded. */
    val modelKey: Boolean = false,
    val language: Language = Language.Ru,
)

/** Local-mode settings the UI edits: the model key (required) and the language. */
interface LocalSettings {
    val capabilities: Flow<Capabilities>

    /** What the agent is told before every message: the system prompt and the local-mode addendum. Read-only. */
    val agentPrompt: String

    /** Rejects keys that cannot be real; accepts anything that looks like one (it is verified on first use). */
    fun saveKey(key: String): Result<Unit>
    fun clearKey()
    fun setLanguage(language: Language)
}

@Singleton
class LocalSettingsImpl @Inject constructor(
    private val mode: ModeStore,
    private val secrets: SecretStore,
    prompts: PromptAssets,
) : LocalSettings {
    override val capabilities: Flow<Capabilities> =
        combine(mode.mode, secrets.hasKey, mode.language) { m, key, language ->
            Capabilities(local = m == AppMode.Local, modelKey = key, language = language)
        }

    override val agentPrompt: String = prompts.agentPrompt

    override fun saveKey(key: String): Result<Unit> {
        val trimmed = key.trim()
        if (trimmed.length < 10 || trimmed.any { it.isWhitespace() }) {
            return Result.failure(dev.dietapp.data.net.AppError(Lang.t("Это не похоже на ключ DeepSeek.", "This does not look like a DeepSeek key."), "bad_key"))
        }
        return runCatching { secrets.saveKey(trimmed) }
    }

    override fun clearKey() = secrets.clear()

    override fun setLanguage(language: Language) = mode.setLanguage(language)
}
