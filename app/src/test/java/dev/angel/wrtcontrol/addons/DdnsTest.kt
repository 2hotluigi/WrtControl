package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.PrefixAssignment
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
        assertEquals("micasa.duckdns.org", DdnsEdit(DdnsProvider.DuckDns, " MiCasa ", "t").fullDomain())
        assertEquals("micasa.duckdns.org", DdnsEdit(DdnsProvider.DuckDns, "https://micasa.duckdns.org/", "t").fullDomain())
        assertEquals("micasa.dedyn.io", DdnsEdit(DdnsProvider.Desec, "micasa", "t").fullDomain())
        assertEquals("micasa.dynv6.net", DdnsEdit(DdnsProvider.Dynv6, "micasa.dynv6.net", "t").fullDomain())
    }

    @Test
    fun `explica lo que falta o sobra`() {
        assertNull(ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "micasa", "abc-123")))
        assertEquals("Falta el token (la clave que te da el proveedor).", ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "micasa", " ")))
        assertTrue(ddnsProblem(DdnsEdit(DdnsProvider.Dynv6, "micasa", "t"))!!.startsWith("Escribe un nombre válido"))
        assertTrue(ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "mi casa", "t"))!!.startsWith("Escribe un nombre válido"))
        assertEquals("El token no puede llevar espacios ni comillas.", ddnsProblem(DdnsEdit(DdnsProvider.DuckDns, "micasa", "a'b")))
        assertEquals("Falta la URL de actualización.", ddnsProblem(DdnsEdit(DdnsProvider.Custom, "a.example.org", "t")))
        assertEquals(
            "La URL tiene que llevar [IP], donde va la dirección.",
            ddnsProblem(DdnsEdit(DdnsProvider.Custom, "a.example.org", "t", updateUrl = "https://x.org/u?h=[DOMAIN]")),
        )
    }

    @Test
    fun `la seccion lleva lo que ddns-scripts necesita`() {
        val duck = ddnsSection(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"), "wan6", IpFamily.V6)
        assertEquals(DDNS_SECTION6, duck.name)
        assertEquals("duckdns.org", duck.text("service_name"))
        assertEquals("micasa.duckdns.org", duck.text("domain"))
        assertEquals("micasa.duckdns.org", duck.text("lookup_host"))
        assertEquals("tok", duck.text("password"))
        assertEquals("1", duck.text("use_ipv6"))
        assertEquals("wan6", duck.text("ip_network"))
        assertEquals("1", duck.text("use_https"))
        assertNull(duck.text("update_url"))
        val v4 = ddnsSection(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"), "wan", IpFamily.V4)
        assertEquals(DDNS_SECTION, v4.name)
        assertEquals("0", v4.text("use_ipv6"))
        // deSEC usa el dominio como usuario.
        assertEquals("micasa.dedyn.io", ddnsSection(DdnsEdit(DdnsProvider.Desec, "micasa", "tok"), "wan6", IpFamily.V6).text("username"))
        // Una URL propia por http no puede forzar https, y el usuario es el que se escriba.
        val custom = ddnsSection(
            DdnsEdit(DdnsProvider.Custom, "a.example.org", "t", updateUrl = "http://x.org/u?u=[USERNAME]&ip=[IP]", username = "pepe"),
            "wan", IpFamily.V4,
        )
        assertEquals("0", custom.text("use_https"))
        assertNull(custom.text("service_name"))
        assertEquals("http://x.org/u?u=[USERNAME]&ip=[IP]", custom.text("update_url"))
        assertEquals("pepe", custom.text("username"))
    }

    private fun iface(name: String, v4: String? = null, v6: List<String> = emptyList(), wan: Boolean = false, pd: String? = null) = NetInterface(
        name = name, up = true, ipv4 = listOfNotNull(v4?.let { IpAddress(it, 24) }), ipv6 = v6.map { IpAddress(it, 64) },
        ipv6Prefixes = listOfNotNull(pd?.let { PrefixAssignment(it.substringBeforeLast(':') + ":", 64, IpAddress(it, 64)) }),
        routes = if (wan) listOf(Route("0.0.0.0", 0)) else emptyList(),
    )

    @Test
    fun `publica la interfaz que tiene la IPv6 publica`() {
        val wan = iface("wan", "100.64.1.2", wan = true)
        assertEquals("lan", ddnsInterface(listOf(wan, iface("wan6", wan = true), iface("lan", "192.168.1.1", listOf("2001:db8:1::1"))), ipv6 = true))
        // Con solo prefijo delegado, la dirección del router está en la asignación del prefijo a la LAN.
        assertEquals("lan", ddnsInterface(listOf(wan, iface("wan6", wan = true), iface("lan", "192.168.1.1", pd = "2001:db8:7::1")), ipv6 = true))
        assertEquals(listOf("2001:db8:7::1"), publicAddresses(listOf(wan, iface("wan6", wan = true), iface("lan", "192.168.1.1", pd = "2001:db8:7::1"))))
        assertEquals("wan6", ddnsInterface(listOf(wan, iface("wan6", v6 = listOf("2001:db8::5"), wan = true), iface("lan", v6 = listOf("2001:db8:1::1"))), ipv6 = true))
        assertEquals("sin IPv6 pública, la wan6 por si llega", "wan6", ddnsInterface(listOf(wan, iface("lan", v6 = listOf("fd00::1"))), ipv6 = true))
        assertEquals("wan", ddnsInterface(listOf(wan), ipv6 = false))
    }

    @Test
    fun `publica la IPv4 si es publica y la IPv6 si hay`() {
        val cgnat = iface("wan", "100.64.1.2", wan = true)
        val public4 = iface("wan", "203.0.113.5", wan = true)
        val v6 = iface("lan", "192.168.1.1", pd = "2001:db8:7::1")
        assertEquals(setOf(IpFamily.V6), ddnsFamilies(listOf(cgnat, v6)))
        assertEquals(setOf(IpFamily.V4, IpFamily.V6), ddnsFamilies(listOf(public4, v6)))
        assertEquals(setOf(IpFamily.V4), ddnsFamilies(listOf(public4)))
        assertEquals("sin nada público, la IPv6 por si llega", setOf(IpFamily.V6), ddnsFamilies(listOf(cgnat)))
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

    @Test
    fun `deSEC borra con un valor vacio y dynv6 con un guion`() {
        val only6 = DdnsRecords(null, "2001:db8::1")
        val desec = ddnsSyncCalls(DdnsEdit(DdnsProvider.Desec, "micasa", "t0k"), only6).single()
        assertEquals(
            "https://update.dedyn.io/update?username=micasa.dedyn.io&password=t0k&hostname=micasa.dedyn.io&myipv4=&myipv6=2001%3Adb8%3A%3A1",
            desec.url,
        )
        assertTrue(desec.accepted(DdnsHttpResponse(200, "good")))
        assertFalse(desec.accepted(DdnsHttpResponse(401, "badauth")))
        val only4 = ddnsSyncCalls(DdnsEdit(DdnsProvider.Desec, "micasa", "t0k"), DdnsRecords("203.0.113.5", null)).single()
        assertTrue(only4.url.endsWith("&myipv4=203.0.113.5&myipv6="))

        val dynv6 = ddnsSyncCalls(DdnsEdit(DdnsProvider.Dynv6, "micasa.dynv6.net", "t"), only6).single()
        assertEquals("https://dynv6.com/api/update?hostname=micasa.dynv6.net&token=t&ipv4=-&ipv6=2001%3Adb8%3A%3A1", dynv6.url)
        assertTrue(dynv6.accepted(DdnsHttpResponse(200, "addresses updated")))
        // El AAAA no se puede borrar en dynv6: no se manda ipv6 y se avisa.
        val dynv6Only4 = ddnsSyncCalls(DdnsEdit(DdnsProvider.Dynv6, "micasa.dynv6.net", "t"), DdnsRecords("203.0.113.5", null)).single()
        assertTrue(dynv6Only4.url.endsWith("&ipv4=203.0.113.5"))
        assertTrue(ddnsSyncCalls(DdnsEdit(DdnsProvider.Custom, "a.example.org", "t", updateUrl = "https://x/?ip=[IP]"), only6).isEmpty())
    }

    @Test
    fun `dynv6 avisa de que la IPv6 antigua hay que quitarla a mano`() = runTest {
        val notice = syncDdnsRecords(
            { DdnsHttpResponse(200, "addresses unchanged") },
            DdnsEdit(DdnsProvider.Dynv6, "micasa.dynv6.net", "t"),
            DdnsRecords("203.0.113.5", null),
        )
        assertTrue(notice!!.contains("quítala en dynv6.com"))
    }

    // ---------- contra el router simulado ----------

    private fun TestScope.setup(http: DdnsHttp? = null): Pair<FakeAddons, AddonsApi> {
        val f = FakeAddons()
        f.install("luci-app-ddns")
        f.fake.interfaces = mapOf("lan" to "br-lan", "wan" to "eth1")
        f.fake.clock = { testScheduler.currentTime }
        return f to AddonsApi(f.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)), ddnsHttp = http)
    }

    /** El proveedor: apunta lo que se le pide y contesta [answer]. */
    private class FakeProvider(var answer: (String) -> DdnsHttpResponse = { DdnsHttpResponse(200, "OK") }) : DdnsHttp {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String): DdnsHttpResponse {
            urls += url
            return answer(url)
        }
    }

    @Test
    fun `con solo IPv6 el telefono borra en DuckDNS la IPv4 que tuviera el nombre`() = runTest {
        // Como el GL-MT6000 tras el módem de Megacable: IPv4 privada en la WAN y una IPv6 global.
        val provider = FakeProvider()
        val (f, api) = setup(provider)
        f.ipv4 = mapOf("wan" to "192.168.1.2")
        f.prefixLocal = mapOf("lan" to "2001:db8:7::1")
        f.ddnsLog = okLog

        val notice = api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"))

        assertNull(notice)
        assertEquals(
            listOf(
                "https://www.duckdns.org/update?domains=micasa&token=tok&clear=true",
                // Sin ip=: DuckDNS pondría la IPv4 del teléfono.
                "https://www.duckdns.org/update?domains=micasa&token=tok&ipv6=2001%3Adb8%3A7%3A%3A1",
            ),
            provider.urls,
        )
        assertNull(f.fake.committed[DDNS_SECTION])
        assertEquals("1", f.fake.option(DDNS_SECTION6, "use_ipv6"))
    }

    @Test
    fun `con IPv4 publica e IPv6 se publican las dos`() = runTest {
        val provider = FakeProvider()
        val (f, api) = setup(provider)
        f.ipv4 = mapOf("wan" to "203.0.113.5")
        f.prefixLocal = mapOf("lan" to "2001:db8:7::1")
        f.ddnsLog = okLog

        api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"))

        assertEquals("https://www.duckdns.org/update?domains=micasa&token=tok&ip=203.0.113.5&ipv6=2001%3Adb8%3A7%3A%3A1", provider.urls.last())
        assertEquals("0", f.fake.option(DDNS_SECTION, "use_ipv6"))
        assertEquals("1", f.fake.option(DDNS_SECTION6, "use_ipv6"))
    }

    @Test
    fun `si el proveedor rechaza el token no se toca el router`() = runTest {
        val provider = FakeProvider { DdnsHttpResponse(200, "KO") }
        val (f, api) = setup(provider)
        f.prefixLocal = mapOf("lan" to "2001:db8:7::1")

        val e = runCatching { api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "malo")) }.exceptionOrNull()

        assertTrue(e is DdnsRejectedException)
        assertTrue(e!!.message!!.contains("rechazó el nombre o el token"))
        assertEquals(1, provider.urls.size)
        assertNull(f.fake.committed[DDNS_SECTION6])
    }

    @Test
    fun `sin llegar al proveedor se configura el router igual y se avisa`() = runTest {
        val provider = FakeProvider { throw java.io.IOException("sin conexión") }
        val (f, api) = setup(provider)
        f.prefixLocal = mapOf("lan" to "2001:db8:7::1")
        f.ddnsLog = okLog

        val notice = api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"))

        assertTrue(notice!!.startsWith("No se pudo avisar a DuckDNS desde el teléfono"))
        assertEquals("1", f.fake.option(DDNS_SECTION6, "use_ipv6"))
    }

    @Test
    fun `configurar el DNS dinamico crea un servicio por cada direccion publica y no toca los de LuCI`() = runTest {
        val (f, api) = setup()
        f.ipv4 = mapOf("wan" to "203.0.113.5")
        f.prefixLocal = mapOf("lan" to "2001:db8:7::1")
        f.ddnsLog = okLog
        val luci = f.fake.committed["myddns_ipv4"]!!.toMap()

        api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"))

        assertEquals("micasa.duckdns.org", f.fake.option(DDNS_SECTION, "domain"))
        assertEquals("wan", f.fake.option(DDNS_SECTION, "ip_network"))
        assertEquals("0", f.fake.option(DDNS_SECTION, "use_ipv6"))
        assertEquals("lan", f.fake.option(DDNS_SECTION6, "ip_network"))
        assertEquals("1", f.fake.option(DDNS_SECTION6, "use_ipv6"))
        assertEquals(luci, f.fake.committed["myddns_ipv4"])
        assertTrue(f.initActions.contains("ddns enable"))
        val state = api.state()
        assertEquals("micasa.duckdns.org", state.ddnsDomain)
        assertEquals(2, state.ownDdns.size)
        val own = state.ownDdns.first()
        assertTrue(own.running)
        assertEquals("10.0.3.15", own.lastSentIp)
        assertNull(own.lastError)
        assertEquals(DdnsProvider.DuckDns, own.provider)

        // Lo mismo otra vez: nada que aplicar.
        val applies = f.fake.calls.count { it == "uci.confirm" }
        api.saveDdns(DdnsEdit(DdnsProvider.DuckDns, "micasa", "tok"))
        assertEquals(applies, f.fake.calls.count { it == "uci.confirm" })

        api.removeDdns()
        assertNull(f.fake.committed[DDNS_SECTION])
        assertNull(f.fake.committed[DDNS_SECTION6])
        assertTrue(api.state().ownDdns.isEmpty())
        assertFalse(f.fake.committed["myddns_ipv4"] == null)
    }

    @Test
    fun `hasta 24_10 el actualizador no arranca solo y la app lo reinicia y lo comprueba con luci_ddns`() = runTest {
        val (f, api) = setup()
        f.oldRouter = true
        f.prefixLocal = mapOf("lan" to "2001:db8:7::1")

        api.saveDdns(DdnsEdit(DdnsProvider.Dynv6, "micasa.dynv6.net", "tok"))
        assertTrue(f.initActions.contains("ddns restart"))
        assertTrue(api.state().ownDdns.single().running)

        // Al quitarlo, el actualizador que seguía en marcha también se para.
        f.initActions.clear()
        api.removeDdns()
        assertTrue(f.initActions.contains("ddns restart"))
        assertTrue(api.state().ddns.orEmpty().none { it.running && it.own })
    }
}
