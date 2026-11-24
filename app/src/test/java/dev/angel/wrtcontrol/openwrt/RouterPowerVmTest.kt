package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Reiniciar contra un OpenWrt de verdad. Que el ACL de root deja reiniciar y restablecer se mira en
 * cualquier router (solo lee). Reiniciar de verdad, solo en una VM de QEMU y si se pide con
 * `WRTCONTROL_VM_REBOOT=1` (la CI lo hace al final, porque se pierde el cliente de pruebas):
 * nunca en un router de casa. Restablecer no se lanza nunca: borraría la VM.
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*RouterPowerVmTest*" --rerun
 */
class RouterPowerVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    private fun ssh(command: String): String {
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", "sh", "-s"))
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(command) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun client(): UbusClient {
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        return UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
    }

    @Test
    fun `el ACL de LuCI deja reiniciar y restablecer`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val client = client()
        client.login(Credentials("root", password))
        val caps = Capabilities("", "", 0, null, null, client.acl(), null, emptyList())
        assertTrue("system.reboot", caps.canReboot)
        assertTrue("/sbin/firstboot -r -y", caps.canFactoryReset)
    }

    @Test
    fun `reiniciar apaga el router y vuelve con la misma contrasena`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        assumeTrue("Define WRTCONTROL_VM_REBOOT=1 para reiniciarla", System.getenv("WRTCONTROL_VM_REBOOT") == "1")
        assumeTrue("Solo en una VM de QEMU", "QEMU" in ssh("cat /sys/class/dmi/id/sys_vendor 2>/dev/null"))
        val client = client()
        val before = client.callAs<SystemInfo>("system", "info").uptime
        RouterPower(client).reboot()
        delay(10.seconds)
        // Como la app: la sesión se pierde y el cliente vuelve a entrar solo.
        var after: Long? = null
        withTimeout(5.minutes) {
            while (after?.let { it < before } != true) {
                delay(5.seconds)
                after = runCatching { client.callAs<SystemInfo>("system", "info").uptime }.getOrNull()
            }
        }
        println("Encendido antes: $before s; después: $after s")
        assertTrue(after!! < before)
    }
}
