package dev.angel.wrtcontrol.parental

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Una persona (o grupo) con sus dispositivos y sus reglas de acceso a internet. */
data class Profile(
    val id: String,
    val name: String,
    /** MAC en mayúsculas con ':' y ordenadas. */
    val macs: List<String>,
    val pause: Pause? = null,
    val schedules: List<Schedule> = emptyList(),
    val filter: ContentFilter = ContentFilter(),
    /** Puerto de su servidor DNS en el router; lo asigna la app al activar el filtro. */
    val dnsPort: Int? = null,
)

/** Qué sitios se bloquean a un perfil, a través del DNS. */
data class ContentFilter(
    val level: FilterLevel = FilterLevel.None,
    /** Identificadores del catálogo [SERVICES]. */
    val services: Set<String> = emptySet(),
    /** Dominios propios, normalizados con [normalizeDomain]. */
    val domains: List<String> = emptyList(),
) {
    val active: Boolean get() = level != FilterLevel.None || services.isNotEmpty() || domains.isNotEmpty()
}

/** Nivel de filtrado por categorías, delegado a un DNS para familias. */
enum class FilterLevel(val uciValue: String?) {
    None(null),

    /** Cloudflare for Families: contenido para adultos y malware. */
    Adult("adult"),

    /** CleanBrowsing Family: además búsqueda segura, YouTube restringido y VPN/proxys. */
    Strict("strict"),
}

sealed interface Pause {
    /** Hasta que se reanude a mano. */
    data object Indefinite : Pause

    /** Hasta una hora local del router; después vuelve el acceso sin que intervenga la app. */
    data class Until(val end: LocalDateTime) : Pause
}

/**
 * Franja sin internet que empieza en cada día de [days] a la hora [start] y acaba a la hora
 * [end], que puede ser del día siguiente (21:00 → 07:00). Si [start] == [end] bloquea esos días
 * enteros, de 00:00 a 24:00.
 */
data class Schedule(
    val id: String,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val enabled: Boolean = true,
) {
    val allDay: Boolean get() = start == end
    val overnight: Boolean get() = end < start
}

/** Intervalo [start, end) en hora local del router. */
data class Interval(val start: LocalDateTime, val end: LocalDateTime)

/** Estado de acceso de un perfil en un momento dado. */
sealed interface Access {
    data object Allowed : Access

    /** Sin dispositivos: las reglas no afectan a nadie. */
    data object NoDevices : Access

    data class Paused(val until: LocalDateTime?) : Access

    data class Blocked(val until: LocalDateTime) : Access
}

fun Profile.withoutExpiredPause(now: LocalDateTime): Profile =
    if (pause is Pause.Until && !pause.end.isAfter(now)) copy(pause = null) else this

fun Profile.access(now: LocalDateTime): Access {
    if (macs.isEmpty()) return Access.NoDevices
    when (val p = withoutExpiredPause(now).pause) {
        Pause.Indefinite -> return Access.Paused(null)
        is Pause.Until -> return Access.Paused(p.end)
        null -> {}
    }
    val current = blockedIntervals(now.toLocalDate()).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
    return if (current != null) Access.Blocked(current.end) else Access.Allowed
}

/** Inicio del próximo bloqueo por horario en los próximos 7 días (null si no hay). */
fun Profile.nextBlock(now: LocalDateTime): LocalDateTime? =
    blockedIntervals(now.toLocalDate()).map { it.start }.firstOrNull { it.isAfter(now) }

/**
 * Bloqueos de todos los horarios activos alrededor de [today] (desde ayer hasta dentro de una
 * semana), ordenados y fusionados cuando se solapan o se tocan.
 */
fun Profile.blockedIntervals(today: LocalDate): List<Interval> {
    val raw = schedules.filter { it.enabled }.flatMap { it.intervals(today.minusDays(1), today.plusDays(7)) }
        .sortedBy { it.start }
    val merged = mutableListOf<Interval>()
    for (i in raw) {
        val last = merged.lastOrNull()
        if (last != null && !i.start.isAfter(last.end)) {
            if (i.end.isAfter(last.end)) merged[merged.lastIndex] = last.copy(end = i.end)
        } else {
            merged += i
        }
    }
    return merged
}

/** Intervalos que generan los días de [from] a [to] (ambos incluidos) en los que empieza la franja. */
fun Schedule.intervals(from: LocalDate, to: LocalDate): List<Interval> {
    val out = mutableListOf<Interval>()
    var day = from
    while (!day.isAfter(to)) {
        if (day.dayOfWeek in days) {
            out += when {
                allDay -> Interval(day.atStartOfDay(), day.plusDays(1).atStartOfDay())
                overnight -> Interval(day.atTime(start), day.plusDays(1).atTime(end))
                else -> Interval(day.atTime(start), day.atTime(end))
            }
        }
        day = day.plusDays(1)
    }
    return out
}
