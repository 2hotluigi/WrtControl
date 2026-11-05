package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.Route
import dev.angel.wrtcontrol.openwrt.uciSection
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.parental.ParentalApi
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.routerEnv
import dev.angel.wrtcontrol.ubus.Credentials
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class RemoteAccessTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun b64(s: String) = Base64.getEncoder().encodeToString(hex(s))

    @Test
    fun `X25519 da lo que dice el RFC 7748`() {
        // RFC 7748, 6.1: claves de Alice y de Bob y su secreto compartido.
        val alice = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        val bob = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        assertEquals(b64("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), X25519.publicKey(b64(alice)))
        assertEquals(b64("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), X25519.publicKey(b64(bob)))
        val shared = X25519.scalarMult(hex(alice), hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"))
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", shared.joinToString("") { "%02x".format(it) })
        // RFC 7748, 5.2: un vector con un punto que no es el 9.
        val k = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
        val u = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", X25519.scalarMult(hex(k), hex(u)).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `las claves nuevas salen como las de wg genkey`() {
        val key = Base64.getDecoder().decode(X25519.generatePrivateKey())
        assertEquals(32, key.size)
        assertEquals(0, key[0].toInt() and 7)
        assertEquals(64, key[31].toInt() and 0xc0)
        assertTrue(X25519.isKey(X25519.generatePresharedKey()))
        assertFalse(X25519.isKey("no es una clave"))
        assertFalse(X25519.isKey(Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    private fun iface(name: String, v4: String? = null, mask: Int = 24, v6: List<String> = emptyList(), wan: Boolean = false) = NetInterface(
        name = name, up = true, proto = "static", device = name, l3Device = name,
        ipv4 = listOfNotNull(v4?.let { IpAddress(it, mask) }),
        ipv6 = v6.map { IpAddress(it, 64) },
        routes = if (wan) listOf(Route("0.0.0.0", 0)) else emptyList(),
    )

    @Test
    fun `la red del tunel no choca con las del router`() {
        assertEquals("10.77.0.0", freeVpnSubnet(listOf(iface("lan", "192.168.1.1"))))
        // Una LAN 10.0.0.0/8 ocupa todas las 10.x: se pasa a 172.31.
        assertEquals("172.31.77.0", freeVpnSubnet(listOf(iface("lan", "10.0.0.1", mask = 8))))
        assertEquals("10.78.0.0", freeVpnSubnet(listOf(iface("lan", "192.168.1.1"), iface("guest", "10.77.0.1"))))
        // La propia interfaz del túnel no cuenta.
        assertEquals("10.77.0.0", freeVpnSubnet(listOf(iface(VPN_INTERFACE, "10.77.0.1"))))
    }

    @Test
    fun `cada telefono tiene su direccion y el router la 1`() {
        assertEquals("10.77.0.2", nextPeerAddress("10.77.0.1", 24, emptySet()))
        assertEquals("10.77.0.4", nextPeerAddress("10.77.0.1", 24, setOf("10.77.0.2", "10.77.0.3")))
        try {
            nextPeerAddress("10.77.0.1", 30, setOf("10.77.0.2"))
            fail("una /30 solo tiene sitio para el router y un teléfono")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `direcciones publicas y zonas por las que llega internet`() {
        val wan = iface("wan", "100.72.10.5", wan = true)
        val wan6 = iface("wan6", v6 = listOf("2001:db8:5::7", "fd00::1"), wan = true)
        val lan = iface("lan", "192.168.1.1", v6 = listOf("2001:db8:6::1", "fe80::1"))
        assertEquals("la IPv4 de CGNAT no sirve", listOf("2001:db8:5::7", "2001:db8:6::1"), publicAddresses(listOf(lan, wan, wan6)))
        assertEquals(listOf("2001:db8:5::7", "203.0.113.9"), publicAddresses(listOf(iface("wan", "203.0.113.9", wan = true), wan6)))
        assertFalse(isGlobalIpv6("fe80::1"))
        assertFalse(isGlobalIpv6("fd12::1"))
        assertFalse(isGlobalIpv6("::1"))
        assertTrue(isGlobalIpv6("2a02:8070::1"))

        val zones = listOf(
            uciSection("z1", "zone", "name" to "lan", "network" to listOf("lan")),
            uciSection("z2", "zone", "name" to "wan", "network" to listOf("wan", "wan6"), "masq" to "1"),
        )
        assertEquals(listOf("wan"), internetZones(zones, listOf(lan, wan, wan6)))
        assertEquals("sin interfaz con ruta, la zona con NAT", listOf("wan"), internetZones(zones, listOf(lan)))
    }

    @Test
    fun `la zona del acceso remoto no es una red de casa para el control parental`() {
        val firewall = listOf(
            uciSection("z1", "zone", "name" to "lan", "network" to listOf("lan")),
            uciSection("z2", "zone", "name" to "wan", "masq" to "1"),
            uciSection("wrtcvpn_zone", "zone", "name" to VPN_ZONE, "network" to listOf(VPN_INTERFACE), "input" to "REJECT"),
            uciSection("f1", "forwarding", "src" to "lan", "dest" to "wan"),
        )
        assertEquals(listOf("lan"), routerEnv(firewall, emptyList()).lanZones)
    }

    // ---------- contra el router simulado ----------

    private fun TestScope.setup(): Pair<FakeAddons, AddonsApi> {
        val f = FakeAddons()
        f.install("luci-proto-wireguard")
        f.protos += "wireguard"
        f.fake.section("z1", "zone", "name" to "lan", "network" to listOf("lan"), "input" to "ACCEPT")
        f.fake.section("z2", "zone", "name" to "wan", "network" to listOf("wan", "wan6"), "masq" to "1")
        f.fake.section("f1", "forwarding", "src" to "lan", "dest" to "wan")
        f.fake.section("main", "uhttpd", "listen_http" to listOf("0.0.0.0:80", "[::]:80"), config = "uhttpd")
        f.fake.clock = { testScheduler.currentTime }
        return f to AddonsApi(f.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    private val phone = X25519.generatePrivateKey()
    private val psk = X25519.generatePresharedKey()

    @Test
    fun `activar el acceso remoto crea el servidor con el par del telefono y una zona cerrada`() = runTest {
        val (f, api) = setup()

        val result = api.enableRemoteAccess("abcd1234", "Pixel 8", X25519.publicKey(phone), psk)

        assertEquals(DEFAULT_VPN_PORT, result.port)
        assertEquals("10.77.0.1", result.routerTunnelAddress)
        assertEquals("10.77.0.2", result.phoneAddress)
        assertEquals("wireguard", f.fake.option(VPN_INTERFACE, "proto"))
        assertEquals("la clave del router la creó el router", result.routerPublicKey, X25519.publicKey(f.fake.option(VPN_INTERFACE, "private_key")!!))
        assertEquals(X25519.publicKey(phone), f.fake.option("wrtcvpn_p_abcd1234", "public_key"))
        assertEquals(psk, f.fake.option("wrtcvpn_p_abcd1234", "preshared_key"))
        assertEquals("10.77.0.2/32", f.fake.option("wrtcvpn_p_abcd1234", "allowed_ips"))
        assertEquals("WrtControl · Pixel 8", f.fake.option("wrtcvpn_p_abcd1234", "description"))
        // La zona no reenvía a ningún sitio y desde ella solo se llega a la web del router.
        assertEquals("REJECT", f.fake.option("wrtcvpn_zone", "input"))
        assertEquals("REJECT", f.fake.option("wrtcvpn_zone", "forward"))
        assertEquals("80", f.fake.option("wrtcvpn_web", "dest_port"))
        assertEquals("wan", f.fake.option("wrtcvpn_in", "src"))
        assertEquals("51820", f.fake.option("wrtcvpn_in", "dest_port"))
        assertEquals("udp", f.fake.option("wrtcvpn_in", "proto"))
        assertNull(f.fake.pendingApply)

        val state = api.state()
        assertEquals(listOf("Pixel 8"), state.remote!!.peers.map { it.name })
        assertEquals(result.routerPublicKey, state.remote!!.publicKey)
    }

    @Test
    fun `un segundo telefono no cambia la clave del router ni la direccion del primero`() = runTest {
        val (f, api) = setup()
        val first = api.enableRemoteAccess("abcd1234", "Pixel 8", X25519.publicKey(phone), psk)
        val other = X25519.generatePrivateKey()

        val second = api.enableRemoteAccess("zzzz9999", "Galaxy", X25519.publicKey(other), X25519.generatePresharedKey())
        assertEquals(first.routerPublicKey, second.routerPublicKey)
        assertEquals("10.77.0.3", second.phoneAddress)
        assertEquals("10.77.0.2/32", f.fake.option("wrtcvpn_p_abcd1234", "allowed_ips"))

        // Repetirlo para el mismo teléfono (p. ej. tras reinstalar la app con la misma clave) no lo duplica.
        val again = api.enableRemoteAccess("abcd1234", "Pixel 8", X25519.publicKey(phone), psk)
        assertEquals("10.77.0.2", again.phoneAddress)
        assertEquals(2, api.state().remote!!.peers.size)
    }

    @Test
    fun `quitar el ultimo telefono quita el servidor entero`() = runTest {
        val (f, api) = setup()
        api.enableRemoteAccess("abcd1234", "Pixel 8", X25519.publicKey(phone), psk)
        api.enableRemoteAccess("zzzz9999", "Galaxy", X25519.publicKey(X25519.generatePrivateKey()), X25519.generatePresharedKey())

        api.removeRemotePeer("zzzz9999")
        assertNull(f.fake.committed["wrtcvpn_p_zzzz9999"])
        assertNotNull(f.fake.committed[VPN_INTERFACE])

        api.removeRemotePeer("abcd1234")
        assertTrue("sin rastro", f.fake.committed.keys.none { it.startsWith(VPN_PREFIX) })
        assertNull(api.state().remote)
    }

    @Test
    fun `si WireGuard no se levanta se deshace y se explica`() = runTest {
        val (f, api) = setup()
        f.wgFails = true
        try {
            api.enableRemoteAccess("abcd1234", "Pixel 8", X25519.publicKey(phone), psk)
            fail("tenía que deshacerse")
        } catch (e: dev.angel.wrtcontrol.parental.ChangeRevertedException) {
            assertEquals("El router no levantó el acceso remoto (WireGuard), así que se dejó como estaba.", e.message)
        }
        assertTrue(f.fake.committed.keys.none { it.startsWith(VPN_PREFIX) })
    }

    @Test
    fun `el control parental no toca las secciones del acceso remoto`() = runTest {
        val (f, api) = setup()
        f.fake.section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "CST6", "zonename" to "America/Mexico_City")
        api.enableRemoteAccess("abcd1234", "Pixel 8", X25519.publicKey(phone), psk)
        val before = f.fake.committed.filterKeys { it.startsWith(VPN_PREFIX) }

        val parental = ParentalApi(f.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
        parental.updateProfiles { profiles, _ -> profiles + Profile("j1", "Juan", listOf("AA:BB:CC:DD:EE:01"), pause = Pause.Indefinite) }
        parental.updateProfiles { _, _ -> emptyList() }

        assertEquals(before, f.fake.committed.filterKeys { it.startsWith(VPN_PREFIX) })
    }
}
