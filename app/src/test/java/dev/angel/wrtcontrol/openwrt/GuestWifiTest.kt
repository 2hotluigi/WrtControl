package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.addons.homeZones
import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.parental.routerEnv
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ui.home.guestNameFor
import dev.angel.wrtcontrol.ui.home.guestPreview
import dev.angel.wrtcontrol.ui.home.newGuestKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El Wi-Fi de invitados: qué crea en el router, cómo queda aislado y cómo se enciende y se apaga. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuestWifiTest {

    private val edit = GuestEdit(on = true, ssid = "Casa invitados", key = "abcd2345ef")

    @Test
    fun `lo que crea - red propia, reparto, zona que solo sale a internet y una red por radio`() {
        val sections = guestSections(edit, listOf("radio0", "radio1"), "192.168.99.1", listOf("wan"))

        val iface = sections.getValue("network").single { it.type == "interface" }
        assertEquals("192.168.99.1", iface.text("ipaddr"))
        assertEquals(iface.text("device"), sections.getValue("network").single { it.type == "device" }.text("name"))
        assertEquals(GUEST_INTERFACE, sections.getValue("dhcp").single().text("interface"))

        val firewall = sections.getValue("firewall")
        val zone = firewall.single { it.type == "zone" }
        assertEquals(listOf("REJECT", "REJECT"), listOf(zone.text("input"), zone.text("forward")))
        assertEquals("solo reenvía hacia internet", listOf("wan"), firewall.filter { it.type == "forwarding" }.map { it.text("dest") })
        assertEquals("al router, solo DHCP y DNS", listOf("67", "53"), firewall.filter { it.type == "rule" && it.text("dest") == null }.map { it.text("dest_port") })
        val private = firewall.single { it.type == "rule" && it.text("dest") == "wan" }
        assertEquals("REJECT", private.text("target"))
        assertEquals(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"), private.items("dest_ip"))

        val wifi = sections.getValue("wireless")
        assertEquals(listOf("radio0", "radio1"), wifi.map { it.text("device") })
        assertTrue(wifi.all { it.text("isolate") == "1" && it.text("network") == GUEST_INTERFACE && it.text("encryption") == "sae-mixed" })
        assertTrue("todo lleva el prefijo de la app", sections.values.flatten().all { it.name.startsWith(GUEST_PREFIX) })
    }

    @Test
    fun `la red de invitados no choca con las del router`() {
        fun net(name: String, address: String) = NetInterface(name, up = true, ipv4 = listOf(IpAddress(address, 24)))
        assertEquals("192.168.99.1", freeGuestAddress(listOf(net("lan", "192.168.8.1"), net("wan", "192.168.1.2"))))
        assertEquals("192.168.98.1", freeGuestAddress(listOf(net("lan", "192.168.99.1"))))
        assertEquals("la que ya tiene no cuenta como ocupada", "192.168.99.1", freeGuestAddress(listOf(net(GUEST_INTERFACE, "192.168.99.1"))))
        val wide = NetInterface("wan", up = true, ipv4 = listOf(IpAddress("192.168.0.5", 16)))
        assertEquals("con toda 192.168 ocupada, otra", "10.99.99.1", freeGuestAddress(listOf(wide)))
    }

    @Test
    fun `los invitados no son red de casa para la cuarentena ni para el acceso remoto`() {
        val firewall = listOf(
            uciSection("z_lan", "zone", "name" to "lan", "network" to listOf("lan")),
            uciSection("z_wan", "zone", "name" to "wan", "network" to listOf("wan"), "masq" to "1"),
            uciSection("f_lan", "forwarding", "src" to "lan", "dest" to "wan"),
        ) + guestSections(edit, listOf("radio0"), "192.168.99.1", listOf("wan")).getValue("firewall")
        val env = routerEnv(firewall, emptyList())
        assertEquals(listOf("lan"), env.lanZones)
        assertEquals(listOf("lan"), env.homeZones)
        assertEquals(listOf("lan"), homeZones(firewall, listOf("wan")))
    }

    @Test
    fun `el QR escapa lo que confunde a la camara, y lo que se propone es corto y claro`() {
        assertEquals("WIFI:T:WPA;S:Casa invitados;P:abcd2345ef;;", wifiQrText("Casa invitados", "abcd2345ef"))
        assertEquals("""WIFI:T:WPA;S:Mi\;red\:1;P:a\\b\,c\"d;;""", wifiQrText("Mi;red:1", """a\b,c"d"""))
        val key = newGuestKey()
        assertEquals(10, key.length)
        assertTrue("sin letras ni cifras que se confundan", key.none { it in "01ilo" })
        assertNull(guestProblem(GuestEdit(true, "Invitados", key)))
        assertNotNull("siempre con contraseña", guestProblem(GuestEdit(true, "Invitados", "")))
        assertEquals("Casa invitados", guestNameFor("Casa", "Casa invitados", "Invitados"))
        assertEquals("no cabe en un nombre de red", "Invitados", guestNameFor("x".repeat(30), "x".repeat(30) + " invitados", "Invitados"))
        assertEquals("Invitados", guestNameFor("", " invitados", "Invitados"))
    }

    private val factory = listOf(
        uciSection("radio0", "wifi-device", "band" to "2g", "channel" to "1", "htmode" to "HE20"),
        uciSection("default_radio0", "wifi-iface", "device" to "radio0", "network" to "lan", "mode" to "ap", "ssid" to "Casa", "encryption" to "sae-mixed", "key" to "clave-de-casa"),
        uciSection("radio1", "wifi-device", "band" to "5g", "channel" to "36", "htmode" to "HE80", "disabled" to "1"),
    )

    private fun TestScope.apis(fake: FakeOpenWrt): Pair<WifiApi, GuestWifiApi> {
        fake.clock = { testScheduler.currentTime }
        factory.forEach { s -> fake.section(s.name, s.type, *s.options.map { (k, v) -> k to (v as UciValue.Text).value }.toTypedArray()) }
        val client = fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler))
        return WifiApi(client) to GuestWifiApi(client)
    }

    @Test
    fun `se crea en las radios encendidas, se lee de vuelta y al apagarlo se conserva`() = runTest {
        val fake = FakeOpenWrt()
        val (wifi, guest) = apis(fake)
        assertNull(guestWifi(wifi.state()))

        // Apagarlo sin haberlo creado no toca nada.
        guest.save(edit.copy(on = false), phoneOnIt = false)
        assertTrue(fake.calls.none { it == "uci.add" })

        guest.save(edit, phoneOnIt = false)
        assertEquals("192.168.99.1", fake.option(GUEST_INTERFACE, "ipaddr"))
        assertEquals("Casa invitados", fake.option("wrtcguest_radio0", "ssid"))
        assertNull("la radio apagada no se usa", fake.committed["wrtcguest_radio1"])
        val state = wifi.state()
        assertEquals(GuestWifi("Casa invitados", "abcd2345ef", on = true, clients = emptyList()), guestWifi(state))
        assertEquals("no es parte de tu Wi-Fi", listOf("default_radio0"), state.home.map { it.section })

        guest.save(edit.copy(on = false), phoneOnIt = false)
        assertEquals("1", fake.option("wrtcguest_radio0", "disabled"))
        assertEquals("lo demás sigue", "192.168.99.1", fake.option(GUEST_INTERFACE, "ipaddr"))
        assertEquals(GuestWifi("Casa invitados", "abcd2345ef", on = false, clients = emptyList()), guestWifi(wifi.state()))
    }

    @Test
    fun `con tu Wi-Fi emitiendo avisa del reinicio, y si la red no se levanta se deshace entero`() = runTest {
        val fake = FakeOpenWrt()
        val (wifi, guest) = apis(fake)
        val state = wifi.state()
        val current = GuestEdit(false, "Casa invitados", "abcd2345ef")
        assertEquals(listOf(txt(R.string.guest_preview_restart)), guestPreview(state, current, edit, emptySet()).notes)
        assertEquals(1, guestPreview(state, current, edit, emptySet()).lines.size)

        fake.newInterfacesFail = true
        val result = runCatching { guest.save(edit, phoneOnIt = false) }
        advanceUntilIdle()
        assertTrue(result.exceptionOrNull() is ChangeRevertedException)
        assertTrue("no queda nada a medias", fake.committed.keys.none { it.startsWith(GUEST_PREFIX) })
    }

    @Test
    fun `sin contrasena valida o con todas las radios apagadas no llega al router`() = runTest {
        val fake = FakeOpenWrt()
        val (_, guest) = apis(fake)
        assertTrue(runCatching { guest.save(edit.copy(key = "corta"), phoneOnIt = false) }.exceptionOrNull() is UserFacingException)
        fake.section("radio0", "wifi-device", "band" to "2g", "disabled" to "1")
        assertTrue(runCatching { guest.save(edit, phoneOnIt = false) }.exceptionOrNull() is UserFacingException)
        assertFalse(fake.committed.keys.any { it.startsWith(GUEST_PREFIX) })
    }
}
