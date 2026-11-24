package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * vnStat 2 (luci-app-vnstat2): guarda en el router lo que pasa por cada tarjeta, por horas y por
 * días. Lo que la app necesita de él, comprobado en 24.10 y 25.12:
 *
 * - Su ACL de escritura deja ejecutar /usr/bin/vnstat con cualquier argumento: `--json h 24 -i
 *   <tarjeta>` y `--json d 30` dan el historial, y `--showconfig` la configuración en uso.
 * - Viene sin tarjetas que mirar: se ponen en la lista `interface` de /etc/config/vnstat (UCI), y
 *   su init las añade a la base al recargarse.
 * - Guarda la base en /var/lib/vnstat, que es RAM: al reiniciar el router se pierde todo. Hay que
 *   cambiar `DatabaseDir` en /etc/vnstat.conf, que el ACL de LuCI no deja ni leer ni escribir.
 *   Lo hace una línea del crontab de root (que sí deja escribir) que se ejecuta una vez y se borra
 *   sola: para vnStat, mueve la base a [VNSTAT_DIR] (en la flash), pone `SaveInterval 60` (una
 *   escritura por hora en la flash en vez de cada 5 minutos) y lo vuelve a arrancar.
 * - El cron de BusyBox corta la línea en el primer `#`, también dentro de comillas: la orden no
 *   puede llevar ninguno.
 */

internal const val VNSTAT = "/usr/bin/vnstat"

/** Donde la app deja la base de vnStat: en la flash, para que no se pierda al reiniciar. */
const val VNSTAT_DIR = "/etc/wrtc_vnstat"

/** Minutos entre escrituras de la base: una por hora, para no gastar la flash. */
internal const val VNSTAT_SAVE_MINUTES = 60

/** Lo que se instala: trae vnStat y el permiso de LuCI para usarlo desde la app. */
const val VNSTAT_PACKAGE = "luci-app-vnstat2"

/**
 * El sitio que hace falta para instalarlo, en KiB: unos 3 MB con sus dependencias (sqlite, y
 * libgd con las librerías de imagen para las gráficas de LuCI), más margen.
 */
const val VNSTAT_SPACE_KIB = 4096L

/** ¿Guarda vnStat donde lo deja la app? Según lo que da `vnstat --showconfig`. */
internal fun vnstatKeepsData(showconfig: String): Boolean {
    fun value(key: String) = showconfig.lines().firstOrNull { it.startsWith("$key ") }?.substringAfter(' ')?.trim()?.trim('"')
    return value("DatabaseDir") == VNSTAT_DIR && value("SaveInterval") == VNSTAT_SAVE_MINUTES.toString()
}

@Serializable
internal data class VnstatJson(val interfaces: List<Iface> = emptyList()) {
    @Serializable
    data class Iface(val name: String = "", val created: Stamp? = null, val updated: Stamp? = null, val traffic: Traffic = Traffic())

    /** [timestamp]: segundos UTC. `created` trae la fecha y el timestamp, pero no la hora. */
    @Serializable
    data class Stamp(val date: Date, val time: Time? = null, val timestamp: Long? = null)

    @Serializable
    data class Date(val year: Int, val month: Int, val day: Int)

    @Serializable
    data class Time(val hour: Int = 0, val minute: Int = 0)

    @Serializable
    data class Traffic(val hour: List<Entry> = emptyList(), val day: List<Entry> = emptyList())

    @Serializable
    data class Entry(val date: Date, val time: Time? = null, val rx: Long = 0, val tx: Long = 0)
}

private fun VnstatJson.Date.local(): LocalDate = LocalDate.of(year, month, day)

private fun VnstatJson.Stamp.hour(): LocalDateTime = date.local().atTime(time?.hour ?: 0, 0)

private fun VnstatJson.Stamp.minute(): LocalDateTime = date.local().atTime(time?.hour ?: 0, time?.minute ?: 0)

/**
 * La hora (del router) en que vnStat empezó a mirar la tarjeta. `created` no trae la hora: sale de
 * su timestamp con la diferencia horaria del router, que se ve en `updated` (trae las dos cosas).
 * Sin eso, el principio de su día.
 */
private fun VnstatJson.Iface.startedHour(updated: VnstatJson.Stamp): LocalDateTime? {
    val created = created ?: return null
    val createdAt = created.timestamp
    val updatedAt = updated.timestamp
    return if (created.time != null || createdAt == null || updatedAt == null) {
        created.hour()
    } else {
        val offset = Duration.between(LocalDateTime.ofEpochSecond(updatedAt, 0, ZoneOffset.UTC), updated.minute()).toMinutes()
        LocalDateTime.ofEpochSecond(createdAt, 0, ZoneOffset.UTC).plusMinutes(offset).truncatedTo(ChronoUnit.HOURS)
    }
}

/**
 * El historial de [device] con lo que dan `vnstat --json h` ([hours]) y `--json d` ([days]). Va
 * hasta la última vez que vnStat guardó (no hasta ahora: guarda cada hora) y empieza cuando
 * empezó a mirar esa tarjeta; las horas y los días sin entrada cuentan cero. null si vnStat no
 * conoce [device].
 */
internal fun parseUsageHistory(hours: VnstatJson, days: VnstatJson, device: String): UsageHistory? {
    val iface = hours.interfaces.firstOrNull { it.name == device } ?: return null
    val updated = iface.updated
    return if (updated == null) UsageHistory(emptyList(), emptyList()) else history(iface, days, device, updated)
}

private fun history(iface: VnstatJson.Iface, days: VnstatJson, device: String, updated: VnstatJson.Stamp): UsageHistory {
    val end = updated.hour()
    val created = iface.startedHour(updated) ?: end
    val byHour = iface.traffic.hour.associateBy { it.date.local().atTime(it.time?.hour ?: 0, 0) }
    val hourly = (0 until UsagePeriod.Day.buckets).map { end.minusHours(it.toLong()) }
        .filter { !it.isBefore(created) }
        .reversed()
        .map { start -> byHour[start].let { UsageBucket(start, it?.rx ?: 0, it?.tx ?: 0) } }
    val dayTraffic = days.interfaces.firstOrNull { it.name == device }?.traffic?.day.orEmpty().associateBy { it.date.local() }
    val daily = (0 until UsagePeriod.Month.buckets).map { end.toLocalDate().minusDays(it.toLong()) }
        .filter { !it.isBefore(created.toLocalDate()) }
        .reversed()
        .map { day -> dayTraffic[day].let { UsageBucket(day.atStartOfDay(), it?.rx ?: 0, it?.tx ?: 0) } }
    return UsageHistory(hourly, daily)
}
