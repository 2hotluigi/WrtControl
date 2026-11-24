package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * El consumo sin instalar nada, contra un OpenWrt de verdad (ver Usage.kt): con el ACL de LuCI (el
 * de 24.10 también) la app lee los contadores de la salida a internet, son los mismos que da el kernel y suben al salir
 * tráfico por ella. Solo lee y manda unos pings: vale también para un router de casa.
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*UsageVmTest*" --rerun
 */
class UsageVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /** Ejecuta [command] en el router por la entrada de `sh -s` (así las comillas llegan tal cual desde Windows). */
    private fun ssh(command: String): String {
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", "sh -s"))
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(command) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(SSH_TIMEOUT_S, TimeUnit.SECONDS)
        return out.trim()
    }

    @Test
    fun `lo gastado desde el arranque sale de los contadores de la WAN`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))

        val interfaces = api.interfaces()
        val before = api.usage.sinceBoot(interfaces, api.systemInfo().uptime)
        assertNotNull("el ACL de LuCI deja leer los contadores de la WAN", before)
        before!!
        val kernel = ssh("cat /sys/class/net/${before.device}/statistics/rx_bytes /sys/class/net/${before.device}/statistics/tx_bytes")
            .lines().map { it.trim().toLong() }
        // Entre una lectura y otra pasa algo de tráfico: casi lo mismo, nunca menos.
        assertTrue("recibido: app ${before.receivedBytes}, kernel ${kernel[0]}", kernel[0] in before.receivedBytes..before.receivedBytes + SLACK)
        assertTrue("enviado: app ${before.sentBytes}, kernel ${kernel[1]}", kernel[1] in before.sentBytes..before.sentBytes + SLACK)

        // Unos pings grandes a la puerta de enlace de la WAN: salen por ella aunque nadie conteste.
        val gateway = ssh("ip -4 route show default | awk '{print \$3; exit}'")
        assumeTrue("la WAN tiene puerta de enlace", gateway.isNotBlank())
        ssh("ping -c $PINGS -s $PING_BYTES -W 1 $gateway >/dev/null 2>&1; true")

        val after = api.usage.sinceBoot(api.interfaces(), api.systemInfo().uptime)!!
        assertEquals(before.device, after.device)
        assertTrue("enviado subió ${after.sentBytes - before.sentBytes} bytes", after.sentBytes - before.sentBytes >= PINGS * PING_BYTES)
        assertTrue(after.sinceSeconds >= before.sinceSeconds)
    }

    /**
     * vnStat desde la app (ver Vnstat.kt): se instala, mira la WAN, guarda en la flash y da su
     * historial; el crontab y cron quedan como estaban. Instala un paquete: solo en una máquina
     * virtual.
     */
    @Test
    fun `vnStat - se instala, guarda en la flash y da el historial de la WAN`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeTrue("Solo en una máquina virtual", ssh("cat /sys/class/dmi/id/sys_vendor 2>/dev/null").let { "QEMU" in it || "innotek" in it })
        val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val interfaces = api.interfaces()
        val device = wanDevice(interfaces)
        assumeTrue("la VM tiene salida a internet", device != null)
        val crontab = ssh(CRONTAB_STATE)
        val cron = ssh("pidof crond >/dev/null && echo en-marcha || echo parado")

        when (api.usage.recorder(interfaces, api.systemInfo().root?.avail)) {
            is Recorder.Missing -> api.usage.install(interfaces)
            Recorder.NeedsSetup -> api.usage.setUp(interfaces)
            is Recorder.Ready, null -> Unit
        }
        assertTrue("guarda en la flash cada hora", vnstatKeepsData(ssh("/usr/bin/vnstat --showconfig")))
        assertTrue("mira la WAN", device!! in ssh("uci -q get vnstat.@vnstat[0].interface"))
        assertEquals("el crontab, como estaba", crontab, ssh(CRONTAB_STATE))
        assertEquals("cron, como estaba", cron, ssh("pidof crond >/dev/null && echo en-marcha || echo parado"))

        // Tráfico por la WAN y que vnStat lo vuelque a la base (sin esperar a la hora).
        val gateway = ssh("ip -4 route show default | awk '{print \$3; exit}'")
        ssh("ping -c $PINGS -s $PING_BYTES -W 1 $gateway >/dev/null 2>&1; sleep $VNSTAT_UPDATE_S; kill -HUP \$(pidof vnstatd); sleep 2")
        val recorder = api.usage.recorder(api.interfaces(), api.systemInfo().root?.avail)
        assertTrue("listo: $recorder", recorder is Recorder.Ready)
        val hours = (recorder as Recorder.Ready).history.hours
        assertTrue("la hora actual lleva lo enviado: $hours", hours.isNotEmpty() && hours.last().sentBytes >= PINGS * PING_BYTES)

        // Otra vez no cambia nada.
        api.usage.setUp(api.interfaces())
        assertEquals(crontab, ssh(CRONTAB_STATE))
    }

    /**
     * nlbwmon desde la app (ver Nlbwmon.kt): se instala, guarda en la flash cada hora y cuenta lo
     * que gasta el cliente de pruebas, sin contar al propio router. Instala un paquete: solo en una
     * máquina virtual, y con el cliente de pruebas (tools/vm/netns-client.sh).
     */
    @Test
    fun `nlbwmon - se instala, guarda en la flash y cuenta cada aparato`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeTrue("Solo en una máquina virtual", ssh("cat /sys/class/dmi/id/sys_vendor 2>/dev/null").let { "QEMU" in it || "innotek" in it })
        assumeTrue("Hace falta el cliente de pruebas", ssh("/usr/libexec/ip-full netns list 2>/dev/null").contains("wrtc_client"))
        val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val interfaces = api.interfaces()
        val free = api.systemInfo().root?.avail

        when (api.deviceUsage.recorder(interfaces, free)) {
            is DeviceRecorder.Missing -> api.deviceUsage.install()
            DeviceRecorder.NeedsSetup -> api.deviceUsage.setUp()
            is DeviceRecorder.Ready -> Unit
        }
        assertEquals(NLBW_DIR, ssh("uci -q get nlbwmon.@nlbwmon[0].database_directory"))
        assertEquals("1h", ssh("uci -q get nlbwmon.@nlbwmon[0].commit_interval"))
        assertEquals("en marcha", ssh("pidof nlbwmon >/dev/null && echo en-marcha || echo parado").replace('-', ' '))

        // Tráfico del cliente hacia internet, y que nlbwmon lo cuente (refresh_interval 30 s). Con TCP: el
        // ping no lo cuenta. Y a una dirección pública: nlbwmon descarta lo que va de una red local a
        // otra, y el PC de la CI (10.0.3.2, WRTCONTROL_HTTP_TARGET) está en 10.0.0.0/8. La VM llega a
        // downloads.openwrt.org: de ahí instala los paquetes. Por IP, resuelta en el router: el cliente de
        // pruebas no tiene DNS. Sin el nombre, el servidor contesta con un error, pero el tráfico cuenta.
        val ip = ssh("nslookup $INTERNET_HOST 2>/dev/null | awk '/^Address/ {print \$NF}' | grep -v : | tail -1")
        assumeTrue("el router resuelve $INTERNET_HOST", ip.matches(Regex("[0-9.]+")))
        val fetch = "/usr/libexec/ip-full netns exec wrtc_client wget -q -O /dev/null http://$ip/ 2>/dev/null"
        ssh("for i in 1 2 3 4 5; do $fetch; done; sleep $NLBW_REFRESH_S")
        val recorder = api.deviceUsage.recorder(api.interfaces(), free)
        assertTrue("listo: $recorder", recorder is DeviceRecorder.Ready)
        val devices = (recorder as DeviceRecorder.Ready).devices
        val client = devices.firstOrNull { it.mac == CLIENT_MAC }
        assertTrue("el cliente de pruebas gasta: $devices", client != null && client.receivedBytes > 0 && client.sentBytes > 0)
        val routerMacs = ssh("cat /sys/class/net/*/address").lines().map { it.trim().uppercase() }.toSet() - CLIENT_MAC
        assertTrue("el router no sale como un aparato: $devices", devices.none { it.mac in routerMacs })
    }

    private companion object {
        /** Cada cuánto lee vnStat los contadores (UpdateInterval). */
        const val VNSTAT_UPDATE_S = 25

        /** Lo que tarda nlbwmon en contar una conexión abierta (refresh_interval), con margen. */
        const val NLBW_REFRESH_S = 35
        const val CLIENT_MAC = "02:00:00:00:00:99"
        const val INTERNET_HOST = "downloads.openwrt.org"

        /** El crontab de root tal cual, o que no existe (no es lo mismo que vacío: con él, cron arranca). */
        const val CRONTAB_STATE = "cat /etc/crontabs/root 2>/dev/null || echo sin-crontab"
        const val SSH_TIMEOUT_S = 30L
        const val PINGS = 20
        const val PING_BYTES = 1000

        /** Lo que puede pasar por la WAN de una VM entre la lectura de la app y la del kernel. */
        const val SLACK = 5L * 1024 * 1024
    }
}
