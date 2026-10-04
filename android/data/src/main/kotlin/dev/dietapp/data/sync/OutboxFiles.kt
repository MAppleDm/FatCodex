package dev.dietapp.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Photos waiting to be sent live here (already compressed), one file per outbox message. */
@Singleton
class OutboxFiles(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "outbox"))

    init {
        dir.mkdirs()
    }

    private fun file(id: String) = File(dir, "$id.jpg")

    fun write(id: String, bytes: ByteArray) {
        dir.mkdirs()
        file(id).writeBytes(bytes)
    }

    fun read(id: String): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()

    fun delete(id: String) {
        file(id).delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
