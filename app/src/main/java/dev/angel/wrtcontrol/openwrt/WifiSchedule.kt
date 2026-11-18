package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.txt
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/*
 * El horario del Wi-Fi: franjas en las que el router deja de emitir (todas sus redes, también la de
 * invitados), como "de 23:00 a 07:00 de domingo a jueves". Lo hace cron, que trae cualquier
 * OpenWrt: el ACL de LuCI deja escribir /etc/crontabs/root y reiniciar cron (su página "Tareas
 * programadas"). A la hora de apagar, `wifi down`; a la de encender, `wifi up`.
 *
 * `wifi down` no toca la configuración: el Wi-Fi sigue apagado aunque se recargue (otro cambio de
 * Wi-Fi a medianoche no lo enciende), y al reiniciar el router vuelve a emitir. Es a propósito: un
 * corte de luz de noche no deja la casa sin Wi-Fi hasta el día siguiente.
 *
 * La app escribe un bloque propio en el crontab y respeta lo demás:
 *
 *   # wrtcontrol: horario del Wi-Fi. Lo escribe la app WrtControl: cámbialo desde ella.
 *   # wrtcontrol wifi-off 1,2,3,4,7 23:00 07:00
 *   0 23 * * 0,1,2,3,4 /sbin/wifi down
 *   0 7 * * 1,2,3,4,5 /sbin/wifi up
 *   # wrtcontrol: fin
 *
 * Las líneas `wifi-off` (días ISO, lunes = 1) son las franjas tal como se eligieron; las de cron
 * salen de juntarlas, para que una franja que pisa a otra no encienda el Wi-Fi en mitad de ella.
 */

const val CRONTAB = "/etc/crontabs/root"

internal const val SCHEDULE_BEGIN = "# wrtcontrol: horario del Wi-Fi. Lo escribe la app WrtControl: cámbialo desde ella."
internal const val SCHEDULE_END = "# wrtcontrol: fin"
private const val ENTRY = "# wrtcontrol wifi-off "
internal const val WIFI_DOWN = "/sbin/wifi down"
internal const val WIFI_UP = "/sbin/wifi up"

private const val HOUR_MINUTES = 60
private const val WEEK_DAYS = 7
private const val DAY_MINUTES = 24 * HOUR_MINUTES
private const val WEEK_MINUTES = WEEK_DAYS * DAY_MINUTES

/** Días, hora de apagar y hora de encender. */
private const val ENTRY_PARTS = 3
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** Las franjas sin Wi-Fi que hay en [crontab] (vacío si no hay bloque de la app). */
fun parseWifiSchedule(crontab: String): List<Schedule> =
    ownBlock(crontab).filter { it.startsWith(ENTRY) }.mapNotNull { line ->
        val parts = line.removePrefix(ENTRY).trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size != ENTRY_PARTS) return@mapNotNull null
        val numbers = parts[0].split(',').mapNotNull { it.toIntOrNull() }
        val days = DayOfWeek.entries.filter { it.value in numbers }.toSet()
        val start = parseHour(parts[1])
        val end = parseHour(parts[2])
        if (days.isEmpty() || start == null || end == null) null else Schedule("", days, start, end)
    }.mapIndexed { i, s -> s.copy(id = i.toString()) }

private fun parseHour(text: String): LocalTime? = try {
    LocalTime.parse(text, HH_MM)
} catch (_: DateTimeParseException) {
    null
}

/** Las líneas del bloque de la app (sin las marcas de principio y fin). */
private fun ownBlock(crontab: String): List<String> {
    val lines = crontab.lines()
    val begin = lines.indexOf(SCHEDULE_BEGIN).takeIf { it >= 0 } ?: return emptyList()
    val end = lines.subList(begin, lines.size).indexOf(SCHEDULE_END).takeIf { it >= 0 }?.plus(begin) ?: lines.size
    return lines.subList(begin + 1, end)
}

/**
 * [crontab] con el horario [schedules] en lugar del que hubiera; lo que no es de la app queda como
 * estaba. Sin franjas, el bloque desaparece. Siempre acaba en salto de línea (cron ignora la última
 * línea sin él).
 */
fun withWifiSchedule(crontab: String, schedules: List<Schedule>): String {
    val lines = crontab.lines()
    val begin = lines.indexOf(SCHEDULE_BEGIN)
    val kept = if (begin < 0) {
        lines
    } else {
        val end = lines.subList(begin, lines.size).indexOf(SCHEDULE_END).takeIf { it >= 0 }?.plus(begin) ?: lines.lastIndex
        lines.subList(0, begin) + lines.subList(end + 1, lines.size)
    }.dropLastWhile { it.isBlank() }
    val block = if (schedules.isEmpty()) emptyList() else listOf(SCHEDULE_BEGIN) + scheduleLines(schedules) + SCHEDULE_END
    val all = kept + block
    return if (all.isEmpty()) "" else all.joinToString("\n", postfix = "\n")
}

private fun scheduleLines(schedules: List<Schedule>): List<String> {
    val entries = schedules.map { s ->
        val days = s.days.map { it.value }.sorted().joinToString(",")
        "$ENTRY$days ${s.start.format(HH_MM)} ${s.end.format(HH_MM)}"
    }
    return entries + cronLines(schedules)
}

/** ¿Qué minutos de la semana (lunes 00:00 = 0) quedan sin Wi-Fi con [schedules]? */
private fun offMinutes(schedules: List<Schedule>): BooleanArray {
    val off = BooleanArray(WEEK_MINUTES)
    for (s in schedules) {
        val length = if (s.allDay) DAY_MINUTES else Math.floorMod(s.end.minuteOfDay() - s.start.minuteOfDay(), DAY_MINUTES)
        for (day in s.days) {
            val from = (day.value - 1) * DAY_MINUTES + s.start.minuteOfDay()
            for (m in 0 until length) off[(from + m) % WEEK_MINUTES] = true
        }
    }
    return off
}

private fun LocalTime.minuteOfDay(): Int = hour * HOUR_MINUTES + minute

/**
 * Las líneas de cron de [schedules]: una por hora y acción, con los días en que toca (en cron, el
 * domingo es 0). Las franjas que se pisan o se tocan cuentan como una.
 */
internal fun cronLines(schedules: List<Schedule>): List<String> {
    val off = offMinutes(schedules)
    // (minuto del día, apagar) → días de cron
    val edges = sortedMapOf<Pair<Int, Boolean>, MutableSet<Int>>(compareBy({ it.first }, { it.second }))
    for (m in 0 until WEEK_MINUTES) {
        val before = off[Math.floorMod(m - 1, WEEK_MINUTES)]
        if (off[m] == before) continue
        val isoDay = m / DAY_MINUTES + 1
        edges.getOrPut((m % DAY_MINUTES) to off[m]) { sortedSetOf() } += isoDay % WEEK_DAYS
    }
    return edges.map { (key, days) ->
        val (minute, down) = key
        val dayField = if (days.size == WEEK_DAYS) "*" else days.joinToString(",")
        "${minute % HOUR_MINUTES} ${minute / HOUR_MINUTES} * * $dayField ${if (down) WIFI_DOWN else WIFI_UP}"
    }
}

/** Por qué no se puede guardar [schedules], o null si se puede. */
fun wifiScheduleProblem(schedules: List<Schedule>): Txt? =
    if (schedules.isNotEmpty() && offMinutes(schedules).all { it }) txt(R.string.wifi_schedule_always_off) else null
