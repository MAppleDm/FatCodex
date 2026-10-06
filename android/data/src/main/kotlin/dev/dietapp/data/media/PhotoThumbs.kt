package dev.dietapp.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The small picture that stays in the chat under a photo message. The photo itself is sent and deleted; this is a few
 * kilobytes of it, kept in the app's private files (not in a backup) for as long as the message is. Without it (another
 * phone, a restore) the chat shows the word "фото" instead.
 */
@Singleton
class PhotoThumbs(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "thumbs"))

    init {
        dir.mkdirs()
    }

    private fun file(id: String) = File(dir, "$id.jpg")

    /** Makes the thumbnail of [jpeg]. False when the bytes are not a picture (then nothing is kept). */
    fun save(id: String, jpeg: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val longest = max(decoded.width, decoded.height)
        val scaled = if (longest > MAX_SIDE) {
            val k = MAX_SIDE.toDouble() / longest
            Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * k).roundToInt()), max(1, (decoded.height * k).roundToInt()), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        dir.mkdirs()
        file(id).writeBytes(out.toByteArray())
        return true
    }

    fun read(id: String): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()

    fun delete(id: String) {
        file(id).delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Enough for a small picture in the chat, even on a dense screen. */
        const val MAX_SIDE = 360
        const val QUALITY = 78
    }
}
