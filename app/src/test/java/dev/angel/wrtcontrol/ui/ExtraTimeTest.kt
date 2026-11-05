package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.parental.Access
import dev.angel.wrtcontrol.parental.ContentFilter
import dev.angel.wrtcontrol.parental.FilterLevel
import dev.angel.wrtcontrol.parental.Interval
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.YoutubeMode
import dev.angel.wrtcontrol.parental.access
import dev.angel.wrtcontrol.parental.blockedIntervals
import dev.angel.wrtcontrol.parental.effectiveBlocks
import dev.angel.wrtcontrol.parental.nextBlock
import dev.angel.wrtcontrol.parental.withoutExpired
import dev.angel.wrtcontrol.ui.parental.AccessText
import dev.angel.wrtcontrol.ui.parental.ExtraOption
import dev.angel.wrtcontrol.ui.parental.accessText
import dev.angel.wrtcontrol.ui.parental.extraTimeOptions
import dev.angel.wrtcontrol.ui.parental.formatFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime

/** Tiempo extra sobre los horarios: modelo (acceso, bloqueos efectivos) y textos de la interfaz. */
class ExtraTimeTest {

    // 2026-11-02 es lunes; el 4 es miércoles.
    private fun at(day: Int, h: Int, m: Int = 0, s: Int = 0) = LocalDateTime.of(2026, 11, day, h, m, s)

    /** Miércoles 22:20: dentro del horario de la noche, que acaba el jueves a las 07:00. */
    private val now = at(4, 22, 20)

