package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.Interval
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.intervals
import dev.angel.wrtcontrol.parental.routerDateTime
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/** El horario del Wi-Fi tal como está en el router, con su hora local al leerlo. */
data class WifiSchedule(val schedules: List<Schedule>, val now: LocalDateTime)

/** rw------- : el crontab es de root. */
private const val CRONTAB_MODE = 0x180

/** El horario del Wi-Fi (ver WifiSchedule.kt): lo lee y lo escribe en el crontab del router. */
class WifiScheduleApi(private val ubus: UbusClient, private val writeLock: Mutex = Mutex()) {

    /** El horario y la hora del router, o null si no deja leer el crontab. */
    suspend fun read(): WifiSchedule? {
        val crontab = ubus.fileRead(CRONTAB) ?: return null
        return WifiSchedule(parseWifiSchedule(crontab), routerDateTime(ubus.routerLocalTime()))
    }

    /**
     * Deja el horario en [schedules] (vacío: sin horario). No apaga nada en el momento: empieza en
     * la próxima hora de apagar. Si con el horario nuevo ahora toca Wi-Fi y estaba apagado por el
     * anterior, lo enciende.
     */
    suspend fun save(schedules: List<Schedule>) = writeLock.withLock {
        wifiScheduleProblem(schedules)?.let { throw UserFacingException(it) }
        val before = ubus.fileRead(CRONTAB) ?: throw UserFacingException(txt(R.string.wifi_schedule_denied))
        val after = withWifiSchedule(before, schedules)
        if (after != before) {
            ubus.fileWrite(CRONTAB, after, mode = CRONTAB_MODE)
            if (schedules.isNotEmpty()) ubus.initAction("cron", "enable")
            ubus.initAction("cron", "restart")
            if (ubus.fileRead(CRONTAB) != after) throw UserFacingException(txt(R.string.wifi_schedule_not_saved))
            if (schedules.isNotEmpty() && ubus.serviceRunning("cron") == false) {
                throw UserFacingException(txt(R.string.wifi_schedule_cron_failed))
            }
        }
        val now = routerDateTime(ubus.routerLocalTime())
        // Sin /etc/config/wireless (un router sin Wi-Fi) no se puede leer: no hay nada que encender.
        val down = try {
            ubus.readWifi(withClients = false).someRadioDown
        } catch (_: UbusException) {
            false
        }
        if (wifiOffWindow(schedules, now) == null && down) turnOn()
    }

    /** Vuelve a encender el Wi-Fi que apagó el horario, hasta la próxima hora de apagar. */
    suspend fun turnOnNow() = writeLock.withLock { turnOn() }

    private suspend fun turnOn() {
        ubus.exec("/sbin/wifi", listOf("up"))
    }
}

/** La franja sin Wi-Fi en la que está [now] (hora local del router), o null si toca Wi-Fi. */
fun wifiOffWindow(schedules: List<Schedule>, now: LocalDateTime): Interval? =
    merged(schedules, now).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }

/** Cuándo empieza la próxima franja sin Wi-Fi después de [now], o null si no hay ninguna. */
fun nextWifiOff(schedules: List<Schedule>, now: LocalDateTime): LocalDateTime? =
    merged(schedules, now).map { it.start }.firstOrNull { it.isAfter(now) }

/** Las franjas de alrededor de [now], ordenadas y juntas cuando se pisan o se tocan. */
private fun merged(schedules: List<Schedule>, now: LocalDateTime): List<Interval> {
    val today = now.toLocalDate()
    val raw = schedules.flatMap { it.intervals(today.minusDays(1), today.plusDays(7)) }.sortedBy { it.start }
    val out = mutableListOf<Interval>()
    for (i in raw) {
        val last = out.lastOrNull()
        if (last != null && !i.start.isAfter(last.end)) {
            if (i.end.isAfter(last.end)) out[out.lastIndex] = last.copy(end = i.end)
        } else {
            out += i
        }
    }
    return out
}
