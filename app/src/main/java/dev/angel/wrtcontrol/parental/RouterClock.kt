package dev.angel.wrtcontrol.parental

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong

/** `system.info.localtime` son los segundos del reloj local del router contados como si fuera UTC. */
fun routerDateTime(localSeconds: Long): LocalDateTime = LocalDateTime.ofEpochSecond(localSeconds, 0, ZoneOffset.UTC)

/** Desfase respecto a UTC redondeado al cuarto de hora (absorbe la diferencia entre relojes). */
fun roundOffset(seconds: Long): Int = ((seconds / 900.0).roundToLong() * 900).toInt()

/** LuCI antiguo escribe "America/Mexico City" y el nuevo "America/Mexico_City". */
fun sameZone(a: String, b: String): Boolean = a.replace(' ', '_').equals(b.replace(' ', '_'), ignoreCase = true)

/** Zona horaria del teléfono frente a la que usa el router para los horarios. */
data class ZoneCheck(
    val phoneZone: String,
    val phoneOffsetSeconds: Int,
    val routerZone: String?,
    val routerOffsetSeconds: Int?,
) {
    /**
     * Hay que avisar si ahora mismo el router cuenta otra hora que el teléfono. Se comparan
     * desfases y no nombres: dos zonas distintas con la misma hora valen, y la misma zona con
     * reglas viejas en el router (p. ej. un horario de verano ya abolido) no.
     */
    val mismatch: Boolean
        get() = routerOffsetSeconds != null && routerOffsetSeconds != phoneOffsetSeconds
}

fun zoneCheck(phone: ZoneId, now: Instant, routerZone: String?, routerOffsetSeconds: Int?) = ZoneCheck(
    phoneZone = phone.id,
    phoneOffsetSeconds = phone.rules.getOffset(now).totalSeconds,
    routerZone = routerZone,
    routerOffsetSeconds = routerOffsetSeconds,
)

/**
 * Cadena TZ POSIX fija ("<-06>6", "<+0530>-5:30") si la zona ya no cambia de hora según las
 * reglas del teléfono; null si sigue teniendo horario de verano. Sirve cuando el router trae
 * reglas viejas: p. ej. LuCI de 22.03.0 da "CST6CDT,..." para México, que abolió el horario de
 * verano en 2022, y en verano adelantaría una hora.
 */
fun fixedPosixTz(zone: ZoneId, now: Instant): String? {
    if (zone.rules.nextTransition(now) != null) return null
    val total = zone.rules.getOffset(now).totalSeconds
    if (total == 0) return "GMT0"
    val h = abs(total) / 3600
    val m = abs(total) % 3600 / 60
    val name = (if (total < 0) "-" else "+") + h.toString().padStart(2, '0') + if (m == 0) "" else m.toString().padStart(2, '0')
    // En POSIX el signo va al revés: UTC−6 se escribe "6" y UTC+5:30, "-5:30".
    val offset = (if (total < 0) "" else "-") + h + if (m == 0) "" else ":" + m.toString().padStart(2, '0')
    return "<$name>$offset"
}

/** "UTC−6", "UTC+5:30", "UTC". */
fun formatUtcOffset(seconds: Int): String {
    if (seconds == 0) return "UTC"
    val sign = if (seconds < 0) "−" else "+"
    val h = abs(seconds) / 3600
    val m = abs(seconds) % 3600 / 60
    return if (m == 0) "UTC$sign$h" else "UTC$sign$h:${m.toString().padStart(2, '0')}"
}
