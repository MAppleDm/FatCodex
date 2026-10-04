package dev.dietapp.data.local

import java.io.File

/** Test helpers for files that live elsewhere in the monorepo (services/, contracts/). */
object TestFiles {
    /** Looks for [relative] in the working directory and its parents (tests run from android/data). */
    fun repoFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
            ?: error("$relative not found above ${File("").absolutePath}")

    /** Minimal CSV reader: quoted fields, doubled quotes, no multi-line fields (enough for the USDA subset). */
    fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
