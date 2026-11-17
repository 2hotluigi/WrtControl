package dev.angel.wrtcontrol.ui.home

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.PreviewLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué se le enseña al usuario antes de guardar un aparato, y cuándo hace falta enseñarlo. */
class DevicePreviewTest {

    private val mac = "9C:29:76:00:00:01"
    private val tablet = Device(mac, "tablet", listOf("192.168.1.42"), emptyList(), LeaseState.Expires(3600), label = "Tableta")
    private val ana = Profile("a1", "Ana", emptyList())
    private val beto = Profile("b2", "Beto", listOf(mac))
    private val name = raw("Tableta")

    @Test
    fun `cambiar solo el nombre no trae avisos, asi que se guarda sin preguntar`() {
        val preview = devicePreview(name, tablet, DeviceChange("Tableta de Ana", null), listOf(ana))

        assertEquals(listOf(PreviewLine(txt(R.string.devices_edit_name_label), raw("Tableta"), raw("Tableta de Ana"))), preview.lines)
        assertTrue(preview.notes.isEmpty())
    }

    @Test
    fun `sin cambios no hay nada que ensenar`() {
        val preview = devicePreview(name, tablet, DeviceChange("Tableta", null), listOf(ana))

        assertTrue(preview.lines.isEmpty())
        assertTrue(preview.notes.isEmpty())
    }

    @Test
    fun `fijar la direccion avisa de cual queda reservada, y soltarla de que puede cambiar`() {
        val fixing = devicePreview(name, tablet, DeviceChange("Tableta", "192.168.1.42"), emptyList())
        assertEquals(listOf(txt(R.string.devices_preview_fixed_on, "192.168.1.42")), fixing.notes)
        assertEquals(txt(R.string.devices_preview_fixed_none), fixing.lines.single().before)

        val fixed = tablet.copy(fixedIp = "192.168.1.42")
        val freeing = devicePreview(name, fixed, DeviceChange("Tableta", null), emptyList())
        assertEquals(listOf(txt(R.string.devices_preview_fixed_off)), freeing.notes)
        assertEquals(raw("192.168.1.42"), freeing.lines.single().before)
    }

    @Test
    fun `pasar un aparato a una persona avisa de que le aplican sus reglas`() {
        val preview = devicePreview(name, tablet, DeviceChange("Tableta", null, owner = "a1"), listOf(ana, beto))

        assertEquals(listOf(PreviewLine(txt(R.string.devices_preview_person), raw("Beto"), raw("Ana"))), preview.lines)
        assertEquals(listOf(txt(R.string.devices_preview_person_on, "Ana")), preview.notes)
    }

    @Test
    fun `quitarselo a su persona avisa de que dejan de aplicarle`() {
        val preview = devicePreview(name, tablet, DeviceChange("Tableta", null, owner = ""), listOf(ana, beto))

        assertEquals(txt(R.string.devices_edit_person_none), preview.lines.single().after)
        assertEquals(listOf(txt(R.string.devices_preview_person_off, "Beto")), preview.notes)
    }

    @Test
    fun `dejarlo con la misma persona, o sin tocar de quien es, no cuenta como cambio`() {
        assertTrue(devicePreview(name, tablet, DeviceChange("Tableta", null, owner = "b2"), listOf(ana, beto)).lines.isEmpty())
        assertTrue(devicePreview(name, tablet, DeviceChange("Tableta", null, owner = null), listOf(ana, beto)).lines.isEmpty())
    }

    @Test
    fun `bloquear siempre avisa`() {
        assertEquals(listOf(txt(R.string.devices_preview_block_note)), blockPreview(name).notes)
    }
}
