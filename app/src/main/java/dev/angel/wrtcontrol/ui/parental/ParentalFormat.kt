package dev.angel.wrtcontrol.ui.parental

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.joinTxt
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
import dev.angel.wrtcontrol.plural
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
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

/** Inicial del día (en español, la del miércoles es X para no confundirla con la del martes). */
fun DayOfWeek.letter(): Txt = txt(
    when (this) {
        MONDAY -> R.string.parental_day_letter_monday
        TUESDAY -> R.string.parental_day_letter_tuesday
        WEDNESDAY -> R.string.parental_day_letter_wednesday
        THURSDAY -> R.string.parental_day_letter_thursday
        FRIDAY -> R.string.parental_day_letter_friday
        SATURDAY -> R.string.parental_day_letter_saturday
        SUNDAY -> R.string.parental_day_letter_sunday
    },
)

/** "sábado" (en español en minúscula, como va en mitad de una frase). */
fun DayOfWeek.dayName(): Txt = txt(
    when (this) {
        MONDAY -> R.string.parental_day_monday
        TUESDAY -> R.string.parental_day_tuesday
        WEDNESDAY -> R.string.parental_day_wednesday
        THURSDAY -> R.string.parental_day_thursday
        FRIDAY -> R.string.parental_day_friday
        SATURDAY -> R.string.parental_day_saturday
        SUNDAY -> R.string.parental_day_sunday
    },
)

/** lun, mar, mié...; con [first], como empieza una lista (Lun). */
private fun DayOfWeek.short(first: Boolean): Txt = txt(if (first) shortFirstRes() else shortRes())

private fun DayOfWeek.shortRes(): Int = when (this) {
    MONDAY -> R.string.parental_day_short_monday
    TUESDAY -> R.string.parental_day_short_tuesday
    WEDNESDAY -> R.string.parental_day_short_wednesday
    THURSDAY -> R.string.parental_day_short_thursday
    FRIDAY -> R.string.parental_day_short_friday
    SATURDAY -> R.string.parental_day_short_saturday
    SUNDAY -> R.string.parental_day_short_sunday
}

private fun DayOfWeek.shortFirstRes(): Int = when (this) {
    MONDAY -> R.string.parental_day_short_first_monday
    TUESDAY -> R.string.parental_day_short_first_tuesday
    WEDNESDAY -> R.string.parental_day_short_first_wednesday
    THURSDAY -> R.string.parental_day_short_first_thursday
    FRIDAY -> R.string.parental_day_short_first_friday
    SATURDAY -> R.string.parental_day_short_first_saturday
    SUNDAY -> R.string.parental_day_short_first_sunday
}

fun formatDays(days: Set<DayOfWeek>): Txt = when (days) {
    WEEK.toSet() -> txt(R.string.parental_days_every)
    WEEKDAYS -> txt(R.string.parental_days_weekdays)
    WEEKEND -> txt(R.string.parental_days_weekend)
    SCHOOL_NIGHTS -> txt(R.string.parental_days_school_nights)
    else -> WEEK.filter { it in days }.mapIndexed { i, day -> day.short(first = i == 0) }.joinTxt(", ")
}

fun formatHour(time: LocalTime): String = time.format(HOUR)

/** "Lunes a viernes · 21:00 → 07:00 del día siguiente" */
fun formatSchedule(s: Schedule): Txt {
    val hours = when {
        s.allDay -> txt(R.string.parental_hours_all_day)
        s.overnight -> txt(R.string.parental_hours_overnight, formatHour(s.start), formatHour(s.end))
        else -> raw("${formatHour(s.start)} → ${formatHour(s.end)}")
    }
    return listOf(formatDays(s.days), hours).joinTxt(" · ")
}

/** "hoy a las 21:00", "mañana a las 07:00", "el sábado a las 09:00" (respecto a [now]). */
fun formatWhen(target: LocalDateTime, now: LocalDateTime): Txt =
    relative(target, now, R.string.parental_when_today, R.string.parental_when_tomorrow, R.string.parental_when_day)

/** Para "hasta …": "las 19:30" si es hoy, "mañana a las 07:00", "el sábado a las 09:00". */
fun formatUntil(target: LocalDateTime, now: LocalDateTime): Txt =
    relative(target, now, R.string.parental_until_today, R.string.parental_until_tomorrow, R.string.parental_until_day)

private fun relative(
    target: LocalDateTime,
    now: LocalDateTime,
    @StringRes today: Int,
    @StringRes tomorrow: Int,
    @StringRes day: Int,
): Txt {
    val hour = formatHour(target.toLocalTime())
    return when (ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())) {
        0L -> txt(today, hour)
        1L -> txt(tomorrow, hour)
        in 2L..6L -> txt(day, target.dayOfWeek.dayName(), hour)
        else -> txt(day, target.toLocalDate().toString(), hour) // no debería pasar: los bloqueos se miran a una semana
    }
}

