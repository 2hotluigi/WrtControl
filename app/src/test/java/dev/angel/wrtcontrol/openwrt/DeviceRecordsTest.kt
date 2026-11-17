package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.ok
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceRecordsTest {

    private val tv = "AA:BB:CC:DD:EE:01"
    private val printer = "AA:BB:CC:DD:EE:02"
    private val interfaces = listOf(
        NetInterface("lan", up = true, proto = "static", ipv4 = listOf(IpAddress("192.168.1.1", 24))),
        NetInterface("wan", up = true, proto = "dhcp", ipv4 = listOf(IpAddress("10.0.3.15", 24))),
    )

    private fun TestScope.api(fake: FakeOpenWrt): DevicesApi {
        fake.clock = { testScheduler.currentTime }
        return DevicesApi(fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    private fun FakeOpenWrt.base() = apply {
        section("cfg01", "dnsmasq", "domain" to "lan")
        section("lan", "dhcp", "interface" to "lan")
    }

    private suspend inline fun <reified E : Throwable> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("Se esperaba ${E::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun `un nombre con espacios y acentos se guarda en una opcion que dnsmasq no mira`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).save(DeviceEdit(tv, "  Tele de  la sala\n", null), interfaces)

        val section = "host_aabbccddee01"
        assertEquals(tv, fake.option(section, "mac"))
        assertEquals("Tele de la sala", fake.option(section, LABEL_OPTION))
        // Sin `name` ni `ip`, dnsmasq no hace nada con la sección: un nombre así no es un nombre de DNS.
        assertNull(fake.option(section, "name"))
        assertNull(fake.option(section, "ip"))
        assertNull("el cambio quedó confirmado", fake.pendingApply)
        assertFalse("no usa el prefijo del control parental", section.startsWith("wrtc_"))
    }

    @Test
    fun `fijar la direccion y quitarla despues deja el nombre`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)

        api.save(DeviceEdit(tv, "Tele", "192.168.1.50"), interfaces)
        assertEquals("192.168.1.50", fake.option("host_aabbccddee01", "ip"))

        api.save(DeviceEdit(tv, "Tele", null), interfaces)
        assertNull(fake.option("host_aabbccddee01", "ip"))
        assertEquals("Tele", fake.option("host_aabbccddee01", LABEL_OPTION))
    }

    @Test
    fun `sin nombre ni direccion la seccion de la app desaparece`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.save(DeviceEdit(tv, "Tele", null), interfaces)

        api.save(DeviceEdit(tv, null, null), interfaces)

        assertFalse("host_aabbccddee01" in fake.committed)
    }

    @Test
    fun `una reserva hecha en LuCI se aprovecha y no se borra`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.section("cfg0a", "host", "name" to "impresora", "mac" to listOf(printer.lowercase()), "ip" to "192.168.1.60")
        val api = api(fake)
        val before = parseDeviceRecords(listOf(uciSection("cfg0a", "host", "name" to "impresora", "mac" to listOf(printer), "ip" to "192.168.1.60")))
        assertEquals(listOf(DeviceRecord("cfg0a", printer, "impresora", "192.168.1.60")), before)

        api.save(DeviceEdit(printer, "Impresora del estudio", "192.168.1.60"), interfaces)
        assertEquals("Impresora del estudio", fake.option("cfg0a", LABEL_OPTION))
        assertEquals("el nombre de DNS de LuCI sigue", "impresora", fake.option("cfg0a", "name"))

        // Quitarlo todo desde la app no borra lo que escribió LuCI.
        api.save(DeviceEdit(printer, null, null), interfaces)
        assertTrue("cfg0a" in fake.committed)
        assertEquals("impresora", fake.option("cfg0a", "name"))
        assertNull(fake.option("cfg0a", "ip"))
    }

    @Test
    fun `sin cambios no toca el router`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.save(DeviceEdit(tv, "Tele", null), interfaces)
        val applies = fake.calls.count { it == "uci.apply" }

        api.save(DeviceEdit(tv, "Tele", null), interfaces)
        api.save(DeviceEdit(printer, null, null), interfaces)

        // Solo el sondeo de cada intento (un apply sin nada preparado), ningún cambio.
        assertEquals(applies + 2, fake.calls.count { it == "uci.apply" })
        assertEquals(1, fake.calls.count { it == "uci.confirm" })
    }

    @Test
    fun `una direccion que no vale se rechaza antes de tocar nada`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.save(DeviceEdit(printer, "Impresora", "192.168.1.60"), interfaces)

        val outside = expect<UserFacingException> { api.save(DeviceEdit(tv, null, "10.0.3.20"), interfaces) }
        assertEquals("Esa dirección no es de la red de casa, o es la del propio router.", outside.text.es)
        val router = expect<UserFacingException> { api.save(DeviceEdit(tv, null, "192.168.1.1"), interfaces) }
        assertEquals(outside.text.es, router.text.es)
        val taken = expect<UserFacingException> { api.save(DeviceEdit(tv, null, "192.168.1.60"), interfaces) }
        assertEquals("Esa dirección ya está reservada para «Impresora».", taken.text.es)
        val bad = expect<UserFacingException> { api.save(DeviceEdit(tv, null, "192.168.1"), interfaces) }
        assertEquals("Esa dirección no es una IPv4 válida.", bad.text.es)

        assertFalse("host_aabbccddee01" in fake.committed)
    }

    @Test
    fun `si el DNS deja de contestar el router lo deshace y la app lo dice`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        // dnsmasq no vuelve a arrancar con el cambio: nslookup no encuentra servidor.
        fake.intercept = { call ->
            if (call.name == "file.exec") ok("""{"code":1,"stdout":";; connection timed out; no servers could be reached\n"}""") else null
        }

        val error = expect<ChangeRevertedException> { api.save(DeviceEdit(tv, "Tele", null), interfaces) }

        assertEquals("El router dejó de resolver nombres con el cambio, así que se dejó como estaba.", error.text.es)
        assertFalse("host_aabbccddee01" in fake.committed)
        assertFalse("nunca se confirmó", fake.calls.contains("uci.confirm"))
    }

    @Test
    fun `las secciones con varias MAC o sin ella no son de un aparato`() {
        val records = parseDeviceRecords(
            listOf(
                uciSection("a", "host", "mac" to listOf(tv, printer), "ip" to "192.168.1.70"),
                uciSection("b", "host", "name" to "sin-mac", "ip" to "192.168.1.71"),
                uciSection("c", "host", "mac" to "aa-bb-cc-dd-ee-03", LABEL_OPTION to "Consola", "ip" to "ignore"),
                uciSection("d", "dhcp", "interface" to "lan"),
            ),
        )
        assertEquals(listOf(DeviceRecord("c", "AA:BB:CC:DD:EE:03", "Consola", null)), records)
    }
}
