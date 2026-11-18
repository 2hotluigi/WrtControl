package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * La red de casa con otro nombre, contra un OpenWrt de verdad (ver HomeNetwork.kt): se renombra
 * "lan" a "casa" (interfaz, zona del cortafuegos y sus reglas, DHCP y Wi-Fi) y la app tiene que
 * encontrarla, identificar el mismo router y seguir viendo los aparatos. Al terminar se deja todo
 * como estaba (copia de /etc/config). Solo en una máquina virtual: en un router de casa cortaría
 * la red un momento.
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*HomeNetworkVmTest*" --rerun
 */
class HomeNetworkVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /**
     * Ejecuta [command] en el router. Va por la entrada de `sh -s`, no como argumento: así las
     * comillas llegan tal cual también desde Windows (ProcessBuilder las reescribe allí).
     */
    private fun ssh(command: String): String {
        var out = ""
        repeat(SSH_TRIES) {
            val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", "sh -s"))
                .redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(command) }
            out = process.inputStream.bufferedReader().readText()
            process.waitFor(SSH_TIMEOUT_S, TimeUnit.SECONDS)
            if (process.exitValue() != SSH_FAILED) return out.trim()
            Thread.sleep(SSH_RETRY_MS)
        }
        return out.trim()
    }

    /** Espera a que la interfaz [name] esté levantada (tras recargar la red). */
    private fun waitUp(name: String) {
        repeat(WAIT_TRIES) {
            if (ssh("ubus call network.interface.$name status 2>/dev/null | grep -q '\"up\": true' && echo si").endsWith("si")) return
            Thread.sleep(WAIT_STEP_MS)
        }
        error("$name no se levantó")
    }

    /**
     * Recarga red, cortafuegos y DNS aparte (la conexión por la LAN se corta un momento) y vuelve a
     * enchufar al puente al cliente de pruebas, si lo había: netifd rehace br-lan.
     */
    private fun reloadNetwork(home: String) {
        ssh(
            "(sleep 1; /etc/init.d/network reload; fw4 reload; /etc/init.d/dnsmasq restart; " +
                "ip link show wrtc_v0 >/dev/null 2>&1 && ip link set wrtc_v0 master br-lan) >/dev/null 2>&1 &",
        )
        Thread.sleep(RELOAD_MS)
        waitUp(home)
    }

    @Test
    fun `con la red de casa renombrada la app la encuentra`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeTrue("Solo en una máquina virtual", ssh("cat /sys/class/dmi/id/sys_vendor 2>/dev/null").let { "QEMU" in it || "innotek" in it })
        assumeTrue("La red de casa ya no se llama lan", ssh("uci -q get network.lan") == "interface")
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val id = api.routerId()
        assertEquals("lan", api.homeLayout().main)

        ssh("rm -rf $BACKUP; mkdir -p $BACKUP; for f in $CONFIGS; do [ -f /etc/config/\$f ] && cp /etc/config/\$f $BACKUP/; done; true")
        try {
            ssh(RENAME)
            reloadNetwork("casa")

            val layout = api.homeLayout()
            assertEquals("casa", layout.main)
            assertTrue("casa es del lado de dentro: ${layout.inside}", layout.inside.orEmpty().contains("casa"))
            // La misma MAC de la red de casa: el mismo router para la app.
            assertEquals(id, api.routerId())

            if (ssh("/usr/libexec/ip-full netns list").contains("wrtc_client")) {
                // Que el router lo vea (la recarga vacía la tabla de vecinos).
                ssh("/usr/libexec/ip-full netns exec wrtc_client ping -c 2 -W 2 ${vm!!} >/dev/null 2>&1; true")
                val macs = api.devices().map { it.mac }
                assertTrue("el cliente de pruebas sigue en Dispositivos: $macs", CLIENT_MAC in macs)
            }
        } finally {
            ssh("for f in $CONFIGS; do [ -f $BACKUP/\$f ] && cp $BACKUP/\$f /etc/config/\$f; done; rm -rf $BACKUP")
            reloadNetwork("lan")
        }
        assertEquals("lan", api.homeLayout().main)
    }

    private companion object {
        const val BACKUP = "/tmp/wrtc_home_backup"
        const val CONFIGS = "network firewall dhcp wireless"
        const val CLIENT_MAC = "02:00:00:00:00:99"

        /** De "lan" a "casa" en todo lo que la nombra; sin recargar (ver [reloadNetwork]). */
        const val RENAME = "set -e; " +
            "uci rename network.lan=casa; " +
            "for s in \$(uci show firewall | sed -n \"s/^firewall\\.\\([^.]*\\)\\.\\(src\\|dest\\|name\\)='lan'\$/\\1.\\2/p\"); do uci set firewall.\$s=casa; done; " +
            "for z in \$(uci show firewall | sed -n \"s/^firewall\\.\\([^.]*\\)\\.network=.*'lan'.*/\\1/p\"); do " +
            "uci del_list firewall.\$z.network=lan; uci add_list firewall.\$z.network=casa; done; " +
            "if uci -q get dhcp.lan >/dev/null; then uci rename dhcp.lan=casa; uci set dhcp.casa.interface=casa; fi; " +
            "for w in \$(uci -q show wireless | sed -n \"s/^wireless\\.\\([^.]*\\)\\.network=.*'lan'.*/\\1/p\"); do " +
            "uci del_list wireless.\$w.network=lan; uci add_list wireless.\$w.network=casa; done; " +
            "uci commit"

        const val SSH_TRIES = 4
        const val SSH_TIMEOUT_S = 60L
        const val SSH_FAILED = 255
        const val SSH_RETRY_MS = 1000L
        const val WAIT_TRIES = 30
        const val WAIT_STEP_MS = 2000L
        const val RELOAD_MS = 5000L
    }
}
