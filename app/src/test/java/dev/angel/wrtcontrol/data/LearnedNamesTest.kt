package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A quién se le pregunta el nombre, cuándo se le vuelve a preguntar y a quién se le pone. */
class LearnedNamesTest {

    private var time = 0L
    private val names = LearnedNames { time }

    private fun device(mac: String, ip: String?, name: String? = null, presence: Presence = Presence.Online) =
        Device(mac, name, listOfNotNull(ip), emptyList(), LeaseState.None, presence = presence)

    private val box = device("5C:8A:AE:4B:88:E8", "192.168.8.235")

    @Test
    fun `solo se pregunta a los que no tienen nombre, estan en la red y tienen direccion`() {
        val devices = listOf(
            box,
            device("AA:00:00:00:00:01", "192.168.8.10", name = "tele"),
            device("AA:00:00:00:00:02", "192.168.8.11", presence = Presence.Offline),
            device("AA:00:00:00:00:03", null),
        )
        assertEquals(mapOf(box.mac to "192.168.8.235"), names.pending(devices))
    }

    @Test
    fun `al que contesto no se le vuelve a preguntar, y sale con su nombre`() {
        assertTrue(names.learned(box.mac, "192.168.8.235", "OTT Xview+ AV1"))

        assertTrue(names.pending(listOf(box)).isEmpty())
        assertEquals("OTT Xview+ AV1", names.named(listOf(box)).single().name)
        assertFalse("el mismo nombre otra vez no es una novedad", names.learned(box.mac, "192.168.8.235", "OTT Xview+ AV1"))
    }

    @Test
    fun `al que no contesto se le vuelve a preguntar pasado un rato`() {
        assertFalse(names.learned(box.mac, "192.168.8.235", null))
        assertTrue(names.pending(listOf(box)).isEmpty())

        time += 11 * 60 * 1000
        assertEquals(setOf(box.mac), names.pending(listOf(box)).keys)
    }

    @Test
    fun `el nombre no pisa el que ya tenga ni sigue a otra direccion`() {
        names.learned(box.mac, "192.168.8.235", "OTT Xview+ AV1")

        assertEquals("Tele", names.named(listOf(box.copy(name = "Tele"))).single().name)
        // Con otra dirección puede ser otro aparato con la misma MAC privada... o el mismo: se pregunta otra vez.
        val moved = device(box.mac, "192.168.8.99")
        assertEquals(null, names.named(listOf(moved)).single().name)
        assertEquals(mapOf(box.mac to "192.168.8.99"), names.pending(listOf(moved)))
    }

    @Test
    fun `no se pregunta a media casa a la vez, y al cambiar de router se olvida todo`() {
        val many = (1..20).map { device("AA:00:00:00:01:%02d".format(it), "192.168.8.$it") }
        assertEquals(LearnedNames.MAX_PER_ROUND, names.pending(many).size)

        names.learned(box.mac, "192.168.8.235", "OTT Xview+ AV1")
        names.clear()
        assertEquals(null, names.named(listOf(box)).single().name)
    }
}
