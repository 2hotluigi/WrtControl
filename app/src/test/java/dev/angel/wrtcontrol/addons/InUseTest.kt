package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.raw
import org.junit.Assert.assertEquals
import org.junit.Test

/** Qué funciones de los paquetes se ven en marcha y funcionando (ver InUse.kt). */
class InUseTest {

    private val empty = AddonsState(
        freeKiB = 50_000,
        internetDevice = "eth1",
        internetIpv4 = "192.0.2.10",
        devices = listOf("eth1", "br-lan"),
        wireguard = emptyList(),
        wireguardLoaded = true,
        sqm = emptyList(),
        upnp = null,
        ddnsConfig = emptyList(),
    )

    private fun tunnel(handshake: Long?, up: Boolean = true) = WireGuardInterface(
        name = VPN_INTERFACE,
        listenPort = 51820,
        addresses = listOf("10.77.0.1/24"),
        peers = listOf(WireGuardPeer("p1", "Teléfono", "clave", listOf("10.77.0.2/32"), handshake, received = 0, sent = 0)),
        up = up,
    )

    private val server = RemoteServer(51820, "10.77.0.1", 24, "clave-router", emptyList())

    private fun ddns(registered: String?, error: Boolean = false) = DdnsService(
        section = DDNS_SECTION,
        enabled = true,
        provider = null,
        serviceName = null,
        domain = "casa.example.org",
        ipv6 = false,
        registeredIp = registered,
        lastUpdate = null,
        running = true,
        lastError = if (error) raw("falló") else null,
    )

    @Test
    fun `sin nada configurado no hay nada en marcha`() {
        assertEquals(emptySet<Feature>(), empty.inUse())
    }

    @Test
    fun `en marcha y funcionando`() {
        val state = empty.copy(
            wireguard = listOf(tunnel(handshake = 1_795_000_000)),
            remote = server,
            sqm = listOf(SqmQueue("q", enabled = true, device = "eth1", downloadKbit = 285_000, uploadKbit = 285_000, qdisc = "cake", script = "piece_of_cake.qos")),
            upnp = UpnpState("config", enabled = true, secureMode = true, running = true, mappings = emptyList()),
            ddns = listOf(ddns(registered = "192.0.2.10")),
        )
        assertEquals(setOf(Feature.RemoteAccess, Feature.Sqm, Feature.Upnp, Feature.DynamicDns), state.inUse())
    }

    @Test
    fun `configurado pero sin funcionar no cuenta`() {
        val state = empty.copy(
            // Nadie se ha conectado nunca: no se sabe si se llega.
            wireguard = listOf(tunnel(handshake = null)),
            remote = server,
            // En otra salida que no es la de internet.
            sqm = listOf(SqmQueue("q", enabled = true, device = "br-lan", downloadKbit = 1, uploadKbit = 1, qdisc = "cake", script = "piece_of_cake.qos")),
            // Activado pero miniupnpd no está en marcha.
            upnp = UpnpState("config", enabled = true, secureMode = true, running = false, mappings = emptyList()),
            // Con un error desde la última actualización.
            ddns = listOf(ddns(registered = "192.0.2.10", error = true)),
        )
        assertEquals(emptySet<Feature>(), state.inUse())
    }
}
