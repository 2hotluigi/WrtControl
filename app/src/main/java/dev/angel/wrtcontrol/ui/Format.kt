package dev.angel.wrtcontrol.ui

import java.util.Locale

private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = if (unit == 0 || value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.ROOT, pattern, value, UNITS[unit])
}

/** 93784 → "1 d 2 h 3 min" */
fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds < 0) return "—"
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return when {
        days > 0 -> "$days d $hours h $minutes min"
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "$totalSeconds s"
    }
}

/** La carga de system.info viene en punto fijo con 16 bits de fracción. */
fun formatLoad(raw: Long): String = String.format(Locale.ROOT, "%.2f", raw / 65_536.0)

fun fraction(part: Long, total: Long): Float =
    if (total <= 0) 0f else (part.toFloat() / total).coerceIn(0f, 1f)
