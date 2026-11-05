package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.Route
import dev.angel.wrtcontrol.ubus.Credentials
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DdnsTest {

    @Test
    fun `el nombre se completa con el dominio del proveedor`() {
        assertEquals("micasa.duckdns.org", DdnsEdit(DdnsProvider.DuckDns, " MiCasa ", "t", true).fullDomain())
        assertEquals("micasa.duckdns.org", DdnsEdit(DdnsProvider.DuckDns, "https://micasa.duckdns.org/", "t", true).fullDomain())
        assertEquals("micasa.dedyn.io", DdnsEdit(DdnsProvider.Desec, "micasa", "t", true).fullDomain())
        assertEquals("micasa.dynv6.net", DdnsEdit(DdnsProvider.Dynv6, "micasa.dynv6.net", "t", true).fullDomain())
    }

    @Test
    fun `explica lo que falta o sobra`() {
        assertNull(ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "micasa", "abc-123", true)))
        assertEquals("Falta el token (la clave que te da el proveedor).", ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "micasa", " ", true)))
        assertTrue(ddnsProblem(DdnsEdit(DdnsProvider.Dynv6, "micasa", "t", true))!!.startsWith("Escribe un nombre válido"))
        assertTrue(ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "mi casa", "t", true))!!.startsWith("Escribe un nombre válido"))
        assertEquals("El token no puede llevar espacios ni comillas.", ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "micasa", "a'b", true)))
        assertEquals("Falta la URL de actualización.", ddnsProblem(DdnsEdit(DdnsProvider.Custom, "a.example.org", "t", true)))
        assertEquals(
            "La URL tiene que llevar [IP], donde va la dirección.",
            ddnsProblem(DdnsEdit(DdnsProvider.Custom, "a.example.org", "t", true, updateUrl = "https://x.org/u?h=[DOMAIN]")),
        )
    }

    @Test
    fun `la seccion lleva lo que ddns-scripts necesita`() {
        val duck = ddnsSection(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok", ipv6 = true), "wan6")
        assertEquals(DDNS_SECTION, duck.name)
        assertEquals("duckdns.org", duck.text("service_name"))
        assertEquals("micasa.duckdns.org", duck.text("domain"))
        assertEquals("micasa.duckdns.org", duck.text("lookup_host"))
        assertEquals("tok", duck.text("password"))
        assertEquals("1", duck.text("use_ipv6"))
        assertEquals("wan6", duck.text("ip_network"))
        assertEquals("1", duck.text("use_https"))
        assertNull(duck.text("update_url"))
        // deSEC usa el dominio como usuario.
        assertEquals("micasa.dedyn.io", ddnsSection(DdnsEdit(DdnsProvider.Desec, "micasa", "tok", true), "wan6").text("username"))
        // Una URL propia por http no puede forzar https.
        val custom = ddnsSection(DdnsEdit(DdnsProvider.Custom, "a.example.org", "t", false, updateUrl = "http://x.org/u?ip=[IP]"), "wan")
        assertEquals("0", custom.text("use_https"))
        assertNull(custom.text("service_name"))
        assertEquals("http://x.org/u?ip=[IP]", custom.text("update_url"))
    }

    private fun iface(name: String, v4: String? = null, v6: List<String> = emptyList(), wan: Boolean = false) = NetInterface(
        name = name, up = true, ipv4 = listOfNotNull(v4?.let { IpAddress(it, 24) }), ipv6 = v6.map { IpAddress(it, 64) },
        routes = if (wan) listOf(Route("0.0.0.0", 0)) else emptyList(),
    )

    @Test
    fun `publica la interfaz que tiene la IPv6 publica`() {
        val wan = iface("wan", "100.64.1.2", wan = true)
        // Con solo prefijo delegado, la wan6 no tiene dirección propia: se publica la del router en la LAN.
        assertEquals("lan", ddnsInterface(listOf(wan, iface("wan6", wan = true), iface("lan", "192.168.1.1", listOf("2001:db8:1::1"))), ipv6 = true))
        assertEquals("wan6", ddnsInterface(listOf(wan, iface("wan6", v6 = listOf("2001:db8::5"), wan = true), iface("lan", v6 = listOf("2001:db8:1::1"))), ipv6 = true))
        assertEquals("sin IPv6 pública, la wan6 por si llega", "wan6", ddnsInterface(listOf(wan, iface("lan", v6 = listOf("fd00::1"))), ipv6 = true))
        assertEquals("wan", ddnsInterface(listOf(wan), ipv6 = false))
    }

    /** Registro real de ddns-scripts 2.8.4 en la VM. */
    private val okLog = """
         180812       : Detect registered/public IP
         180812       : #> /usr/bin/nslookup vmtest.example.org  >/var/run/ddns/wrtcontrol.dat 2>/var/run/ddns/wrtcontrol.err
         180812  WARN : NO valid IP found
         180812  info : Starting main loop at 2026-11-05 18:08
         180812       : Current IP '10.0.3.15' detected on network 'wan'
         180813       : DDNS Provider answered:
        good
         180813  info : Update successful - IP '10.0.3.15' send
         180813  info : Forced update successful - IP: '10.0.3.15' send
         180813       : Waiting 300 seconds (Check Interval); Next check at 2026-11-05 18:13
    """.trimIndent()

    @Test
    fun `del registro saca la ultima IP enviada y solo los fallos posteriores`() {
        assertEquals(DdnsLog(lastError = null, lastSentIp = "10.0.3.15"), parseDdnsLog(okLog))
        val rejected = okLog + "\n" + """
             181113       : DDNS Provider answered:
            KO
             181113 ERROR : IP update not accepted by DDNS Provider
        """.trimIndent()
        assertEquals(DdnsLog("El proveedor rechazó la actualización: revisa el nombre y el token.", "10.0.3.15"), parseDdnsLog(rejected))
        val offline = " 1 ERROR : uclient-fetch Error: '4'\n 1  WARN : Updating IP at DDNS provider failed"
        assertTrue(parseDdnsLog(offline).lastError!!.startsWith("El router no pudo conectar con el proveedor"))
        assertEquals(DdnsLog(null, null), parseDdnsLog(null))
    }

    // ---------- contra el router simulado ----------

    private fun TestScope.setup(): Pair<FakeAddons, AddonsApi> {
        val f = FakeAddons()
        f.install("luci-app-ddns")
        f.fake.interfaces = mapOf("lan" to "br-lan", "wan" to "eth1")
        f.fake.clock = { testScheduler.currentTime }
        return f to AddonsApi(f.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `configurar el DNS dinamico crea el servicio de WrtControl y no toca los de LuCI`() = runTest {
        val (f, api) = setup()
        f.ddnsLog = okLog
        val luci = f.fake.committed["myddns_ipv4"]!!.toMap()

        api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok", ipv6 = false))

        assertEquals("micasa.duckdns.org", f.fake.option(DDNS_SECTION, "domain"))
        assertEquals("wan", f.fake.option(DDNS_SECTION, "ip_network"))
        assertEquals(luci, f.fake.committed["myddns_ipv4"])
        assertTrue(f.initActions.contains("ddns enable"))
        val own = api.state().ownDdns!!
        assertTrue(own.running)
        assertEquals("10.0.3.15", own.lastSentIp)
        assertNull(own.lastError)
        assertEquals(DdnsProvider.DuckDns, own.provider)

        // Lo mismo otra vez: nada que aplicar.
        val applies = f.fake.calls.count { it == "uci.confirm" }
        api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok", ipv6 = false))
        assertEquals(applies, f.fake.calls.count { it == "uci.confirm" })

        api.removeDdns()
        assertNull(f.fake.committed[DDNS_SECTION])
        assertNull(api.state().ownDdns)
        assertFalse(f.fake.committed["myddns_ipv4"] == null)
    }
}
