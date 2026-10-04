package dev.dietapp.media

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageSizing {
    const val MAX_SIDE = 1024
    const val JPEG_QUALITY = 80

    /** Scale so the longer side is at most [maxSide]; never enlarge. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Clockwise degrees to turn a camera frame upright, normalised to 0/90/180/270. */
    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

object ImageCompressor {
    /** Upright, at most 1024 px on the long side, JPEG 80%: what gets sent to the server. */
    fun compress(source: Bitmap, rotationDegrees: Int): ByteArray {
        val rotation = ImageSizing.normalizeRotation(rotationDegrees)
        val (targetW, targetH) = ImageSizing.fit(source.width, source.height)
        val scale = targetW.toFloat() / source.width
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(scale, scale)
        }
        val bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, ImageSizing.JPEG_QUALITY, out)
        if (bitmap !== source) bitmap.recycle()
        return out.toByteArray()
    }
}
