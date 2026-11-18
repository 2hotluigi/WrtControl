package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La red de casa sale del cortafuegos, no de que se llame "lan" (ver HomeNetwork.kt). */
class HomeNetworkTest {

    private val defaults = uciSection("cfg01", "defaults", "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT")
    private val wanZone = uciSection("cfg03", "zone", "name" to "wan", "network" to "wan wan6", "input" to "REJECT", "masq" to "1")

    /** El cortafuegos de OpenWrt de serie. */
    private val stock = listOf(
        defaults,
        uciSection("cfg02", "zone", "name" to "lan", "network" to "lan", "input" to "ACCEPT"),
        wanZone,
        uciSection("cfg04", "forwarding", "src" to "lan", "dest" to "wan"),
    )

    /**
     * La red de casa se llama "casa", hay una red de invitados hecha a mano (no deja entrar al
     * router) y las zonas que crea la app (invitados y acceso remoto).
     */
    private val renamed = listOf(
        defaults,
        uciSection("cfg02", "zone", "name" to "casa", "network" to "casa", "input" to "ACCEPT"),
        wanZone,
        uciSection("cfg04", "forwarding", "src" to "casa", "dest" to "wan"),
        uciSection("cfg05", "zone", "name" to "visitas", "network" to "visitas", "input" to "REJECT"),
        uciSection("cfg06", "forwarding", "src" to "visitas", "dest" to "wan"),
        uciSection("wrtcguest_zone", "zone", "name" to "wrtcguest", "network" to "wrtcguest", "input" to "REJECT"),
        uciSection("wrtcguest_fwd", "forwarding", "src" to "wrtcguest", "dest" to "wan"),
        uciSection("wrtcvpn_zone", "zone", "name" to "wrtcvpn", "network" to "wrtcvpn", "input" to "ACCEPT"),
        uciSection("wrtcvpn_fwd", "forwarding", "src" to "wrtcvpn", "dest" to "wan"),
    )

    @Test
    fun `en el router de serie la red de casa es lan`() {
        val layout = parseHomeLayout(stock)
        assertEquals(listOf("lan"), layout.home)
        assertEquals("lan", layout.main)
        assertEquals(setOf("lan"), layout.inside)
    }

    @Test
    fun `con la LAN renombrada se encuentra por la zona del cortafuegos`() {
        val layout = parseHomeLayout(renamed)
        // Ni la de invitados hecha a mano (no deja entrar al router) ni las de la app.
        assertEquals(listOf("casa"), layout.home)
        // Los invitados sí son del lado de dentro: ahí también hay aparatos. El acceso remoto, no.
        assertEquals(setOf("casa", "visitas", "wrtcguest"), layout.inside)
    }

    @Test
    fun `con varias redes de casa va primero lan`() {
        val layout = parseHomeLayout(
            listOf(
                defaults,
                uciSection("cfg02", "zone", "name" to "iot", "network" to "iot", "input" to "ACCEPT"),
                uciSection("cfg03", "zone", "name" to "lan", "network" to "lan oficina", "input" to "ACCEPT"),
                wanZone,
                uciSection("cfg04", "forwarding", "src" to "iot", "dest" to "wan"),
                uciSection("cfg05", "forwarding", "src" to "lan", "dest" to "wan"),
            ),
        )
        assertEquals(listOf("lan", "iot", "oficina"), layout.home)
    }

    @Test
    fun `una zona sin input usa el de defaults`() {
        fun home(defaultInput: String) = parseHomeLayout(
            listOf(
                uciSection("cfg01", "defaults", "input" to defaultInput),
                uciSection("cfg02", "zone", "name" to "casa", "network" to "casa"),
                wanZone,
                uciSection("cfg04", "forwarding", "src" to "casa", "dest" to "wan"),
            ),
        ).home
        assertEquals(listOf("casa"), home("ACCEPT"))
        // Sin ninguna que valga, lo de serie.
        assertEquals(listOf("lan"), home("REJECT"))
    }

