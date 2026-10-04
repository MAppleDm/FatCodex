package dev.dietapp.data.domain

import java.util.Locale

enum class Language(val code: String) {
    Ru("ru"), En("en");

    val locale: Locale get() = Locale.forLanguageTag(code)

    companion object {
        fun of(code: String?): Language? = entries.firstOrNull { it.code == code }

        /** A phone set to Russian (or a neighbouring language most users of it also read) starts in Russian. */
        fun forDevice(locale: Locale = Locale.getDefault()): Language =
            if (locale.language in setOf("ru", "uk", "be", "kk")) Ru else En
    }
}

/**
 * The language of everything the app writes: the screens, and the lines it adds to the chat. Set by ModeStore from the
 * user's choice; read wherever a text is made. Lines already in the chat stay in the language they were written in.
 */
object Lang {
    @Volatile var current: Language = Language.Ru

    val en: Boolean get() = current == Language.En

    /** The Russian text, or the English one. */
    fun t(ru: String, en: String): String = if (this.en) en else ru
}