fun FilterLevel.title(): Txt = txt(
    when (this) {
        FilterLevel.None -> R.string.parental_filter_none
        FilterLevel.Adult -> R.string.parental_filter_adult
        FilterLevel.Strict -> R.string.parental_filter_strict
    },
)

fun FilterLevel.description(): Txt = txt(
    when (this) {
        FilterLevel.None -> R.string.parental_filter_none_description
        FilterLevel.Adult -> R.string.parental_filter_adult_description
        FilterLevel.Strict -> R.string.parental_filter_strict_description
    },
)

/** "Filtro: contenido para adultos · TikTok, Roblox · 2 sitios", o null sin filtro. */
fun formatFilter(filter: ContentFilter): Txt? {
    if (!filter.active) return null
    val parts = buildList {
        when (filter.level) {
            FilterLevel.None -> if (filter.safeSearch) add(txt(R.string.parental_filter_summary_safe_search))
            FilterLevel.Adult -> add(txt(R.string.parental_filter_summary_adult))
            FilterLevel.Strict -> add(txt(R.string.parental_filter_summary_strict))
        }
        if (filter.youtube != YoutubeMode.Off && "youtube" !in filter.services) add(txt(R.string.parental_filter_summary_youtube))
        // Nombres propios: no se traducen.
        val names = SERVICES.filter { it.id in filter.services }.map { it.name }
        if (names.isNotEmpty()) add(raw(names.joinToString(", ")))
        val sites = filter.domains.size
        if (sites > 0) add(plural(R.plurals.parental_filter_summary_sites, sites, sites))
    }
    return txt(R.string.parental_filter_summary, parts.joinTxt(" · "))
}

/** Etiqueta corta, si bloquea (null: no aplica) y frase con el detalle. */
data class AccessText(val pill: Txt, val blocked: Boolean?, val detail: Txt?)

fun accessText(profile: Profile, now: LocalDateTime): AccessText = when (val a = profile.access(now)) {
    Access.NoDevices -> AccessText(txt(R.string.parental_access_no_devices), null, txt(R.string.parental_access_no_devices_detail))
    is Access.Paused -> AccessText(
        txt(R.string.parental_access_paused),
        true,
        a.until?.let { txt(R.string.parental_access_offline_until, formatUntil(it, now)) }
            ?: txt(R.string.parental_access_paused_indefinite),
    )
    is Access.Blocked ->
        AccessText(txt(R.string.parental_access_blocked), true, txt(R.string.parental_access_offline_until, formatUntil(a.until, now)))
    is Access.ExtraTime ->
        AccessText(txt(R.string.parental_access_extra), false, txt(R.string.parental_access_extra_detail, formatUntil(a.until, now)))
    Access.Allowed -> AccessText(
        txt(R.string.parental_access_allowed),
        false,
        profile.nextBlock(now)?.let { txt(R.string.parental_access_next_block, formatWhen(it, now)) },
    )
}

fun YoutubeMode.title(): Txt = txt(
    when (this) {
        YoutubeMode.Off -> R.string.parental_youtube_off
        YoutubeMode.Moderate -> R.string.parental_youtube_moderate
        YoutubeMode.Strict -> R.string.parental_youtube_strict
    },
)

/**
 * Opción del diálogo de tiempo extra: hasta cuándo (hora del router, para mostrar), [minutes]
 * desde ahora o, si es null, hasta el final del horario en curso. Al aplicar se recalcula con la
 * hora del router en ese momento.
 */
data class ExtraOption(val until: LocalDateTime, val label: Txt, val minutes: Long? = null)

/**
 * Tiempo extra que se puede dar ahora: 30 min, 1 h o 2 h (si caben antes de que acabe el
 * horario en curso) o hasta el final del horario. Vacío si ahora no hay ningún horario.
 */
fun extraTimeOptions(profile: Profile, now: LocalDateTime): List<ExtraOption> {
    val end = profile.blockedIntervals(now.toLocalDate()).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }?.end
        ?: return emptyList()
    val short = listOf(
        30L to plural(R.plurals.parental_extra_minutes, 30, 30),
        60L to plural(R.plurals.parental_extra_hours, 1, 1),
        120L to plural(R.plurals.parental_extra_hours, 2, 2),
    )
        .map { (minutes, label) -> ExtraOption(now.plusMinutes(minutes).withNano(0), label, minutes) }
        .filter { it.until.isBefore(end) }
    // Un horario que no acaba (todos los días, todo el día) "termina" en el límite de lo que se
    // calcula: ofrecerlo daría días de internet presentados como el fin del horario.
    val untilEnd = if (end.isAfter(now.plusDays(1))) {
        emptyList()
    } else {
        listOf(ExtraOption(end, txt(R.string.parental_extra_until_end, formatUntil(end, now))))
    }
    return short + untilEnd
}
