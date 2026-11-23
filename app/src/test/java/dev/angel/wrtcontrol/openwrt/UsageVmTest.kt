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
 * El consumo sin instalar nada, contra un OpenWrt de verdad (ver Usage.kt): con el ACL de LuCI la
 * app lee los contadores de la salida a internet, son los mismos que da el kernel y suben al salir
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

    private companion object {
        const val SSH_TIMEOUT_S = 30L
        const val PINGS = 20
        const val PING_BYTES = 1000

        /** Lo que puede pasar por la WAN de una VM entre la lectura de la app y la del kernel. */
        const val SLACK = 5L * 1024 * 1024
    }
}
