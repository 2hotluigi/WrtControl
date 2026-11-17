package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.es
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DeviceIdentityTest {

    private fun table(text: String) = VendorTable { ByteArrayInputStream(text.toByteArray()) }

    private val vendors = table("Acme\tA4BBCC 001122\nEspressif\t246F28\nMotorola Mobility LLC, a Lenovo\t9C2976\n")

    private fun device(mac: String, name: String? = null) = Device(mac, name, listOf("192.168.1.20"), emptyList(), LeaseState.None)

    @Test
    fun `MAC privada - el bit de administrada localmente`() {
        listOf("02:00:00:00:00:01", "A6:11:22:33:44:55", "da:a1:19:00:00:01", "FE:FF:FF:FF:FF:FF").forEach {
            assertTrue(it, isPrivateMac(it))
        }
        listOf("00:11:22:33:44:55", "A4:11:22:33:44:55", "F0:18:98:00:00:01", "24:6F:28:00:00:01", "").forEach {
            assertFalse(it, isPrivateMac(it))
        }
    }

    @Test
    fun `fabricante por prefijo, en cualquier formato`() {
        assertEquals("Acme", vendors.vendor("A4:BB:CC:00:00:01"))
        assertEquals("Acme", vendors.vendor("a4-bb-cc-00-00-01"))
        assertEquals("Acme", vendors.vendor("00:11:22:33:44:55"))
        assertNull("no está en la lista", vendors.vendor("00:99:99:00:00:01"))
        assertNull("demasiado corta", vendors.vendor("AA:BB"))
    }

    @Test
    fun `una MAC privada no tiene fabricante aunque su prefijo coincida con uno`() {
        val table = table("Acme\tA6BBCC\n")
        assertNull(table.vendor("A6:BB:CC:00:00:01"))
    }

    @Test
    fun `nombre - el que dice el aparato o, si no, lo que se sepa`() {
        val named = identify(device("A4:BB:CC:00:00:01", "tele-salon"), vendors)
        assertEquals("tele-salon", named.title.es)
        assertTrue(named.named)
        assertEquals("Acme", named.vendor)

        val byVendor = identify(device("A4:BB:CC:00:00:02"), vendors)
        assertEquals("Aparato Acme", byVendor.title.es)
        assertFalse(byVendor.named)

        val private = identify(device("A6:00:00:00:00:03"), vendors)
        assertEquals("Sin nombre", private.title.es)
        assertTrue(private.privateMac)
        assertNull(private.vendor)

        assertEquals("Sin nombre", identify(device("00:99:99:00:00:04"), vendors).title.es)
        assertEquals("sin la lista de fabricantes", "Sin nombre", identify(device("A4:BB:CC:00:00:02"), null).title.es)
    }

    @Test
    fun `clase de aparato - el nombre manda sobre el fabricante`() {
        assertEquals(DeviceKind.Phone, deviceKind("Galaxy-A54-de-Ana", "Samsung"))
        assertEquals(DeviceKind.Phone, deviceKind("iPhone-de-Mari", "Apple"))
        assertEquals(DeviceKind.Tv, deviceKind("Samsung-TV", "Samsung"))
        assertEquals(DeviceKind.Network, deviceKind("deco-XE75", "TP-Link Systems"))
        assertEquals(DeviceKind.Computer, deviceKind("raspberrypi", null))
        assertEquals(DeviceKind.Smart, deviceKind(null, "Espressif"))
        assertEquals(DeviceKind.Phone, deviceKind(null, "Motorola Mobility LLC, a Lenovo"))
        assertEquals(DeviceKind.Network, deviceKind(null, "TP-Link Systems"))
        // Apple y Samsung hacen de todo: sin nombre no se adivina.
        assertEquals(DeviceKind.Other, deviceKind(null, "Apple"))
        assertEquals(DeviceKind.Other, deviceKind("C211", null))
        assertEquals(DeviceKind.Other, deviceKind(null, null))
    }

    @Test
    fun `con saltos de linea de Windows no se pierde el ultimo prefijo de cada fabricante`() {
        assertEquals("Acme", table("Acme\tA4BBCC 001122\r\nOtro\t246F28\r\n").vendor("00:11:22:00:00:01"))
    }

    @Test
    fun `si la lista no se puede leer no hay fabricantes, pero tampoco se rompe nada`() {
        val broken = VendorTable { throw java.io.FileNotFoundException("oui.txt") }
        assertNull(broken.load().vendor("A4:BB:CC:00:00:01"))
        assertEquals("Sin nombre", identify(device("A4:BB:CC:00:00:02"), broken).title.es)
    }

    // ---------- la lista que va dentro de la app ----------

    private val asset = listOf("src/main/assets/oui.txt", "app/src/main/assets/oui.txt").map(::File).first { it.isFile }

    @Test
    fun `la lista de la app conoce a los fabricantes de siempre y pesa poco`() {
        val real = VendorTable { asset.inputStream() }
        assertEquals("Xerox", real.vendor("00:00:01:00:00:01"))
        assertEquals("Cisco Systems", real.vendor("00:00:0C:12:34:56"))
        assertEquals("Raspberry Pi Foundation", real.vendor("B8:27:EB:00:00:01"))
        assertEquals("Espressif", real.vendor("24:6F:28:00:00:01"))
        assertTrue("${asset.length()} bytes", asset.length() < 800_000)
    }

    @Test
    fun `la lista de la app no lleva coletillas legales ni lineas rotas`() {
        val lines = asset.readLines()
        assertTrue(lines.size > 15_000)
        lines.forEach { line ->
            val (name, prefixes) = line.split('\t').let { it[0] to it[1] }
            assertTrue(line, name.isNotBlank() && prefixes.split(' ').all { it.matches(Regex("[0-9A-F]{6}")) })
        }
        val names = lines.map { it.substringBefore('\t') }
        assertTrue("Apple" in names && "Samsung" in names && "Xiaomi" in names)
        // Solo se libran los nombres que sin la coletilla quedarían en dos letras ("HP Inc.").
        assertTrue(names.count { it.endsWith(" Inc.") || it.endsWith(", Ltd.") || it.endsWith(" GmbH") } < 10)
    }
}
