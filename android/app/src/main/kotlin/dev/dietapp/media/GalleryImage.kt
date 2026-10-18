package dev.dietapp.media

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException
import kotlin.math.max

/**
 * A picture the person chose in the gallery, made the same as a camera frame: upright, at most [ImageSizing.MAX_SIDE] px on
 * the long side, JPEG. The phone's photo picker hands over a [Uri] and no permission is needed.
 */
object GalleryImage {
    /** Null when the file cannot be read as a picture (not an image, gone, too big for memory). */
    fun read(resolver: ContentResolver, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(max(bounds.outWidth, bounds.outHeight)) }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val rotation = resolver.openInputStream(uri)?.use { rotationOf(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) } ?: 0
            bitmap?.let { ImageCompressor.compress(it, rotation) }
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The largest power of two that still leaves the long side at [target] or more: decoding a 12 MP photo at full size is a waste. */
    fun sampleFor(longest: Int, target: Int = ImageSizing.MAX_SIDE): Int {
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Clockwise degrees that stand a picture up, from its EXIF orientation (mirrored ones are only turned). */
    fun rotationOf(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
}
