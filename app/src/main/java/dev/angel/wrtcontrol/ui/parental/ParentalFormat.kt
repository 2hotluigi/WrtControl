package dev.angel.wrtcontrol.ui.parental

import dev.angel.wrtcontrol.parental.Access
import dev.angel.wrtcontrol.parental.ContentFilter
import dev.angel.wrtcontrol.parental.FilterLevel
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.SERVICES
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.YoutubeMode
import dev.angel.wrtcontrol.parental.access
import dev.angel.wrtcontrol.parental.blockedIntervals
import dev.angel.wrtcontrol.parental.nextBlock
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Semana empezando en lunes, como se usa en español. */
val WEEK: List<DayOfWeek> = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)

private val WEEKDAYS = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
private val WEEKEND = setOf(SATURDAY, SUNDAY)
private val SCHOOL_NIGHTS = setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY)

private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** Inicial del día (la del miércoles es X para no confundirla con la del martes). */
fun DayOfWeek.letter(): String = when (this) {
    MONDAY -> "L"
    TUESDAY -> "M"
    WEDNESDAY -> "X"
    THURSDAY -> "J"
    FRIDAY -> "V"
    SATURDAY -> "S"
    SUNDAY -> "D"
}

fun DayOfWeek.spanishName(): String = when (this) {
    MONDAY -> "lunes"
    TUESDAY -> "martes"
    WEDNESDAY -> "miércoles"
    THURSDAY -> "jueves"
    FRIDAY -> "viernes"
    SATURDAY -> "sábado"
    SUNDAY -> "domingo"
}

private fun DayOfWeek.short(): String = spanishName().take(3) // lun, mar, mié, jue, vie, sáb, dom

fun formatDays(days: Set<DayOfWeek>): String = when (days) {
    WEEK.toSet() -> "Todos los días"
    WEEKDAYS -> "Lunes a viernes"
    WEEKEND -> "Fin de semana"
    SCHOOL_NIGHTS -> "Domingo a jueves"
    else -> WEEK.filter { it in days }.joinToString(", ") { it.short() }.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

fun formatHour(time: LocalTime): String = time.format(HOUR)

/** "Lunes a viernes · 21:00 → 07:00 del día siguiente" */
fun formatSchedule(s: Schedule): String {
    val hours = when {
        s.allDay -> "todo el día"
        s.overnight -> "${formatHour(s.start)} → ${formatHour(s.end)} del día siguiente"
        else -> "${formatHour(s.start)} → ${formatHour(s.end)}"
    }
    return "${formatDays(s.days)} · $hours"
}

/** "hoy a las 21:00", "mañana a las 07:00", "el sábado a las 09:00" (respecto a [now]). */
fun formatWhen(target: LocalDateTime, now: LocalDateTime): String {
    val hour = "las ${formatHour(target.toLocalTime())}"
    return when (val days = ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())) {
        0L -> "hoy a $hour"
        1L -> "mañana a $hour"
        in 2L..6L -> "el ${target.dayOfWeek.spanishName()} a $hour"
        else -> "el ${target.toLocalDate()} a $hour" // no debería pasar: los bloqueos se miran a una semana
    }
}

/** Para "hasta …": "las 19:30" si es hoy; si no, como [formatWhen]. */
fun formatUntil(target: LocalDateTime, now: LocalDateTime): String =
    if (target.toLocalDate() == now.toLocalDate()) "las ${formatHour(target.toLocalTime())}" else formatWhen(target, now)

fun FilterLevel.title(): String = when (this) {
    FilterLevel.None -> "Sin filtro"
    FilterLevel.Adult -> "Contenido para adultos"
    FilterLevel.Strict -> "Estricto"
}

