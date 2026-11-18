package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.parental.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

class WifiScheduleTest {

    private val schoolNights = setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY)

    private fun schedule(days: Set<DayOfWeek>, start: String, end: String, id: String = "0") =
        Schedule(id, days, LocalTime.parse(start), LocalTime.parse(end))

    @Test
    fun `una franja de noche apaga los dias elegidos y enciende al dia siguiente`() {
        val lines = cronLines(listOf(schedule(schoolNights, "23:00", "07:00")))
        assertEquals(
            listOf(
                "0 7 * * 1,2,3,4,5 /sbin/wifi up",
                "0 23 * * 0,1,2,3,4 /sbin/wifi down",
            ),
            lines,
        )
    }

    @Test
    fun `todos los dias se escribe con asterisco`() {
        assertEquals(
            listOf("30 6 * * * /sbin/wifi up", "15 22 * * * /sbin/wifi down"),
            cronLines(listOf(schedule(DayOfWeek.entries.toSet(), "22:15", "06:30"))),
        )
    }

    @Test
    fun `las franjas que se pisan no encienden el Wi-Fi en mitad`() {
        // Lunes de 23 a 7 y martes de 6 a 8: el martes solo se enciende a las 8.
        val lines = cronLines(listOf(schedule(setOf(MONDAY), "23:00", "07:00"), schedule(setOf(TUESDAY), "06:00", "08:00", "1")))
        assertEquals(listOf("0 8 * * 2 /sbin/wifi up", "0 23 * * 1 /sbin/wifi down"), lines)
    }

    @Test
    fun `un dia entero va de medianoche a medianoche`() {
        val lines = cronLines(listOf(schedule(setOf(SATURDAY), "00:00", "00:00")))
        assertEquals(listOf("0 0 * * 0 /sbin/wifi up", "0 0 * * 6 /sbin/wifi down"), lines)
    }

    @Test
    fun `un domingo por la noche enciende el lunes`() {
        val lines = cronLines(listOf(schedule(setOf(SUNDAY), "22:00", "06:00")))
        assertEquals(listOf("0 6 * * 1 /sbin/wifi up", "0 22 * * 0 /sbin/wifi down"), lines)
    }

    @Test
    fun `sin Wi-Fi a ninguna hora no se puede guardar`() {
        assertNotNull(wifiScheduleProblem(listOf(schedule(DayOfWeek.entries.toSet(), "00:00", "00:00"))))
        assertNull(wifiScheduleProblem(listOf(schedule(DayOfWeek.entries.toSet(), "23:00", "07:00"))))
        assertNull(wifiScheduleProblem(emptyList()))
    }

    @Test
    fun `se escribe, se lee y se quita sin tocar lo demas del crontab`() {
        val other = "# copia de seguridad\n0 3 * * * /usr/bin/backup.sh\n"
        val schedules = listOf(schedule(schoolNights, "23:00", "07:00"), schedule(setOf(FRIDAY, SATURDAY), "01:30", "08:00", "1"))
        val written = withWifiSchedule(other, schedules)
        assertTrue(written.startsWith(other))
        assertTrue(written.endsWith("$SCHEDULE_END\n"))
        assertEquals(schedules, parseWifiSchedule(written))
        // Volver a escribirlo no lo duplica.
        assertEquals(written, withWifiSchedule(written, schedules))
        // Quitarlo deja lo de antes.
        assertEquals(other, withWifiSchedule(written, emptyList()))
        assertEquals("", withWifiSchedule(withWifiSchedule("", schedules), emptyList()))
    }

    @Test
    fun `lo que hay despues del bloque se conserva`() {
        val written = withWifiSchedule("", listOf(schedule(setOf(MONDAY), "23:00", "07:00"))) + "5 4 * * * /bin/otra\n"
        val changed = withWifiSchedule(written, listOf(schedule(setOf(TUESDAY), "22:00", "06:00")))
        assertTrue(changed.contains("5 4 * * * /bin/otra"))
        assertEquals(setOf(TUESDAY), parseWifiSchedule(changed).single().days)
        assertEquals(1, changed.lines().count { it == SCHEDULE_BEGIN })
    }

    @Test
    fun `un crontab sin bloque de la app no tiene horario`() {
        assertEquals(emptyList<Schedule>(), parseWifiSchedule("0 3 * * * /usr/bin/backup.sh\n"))
        assertEquals(emptyList<Schedule>(), parseWifiSchedule(""))
    }

    @Test
    fun `dice si ahora toca apagado y cuando empieza el siguiente`() {
        val list = listOf(schedule(schoolNights, "23:00", "07:00"))
        val mondayNight = LocalDateTime.parse("2026-11-16T23:30") // lunes
        val window = wifiOffWindow(list, mondayNight)
        assertEquals(LocalDateTime.parse("2026-11-17T07:00"), window?.end)
        val fridayNoon = LocalDateTime.parse("2026-11-20T12:00") // viernes: la siguiente es el domingo
        assertNull(wifiOffWindow(list, fridayNoon))
        assertEquals(LocalDateTime.parse("2026-11-22T23:00"), nextWifiOff(list, fridayNoon))
        // El sábado a las 6 ya hay Wi-Fi: la franja del jueves acabó el viernes a las 7.
        assertNull(wifiOffWindow(list, LocalDateTime.parse("2026-11-21T06:00")))
        assertNotNull(wifiOffWindow(list, LocalDateTime.parse("2026-11-20T06:00")))
    }
}
