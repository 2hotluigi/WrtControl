package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.ui.parental.accessText
import dev.angel.wrtcontrol.ui.parental.formatDays
import dev.angel.wrtcontrol.ui.parental.formatSchedule
import dev.angel.wrtcontrol.ui.parental.formatWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
import java.time.format.TextStyle
import java.util.Locale

class ParentalFormatTest {

    // Miércoles 4 de noviembre de 2026, 22:20.
    private val now = LocalDateTime.of(2026, 11, 4, 22, 20)

    @Test
    fun `los dias en ingles salen en ingles`() {
        val en = listOf("src/main/res/values-en", "app/src/main/res/values-en").map(::File).first { it.isDirectory }
            .resolve("strings_parental.xml").readText()
        for (day in DayOfWeek.entries) {
            val key = day.name.lowercase(Locale.ROOT)
            for ((name, style) in listOf("" to TextStyle.FULL, "short_" to TextStyle.SHORT, "short_first_" to TextStyle.SHORT)) {
                val wanted = "<string name=\"parental_day_$name$key\">${day.getDisplayName(style, Locale.ENGLISH)}</string>"
                assertTrue(wanted, wanted in en)
            }
        }
    }

    @Test
    fun dias() {
        assertEquals("Lunes a viernes", formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)).es)
        assertEquals("Fin de semana", formatDays(setOf(SATURDAY, SUNDAY)).es)
        assertEquals("Domingo a jueves", formatDays(setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY)).es)
        assertEquals("Todos los días", formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)).es)
        assertEquals("Lun, mié, sáb", formatDays(setOf(SATURDAY, WEDNESDAY, MONDAY)).es)
    }

    @Test
    fun horarios() {
        assertEquals(
            "Domingo a jueves · 21:00 → 07:00 del día siguiente",
            formatSchedule(Schedule("a", setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY), LocalTime.of(21, 0), LocalTime.of(7, 0))).es,
        )
        assertEquals("Fin de semana · 15:00 → 17:30", formatSchedule(Schedule("b", setOf(SATURDAY, SUNDAY), LocalTime.of(15, 0), LocalTime.of(17, 30))).es)
        assertEquals("Sáb · todo el día", formatSchedule(Schedule("c", setOf(SATURDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)).es)
    }

    @Test
    fun cuando() {
        assertEquals("hoy a las 23:00", formatWhen(LocalDateTime.of(2026, 11, 4, 23, 0), now).es)
        assertEquals("mañana a las 07:00", formatWhen(LocalDateTime.of(2026, 11, 5, 7, 0), now).es)
        assertEquals("el domingo a las 21:00", formatWhen(LocalDateTime.of(2026, 11, 8, 21, 0), now).es)
    }

    @Test
    fun estado() {
        val juan = Profile(
            "j1", "Juan", listOf("AA:BB:CC:DD:EE:01"),
            schedules = listOf(Schedule("n", setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY), LocalTime.of(21, 0), LocalTime.of(7, 0))),
        )
        assertEquals("Sin internet hasta mañana a las 07:00.", accessText(juan, now).detail?.es)
        assertEquals("Fuera de horario", accessText(juan, now).pill.es)
        assertEquals("Próximo bloqueo: hoy a las 21:00.", accessText(juan, now.withHour(12)).detail?.es)
        assertEquals("Sin internet hasta las 23:50.", accessText(juan.copy(pause = Pause.Until(now.plusMinutes(90).withMinute(50))), now).detail?.es)
        assertEquals("Sin internet hasta que lo reanudes.", accessText(juan.copy(pause = Pause.Indefinite), now).detail?.es)
        assertEquals(null, accessText(juan.copy(macs = emptyList()), now).blocked)
    }
}
