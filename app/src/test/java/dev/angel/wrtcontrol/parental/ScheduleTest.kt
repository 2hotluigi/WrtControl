package dev.angel.wrtcontrol.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class ScheduleTest {

    private val schoolNights = setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY)
    private fun t(h: Int, m: Int = 0, s: Int = 0) = LocalTime.of(h, m, s)

    // 2026-11-02 es lunes.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)

    @Test
    fun `franja dentro del mismo dia - una regla`() {
        val w = Schedule("a", setOf(SATURDAY), t(15), t(17, 30)).windows()
        assertEquals(listOf(RuleWindow(setOf(SATURDAY), t(15), t(17, 29, 59))), w)
    }

    @Test
    fun `franja que cruza la medianoche - noche del dia y madrugada del siguiente`() {
        val w = Schedule("a", schoolNights, t(21), t(7)).windows()
        assertEquals(2, w.size)
        assertEquals(RuleWindow(schoolNights, t(21), t(23, 59, 59)), w[0])
        // El domingo por la noche sigue el lunes de madrugada; el jueves, el viernes.
        assertEquals(RuleWindow(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), t(0), t(6, 59, 59)), w[1])
    }

    @Test
    fun `franja que acaba justo a medianoche - una sola regla`() {
        val w = Schedule("a", setOf(FRIDAY), t(22), LocalTime.MIDNIGHT).windows()
        assertEquals(listOf(RuleWindow(setOf(FRIDAY), t(22), t(23, 59, 59))), w)
    }

    @Test
    fun `todo el dia - sin horas`() {
        val w = Schedule("a", setOf(SUNDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT).windows()
        assertEquals(listOf(RuleWindow(setOf(SUNDAY), null, null)), w)
    }

    @Test
    fun `sabado por la noche pasa al domingo`() {
        assertEquals(DayOfWeek.SUNDAY, SATURDAY.plus(1))
        val w = Schedule("a", setOf(SATURDAY), t(23), t(1)).windows()
        assertEquals(setOf(SUNDAY), w[1].days)
    }

    private val juan = Profile(
        "j1", "Juan", listOf("AA:BB:CC:DD:EE:01"),
        schedules = listOf(Schedule("n", schoolNights, t(21), t(7))),
    )

    @Test
    fun `acceso segun el horario`() {
        assertEquals(Access.Allowed, juan.access(at(2, 20, 59))) // lunes 20:59
        assertEquals(Access.Blocked(at(3, 7)), juan.access(at(2, 21, 0))) // lunes 21:00 → martes 07:00
        assertEquals(Access.Blocked(at(3, 7)), juan.access(at(3, 6, 59))) // martes de madrugada
        assertEquals(Access.Allowed, juan.access(at(3, 7, 0)))
        // Viernes de madrugada sigue bloqueado (viene del jueves); el viernes por la noche no.
        assertEquals(Access.Blocked(at(6, 7)), juan.access(at(6, 2)))
        assertEquals(Access.Allowed, juan.access(at(6, 22)))
        // Sábado de madrugada libre; domingo por la noche bloqueado.
        assertEquals(Access.Allowed, juan.access(at(7, 2)))
        assertEquals(Access.Blocked(at(9, 7)), juan.access(at(8, 23)))
    }

    @Test
    fun `la pausa manda sobre el horario y caduca sola`() {
        val paused = juan.copy(pause = Pause.Until(at(2, 18)))
        assertEquals(Access.Paused(at(2, 18)), paused.access(at(2, 17)))
        assertEquals(Access.Allowed, paused.access(at(2, 18)))
        assertEquals(Access.Paused(null), juan.copy(pause = Pause.Indefinite).access(at(2, 12)))
        assertNull(paused.withoutExpiredPause(at(2, 18, 1)).pause)
    }

    @Test
    fun `sin dispositivos no bloquea a nadie`() {
        assertEquals(Access.NoDevices, juan.copy(macs = emptyList(), pause = Pause.Indefinite).access(at(2, 22)))
    }

    @Test
    fun `franjas que se tocan se fusionan`() {
        val p = juan.copy(
            schedules = listOf(
                Schedule("a", setOf(MONDAY), t(13), t(15)),
                Schedule("b", setOf(MONDAY), t(15), t(17)),
            ),
        )
        assertEquals(Access.Blocked(at(2, 17)), p.access(at(2, 14)))
    }

    @Test
    fun `proximo bloqueo`() {
        assertEquals(at(2, 21), juan.nextBlock(at(2, 12)))
        assertEquals(at(8, 21), juan.nextBlock(at(6, 12))) // viernes → domingo 21:00
        assertNull(juan.copy(schedules = emptyList()).nextBlock(at(2, 12)))
    }

    @Test
    fun `todo el dia bloquea de 00 a 24`() {
        val p = juan.copy(schedules = listOf(Schedule("d", setOf(SUNDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)))
        assertEquals(Access.Blocked(at(9, 0)), p.access(at(8, 0, 0)))
        assertEquals(Access.Allowed, p.access(at(7, 23, 59)))
    }
}