    private val nights = Schedule("n", setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY), LocalTime.of(21, 0), LocalTime.of(7, 0))
    private val juan = Profile("j1", "Juan", listOf("AA:BB:CC:DD:EE:01"), schedules = listOf(nights))

    // ---------- modelo ----------

    @Test
    fun `dentro del horario con tiempo extra hay internet hasta que acaba`() {
        val p = juan.copy(extraUntil = at(4, 23))
        assertEquals(Access.ExtraTime(at(4, 23)), p.access(now))
        assertEquals(Access.ExtraTime(at(4, 23)), p.access(at(4, 22, 59, 59)))
        // Tiempo extra hasta el final del horario.
        assertEquals(Access.ExtraTime(at(5, 7)), juan.copy(extraUntil = at(5, 7)).access(at(5, 3)))
    }

    @Test
    fun `al vencer el tiempo extra vuelve el bloqueo`() {
        val p = juan.copy(extraUntil = at(4, 23))
        assertEquals(Access.Blocked(at(5, 7)), p.access(at(4, 23)))
        assertEquals("ya vencido", Access.Blocked(at(5, 7)), juan.copy(extraUntil = at(4, 22)).access(now))
        assertNull(p.withoutExpired(at(4, 23)).extraUntil)
        assertEquals(at(4, 23), p.withoutExpired(at(4, 22, 59)).extraUntil)
    }

    @Test
    fun `fuera de horario no cambia el acceso pero retrasa el proximo bloqueo`() {
        val p = juan.copy(extraUntil = at(4, 21, 30))
        assertEquals(Access.Allowed, p.access(at(4, 12)))
        assertEquals("el horario empieza al acabar el tiempo extra", at(4, 21, 30), p.nextBlock(at(4, 12)))
    }

    @Test
    fun `la pausa manda sobre el tiempo extra y sin dispositivos no hay nada`() {
        assertEquals(Access.Paused(null), juan.copy(pause = Pause.Indefinite, extraUntil = at(4, 23)).access(now))
        assertEquals(Access.Paused(at(4, 23, 30)), juan.copy(pause = Pause.Until(at(4, 23, 30)), extraUntil = at(4, 23)).access(now))
        assertEquals(Access.NoDevices, juan.copy(macs = emptyList(), extraUntil = at(4, 23)).access(now))
    }

    @Test
    fun `bloqueos efectivos - recorta el que esta en curso y quita los que cubre`() {
        val today = now.toLocalDate()
        val raw = juan.blockedIntervals(today)
        assertEquals("sin tiempo extra, los del horario", raw, juan.effectiveBlocks(today))

        val trimmed = juan.copy(extraUntil = at(4, 23)).effectiveBlocks(today)
        // El de anoche (martes 21:00 → miércoles 07:00) acabó antes del tiempo extra: desaparece.
        assertEquals(Interval(at(4, 23), at(5, 7)), trimmed.first())
        assertEquals(raw.filter { it.start.isAfter(at(4, 23)) }, trimmed.drop(1))

        // Hasta el final justo del bloqueo: ese bloqueo desaparece entero.
        val whole = juan.copy(extraUntil = at(5, 7)).effectiveBlocks(today)
        assertEquals(Interval(at(5, 21), at(6, 7)), whole.first())
        assertFalse(whole.any { it.end == at(5, 7) })

        // Si acaba justo cuando empieza un bloqueo, ese bloqueo queda igual.
        val atStart = juan.copy(extraUntil = at(4, 21)).effectiveBlocks(today)
        assertEquals(Interval(at(4, 21), at(5, 7)), atStart.first())
    }

    @Test
    fun `proximo bloqueo con tiempo extra`() {
        assertEquals("sin extra, el de mañana por la noche", at(5, 21), juan.nextBlock(now))
        assertEquals("el bloqueo sigue al acabar el extra", at(4, 23), juan.copy(extraUntil = at(4, 23)).nextBlock(now))
        assertEquals(at(5, 21), juan.copy(extraUntil = at(5, 7)).nextBlock(now))
        // Vencido: como si no hubiera.
        assertEquals(at(5, 21), juan.copy(extraUntil = at(4, 22)).nextBlock(now))
    }

    // ---------- textos ----------

    @Test
    fun `estado con tiempo extra`() {
        assertEquals(
            AccessText("Tiempo extra", false, "Con internet hasta las 23:00; después vuelve su horario."),
            accessText(juan.copy(extraUntil = at(4, 23)), now),
        )
        assertEquals(
            "Con internet hasta mañana a las 07:00; después vuelve su horario.",
            accessText(juan.copy(extraUntil = at(5, 7)), now).detail,
        )
        // Al vencer vuelve a estar fuera de horario.
        assertEquals("Fuera de horario", accessText(juan.copy(extraUntil = at(4, 22)), now).pill)
    }

    @Test
    fun `opciones de tiempo extra en mitad del horario`() {
        assertEquals(
            listOf(
                ExtraOption(at(4, 22, 50), "30 minutos más", 30),
                ExtraOption(at(4, 23, 20), "1 hora más", 60),
                ExtraOption(at(5, 0, 20), "2 horas más", 120),
                ExtraOption(at(5, 7), "Hasta mañana a las 07:00, fin del horario"),
            ),
            extraTimeOptions(juan, now),
        )
    }

    @Test
    fun `opciones de tiempo extra - solo las que caben antes del fin del horario`() {
        assertEquals(
            listOf(
                ExtraOption(at(5, 6), "30 minutos más", 30),
                ExtraOption(at(5, 6, 30), "1 hora más", 60),
                ExtraOption(at(5, 7), "Hasta las 07:00, fin del horario"),
            ),
            extraTimeOptions(juan, at(5, 5, 30)),
        )
        assertEquals(
            listOf(ExtraOption(at(5, 6, 45), "30 minutos más", 30), ExtraOption(at(5, 7), "Hasta las 07:00, fin del horario")),
            extraTimeOptions(juan, at(5, 6, 15)),
        )
        // Justo 30 minutos antes ya no tiene sentido "30 minutos más": coincide con el fin.
        assertEquals(listOf(ExtraOption(at(5, 7), "Hasta las 07:00, fin del horario")), extraTimeOptions(juan, at(5, 6, 30)))
    }

    @Test
    fun `opciones de tiempo extra - vacio fuera de horario`() {
        assertTrue(extraTimeOptions(juan, at(4, 12)).isEmpty())
        assertTrue("justo al acabar", extraTimeOptions(juan, at(5, 7)).isEmpty())
        assertTrue("viernes por la noche no hay horario", extraTimeOptions(juan, at(6, 22)).isEmpty())
        assertTrue("horario desactivado", extraTimeOptions(juan.copy(schedules = listOf(nights.copy(enabled = false))), now).isEmpty())
        assertTrue("sin horarios", extraTimeOptions(juan.copy(schedules = emptyList()), now).isEmpty())
    }

    @Test
    fun `opciones de tiempo extra - horarios que se tocan cuentan como uno y sin fracciones de segundo`() {
        val morning = Schedule("m", setOf(THURSDAY), LocalTime.of(7, 0), LocalTime.of(8, 0))
        val p = juan.copy(schedules = listOf(nights, morning))
        assertEquals(ExtraOption(at(5, 8), "Hasta mañana a las 08:00, fin del horario"), extraTimeOptions(p, now).last())

        val options = extraTimeOptions(juan, now.withSecond(15).withNano(500_000_000))
        assertEquals(at(4, 22, 50, 15), options.first().until)
        assertTrue(options.all { it.until.nano == 0 })
    }

    @Test
    fun `opciones de tiempo extra - minutos desde ahora o null para hasta el fin del horario`() {
        val options = extraTimeOptions(juan, now)
        assertEquals(listOf(30L, 60L, 120L, null), options.map { it.minutes })
        // Las cortas cuentan desde ahora; al aplicar se recalculan con la hora del router de ese momento.
        for (o in options.filter { it.minutes != null }) assertEquals(now.plusMinutes(o.minutes!!), o.until)
        assertNull(options.single { it.label.endsWith("fin del horario") }.minutes)
        // Por defecto (p. ej. una opción construida a mano), hasta el fin del horario.
        assertNull(ExtraOption(at(5, 7), "Hasta las 07:00, fin del horario").minutes)
    }

    @Test
    fun `opciones de tiempo extra - un horario que no acaba no ofrece fin del horario`() {
        val always = Schedule("a", DayOfWeek.entries.toSet(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)
        val options = extraTimeOptions(juan.copy(schedules = listOf(always)), now)
        assertTrue(options.toString(), options.none { it.label.endsWith("fin del horario") })
    }

    @Test
    fun `filtro con busqueda segura y YouTube restringido`() {
        assertEquals("Filtro: búsqueda segura", formatFilter(ContentFilter(safeSearch = true)))
        assertEquals("el nivel ya la incluye", "Filtro: contenido para adultos", formatFilter(ContentFilter(FilterLevel.Adult, safeSearch = true)))
        assertEquals("Filtro: YouTube restringido", formatFilter(ContentFilter(youtube = YoutubeMode.Moderate)))
        assertEquals("YouTube bloqueado del todo", "Filtro: YouTube", formatFilter(ContentFilter(services = setOf("youtube"), youtube = YoutubeMode.Strict)))
        assertEquals(
            "Filtro: búsqueda segura · YouTube restringido · TikTok · 2 sitios",
            formatFilter(ContentFilter(services = setOf("tiktok"), domains = listOf("a.com", "b.com"), safeSearch = true, youtube = YoutubeMode.Strict)),
        )
        assertEquals("Filtro: estricto · YouTube restringido", formatFilter(ContentFilter(FilterLevel.Strict, youtube = YoutubeMode.Strict)))
        assertNull(formatFilter(ContentFilter()))
    }

    @Test
    fun `busqueda segura y YouTube efectivos segun el nivel`() {
        assertTrue("solo la búsqueda segura ya activa el filtro", ContentFilter(safeSearch = true).active)
        assertTrue(ContentFilter(youtube = YoutubeMode.Moderate).active)
        assertTrue(ContentFilter(FilterLevel.Adult).effectiveSafeSearch)
        assertFalse(ContentFilter().effectiveSafeSearch)
        assertEquals("CleanBrowsing ya lo restringe", YoutubeMode.Moderate, ContentFilter(FilterLevel.Strict).effectiveYoutube)
        assertEquals(YoutubeMode.Strict, ContentFilter(FilterLevel.Strict, youtube = YoutubeMode.Strict).effectiveYoutube)
        assertEquals(YoutubeMode.Off, ContentFilter(FilterLevel.Adult).effectiveYoutube)
        assertEquals("bloqueado del todo", YoutubeMode.Off, ContentFilter(services = setOf("youtube"), youtube = YoutubeMode.Strict).effectiveYoutube)
    }
}
