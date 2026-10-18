package dev.dietapp.ui.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.coreui.Inter as BundledFamily
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [Inter] is the font the app ships with, [System] the phone's own, [File] a font the person picked from a file. */
enum class FontKind { Inter, System, File }

/** [fileName] is the picked font's name (without its extension) once there is one, whether or not it is the one in use. */
data class FontChoice(val kind: FontKind = FontKind.Inter, val fileName: String? = null)

/**
 * Which font the app is set in. Besides the bundled Inter and the system font there is a font of the person's own: any
 * .ttf or .otf on the phone (Verdana Pro, say). Fonts like that are licensed and cannot travel inside this app, so the file
 * is copied from where the person has it into the app's private storage and stays there: it is never uploaded or exported.
 */
@Singleton
class FontStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("font", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "fonts")
    private val file = File(folder, "custom.font")
    private var loaded: FontFamily? = null

    private val state = MutableStateFlow(read())
    val choice: StateFlow<FontChoice> = state

    private fun read(): FontChoice {
        val name = prefs.getString(NAME, null)?.takeIf { file.isFile }
        val kind = runCatching { FontKind.valueOf(prefs.getString(KIND, null) ?: "") }.getOrDefault(FontKind.Inter)
        // a font file that is gone cannot be the one in use
        return FontChoice(if (kind == FontKind.File && name == null) FontKind.Inter else kind, name)
    }

    fun use(kind: FontKind) {
        if (kind == FontKind.File && !file.isFile) return
        prefs.edit { putString(KIND, kind.name) }
        state.value = read()
    }

    /**
     * Copies the chosen file in and starts using it. False when it is not a font this phone can read; nothing changes then.
     * Reads and writes the file: call it off the main thread.
     */
    fun importFile(uri: Uri): Boolean {
        val name = displayName(uri)
        val incoming = File(folder, "incoming.font")
        try {
            folder.mkdirs()
            val opened = context.contentResolver.openInputStream(uri) ?: return false
            opened.use { input -> incoming.outputStream().use { out -> copyAtMost(input, out) } }
            if (!looksLikeFont(incoming) || Typeface.Builder(incoming).build() == null) return false
            incoming.copyTo(file, overwrite = true)
            loaded = null
            prefs.edit {
                putString(KIND, FontKind.File.name)
                putString(NAME, name)
            }
            state.value = read()
            return true
        } catch (e: IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        } finally {
            incoming.delete()
        }
    }

    /** The font family to set the app in for this choice. */
    fun family(choice: FontChoice): FontFamily = when (choice.kind) {
        FontKind.Inter -> BundledFamily
        FontKind.System -> FontFamily.SansSerif
        FontKind.File -> loaded ?: file.takeIf { it.isFile }?.let { f -> runCatching { Typeface.Builder(f).build()?.let { FontFamily(it) } }.getOrNull() }
            ?.also { loaded = it } ?: BundledFamily
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: ""
        return raw.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().take(60).ifBlank { "font" }
    }

    private fun copyAtMost(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            if (total > MAX_BYTES) throw IOException("too big for a font")
            out.write(buffer, 0, n)
        }
    }

    /** TrueType, OpenType with outlines, a TrueType collection: the first four bytes say which. Anything else is not a font. */
    private fun looksLikeFont(f: File): Boolean {
        val head = ByteArray(4)
        val read = f.inputStream().use { it.read(head) }
        if (read < 4) return false
        val tag = String(head, Charsets.ISO_8859_1)
        return tag == "OTTO" || tag == "true" || tag == "ttcf" || (head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte())
    }

    private companion object {
        const val KIND = "kind"
        const val NAME = "file_name"
        const val MAX_BYTES = 30L * 1024 * 1024
    }
}
