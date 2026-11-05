package dev.angel.wrtcontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun bytes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("986 MiB", formatBytes(1_033_887_744))
        assertEquals("—", formatBytes(-1))
    }

    @Test
    fun duration() {
        assertEquals("42 s", formatDuration(42))
        assertEquals("2 min", formatDuration(125))
        assertEquals("1 h 2 min", formatDuration(3_725))
        assertEquals("1 d 2 h 3 min", formatDuration(93_784))
    }

    @Test
    fun load() {
        assertEquals("0.12", formatLoad(8032))
        assertEquals("1.00", formatLoad(65_536))
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.25f, fraction(50, 200))
        assertEquals(0f, fraction(5, 0))
        assertEquals(1f, fraction(300, 200))
    }
}
