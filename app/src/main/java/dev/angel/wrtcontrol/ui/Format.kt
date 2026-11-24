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

/** Los topes "redondos" de una escala, en cada unidad de [formatBytes]: ni muy holgados ni con decimales raros a la mitad. */
private val NICE_STEPS = listOf(
    1L, 2L, 3L, 4L, 5L, 6L, 8L, 10L, 15L, 20L, 25L, 30L, 40L, 50L, 60L, 80L,
    100L, 150L, 200L, 250L, 300L, 400L, 500L, 600L, 800L, 1024L,
)

/**
 * El tope de la escala de una gráfica: lo primero redondo (1, 2, 3, 4, 5, 6, 8, 10, 15…) en la unidad de
 * [bytes] que no se queda por debajo. Así las marcas del eje se leen "500 MiB", no "487,3 MiB".
 */
fun niceCeilBytes(bytes: Long): Long {
    if (bytes <= 0) return 1
    var unit = 1L
    while (bytes / unit >= 1024 && unit < Long.MAX_VALUE / 1024 / 1024) unit *= 1024
    val value = bytes.toDouble() / unit
    return NICE_STEPS.first { it >= value } * unit
}

/** Como [formatBytes], sin el ".0" de las cifras redondas: las marcas del eje de una gráfica ("20 GiB"). */
fun formatScale(bytes: Long): String = formatBytes(bytes).replace(".0 ", " ")

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

/** [f] (0 a 1) en porcentaje, como se escribe en el idioma del teléfono ("42 %" o "42%"). */
fun formatPercent(f: Float): String = java.text.NumberFormat.getPercentInstance().format(f.toDouble())
