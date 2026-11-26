package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NlbwmonTest {

    private fun json(text: String) = UbusJson.decodeFromString(NlbwJson.serializer(), text)

    /** Como lo da `nlbwmon-action download -g mac,ip` en la VM (25.12): el cliente de pruebas y el propio router. */
    private val vm = json(
        """{"columns":["mac","ip","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts"],"data":[""" +
            """["02:00:00:00:00:99","192.168.56.99",2,196326,145,6580,87],""" +
            """["08:00:27:04:18:53","10.0.3.15",14,4188,20,1598,20]]}""",
    )

    @Test
    fun `lo de cada aparato, sin el propio router`() {
        assertEquals(
            listOf(DeviceUsage("02:00:00:00:00:99", 196_326, 6_580)),
            parseDeviceUsage(vm, routerAddresses = setOf("10.0.3.15", "192.168.56.2")),
        )
    }

    @Test
    fun `un aparato con varias direcciones suma, de mas a menos y sin la MAC vacia`() {
        val many = json(
            """{"columns":["mac","ip","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts"],"data":[""" +
                """["aa:00:00:00:00:01","192.168.1.10",1,100,1,10,1],""" +
                """["aa:00:00:00:00:01","fd00::10",1,900,1,90,1],""" +
                """["aa:00:00:00:00:02","192.168.1.11",1,5000,1,500,1],""" +
                """["00:00:00:00:00:00","192.168.1.250",1,7000,1,0,0],""" +
                """["aa:00:00:00:00:03","192.168.1.12",1,0,0,0,0]]}""",
        )
        assertEquals(
            listOf(DeviceUsage("AA:00:00:00:00:02", 5_000, 500), DeviceUsage("AA:00:00:00:00:01", 1_000, 100)),
            parseDeviceUsage(many, emptySet()),
        )
    }

    @Test
    fun `columnas en otro orden o que faltan`() {
        val reordered = json("""{"columns":["rx_bytes","tx_bytes","mac"],"data":[[10,20,"aa:00:00:00:00:01"]]}""")
        assertEquals(listOf(DeviceUsage("AA:00:00:00:00:01", 10, 20)), parseDeviceUsage(reordered, emptySet()))
        assertEquals(emptyList<DeviceUsage>(), parseDeviceUsage(json("""{"columns":["ip"],"data":[["1.2.3.4"]]}"""), emptySet()))
        assertEquals(emptyList<DeviceUsage>(), parseDeviceUsage(NlbwJson(), emptySet()))
    }

    @Test
    fun `guarda en la flash cada hora solo con lo que pone la app`() {
        val stock = uciSection("cfg01", "nlbwmon", "database_directory" to "/var/lib/nlbwmon", "commit_interval" to "24h")
        assertFalse(nlbwKeepsData(stock))
        assertFalse(nlbwKeepsData(uciSection("cfg01", "nlbwmon", "database_directory" to NLBW_DIR, "commit_interval" to "24h")))
        assertTrue(nlbwKeepsData(uciSection("cfg01", "nlbwmon", "database_directory" to NLBW_DIR, "commit_interval" to "1h")))
    }

    @Test
    fun `cabe si hay sitio o no se sabe`() {
        assertTrue(DeviceRecorder.Missing(null).fits)
        assertTrue(DeviceRecorder.Missing(NLBW_SPACE_KIB).fits)
        assertFalse(DeviceRecorder.Missing(NLBW_SPACE_KIB - 1).fits)
    }

    /** La sección `lan` y la de la WAN (`master`) de /etc/config/dhcp con el IPv6 en relay, como en un router de casa. */
    private val relayDhcp = listOf(
        uciSection("lan", "dhcp", "interface" to "lan", "ra" to "relay", "dhcpv6" to "relay", "ndp" to "relay"),
        uciSection("wan6", "dhcp", "interface" to "wan6", "master" to "1", "ra" to "relay", "ndp" to "relay", "ignore" to "1"),
    )

    @Test
    fun `relay solo si la red de casa lo tiene, no la seccion de la WAN`() {
        assertTrue(ipv6Relay(relayDhcp))
        assertFalse(ipv6Relay(relayDhcp.takeLast(1)))
        assertFalse(ipv6Relay(listOf(uciSection("lan", "dhcp", "ra" to "server", "dhcpv6" to "server"))))
        assertFalse(ipv6Relay(emptyList()))
    }

    /** Lo que da ifstatus en relay: la wan6 con la /64 del proveedor y la ruta por defecto; la lan solo con la ULA. */
    private val relayInterfaces = listOf(
        NetInterface("wan", up = true, routes = listOf(Route("0.0.0.0", 0))),
        NetInterface("wan6", up = true, routes = listOf(Route("2001:DB8:77:1::", 64), Route("::", 0))),
        NetInterface("lan", up = true, routes = listOf(Route("fd00:1::", 60))),
        NetInterface("wg0", up = true, routes = listOf(Route("2001:db8:99::", 64))),
    )

    @Test
    fun `el prefijo de casa en relay es la ruta publica de la salida a internet`() {
        assertEquals(listOf("2001:db8:77:1::/64"), relayPrefixes(relayInterfaces, relay = true))
        assertEquals(emptyList<String>(), relayPrefixes(relayInterfaces, relay = false))
        // Sin IPv6 del proveedor (solo la ULA o la de enlace), nada que añadir.
        val noGlobal = listOf(NetInterface("wan6", up = true, routes = listOf(Route("fe80::", 64), Route("fd00:2::", 64), Route("::", 0))))
        assertEquals(emptyList<String>(), relayPrefixes(noGlobal, relay = true))
        // Caída, no cuenta.
        assertEquals(emptyList<String>(), relayPrefixes(relayInterfaces.map { it.copy(up = false) }, relay = true))
    }

    private val stockNetworks = listOf("192.168.0.0/16", "172.16.0.0/12", "10.0.0.0/8", "lan")

    @Test
    fun `se anade el prefijo y se conserva lo que no es de la app`() {
        val stock = uciSection("cfg01", "nlbwmon", "local_network" to stockNetworks)
        assertEquals(stockNetworks + "2001:db8:77:1::/64", nlbwNetworks(stock, listOf("2001:db8:77:1::/64")))
        assertEquals(stockNetworks, nlbwNetworks(stock, emptyList()))
        // Puesto a mano: no se repite.
        val manual = uciSection("cfg01", "nlbwmon", "local_network" to stockNetworks + "2001:db8:77:1::/64")
        assertEquals(stockNetworks + "2001:db8:77:1::/64", nlbwNetworks(manual, listOf("2001:db8:77:1::/64")))
    }

    @Test
    fun `si el proveedor cambia el prefijo, se quita el que puso la app`() {
        val old = uciSection(
            "cfg01", "nlbwmon",
            "local_network" to stockNetworks + "2001:db8:77:1::/64" + "2001:db8:aa::/48",
            NLBW_OWN_NETWORKS to listOf("2001:db8:77:1::/64"),
        )
        // El /48 lo puso otro: se queda.
        assertEquals(stockNetworks + "2001:db8:aa::/48" + "2001:db8:77:2::/64", nlbwNetworks(old, listOf("2001:db8:77:2::/64")))
        assertEquals(stockNetworks + "2001:db8:aa::/48", nlbwNetworks(old, emptyList()))
    }
}