    @Test
    fun `sin salida a internet no es la red de casa`() {
        val layout = parseHomeLayout(
            listOf(
                defaults,
                uciSection("cfg02", "zone", "name" to "casa", "network" to "casa", "input" to "ACCEPT"),
                wanZone,
                uciSection("cfg04", "forwarding", "src" to "casa", "dest" to "wan", "enabled" to "0"),
            ),
        )
        assertEquals(listOf("lan"), layout.home)
        assertEquals(setOf("casa", "lan"), layout.inside)
    }

    @Test
    fun `sin cortafuegos que leer, lo de serie`() {
        assertEquals(HomeLayout.DEFAULT, parseHomeLayout(emptyList()))
        assertNull(HomeLayout.DEFAULT.inside)
    }

    // Las redes del router con la LAN renombrada, el acceso remoto y una salida a internet sin ruta
    // por defecto propia que no se llama "wan…" (la regla de antes la tomaba por red de casa).
    private val interfaces = listOf(
        NetInterface("casa", up = true, proto = "static", ipv4 = listOf(IpAddress("192.168.8.1", 24))),
        NetInterface("wan", up = true, proto = "dhcp", ipv4 = listOf(IpAddress("10.0.3.15", 24))),
        NetInterface("wrtcvpn", up = true, proto = "wireguard", ipv4 = listOf(IpAddress("10.77.0.1", 24))),
        NetInterface("movil", up = true, proto = "dhcp", ipv4 = listOf(IpAddress("192.168.42.10", 24))),
    )

    @Test
    fun `los aparatos de la LAN renombrada son de casa y los de otras salidas no`() {
        val layout = parseHomeLayout(renamed)
        assertTrue(usableLanAddress("192.168.8.50", interfaces, layout))
        assertFalse(usableLanAddress("10.77.0.5", interfaces, layout))
        assertFalse(usableLanAddress("192.168.42.20", interfaces, layout))

        val hints = mapOf(
            "AA:BB:CC:DD:EE:01" to HostHint(name = "tablet", ipaddrs = listOf("192.168.8.50")),
            "AA:BB:CC:DD:EE:02" to HostHint(name = "telefono-remoto", ipaddrs = listOf("10.77.0.5")),
            "AA:BB:CC:DD:EE:03" to HostHint(name = "modem-movil", ipaddrs = listOf("192.168.42.20")),
        )
        val names = buildDeviceList(hints, DhcpLeases(), interfaces, layout = layout).map { it.name }
        assertEquals(listOf("tablet"), names)
    }

    @Test
    fun `tu Wi-Fi es el que va a la red de casa aunque no se llame lan`() {
        val wireless = listOf(
            uciSection("radio0", "wifi-device", "band" to "2g", "channel" to "1"),
            uciSection("wifinet0", "wifi-iface", "device" to "radio0", "network" to "casa", "mode" to "ap", "ssid" to "Casa", "encryption" to "psk2", "key" to "clave-segura"),
            uciSection("radio1", "wifi-device", "band" to "5g", "channel" to "36"),
        )
        val noStatus = emptyMap<String, JsonObject>()
        val home = parseHomeLayout(renamed).home

        assertEquals(listOf("wifinet0"), parseWifi(wireless, noStatus, home = home).home.map { it.section })
        // Mirando "lan" no habría "Tu Wi-Fi".
        assertTrue(parseWifi(wireless, noStatus).home.isEmpty())

        // La radio que no tenía red de casa la estrena en "casa", no en "lan".
        val plan = wifiPlan(wireless, WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2, setOf("radio0", "radio1")), home)
        val added = plan.filterIsInstance<WifiChange.Add>().single().section
        assertEquals("radio1", added.text("device"))
        assertEquals("casa", added.text("network"))
        // La de radio0 ya estaba como se pide: no se toca.
        assertTrue(plan.none { it is WifiChange.Set && it.section == "wifinet0" })
    }
}
