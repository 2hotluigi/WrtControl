package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesTest {

    // Datos reales de la VM de pruebas (192.168.56.2).
    private val interfaces = listOf(
        NetInterface("lan", up = true, proto = "static", ipv4 = listOf(IpAddress("192.168.56.2", 24))),
        NetInterface("wan", up = true, proto = "dhcp", ipv4 = listOf(IpAddress("10.0.3.15", 24))),
    )

    private val hints = mapOf(
        "08:00:27:04:18:53" to HostHint(ipaddrs = listOf("10.0.3.15")), // WAN del router
        "08:00:27:57:02:44" to HostHint(ipaddrs = listOf("192.168.56.2")), // LAN del router
        "0A:00:27:00:00:3D" to HostHint(ipaddrs = listOf("192.168.56.1"), ip6addrs = listOf("fe80::38d6:deb7:4190:a02b")),
        "52:54:00:12:35:00" to HostHint(ipaddrs = listOf("10.0.3.2")), // pasarela NAT (lado WAN)
        "aa:bb:cc:dd:ee:01" to HostHint(name = "tablet", ipaddrs = listOf("192.168.56.120")),
    )

    // Windows usa un DUID basado en la MAC de su tarjeta Ethernet para todas sus interfaces.
    private val windowsV6 = DhcpLeases.Lease6(
        macaddr = "E0:70:EA:B4:CE:F5",
        hostname = "I7P60",
        ip6addr = "fd27:71c3:f0a8::1be",
        ip6addrs = listOf("fd27:71c3:f0a8::1be/128"),
        expires = JsonPrimitive(5217),
    )

    @Test
    fun `solo clientes de la LAN, sin el router ni vecinos WAN`() {
        val devices = buildDeviceList(hints, DhcpLeases(), interfaces)
        assertEquals(listOf("0A:00:27:00:00:3D", "AA:BB:CC:DD:EE:01"), devices.map { it.mac })
        assertEquals(LeaseState.None, devices[0].lease)
        assertEquals("tablet", devices[1].name)
    }

    @Test
    fun `combina concesiones DHCP normalizando la MAC`() {
        val leases = DhcpLeases(
            v4 = listOf(
                DhcpLeases.Lease4("aa:bb:cc:dd:ee:01", "192.168.56.120", "tablet-dhcp", JsonPrimitive(3600)),
                DhcpLeases.Lease4("aa:bb:cc:dd:ee:02", "192.168.56.50", null, JsonPrimitive(false)),
            ),
            v6 = listOf(DhcpLeases.Lease6(macaddr = "AA:BB:CC:DD:EE:02", ip6addrs = listOf("fd00::50"))),
        )
        val devices = buildDeviceList(hints, leases, interfaces)

        assertEquals(listOf("0A:00:27:00:00:3D", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01"), devices.map { it.mac })
        val tablet = devices.single { it.mac == "AA:BB:CC:DD:EE:01" }
        assertEquals("tablet-dhcp", tablet.name)
        assertEquals(LeaseState.Expires(3600), tablet.lease)
        val fixed = devices.single { it.mac == "AA:BB:CC:DD:EE:02" }
        assertEquals(LeaseState.Permanent, fixed.lease)
        assertEquals(listOf("fd00::50"), fixed.ipv6)
    }

    @Test
    fun `DHCPv6 de Windows se asocia por nombre de host`() {
        val leases = DhcpLeases(
            v4 = listOf(DhcpLeases.Lease4("F4:B3:01:41:71:9D", "192.168.56.77", "I7P60", JsonPrimitive(3000))),
            v6 = listOf(windowsV6),
        )
        val device = buildDeviceList(emptyMap(), leases, interfaces).single()
        assertEquals("F4:B3:01:41:71:9D", device.mac)
        assertEquals(listOf("fd27:71c3:f0a8::1be"), device.ipv6)
    }

    @Test
    fun `DHCPv6 se asocia por direccion IPv6 conocida`() {
        val device = buildDeviceList(
            mapOf("0A:00:27:00:00:3D" to HostHint(ipaddrs = listOf("192.168.56.1"), ip6addrs = listOf("fd27:71c3:f0a8::1be"))),
            DhcpLeases(v6 = listOf(windowsV6)),
            interfaces,
        ).single()
        assertEquals("0A:00:27:00:00:3D", device.mac)
        assertEquals("I7P60", device.name)
    }

    @Test
    fun `DHCPv6 sin coincidencias crea su propio equipo al final, sin prefijo`() {
        val devices = buildDeviceList(hints, DhcpLeases(v6 = listOf(windowsV6)), interfaces)
        val v6only = devices.last()
        assertEquals("E0:70:EA:B4:CE:F5", v6only.mac)
        assertEquals(listOf("fd27:71c3:f0a8::1be"), v6only.ipv6)
        assertTrue(v6only.ipv4.isEmpty())
    }

    @Test
    fun `redes de casa - lan y las interfaces sin ruta por defecto que no son wan`() {
        val ifaces = listOf(
            NetInterface("lan", ipv4 = listOf(IpAddress("192.168.1.1", 24))),
            NetInterface("guest", ipv4 = listOf(IpAddress("192.168.2.1", 24))), // invitados
            NetInterface("iot", ipv4 = listOf(IpAddress("192.168.3.1", 24)), routes = listOf(Route("10.8.0.0", 24))), // ruta, pero no la de defecto
            NetInterface("wan", ipv4 = listOf(IpAddress("10.0.3.15", 24)), routes = listOf(Route("0.0.0.0", 0))),
            NetInterface("wanb", ipv4 = listOf(IpAddress("10.0.4.15", 24))), // segunda WAN, ahora sin ruta
            NetInterface("wwan", ipv4 = listOf(IpAddress("172.20.10.2", 28)), routes = listOf(Route("0.0.0.0", 0))), // Wi-Fi cliente
            NetInterface("vpn", ipv4 = listOf(IpAddress("10.9.0.2", 24)), routes = listOf(Route("::", 0))), // salida por IPv6
        )
        val hints = mapOf(
            "AA:BB:CC:DD:EE:01" to HostHint(ipaddrs = listOf("192.168.1.10")),
            "AA:BB:CC:DD:EE:02" to HostHint(name = "tele", ipaddrs = listOf("192.168.2.20")), // IP fija en invitados
            "AA:BB:CC:DD:EE:03" to HostHint(ipaddrs = listOf("192.168.3.30")),
            "AA:BB:CC:DD:EE:04" to HostHint(ipaddrs = listOf("10.0.3.2")), // pasarela del proveedor
            "AA:BB:CC:DD:EE:05" to HostHint(ipaddrs = listOf("10.0.4.2")),
            "AA:BB:CC:DD:EE:06" to HostHint(ipaddrs = listOf("172.20.10.1")), // el teléfono que da internet
            "AA:BB:CC:DD:EE:07" to HostHint(ipaddrs = listOf("10.9.0.1")),
            "AA:BB:CC:DD:EE:08" to HostHint(ipaddrs = listOf("192.168.2.1")), // el propio router
        )
        val devices = buildDeviceList(hints, DhcpLeases(), ifaces)
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"), devices.map { it.mac })
        assertEquals("tele", devices[1].name)
    }

    @Test
    fun `ruta por defecto en IPv4 o IPv6`() {
        assertTrue(NetInterface("wan", routes = listOf(Route("0.0.0.0", 0))).hasDefaultRoute)
        assertTrue(NetInterface("wan6", routes = listOf(Route("::", 0))).hasDefaultRoute)
        assertFalse(NetInterface("lan", routes = listOf(Route("192.168.1.0", 24))).hasDefaultRoute)
        assertFalse(NetInterface("lan").hasDefaultRoute)
        // Como llega de `network.interface dump`.
        val json = """{"interface":[{"interface":"wan","up":true,"ipv4-address":[{"address":"10.0.3.15","mask":24}],
            "route":[{"target":"0.0.0.0","mask":0,"nexthop":"10.0.3.2","source":"10.0.3.15/32"}]},
            {"interface":"lan","up":true,"route":[]}]}"""
        val dump = UbusJson.decodeFromString(InterfaceDump.serializer(), json)
        assertEquals(listOf(true, false), dump.interfaces.map { it.hasDefaultRoute })
    }

    @Test
    fun `lista vacia sin datos`() {
        assertTrue(buildDeviceList(emptyMap(), DhcpLeases(), interfaces).isEmpty())
    }

    @Test
    fun `decodifica la respuesta real de getDHCPLeases`() {
        val json = """{"dhcp_leases":[{"expires":false,"hostname":"tv","macaddr":"AA:00:00:00:00:01","ipaddr":"192.168.56.9"}],
            "dhcp6_leases":[{"expires":5217,"interface":"br-lan","hostname":"I7P60","macaddr":"E0:70:EA:B4:CE:F5",
            "duid":"000100013091b505e070eab4cef5","iaid":"3d0a0027","ip6addr":"fd27:71c3:f0a8::1be",
            "ip6addrs":["fd27:71c3:f0a8::1be/128"]}]}"""
        val leases = UbusJson.decodeFromString(DhcpLeases.serializer(), json)
        assertEquals(LeaseState.Permanent, leaseState(leases.v4.single().expires))
        assertEquals(LeaseState.Expires(5217), leaseState(leases.v6.single().expires))
    }

    @Test
    fun ipv4InSubnet() {
        assertTrue(ipv4InSubnet("192.168.56.200", IpAddress("192.168.56.2", 24)))
        assertFalse(ipv4InSubnet("192.168.57.1", IpAddress("192.168.56.2", 24)))
        assertTrue(ipv4InSubnet("10.0.3.15", IpAddress("10.0.0.1", 16)))
        assertFalse(ipv4InSubnet("no-es-ip", IpAddress("192.168.56.2", 24)))
        assertTrue(ipv4InSubnet("8.8.8.8", IpAddress("0.0.0.0", 0)))
    }
}
