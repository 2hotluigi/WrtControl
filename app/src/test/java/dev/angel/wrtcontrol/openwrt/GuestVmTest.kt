package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * El Wi-Fi de invitados contra un router con radios de verdad (la VM de la CI no tiene: allí se
 * salta sola). Lo crea, mete un cliente de pruebas en su red (un namespace enganchado al puente,
 * como netns-client.sh; necesita ip-full y kmod-veth) y comprueba el aislamiento con tráfico real:
 * recibe dirección, resuelve nombres y sale a internet, pero no entra al router, ni a la red de
 * casa, ni a la red privada que haya delante del router. Al acabar lo borra todo y comprueba que
 * la configuración queda como estaba.
 *
 *   set WRTCONTROL_VM=192.168.8.1
 *   set WRTCONTROL_PASSWORD=...
 *   set WRTCONTROL_SSH_OPTS=-i %USERPROFILE%\.ssh\wrtcontrol_ed25519
 *   gradlew testDebugUnitTest --tests "*GuestVmTest*" --rerun
 *
 * Emite unos segundos una red de prueba y recarga la red, el cortafuegos y dnsmasq: con alguien
 * conectado al Wi-Fi del router se salta.
 */
class GuestVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /** Algo de internet que conteste por HTTP a una IP (el cliente de pruebas no tiene DNS configurado). */
    private val httpTarget: String = System.getenv("WRTCONTROL_HTTP_TARGET") ?: "http://1.1.1.1/cdn-cgi/trace"

    /**
     * Ejecuta [command] en el router. Va por la entrada estándar de `sh`, no como argumento: en
     * Windows las comillas dobles de un argumento se pierden por el camino, y un comando a medias
     * (un `sed` sin su expresión) hizo que una vez la limpieza final no limpiara nada.
     */
    private fun ssh(command: String): String {
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", "sh -s"))
            .redirectErrorStream(true).start()
        process.outputStream.use { it.write((command + "\n").toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(90, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun log(msg: String) = println("[${LocalTime.now().withNano(0)}] $msg")

    private fun fingerprint() = ssh("for c in network dhcp firewall wireless; do uci export \$c | md5sum; done")

    /** Ejecuta [command] dentro del cliente de pruebas de la red de invitados. */
    private fun guest(command: String) = ssh("$IP_FULL netns exec $NS $command 2>&1; true")

    /** ¿Abre el cliente una conexión HTTP a [url]? */
    private fun reaches(url: String): Boolean {
        val out = guest("wget -q -T 5 -O /dev/null $url && echo LLEGA || echo NO")
        log("invitado → $url: ${out.lines().lastOrNull()}")
        return out.endsWith("LLEGA")
    }

    /** Quita el cliente de pruebas y, si la prueba falló antes de borrarlo, todo lo de invitados. */
    private fun cleanUp() {
        ssh("$IP_FULL link del wrtcg0 2>/dev/null; $IP_FULL netns del $NS 2>/dev/null; true")
        // Por si la prueba falló antes de borrarlo.
        if (ssh("uci show | grep -c $GUEST_PREFIX; true").lines().last() != "0") ssh(
            "for c in network dhcp firewall wireless; do " +
                "for s in \$(uci show \$c | sed -n \"s/^\$c\\.\\($GUEST_PREFIX[a-z0-9_]*\\)=.*/\\1/p\"); do uci delete \$c.\$s; done; uci commit \$c; done; " +
                "/etc/init.d/network reload; /etc/init.d/firewall reload; /etc/init.d/dnsmasq restart; wifi reload; sleep 3",
        )
        // La interfaz tarda un momento en desaparecer después de recargar la red.
        ssh("for i in 1 2 3 4 5 6 7 8 9 10; do ip -br link | grep -q wrtcguest || break; sleep 1; done")
    }

    @Test
    fun `los invitados tienen internet pero no entran al router ni a la casa, y al borrarlo no queda nada`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
        val wifi = WifiApi(client)
        val api = GuestWifiApi(client)
        val before = wifi.state()
        assumeTrue("El router no tiene Wi-Fi", before.radios.isNotEmpty())
        assumeTrue("Hay aparatos en el Wi-Fi del router: se les cortaría", before.networks.all { it.clients.isEmpty() })
        assumeTrue("El router ya tiene Wi-Fi de invitados: la prueba lo borraría", guestWifi(before) == null)
        assumeTrue("Hace falta ip-full y kmod-veth para el cliente de pruebas", ssh("[ -x $IP_FULL ] && echo si").endsWith("si"))

        val original = fingerprint()
        log("huella de network, dhcp, firewall y wireless antes: ${original.lines()}")
        val edit = GuestEdit(on = true, ssid = "WrtControl-invitados", key = "prueba2345")
        try {
            val t0 = System.currentTimeMillis()
            api.save(edit, phoneOnIt = false)
            log("creado y emitiendo en ${System.currentTimeMillis() - t0} ms")
            assertEquals(GuestWifi(edit.ssid, edit.key, on = true, clients = emptyList()), guestWifi(wifi.state()))
            val router = ssh("uci -q get network.$GUEST_INTERFACE.ipaddr")
            val net = router.substringBeforeLast('.')
            log("red de invitados: $router; redes del router: ${ssh("ip -4 -br addr | grep -v '^lo'").lines()}")
            assertTrue("el router emite la red de invitados", ssh("iwinfo | grep -c 'ESSID: \"${edit.ssid}\"'").toInt() >= 1)

            // El cliente de pruebas, enganchado al puente de invitados como un aparato más.
            ssh(
                "$IP_FULL netns add $NS; $IP_FULL link add wrtcg0 type veth peer name wrtcg1; $IP_FULL link set wrtcg1 netns $NS; " +
                    "$IP_FULL link set wrtcg0 master br-wrtcguest up; $IP_FULL netns exec $NS $IP_FULL link set lo up; " +
                    "$IP_FULL netns exec $NS $IP_FULL link set wrtcg1 up",
            )
            val lease = guest("udhcpc -i wrtcg1 -n -q -t 4 -s /bin/true")
            log("DHCP: ${lease.lines().takeLast(2)}")
            assertTrue("el router reparte direcciones a los invitados", "lease of $net." in lease)
            guest("$IP_FULL addr add $net.50/24 dev wrtcg1")
            guest("$IP_FULL route add default via $router")

            assertTrue("resuelve nombres con el router", "Address" in guest("nslookup openwrt.org $router").substringAfter("Name:", ""))
            assertTrue("sale a internet", reaches(httpTarget))
            assertFalse("no entra a la web del router", reaches("http://$router/"))
            assertFalse("ni por su dirección de casa", reaches("http://$vm/"))
            val upstream = ssh("ip route | sed -n 's/^default via \\([0-9.]*\\).*/\\1/p' | head -1")
            // Con doble NAT (el router cuelga de otro), la red de delante tampoco se ve. Solo dice algo
            // si el de delante tiene web a la que el propio router sí llega.
            val routerReaches = PRIVATE.any { upstream.startsWith(it) } &&
                ssh("wget -q -T 5 -O /dev/null http://$upstream/ && echo si || echo no").endsWith("si")
            log("router de delante: $upstream; el router llega a su web: $routerReaches")
            if (routerReaches) assertFalse("no llega a la red privada de delante del router ($upstream)", reaches("http://$upstream/"))

            // Apagado: deja de emitir, pero lo demás sigue para la próxima visita.
            api.save(edit.copy(on = false), phoneOnIt = false)
            assertEquals("0", ssh("iwinfo | grep -c 'ESSID: \"${edit.ssid}\"'; true").lines().last())
            assertEquals(GuestWifi(edit.ssid, edit.key, on = false, clients = emptyList()), guestWifi(wifi.state()))

            // Borrado desde la app: no queda nada suyo.
            ssh("$IP_FULL link del wrtcg0 2>/dev/null; $IP_FULL netns del $NS 2>/dev/null; true")
            api.remove(phoneOnIt = false)
            assertNull(guestWifi(wifi.state()))
        } finally {
            cleanUp()
        }
        assertEquals("no queda ninguna sección de invitados", "0", ssh("uci show | grep -c $GUEST_PREFIX; true").lines().last())
        assertEquals("la configuración queda como estaba", original, fingerprint())
        assertEquals("y no queda la interfaz", "0", ssh("ip -br link | grep -c wrtcguest; true").lines().last())
    }

    private companion object {
        const val IP_FULL = "/usr/libexec/ip-full"
        const val NS = "wrtc_guest"
        val PRIVATE = listOf("10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.", "172.2", "172.30.", "172.31.")
    }
}
