package dev.dietapp.coreui

import kotlin.math.roundToLong

private const val NBSP = ' '

/** 1420 -> "1 420" (non-breaking space, so a number never wraps in the middle). */
fun formatInt(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(NBSP.toString()).reversed()
    return if (value < 0) "−$grouped" else grouped
}

fun formatInt(value: Int): String = formatInt(value.toLong())

fun formatInt(value: Double): String = formatInt(value.roundToLong())

/** 200.0 -> "200", 12.5 -> "12.5". */
fun formatGrams(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, value)

/** 82.36 -> "82.4". */
fun formatKg(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)
