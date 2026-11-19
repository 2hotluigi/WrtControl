package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * El informe de un router de verdad (ver DeviceReport.kt): no lleva sus MAC ni sus direcciones de
 * la WAN, la revisión final no encuentra nada, y trae lo que se lee de él. Solo lee. Lo deja en
 * app/build/device-report-vm.json para mirarlo.
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*DeviceReportVmTest*" --rerun
 */
class DeviceReportVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /** Ejecuta [command] en el router, por la entrada de `sh -s` (ver HomeNetworkVmTest). */
    private fun ssh(command: String): String {
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", "sh -s"))
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(command) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(SSH_TIMEOUT_S, TimeUnit.SECONDS)
        return out.trim()
    }

    @Test
    fun `el informe de un router de verdad no lleva sus datos`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))

        val report = api.report.build("prueba")
        File("build/device-report-vm.json").writeText(report)

        // Las MAC de sus tarjetas (menos las vacías) y las direcciones de su WAN.
        val macs = ssh("cat /sys/class/net/*/address").lines().map { it.trim() }.filter { it.isNotEmpty() && it != "00:00:00:00:00:00" }
        val wan = ssh(". /lib/functions/network.sh; network_get_ipaddr a wan; network_get_ipaddrs6 b wan6; echo \$a \$b")
            .split(' ').filter { it.isNotBlank() }
        assumeTrue("El router tiene tarjetas de red", macs.isNotEmpty())
        val found = (macs + wan).filter { report.contains(it, ignoreCase = true) && !it.startsWith("10.") && !it.startsWith("192.168.") }
        assertTrue("en el informe: $found", found.isEmpty())
        assertTrue(ReportAnonymizer().leaks(report).isEmpty())

        listOf("system.board", "network.interface.dump", "\"network\"", "\"firewall\"", "\"acl\"", "\"objects\"")
            .forEach { assertTrue("falta $it", report.contains(it)) }
    }

    private companion object {
        const val SSH_TIMEOUT_S = 60L
    }
}
