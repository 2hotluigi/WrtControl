package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.FakeRouter
import dev.angel.wrtcontrol.ubus.Reply
import dev.angel.wrtcontrol.ubus.ok
import dev.angel.wrtcontrol.ubus.status
import dev.angel.wrtcontrol.ubus.uriComponent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagesTest {

    @Test
    fun `lee el listado JSON de apk`() {
        val out = """
            [
              { "package": "apk-mbedtls-3.0.5-r3", "name": "apk-mbedtls", "version": "3.0.5-r3", "depends": ["libc"] },
              { "package": "luci-app-sqm-26.304.26398~e969939", "name": "luci-app-sqm", "version": "26.304.26398~e969939" }
            ]
        """.trimIndent()
        assertEquals(mapOf("apk-mbedtls" to "3.0.5-r3", "luci-app-sqm" to "26.304.26398~e969939"), parseInstalled(out))
    }

    @Test
    fun `lee el fichero de estado de opkg y se salta lo desinstalado`() {
        val out = """
            Package: sqm-scripts
            Version: 1.5.2-1
            Depends: libc, tc, kmod-sched-cake
            Status: install user installed
            Architecture: all
            Conffiles:
             /etc/config/sqm 7f1d6c1
            Installed-Time: 1700000000

            Package: miniupnpd-nftables
            Version: 2.3.3-2
            Status: install ok installed

            Package: luci-app-upnp
            Version: git-23.1
            Status: deinstall ok not-installed
        """.trimIndent()
        assertEquals(mapOf("sqm-scripts" to "1.5.2-1", "miniupnpd-nftables" to "2.3.3-2"), parseInstalled(out))
    }

    @Test
    fun `lee apk list con nombres que llevan cifras`() {
        val out = """
            kmod-crypto-lib-chacha20-6.12.112-r1 x86_64 {feeds/base/kmods} (GPL-2.0) [installed]
            attendedsysupgrade-common-10 noarch {feeds/packages/utils} (GPL-2.0) [installed]
            luci-proto-wireguard-26.304.26398~e969939 noarch {feeds/luci} (Apache-2.0) [installed]
        """.trimIndent()
        assertEquals(
            mapOf(
                "kmod-crypto-lib-chacha20" to "6.12.112-r1",
                "attendedsysupgrade-common" to "10",
                "luci-proto-wireguard" to "26.304.26398~e969939",
            ),
            parseInstalled(out),
        )
    }

    @Test
    fun `explica los fallos tipicos del gestor de paquetes`() {
        val what = "instalar luci-app-sqm"
        assertTrue(explainFailure(255, "Failed to acquire lock", what).contains("Otro programa"))
        assertTrue(explainFailure(1, "ERROR: luci-app-sqm: No space left on device", what).contains("espacio"))
        assertTrue(
            explainFailure(1, "ERROR: unable to select packages:\n  kernel-6.12.1~abc-r1:\n    breaks: kmod-wireguard", what)
                .contains("kernel"),
        )
        assertTrue(explainFailure(1, "ERROR: unable to select packages:\n  foo (no such package)", what).contains("no está disponible"))
        assertTrue(
            explainFailure(255, "Collected errors:\n * opkg_download: Failed to download https://downloads.openwrt.org/x", what)
                .contains("internet"),
        )
        // Que el nombre de un paquete lleve "dns" no hace pensar en un fallo de red.
        assertTrue(explainFailure(1, "luci-app-ddns: conflicting files", what).contains("código 1"))
    }

    @Test
    fun `cgi-exec va como el de LuCI y escapa los espacios de los argumentos`() = runTest {
        val router = FakeRouter { call ->
            when (call.name) {
                "session.login" -> ok("""{"ubus_rpc_session":"s1","timeout":300,"expires":300}""")
                else -> if (call.target == "cgi-exec") Reply.Raw(200, "hola:${call.method}") else status(3)
            }
        }
        val client = router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler))
        val out = client.cgiExec("/usr/libexec/package-manager-call", listOf("install", "con espacio"))
        // El falso separa como cgi-io: el argumento con espacio sigue siendo uno.
        assertEquals("hola:/usr/libexec/package-manager-call install con espacio", out)
        assertEquals(listOf("/a", "b", "c d"), dev.angel.wrtcontrol.ubus.splitCommand("/a b c\\ d"))
        // Como encodeURIComponent: la '/' codificada (cgi-io no encuentra el programa si no) y el espacio como %20.
        assertEquals("%2Fusr%2Flibexec%2Fx%20a%5C%20b", uriComponent("/usr/libexec/x a\\ b"))
        assertEquals("a%2Bb%2Fc~d!e'f(g)h*i", uriComponent("a+b/c~d!e'f(g)h*i"))
    }

    @Test
    fun `si la sesion caduco cgi-exec la renueva y repite`() = runTest {
        var logins = 0
        val alive = mutableSetOf<String>()
        val router = FakeRouter { call ->
            when (call.name) {
                "session.login" -> {
                    val sid = "s${++logins}"
                    alive += sid
                    ok("""{"ubus_rpc_session":"$sid","timeout":300,"expires":300}""")
                }
                "session.access" -> if (call.sid in alive) ok("""{"access":true}""") else
                    Reply.Result("""{"error":{"code":-32002,"message":"Access denied"}}""")
                // cgi-io da el mismo 403 a una sesión caducada que a un comando sin permiso.
                else -> when {
                    call.target != "cgi-exec" -> status(3)
                    call.sid in alive -> Reply.Raw(200, "listo")
                    else -> Reply.Raw(403, "Exec permission denied")
                }
            }
        }
        val client = router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler))
        client.login(Credentials("root", ""))
        alive.clear() // rpcd la dio por caducada
        assertEquals("listo", client.cgiExec("/usr/libexec/opkg-call", listOf("list-installed")))
        assertEquals(2, logins)
    }
}
