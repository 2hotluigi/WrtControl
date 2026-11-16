package dev.angel.wrtcontrol.ubus

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAclTest {

    private val acl = SessionAcl(
        ubus = mapOf("uci" to setOf("get", "set"), "hostapd.*" to setOf("del_client"), "luci.ddns" to setOf("*")),
        uci = mapOf("firewall" to setOf("read", "write"), "wireless" to setOf("read")),
        file = mapOf(
            "/etc/luci-uploads/*" to setOf("write"),
            "/sbin/ip -[46] neigh show" to setOf("exec"),
            "/usr/libexec/package-manager-call install *" to setOf("exec"),
            "/bin/dmesg -r" to setOf("exec"),
        ),
        groups = mapOf("luci-app-ddns" to setOf("read", "write")),
    )

    @Test
    fun `objetos y metodos, con comodines`() {
        assertTrue(acl.canCall("uci", "get"))
        assertFalse(acl.canCall("uci", "apply"))
        assertFalse(acl.canCall("system", "board"))
        assertTrue(acl.canCall("hostapd.wlan0", "del_client"))
        assertFalse("el punto del patrón es un punto", acl.canCall("hostapdXwlan0", "del_client"))
        assertTrue("* en los métodos", acl.canCall("luci.ddns", "get_services_status"))
        assertFalse(acl.canCall("luciXddns", "get_services_status"))
    }

    @Test
    fun `configuraciones - leer no es escribir`() {
        assertTrue(acl.canWrite("firewall"))
        assertFalse(acl.canWrite("wireless"))
        assertFalse(acl.canWrite("rpcd"))
    }

    @Test
    fun `ficheros y comandos`() {
        assertTrue(acl.canFile("/etc/luci-uploads/wrtcontrol.nft", "write"))
        assertFalse(acl.canFile("/etc/luci-uploads/wrtcontrol.nft", "read"))
        assertFalse(acl.canFile("/etc/passwd", "write"))
        assertTrue(acl.canFile("/sbin/ip -4 neigh show", "exec"))
        assertTrue(acl.canFile("/sbin/ip -6 neigh show", "exec"))
        assertFalse(acl.canFile("/sbin/ip -5 neigh show", "exec"))
        assertTrue(acl.canFile("/usr/libexec/package-manager-call install luci-app-ddns", "exec"))
        assertFalse(acl.canFile("/usr/libexec/package-manager-call remove luci-app-ddns", "exec"))
        assertFalse("sin comodín, tal cual", acl.canFile("/bin/dmesg -r -x", "exec"))
    }

    @Test
    fun `comodines como los de fnmatch`() {
        assertTrue(globMatches("a?c", "abc"))
        assertFalse(globMatches("a?c", "ac"))
        assertTrue(globMatches("[!x]bc", "abc"))
        assertFalse(globMatches("[!a]bc", "abc"))
        assertTrue(globMatches("/dev/mtdblock[0-9]*", "/dev/mtdblock12"))
        assertFalse(globMatches("/dev/mtdblock[0-9]*", "/dev/mtdblockX"))
        assertTrue("los signos de las expresiones regulares van tal cual", globMatches("a+(b).c*", "a+(b).cd"))
        assertFalse(globMatches("a+(b).c*", "aa(b)xcd"))
        assertTrue("un corchete sin cerrar es un corchete", globMatches("a[b*", "a[bcd"))
    }

    @Test
    fun `grupos - las partes de LuCI instaladas`() {
        assertTrue(acl.hasGroup("luci-app-ddns"))
        assertFalse(acl.hasGroup("luci-app-sqm"))
    }

    // ---------- cómo los da el router ----------

    private val login = """{"ubus_rpc_session":"s1","timeout":300,"expires":300,"acls":{
        "access-group":{"luci-base":["read","write"]},"ubus":{"uci":["get"]},"uci":{"firewall":["read","write"]},
        "file":{"/etc/luci-uploads/*":["write"]},"cgi-io":{"exec":["read","write"]}}}"""

    @Test
    fun `acl - los permisos de ahora salen de un login aparte que no cambia la sesion compartida`() = runTest {
        var logins = 0
        val router = FakeRouter { call ->
            if (call.name == "session.login") ok(login.replace("s1", "s${++logins}")) else ok("{}")
        }
        val client = router.client(Credentials("root", "x"), io = StandardTestDispatcher(testScheduler))
        client.call("system", "board")
        assertEquals("s1", client.session)

        val acl = client.acl()
        assertTrue(acl.canWrite("firewall") && acl.canCall("uci", "get") && acl.hasGroup("luci-base"))
        assertTrue(acl.canFile("/etc/luci-uploads/a.nft", "write"))
        assertEquals("la sesión compartida sigue siendo la misma", "s1", client.session)
        assertEquals(2, logins)
    }

    @Test
    fun `objects - la lista de objetos, sin sesion`() = runTest {
        val router = FakeRouter { call ->
            if (call.name == "ubus.list") Reply.Result("""{"result":["dnsmasq","luci.wireguard","uci"]}""") else status(4)
        }
        val client = router.client(io = StandardTestDispatcher(testScheduler))
        assertEquals(setOf("dnsmasq", "luci.wireguard", "uci"), client.objects())
        assertEquals(listOf("ubus.list"), router.calls.map { it.name })
    }

    @Test
    fun `objects - con los metodos de cada objeto tambien vale`() = runTest {
        val router = FakeRouter { Reply.Result("""{"result":{"uci":{"get":{}},"system":{"board":{}}}}""") }
        assertEquals(setOf("uci", "system"), router.client(io = StandardTestDispatcher(testScheduler)).objects())
    }
}