fun FilterLevel.description(): String = when (this) {
    FilterLevel.None -> "Solo se bloquean los servicios y sitios que elijas abajo."
    FilterLevel.Adult ->
        "Bloquea sitios para adultos y de malware, y fuerza la búsqueda segura de Google, Bing y DuckDuckGo. Usa el DNS para familias de Cloudflare."
    FilterLevel.Strict ->
        "Además fuerza el modo restringido de YouTube y bloquea VPN y proxys. Usa CleanBrowsing."
}

/** "Filtro: contenido para adultos · TikTok, Roblox · 2 sitios", o null sin filtro. */
fun formatFilter(filter: ContentFilter): String? {
    if (!filter.active) return null
    val parts = buildList {
        if (filter.level != FilterLevel.None) add(filter.level.title().lowercase(Locale.ROOT))
        if (filter.safeSearch && filter.level == FilterLevel.None) add("búsqueda segura")
        if (filter.youtube != YoutubeMode.Off && "youtube" !in filter.services) add("YouTube restringido")
        val names = SERVICES.filter { it.id in filter.services }.map { it.name }
        if (names.isNotEmpty()) add(names.joinToString(", "))
        when (val n = filter.domains.size) {
            0 -> {}
            1 -> add("1 sitio")
            else -> add("$n sitios")
        }
    }
    return "Filtro: " + parts.joinToString(" · ")
}

/** Etiqueta corta, si bloquea (null: no aplica) y frase con el detalle. */
data class AccessText(val pill: String, val blocked: Boolean?, val detail: String?)

fun accessText(profile: Profile, now: LocalDateTime): AccessText = when (val a = profile.access(now)) {
    Access.NoDevices -> AccessText("Sin dispositivos", null, "Asígnale dispositivos para que las reglas tengan efecto.")
    is Access.Paused -> AccessText(
        "En pausa", true,
        a.until?.let { "Sin internet hasta ${formatUntil(it, now)}." } ?: "Sin internet hasta que lo reanudes.",
    )
    is Access.Blocked -> AccessText("Fuera de horario", true, "Sin internet hasta ${formatUntil(a.until, now)}.")
    is Access.ExtraTime -> AccessText("Tiempo extra", false, "Con internet hasta ${formatUntil(a.until, now)}; después vuelve su horario.")
    Access.Allowed -> AccessText("Con internet", false, profile.nextBlock(now)?.let { "Próximo bloqueo: ${formatWhen(it, now)}." })
}

fun YoutubeMode.title(): String = when (this) {
    YoutubeMode.Off -> "Sin restricción"
    YoutubeMode.Moderate -> "Moderado"
    YoutubeMode.Strict -> "Estricto"
}

/**
 * Opción del diálogo de tiempo extra: hasta cuándo (hora del router, para mostrar), [minutes]
 * desde ahora o, si es null, hasta el final del horario en curso. Al aplicar se recalcula con la
 * hora del router en ese momento.
 */
data class ExtraOption(val until: LocalDateTime, val label: String, val minutes: Long? = null)

/**
 * Tiempo extra que se puede dar ahora: 30 min, 1 h o 2 h (si caben antes de que acabe el
 * horario en curso) o hasta el final del horario. Vacío si ahora no hay ningún horario.
 */
fun extraTimeOptions(profile: Profile, now: LocalDateTime): List<ExtraOption> {
    val end = profile.blockedIntervals(now.toLocalDate()).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }?.end
        ?: return emptyList()
    val short = listOf(30L to "30 minutos más", 60L to "1 hora más", 120L to "2 horas más")
        .map { (minutes, label) -> ExtraOption(now.plusMinutes(minutes).withNano(0), label, minutes) }
        .filter { it.until.isBefore(end) }
    // Un horario que no acaba (todos los días, todo el día) "termina" en el límite de lo que se
    // calcula: ofrecerlo daría días de internet presentados como el fin del horario.
    val untilEnd = if (end.isAfter(now.plusDays(1))) emptyList() else listOf(ExtraOption(end, "Hasta ${formatUntil(end, now)}, fin del horario"))
    return short + untilEnd
}
