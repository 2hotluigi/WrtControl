package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Prueba manual contra un OpenWrt de verdad (la VM de VirtualBox), con tráfico real.
 * Necesita un cliente en un namespace de red del router con la MAC [CLIENT_MAC]
 * (ver el README) y acceso por SSH sin contraseña. Se activa así:
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*VmIntegrationTest*"
 *
 * Deja el router sin secciones de WrtControl al terminar.
 */
class VmIntegrationTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    private fun ssh(command: String): String {
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", command))
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return out.trim()
    }

    /** Respuestas de ping recibidas por el cliente hasta ahora. */
    private fun replies(): Int = ssh("grep -c 'bytes from' /tmp/wrtc_ping.log || true").lines().last().toInt()

    /** Respuestas que llegan durante [ms] milisegundos (el ping va a una por segundo). */
    private fun repliesDuring(ms: Long): Int {
        val before = replies()
        Thread.sleep(ms)
        return replies() - before
    }

    /** Conexión TCP nueva. /cdn-cgi/trace responde por IP sin redirigir (el cliente no tiene DNS). */
    private fun newConnectionWorks(): Boolean =
        ssh("$IP_FULL netns exec wrtc_client wget -q -T 4 -O /dev/null http://1.1.1.1/cdn-cgi/trace && echo OK || echo FALLO").endsWith("OK")

    private fun startPing() = ssh(
        "kill \$(cat /tmp/wrtc_ping.pid 2>/dev/null) 2>/dev/null; " +
            "(setsid $IP_FULL netns exec wrtc_client ping 1.1.1.1 > /tmp/wrtc_ping.log 2>&1 & echo \$! > /tmp/wrtc_ping.pid); sleep 3",
    )

    private fun stopPing() = ssh("kill \$(cat /tmp/wrtc_ping.pid) 2>/dev/null; rm -f /tmp/wrtc_ping.pid")

    private fun log(msg: String) = println("[${LocalTime.now().withNano(0)}] $msg")

    @Test
    fun `pausa y horario con trafico real`() = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", ""))
        client.login(Credentials("root", ""))
        val api = ParentalApi(client, log = ::log)

        api.update { _, _ -> emptyList() } // punto de partida limpio
        assertTrue(client.loadedFirewall()!!.wrtcontrolRules.isEmpty())

        // Ping continuo: ICMP queda como flujo "establecido" en conntrack, como un vídeo o un juego.
        startPing()
        assertTrue("el cliente debe tener internet al empezar", repliesDuring(3000) >= 2)
        assertTrue(newConnectionWorks())

        val profile = Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC))
        var t0 = System.currentTimeMillis()
        api.update { profiles, _ -> profiles + profile.copy(pause = Pause.Indefinite) }
        log("pausa aplicada y confirmada en ${System.currentTimeMillis() - t0} ms")

        val loaded = client.loadedFirewall()!!
        assertEquals(listOf("WrtControl · Prueba VM · pausa"), loaded.wrtcontrolRules)
        assertEquals("las dos reglas de rechazo de la cadena fija", 2, loaded.blockRules)

        Thread.sleep(1500)
        val duringPause = repliesDuring(5000)
        log("respuestas durante 5 s de pausa: $duringPause")
        assertEquals("la pausa debe cortar también el flujo ya establecido", 0, duringPause)
        assertTrue("y las conexiones nuevas", !newConnectionWorks())

        t0 = System.currentTimeMillis()
        api.update { profiles, _ -> profiles.map { it.copy(pause = null) } }
        log("reanudado en ${System.currentTimeMillis() - t0} ms")
        Thread.sleep(1500)
        assertTrue("al reanudar vuelve el tráfico", repliesDuring(4000) >= 2)

        // Horario que empieza ahora mismo (hora del router) y acaba en 10 minutos.
        val routerNow = api.load().routerNow
        val now = routerNow.toLocalTime().withSecond(0).withNano(0)
        val schedule = Schedule("vmnow1", setOf(routerNow.dayOfWeek), now, now.plusMinutes(10))
        api.update { profiles, _ -> profiles.map { it.copy(schedules = listOf(schedule)) } }
        val snapshot = api.load()
        log("horario ${schedule.start}–${schedule.end}, estado: ${snapshot.profiles.single().access(snapshot.routerNow)}")
        log("reglas: ${client.loadedFirewall()!!.wrtcontrolRules}")
        Thread.sleep(1500)
        assertEquals("el horario en curso también corta el flujo establecido", 0, repliesDuring(5000))

        // Horario que no incluye ahora: vuelve el acceso.
        val later = schedule.copy(start = now.plusHours(2), end = now.plusHours(3))
        api.update { profiles, _ -> profiles.map { it.copy(schedules = listOf(later)) } }
        Thread.sleep(1500)
        assertTrue("fuera del horario hay acceso", repliesDuring(4000) >= 2)

        api.update { _, _ -> emptyList() }
        stopPing()
        val clean = client.loadedFirewall()!!
        assertTrue(clean.wrtcontrolRules.isEmpty())
        assertEquals("la cadena puede quedar, pero vacía", 0, clean.blockRules)
        assertTrue(ssh("uci show firewall | grep -c wrtc_ || true").lines().last() == "0")
        log("router limpio")
    }

    @Test
    fun `horarios con la zona horaria de Mexico`() = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", ""))
        client.login(Credentials("root", ""))
        val api = ParentalApi(client, log = { if ("recargado=false" !in it) log(it) })
        api.update { _, _ -> emptyList() }

        try {
            api.setTimezone("America/Mexico_City", expectedOffsetSeconds = -21_600)
            val tz = api.load()
            log("zona del router: ${tz.routerZone} ${formatUtcOffset(tz.routerOffsetSeconds!!)}; hora local ${tz.routerNow}")
            assertEquals(-21_600, tz.routerOffsetSeconds)
            startPing()

            // Franja que incluye la hora local actual: bloquea.
            val local = tz.routerNow.withSecond(0).withNano(0)
            val now = Schedule("mxnow1", setOf(local.dayOfWeek), local.toLocalTime(), local.toLocalTime().plusMinutes(10))
            val profile = Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC), schedules = listOf(now))
            api.update { _, _ -> listOf(profile) }
            Thread.sleep(1500)
            assertEquals("la franja local en curso debe bloquear", 0, repliesDuring(4000))

            // La misma hora pero en UTC (6 h después en hora local): si el router confundiera
            // hora local con UTC, bloquearía ahora. No debe.
            val utcLike = local.plusHours(6)
            val decoy = Schedule("mxnow1", setOf(utcLike.dayOfWeek), utcLike.toLocalTime(), utcLike.toLocalTime().plusMinutes(10))
            api.update { _, _ -> listOf(profile.copy(schedules = listOf(decoy))) }
            Thread.sleep(1500)
            assertTrue("una franja en otra hora local no bloquea", repliesDuring(4000) >= 2)
            log("con la zona de México, ${now.start}–${now.end} bloqueó y ${decoy.start}–${decoy.end} no")
        } finally {
            api.update { _, _ -> emptyList() }
            stopPing()
            api.setTimezone("UTC", expectedOffsetSeconds = 0)
            log("router de vuelta en ${api.load().routerZone}")
        }
        assertEquals("UTC", api.load().routerZone)
    }

    /** Respuesta de nslookup desde el cliente: "NXDOMAIN", la IP o el error. */
    private fun resolveFromClient(domain: String, server: String): String {
        val out = ssh("$IP_FULL netns exec wrtc_client nslookup -type=a $domain $server 2>&1")
        return when {
            "NXDOMAIN" in out -> "NXDOMAIN"
            out.lines().any { it.startsWith("Name:") } -> out.lines().last { it.startsWith("Address") }.substringAfter(":").trim()
            else -> "ERROR: ${out.lines().lastOrNull { it.isNotBlank() }}"
        }
    }

    @Test
    fun `filtro de contenido por perfil`() = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", ""))
        client.login(Credentials("root", ""))
        val api = ParentalApi(client, log = { if ("recargado=false" !in it) log(it) })
        api.update { _, _ -> emptyList() }
        val routers = listOf("192.168.56.2", "8.8.8.8", "fd27:71c3:f0a8::1") // IPv4, DNS externo e IPv6

        try {
            assertTrue("sin filtro resuelve", resolveFromClient("tiktok.com", "192.168.56.2").first().isDigit())

            val filter = ContentFilter(FilterLevel.Adult, setOf("tiktok"), listOf("wikipedia.org"))
            var t0 = System.currentTimeMillis()
            api.update { _, _ -> listOf(Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC), filter = filter)) }
            log("filtro aplicado y comprobado en ${System.currentTimeMillis() - t0} ms")

            for (server in routers) {
                for (domain in listOf("tiktok.com", "www.tiktok.com", "es.wikipedia.org", CANARY_DOMAIN)) {
                    assertEquals("$domain vía $server", "NXDOMAIN", resolveFromClient(domain, server))
                }
                val allowed = resolveFromClient("openwrt.org", server)
                assertTrue("openwrt.org vía $server debe resolver: $allowed", allowed.first().isDigit())
            }
            log("bloqueados por IPv4, por 8.8.8.8 y por IPv6; openwrt.org resuelve")
            val snapshot = api.load()
            assertEquals(true, snapshot.filterRunning(snapshot.profiles.single()))
            // El propio router y los demás equipos no se filtran.
            assertTrue(ssh("nslookup -type=a tiktok.com 127.0.0.1").lines().any { it.startsWith("Name:") })

            t0 = System.currentTimeMillis()
            api.update { profiles, _ -> profiles.map { it.copy(filter = ContentFilter()) } }
            log("filtro quitado en ${System.currentTimeMillis() - t0} ms")
            assertTrue("sin filtro vuelve a resolver", resolveFromClient("tiktok.com", "8.8.8.8").first().isDigit())
        } finally {
            api.update { _, _ -> emptyList() }
        }
        val running = ssh("ubus call service list '{\"name\":\"dnsmasq\"}' | grep -c wrtc_dns_ || true").lines().last()
        assertEquals("sin servidores DNS de WrtControl al terminar", "0", running)
    }

    private companion object {
        const val CLIENT_MAC = "02:00:00:00:00:99"

        /** Con setsid, `ip` puede resolver al applet de BusyBox, que no tiene `netns`. */
        const val IP_FULL = "/usr/libexec/ip-full"
    }
}
