package dev.dietapp.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** A picture chosen in the gallery becomes what a camera frame is: upright, at most 1600 px, JPEG. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real decoding
class GalleryImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun picture(width: Int, height: Int, name: String, orientation: Int? = null): Uri {
        val file = File(context.cacheDir, name)
        file.outputStream().use { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        }
        return Uri.fromFile(file)
    }

    private fun size(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test fun `a big picture comes out at 1600 on the long side`() {
        val (w, h) = size(GalleryImage.read(context.contentResolver, picture(4000, 3000, "big.jpg"))!!)
        assertEquals(1600, max(w, h))
        assertEquals(1200, min(w, h))
    }

    @Test fun `a small picture is not enlarged`() {
        assertEquals(800 to 600, size(GalleryImage.read(context.contentResolver, picture(800, 600, "small.jpg"))!!))
    }

    @Test fun `a picture the phone stored lying down is stood up`() {
        // a camera that wrote 4000x3000 and "rotate 90": the person saw it upright, as a portrait
        val (w, h) = size(GalleryImage.read(context.contentResolver, picture(4000, 3000, "turned.jpg", ExifInterface.ORIENTATION_ROTATE_90))!!)
        assertEquals(1200 to 1600, w to h)
    }

    @Test fun `something that is not a picture is not one`() {
        val file = File(context.cacheDir, "notes.txt").apply { writeText("not a picture at all") }
        assertNull(GalleryImage.read(context.contentResolver, Uri.fromFile(file)))
    }

    @Test fun `a file that is gone is not one either`() {
        assertNull(GalleryImage.read(context.contentResolver, Uri.fromFile(File(context.cacheDir, "gone.jpg"))))
    }

    @Test fun `the result is a JPEG the model can take`() {
        val bytes = GalleryImage.read(context.contentResolver, picture(1000, 1000, "j.jpg"))!!
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals("JPEG starts with FF D8", listOf(0xFF.toByte(), 0xD8.toByte()), bytes.take(2))
    }

    @Test fun `decoding takes the biggest shrink that still leaves the long side large enough`() {
        assertEquals(1, GalleryImage.sampleFor(800))
        assertEquals(1, GalleryImage.sampleFor(3199))
        assertEquals(2, GalleryImage.sampleFor(3200))
        assertEquals(2, GalleryImage.sampleFor(4000))
        assertEquals(4, GalleryImage.sampleFor(12_000))
    }

    @Test fun `the orientation of a picture is a turn`() {
        assertEquals(0, GalleryImage.rotationOf(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(90, GalleryImage.rotationOf(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, GalleryImage.rotationOf(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, GalleryImage.rotationOf(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(0, GalleryImage.rotationOf(0))
    }
}
