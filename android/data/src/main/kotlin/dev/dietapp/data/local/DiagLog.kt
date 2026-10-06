package dev.dietapp.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.data.local.parse.ModelTrace
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The diagnostics journal as the UI sees it: read it, copy it, clear it. */
interface Journal {
    /** Bumps on every new line, so a screen showing the journal knows to read it again. */
    val version: StateFlow<Long>

    fun read(): String
    fun clear()
}

/**
 * A plain-text journal of what the app did with the model and why something went wrong: every request (without
 * the key and without image bytes), every answer or HTTP error with the server's own words, every tool the model
 * used, every message the agent could not read. Kept on the phone (the last ~256 KB, in `files/diagnostics.log`),
 * mirrored to logcat under the tag "FatCodex", shown on the "Журнал" screen.
 */
@Singleton
class DiagLog @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: Clock,
    private val secrets: SecretStore,
) : ModelTrace, Journal {
    private val file = File(context.filesDir, "diagnostics.log")
    private val lock = Any()
    private val _version = MutableStateFlow(0L)
    override val version: StateFlow<Long> = _version.asStateFlow()

    override fun log(tag: String, message: String) = write(tag, message, null)

    override fun error(tag: String, message: String, error: Throwable?) = write(tag, message, error)

    override fun read(): String = synchronized(lock) { if (file.exists()) file.readText(Charsets.UTF_8) else "" }

    override fun clear() {
        synchronized(lock) { file.delete() }
        _version.value++
    }

    private fun write(tag: String, message: String, error: Throwable?) {
        val text = mask(buildString {
            append(message)
            if (error != null) {
                append('\n').append(error.javaClass.name).append(": ").append(error.message)
                error.stackTrace.take(12).forEach { append("\n    at ").append(it) }
                error.cause?.let { append("\ncaused by ").append(it.javaClass.name).append(": ").append(it.message) }
            }
        })
        if (error == null) Log.i(TAG, "[$tag] $text") else Log.e(TAG, "[$tag] $text")
        val stamp = STAMP.format(Instant.ofEpochMilli(clock.millis()))
        val line = "$stamp [$tag] " + text.replace("\n", "\n    ") + "\n"
        synchronized(lock) {
            runCatching {
                file.appendText(line, Charsets.UTF_8)
                if (file.length() > MAX_BYTES) {
                    val keep = file.readText(Charsets.UTF_8).takeLast(KEEP_CHARS)
                    file.writeText(keep.substringAfter('\n'), Charsets.UTF_8) // start on a whole line
                }
            }
        }
        _version.value++
    }

    /** The key never reaches the journal, whatever a message happens to contain. */
    private fun mask(text: String): String {
        val key = secrets.deepseekKey?.takeIf { it.length >= 8 } ?: return text
        return text.replace(key, "sk-…" + key.takeLast(4))
    }

    private companion object {
        const val TAG = "FatCodex"
        const val MAX_BYTES = 512 * 1024L
        const val KEEP_CHARS = 128 * 1024
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
