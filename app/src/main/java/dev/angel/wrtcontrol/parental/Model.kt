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
    /** Tiempo extra: hasta esta hora local del router, sus horarios no bloquean. */
    val extraUntil: LocalDateTime? = null,
    /** Sus dispositivos no pueden abrir la configuración del router (LuCI, SSH). */
    val protectRouter: Boolean = false,
    /**
     * Época del corte de conexiones (1 a 3). Al endurecer el filtro cambia, y las conexiones
     * que sus dispositivos abrieron antes se cortan para que vuelvan a pasar por el DNS.
     * 0: aún sin asignar.
     */
    val cutEpoch: Int = 0,
    /** Segundo corte tras endurecer el filtro (ver NftInclude.kt), o null. */
    val cutWindow: CutWindow? = null,
)

/**
 * Tras endurecer el filtro, las conexiones que el dispositivo abra durante un rato llevan la
 * marca [value] y se cortan otra vez a las [until] (hora local del router): para entonces su
 * caché DNS ya caducó y, al reconectar, pregunta otra vez y le llega el bloqueo.
 */
data class CutWindow(val value: Int, val until: LocalDateTime)

/** Ajustes de toda la red. */
data class ParentalSettings(
    /** Dispositivos nuevos sin internet hasta que se aprueben. */
    val quarantine: Boolean = false,
    /** MAC aprobadas además de las de los perfiles (TV, impresora, teléfonos de los padres...). */
    val approved: List<String> = emptyList(),
    /**
     * Teléfonos desde los que se ha usado la app: nunca pierden el acceso al router (aunque
     * estén en un perfil protegido) y, con la cuarentena, siempre tienen internet.
     */
    val admins: List<String> = emptyList(),
    /**
     * Desfase con UTC (s) del router cuando se escribieron las horas guardadas (fin de pausa,
     * tiempo extra, segundo corte). Si la zona cambia (desde la app o desde LuCI), en el siguiente
     * cambio se mueven para que sigan siendo el mismo instante.
     */
    val timeOffset: Int? = null,
)

/** Todo lo que la app guarda en el router. */
data class ParentalConfig(
    val profiles: List<Profile>,
    val settings: ParentalSettings = ParentalSettings(),
) {
    /** Dispositivos con permiso para salir a internet cuando la cuarentena está activa. */
    val approvedMacs: Set<String> get() = settings.approved.toSet() + settings.admins + profiles.flatMap { it.macs }
}

/** Qué sitios se bloquean a un perfil, a través del DNS. */
data class ContentFilter(
    val level: FilterLevel = FilterLevel.None,
    /** Identificadores del catálogo [SERVICES]. */
    val services: Set<String> = emptySet(),
    /** Dominios propios, normalizados con [normalizeDomain]. */
    val domains: List<String> = emptyList(),
    /** Búsqueda segura de Google, Bing y DuckDuckGo (los niveles de familia ya la incluyen). */
    val safeSearch: Boolean = false,
    /** Modo restringido de YouTube (el nivel estricto ya lo incluye). */
    val youtube: YoutubeMode = YoutubeMode.Off,
) {
    val active: Boolean
        get() = level != FilterLevel.None || services.isNotEmpty() || domains.isNotEmpty() || safeSearch || youtube != YoutubeMode.Off

    /** ¿Queda forzada la búsqueda segura, por el interruptor o por el nivel? */
    val effectiveSafeSearch: Boolean get() = safeSearch || level != FilterLevel.None

    /** Modo de YouTube que queda en la práctica (CleanBrowsing ya lo restringe). */
    val effectiveYoutube: YoutubeMode
        get() = when {
            "youtube" in services -> YoutubeMode.Off // bloqueado del todo
            level == FilterLevel.Strict && youtube == YoutubeMode.Off -> YoutubeMode.Moderate
            else -> youtube
        }
}

/** Modo restringido de YouTube que fuerza Google por DNS. */
enum class YoutubeMode(val uciValue: String?) {
    Off(null),

    /** Oculta lo más adulto; deja casi todo lo demás. */
    Moderate("moderate"),

    /** Más estricto: oculta también comentarios y muchos vídeos sin clasificar. */
    Strict("strict"),
}

/** Nivel de filtrado por categorías, delegado a un DNS para familias. */
enum class FilterLevel(val uciValue: String?) {
    None(null),

    /** Cloudflare for Families: contenido para adultos y malware, y búsqueda segura de Google, Bing y DuckDuckGo. */
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

    /** Dentro de un horario, pero con tiempo extra hasta [until]. */
    data class ExtraTime(val until: LocalDateTime) : Access
}

/** Sin la pausa ni el tiempo extra que ya terminaron. */
fun Profile.withoutExpired(now: LocalDateTime): Profile = copy(
    pause = if (pause is Pause.Until && !pause.end.isAfter(now)) null else pause,
    extraUntil = extraUntil?.takeIf { it.isAfter(now) },
)

fun Profile.access(now: LocalDateTime): Access {
    if (macs.isEmpty()) return Access.NoDevices
    val current = withoutExpired(now)
    when (val p = current.pause) {
        Pause.Indefinite -> return Access.Paused(null)
        is Pause.Until -> return Access.Paused(p.end)
        null -> {}
    }
    val today = now.toLocalDate()
    current.effectiveBlocks(today).firstOrNull { now in it }?.let { return Access.Blocked(it.end) }
    val extra = current.extraUntil
    if (extra != null && blockedIntervals(today).any { now in it }) return Access.ExtraTime(extra)
    return Access.Allowed
}

private operator fun Interval.contains(t: LocalDateTime) = !t.isBefore(start) && t.isBefore(end)

/** Inicio del próximo bloqueo por horario en los próximos 7 días (null si no hay). */
fun Profile.nextBlock(now: LocalDateTime): LocalDateTime? =
    withoutExpired(now).effectiveBlocks(now.toLocalDate()).map { it.start }.firstOrNull { it.isAfter(now) }

/**
 * Bloqueos que quedan con el tiempo extra: el router no aplica los horarios antes de
 * [Profile.extraUntil] (`start_date` en sus reglas), así que se recortan o desaparecen.
 */
fun Profile.effectiveBlocks(today: LocalDate): List<Interval> {
    val extra = extraUntil ?: return blockedIntervals(today)
    return blockedIntervals(today).mapNotNull { i ->
        when {
            !extra.isAfter(i.start) -> i
            !extra.isBefore(i.end) -> null
            else -> Interval(extra, i.end)
        }
    }
}

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
