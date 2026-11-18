package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * El horario del Wi-Fi contra un OpenWrt de verdad (como VmIntegrationTest): lo que la app escribe
 * en el crontab con el ACL de LuCI, que cron arranca con él y que al quitarlo queda lo de antes.
 * Usa una franja de madrugada del domingo (03:00 a 03:05) y deja el crontab y cron como estaban,
 * así que se puede lanzar también contra el router de la casa:
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*WifiScheduleVmTest*" --rerun
 */
class WifiScheduleVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /** Ejecuta [command] en el router; lo manda por la entrada estándar (sin comillas que se pierdan en Windows). */
    private fun ssh(command: String, input: String? = null): String {
        val script = if (input == null) command else "$command <<'WRTC_EOF'\n${input}WRTC_EOF\n"
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", "sh", "-s"))
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun crondRunning(): Boolean = ssh("pgrep crond >/dev/null && echo si || echo no") == "si"

    @Test
    fun `el horario se guarda en el crontab, arranca cron y se quita sin tocar lo demas`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = WifiScheduleApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))

        val existed = ssh("test -f $CRONTAB && echo si || echo no") == "si"
        val original = if (existed) ssh("cat $CRONTAB") + "\n" else ""
        val wasRunning = crondRunning()
        val foreign = "17 4 * * * true # prueba de WrtControl\n"
        val schedules = listOf(Schedule("0", setOf(DayOfWeek.SUNDAY), LocalTime.of(3, 0), LocalTime.of(3, 5)))
        try {
            // Una línea que no es de la app, para ver que se respeta.
            ssh("cat > $CRONTAB", original + foreign)
            assertEquals(emptyList<Schedule>(), api.read()?.schedules)

            api.save(schedules)
            val written = ssh("cat $CRONTAB")
            println(written)
            assertTrue("lo de antes sigue", written.contains(foreign.trim()))
            assertTrue(written.contains("0 3 * * 0 $WIFI_DOWN"))
            assertTrue(written.contains("5 3 * * 0 $WIFI_UP"))
            assertEquals(schedules, api.read()?.schedules)
            assertTrue("cron en marcha con el horario", crondRunning())
            assertEquals("cron arranca con el router", "si", ssh("/etc/init.d/cron enabled && echo si || echo no"))

            try {
                api.save(listOf(Schedule("0", DayOfWeek.entries.toSet(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)))
                fail("un horario sin Wi-Fi a ninguna hora no se guarda")
            } catch (_: UserFacingException) {
            }

            api.save(emptyList())
            assertEquals(original + foreign, ssh("cat $CRONTAB") + "\n")
            assertEquals(emptyList<Schedule>(), api.read()?.schedules)
        } finally {
            if (existed) ssh("cat > $CRONTAB", original) else ssh("rm -f $CRONTAB")
            ssh(if (wasRunning) "/etc/init.d/cron restart" else "/etc/init.d/cron stop")
        }
        assertEquals(wasRunning, crondRunning())
        if (!existed) assertFalse(ssh("test -f $CRONTAB && echo si || echo no") == "si")
    }
}
