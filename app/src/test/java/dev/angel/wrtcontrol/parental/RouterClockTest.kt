package dev.angel.wrtcontrol.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RouterClockTest {

    private val now = Instant.parse("2026-11-05T06:00:00Z")

    @Test
    fun `cadena TZ fija para zonas sin horario de verano`() {
        assertEquals("<-06>6", fixedPosixTz(ZoneId.of("America/Mexico_City"), now))
        assertEquals("<+0530>-5:30", fixedPosixTz(ZoneId.of("Asia/Kolkata"), now))
        assertEquals("GMT0", fixedPosixTz(ZoneId.of("UTC"), now))
        assertNull("con horario de verano se usa la de LuCI", fixedPosixTz(ZoneId.of("Europe/Madrid"), now))
    }

    @Test
    fun `desfase redondeado y hora local del router`() {
        assertEquals(-21_600, roundOffset(-21_600 + 37)) // relojes desfasados unos segundos
        assertEquals(19_800, roundOffset(19_790))
        // localtime cuenta los segundos del reloj local como si fueran UTC.
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0), routerDateTime(0))
        assertEquals(LocalDateTime.of(1970, 1, 2, 1, 1, 1), routerDateTime(86_400 + 3_661))
    }

    @Test
    fun `aviso solo si la hora no coincide`() {
        val mexico = ZoneId.of("America/Mexico_City")
        assertTrue(zoneCheck(mexico, now, "UTC", 0).mismatch)
        assertFalse(zoneCheck(mexico, now, "America/Monterrey", -21_600).mismatch) // otra zona, misma hora
        assertFalse(zoneCheck(mexico, now, null, null).mismatch)
        assertTrue(sameZone("America/Mexico City", "America/Mexico_City"))
        assertEquals("UTC−6", formatUtcOffset(-21_600))
        assertEquals("UTC+5:30", formatUtcOffset(19_800))
        assertEquals("UTC", formatUtcOffset(0))
    }
}
