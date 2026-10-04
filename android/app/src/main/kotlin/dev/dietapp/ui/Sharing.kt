package dev.dietapp.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a text file (the journal, the exported diary) to the app the user picks. A messenger would cut long text into
 * pieces, so it goes as an attachment: written to the app's cache and handed out through the FileProvider declared in
 * the manifest, readable only by the app the user chooses. Only the latest file is ever kept there.
 */
fun shareTextFile(context: Context, fileName: String, mime: String, text: String, subject: String, chooserTitle: String) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, fileName).apply { writeText(text, Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri) // lets the share sheet itself read it, for the preview
    val chooser = Intent.createChooser(send, chooserTitle)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, dev.dietapp.MainActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
