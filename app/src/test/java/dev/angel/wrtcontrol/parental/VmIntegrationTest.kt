package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.concurrent.thread
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Prueba manual contra un OpenWrt de verdad (la VM de VirtualBox), con tráfico real.
 * Necesita un cliente en un namespace de red del router con la MAC [CLIENT_MAC]
 * (ver docs/desarrollo.md) y acceso por SSH sin contraseña. Se activa así:
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*VmIntegrationTest*"
 *
 * Deja el router sin secciones de WrtControl al terminar.
 */
class VmIntegrationTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    /** Contraseña de root (la VM y la CI no tienen; un router de verdad, sí). */
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /**
     * A dónde hace ping el cliente: algo al otro lado de la WAN, para que pase por el forward del router.
     * En la CI es el propio PC (10.0.3.2), porque los runners de GitHub no dejan salir ICMP a internet.
     */
    private val pingTarget: String = System.getenv("WRTCONTROL_PING_TARGET") ?: "1.1.1.1"

    /**
     * Para "una conexión TCP nueva funciona": por IP, porque el cliente no tiene DNS. /cdn-cgi/trace
     * responde sin redirigir al wget de 25.12, pero el de 24.10 recibe una redirección que no sabe
     * seguir; la CI usa un servidor HTTP en el PC, al otro lado de la WAN.
     */
    private val httpTarget: String = System.getenv("WRTCONTROL_HTTP_TARGET") ?: "http://1.1.1.1/cdn-cgi/trace"

    /**
     * Eco para las conexiones largas ya en marcha al otro lado de la WAN ("host puerto"). Sin
     * definir, se abre uno en este PC y el router llega por su pasarela WAN (en la NAT de
     * VirtualBox, el propio PC). Con un router de verdad, el de `netns-client.sh inet-up`:
     * "198.51.100.2 47101".
     */
    private val echoTarget: String? = System.getenv("WRTCONTROL_ECHO_TARGET")

    /**
     * MAC que las pruebas aprueban siempre al activar la cuarentena (separadas por comas): el PC
     * que lanza la prueba, si sale a internet por este router.
     */
    private val keepMacs: List<String> = System.getenv("WRTCONTROL_KEEP_MACS")?.split(',')
        ?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }.orEmpty()

    /** El cliente de pruebas es la .99 de la LAN del router (tools/vm/netns-client.sh). */
    private val clientIp: String get() = vm!!.substringBeforeLast('.') + ".99"

    /** Dirección ULA del router en la LAN, para preguntarle el DNS por IPv6. */
    private val routerIpv6: String by lazy {
        ssh("ip -6 addr show dev br-lan scope global").lines().map { it.trim() }
            .first { it.startsWith("inet6 fd") }.removePrefix("inet6 ").substringBefore('/')
    }

    /**
     * Los DNS a los que pregunta el cliente: el router por IPv4, uno externo y el router por IPv6.
     * Este último solo si el cliente tiene ULA: con la /64 de la WAN pasada a la LAN en modo relay
     * (IPv6 sin delegación de prefijo, como en el GL-MT6000 de casa), la LAN no la anuncia, y dnsmasq
     * no contesta a una dirección que no es de sus redes.
     */
    private val dnsServers: List<String> by lazy {
        val clientUla = ssh("$IP_FULL netns exec wrtc_client ip -6 addr show scope global").lines().any { it.trim().startsWith("inet6 fd") }
        if (!clientUla) log("el cliente no tiene ULA (¿relay IPv6?): el DNS del router por IPv6 no se prueba")
        listOfNotNull(vm!!, "8.8.8.8", routerIpv6.takeIf { clientUla })
    }

    /**
     * Ejecuta [command] en la VM. Con muchas conexiones seguidas, dropbear rechaza alguna de vez
     * en cuando (ssh termina con 255): se reintenta, para que eso no parezca un fallo del router.
     */
    private fun ssh(command: String): String {
        var out = ""
        repeat(4) { attempt ->
            val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", command))
                .redirectErrorStream(true).start()
            out = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            if (process.exitValue() != 255) return out.trim()
            log("ssh falló (intento ${attempt + 1}): ${out.trim().lines().lastOrNull()}")
            Thread.sleep(1000)
        }
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

    /** Conexión TCP nueva a [httpTarget]. */
    private fun newConnectionWorks(): Boolean =
        ssh("$IP_FULL netns exec wrtc_client wget -q -T 4 -O /dev/null $httpTarget && echo OK || echo FALLO").endsWith("OK")

    private fun startPing() = ssh(
        "kill \$(cat /tmp/wrtc_ping.pid 2>/dev/null) 2>/dev/null; " +
            "(setsid $IP_FULL netns exec wrtc_client ping $pingTarget > /tmp/wrtc_ping.log 2>&1 & echo \$! > /tmp/wrtc_ping.pid); sleep 3",
    )

    private fun stopPing() = ssh("kill \$(cat /tmp/wrtc_ping.pid) 2>/dev/null; rm -f /tmp/wrtc_ping.pid")

    private fun log(msg: String) = println("[${LocalTime.now().withNano(0)}] $msg")

    @Test
    fun `pausa y horario con trafico real`() = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
        client.login(Credentials("root", password))
        val api = ParentalApi(client, log = ::log)

        api.updateProfiles { _, _ -> emptyList() } // punto de partida limpio
        assertTrue(client.loadedFirewall()!!.wrtcontrolRules.isEmpty())

        // Ping continuo: ICMP queda como flujo "establecido" en conntrack, como un vídeo o un juego.
        startPing()
        assertTrue("el cliente debe tener internet al empezar", repliesDuring(3000) >= 2)
        assertTrue(newConnectionWorks())

        val profile = Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC))
        var t0 = System.currentTimeMillis()
        api.updateProfiles { profiles, _ -> profiles + profile.copy(pause = Pause.Indefinite) }
        log("pausa aplicada y confirmada en ${System.currentTimeMillis() - t0} ms")

        val loaded = client.loadedFirewall()!!
        assertEquals(listOf("WrtControl · Prueba VM · pausa"), loaded.wrtcontrolRules)
        assertEquals("cargó el fichero de reglas que pide la configuración", api.load().expectedInclude, loaded.includeVersion)

        Thread.sleep(1500)
        val duringPause = repliesDuring(5000)
        log("respuestas durante 5 s de pausa: $duringPause")
        assertEquals("la pausa debe cortar también el flujo ya establecido", 0, duringPause)
        assertTrue("y las conexiones nuevas", !newConnectionWorks())

        t0 = System.currentTimeMillis()
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(pause = null) } }
        log("reanudado en ${System.currentTimeMillis() - t0} ms")
        Thread.sleep(1500)
        assertTrue("al reanudar vuelve el tráfico", repliesDuring(4000) >= 2)

        // Horario que empieza ahora mismo (hora del router) y acaba en 10 minutos.
        val routerNow = api.load().routerNow
        val now = routerNow.toLocalTime().withSecond(0).withNano(0)
        val schedule = Schedule("vmnow1", setOf(routerNow.dayOfWeek), now, now.plusMinutes(10))
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(schedules = listOf(schedule)) } }
        val snapshot = api.load()
        log("horario ${schedule.start}–${schedule.end}, estado: ${snapshot.profiles.single().access(snapshot.routerNow)}")
        log("reglas: ${client.loadedFirewall()!!.wrtcontrolRules}")
        Thread.sleep(1500)
        assertEquals("el horario en curso también corta el flujo establecido", 0, repliesDuring(5000))

        // Horario que no incluye ahora: vuelve el acceso.
        val later = schedule.copy(start = now.plusHours(2), end = now.plusHours(3))
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(schedules = listOf(later)) } }
        Thread.sleep(1500)
        // Una segunda ventana por si la recarga del firewall de un router de verdad cae en la primera.
        assertTrue("fuera del horario hay acceso", repliesDuring(4000) >= 2 || repliesDuring(4000) >= 2)

        api.updateProfiles { _, _ -> emptyList() }
        stopPing()
        val clean = client.loadedFirewall()!!
        assertTrue(clean.wrtcontrolRules.isEmpty())
        assertNull("sin reglas propias cargadas", clean.includeVersion)
        assertTrue(ssh("uci show firewall | grep -c wrtc_ || true").lines().last() == "0")
        log("router limpio")
    }

    @Test
    fun `horarios con la zona horaria de Mexico`() = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
        client.login(Credentials("root", password))
        val api = ParentalApi(client, log = { if ("recargado=false" !in it) log(it) })
        api.updateProfiles { _, _ -> emptyList() }

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
            api.updateProfiles { _, _ -> listOf(profile) }
            Thread.sleep(1500)
            assertEquals("la franja local en curso debe bloquear", 0, repliesDuring(4000))

            // La misma hora pero en UTC (6 h después en hora local): si el router confundiera
            // hora local con UTC, bloquearía ahora. No debe.
            val utcLike = local.plusHours(6)
            val decoy = Schedule("mxnow1", setOf(utcLike.dayOfWeek), utcLike.toLocalTime(), utcLike.toLocalTime().plusMinutes(10))
            api.updateProfiles { _, _ -> listOf(profile.copy(schedules = listOf(decoy))) }
            Thread.sleep(1500)
            assertTrue("una franja en otra hora local no bloquea", repliesDuring(4000) >= 2)
            log("con la zona de México, ${now.start}–${now.end} bloqueó y ${decoy.start}–${decoy.end} no")
        } finally {
            api.updateProfiles { _, _ -> emptyList() }
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
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
        client.login(Credentials("root", password))
        val api = ParentalApi(client, log = { if ("recargado=false" !in it) log(it) })
        api.updateProfiles { _, _ -> emptyList() }
        val routers = dnsServers // IPv4, DNS externo e IPv6

        try {
            assertTrue("sin filtro resuelve", resolveFromClient("tiktok.com", vm!!).first().isDigit())

            val filter = ContentFilter(FilterLevel.Adult, setOf("tiktok"), listOf("wikipedia.org"))
            var t0 = System.currentTimeMillis()
            api.updateProfiles { _, _ -> listOf(Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC), filter = filter)) }
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
            // null: sin `service list` (24.10 con el ACL de LuCI) no se sabe; SafeApply ya lo comprobó con nslookup.
            assertTrue(snapshot.filterRunning(snapshot.profiles.single()) != false)
            // El propio router y los demás equipos no se filtran.
            assertTrue(ssh("nslookup -type=a tiktok.com 127.0.0.1").lines().any { it.startsWith("Name:") })

            t0 = System.currentTimeMillis()
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(filter = ContentFilter()) } }
            log("filtro quitado en ${System.currentTimeMillis() - t0} ms")
            assertTrue("sin filtro vuelve a resolver", resolveFromClient("tiktok.com", "8.8.8.8").first().isDigit())
        } finally {
            api.updateProfiles { _, _ -> emptyList() }
        }
        val running = ssh("ubus call service list | grep -c wrtc_dns_ || true").lines().last()
        assertEquals("sin servidores DNS de WrtControl al terminar", "0", running)
    }

    /** Salida completa de nslookup desde el cliente. */
    private fun nslookupFromClient(domain: String, server: String, type: String = "a"): String =
        ssh("$IP_FULL netns exec wrtc_client nslookup -type=$type $domain $server 2>&1")

    /**
     * ¿Llega el cliente a [url]? Cuenta como conectado aunque el servidor responda con un error o
     * una redirección que el cliente no sigue (no tiene DNS); como bloqueado, el reset o el rechazo.
     *
     * Las reglas de la app rechazan (reset), nunca descartan en silencio: un bloqueo falla al momento.
     * Si el fallo llega al agotar los 4 s de espera, no hubo ni respuesta ni rechazo, y lo normal es
     * que el servidor de internet haya tardado: se apunta qué hizo el router con esa conexión y se
     * repite, para no dar por bloqueado lo que solo fue lento. (El mensaje no los distingue: según la
     * versión, uclient-fetch dice "Failed to send request" para las dos cosas.)
     */
    private fun connects(url: String): Boolean {
        var out = ""
        repeat(NO_ANSWER_TRIES) { attempt ->
            val t0 = System.currentTimeMillis()
            out = ssh("$IP_FULL netns exec wrtc_client wget -T $WGET_TIMEOUT_SECONDS -O /dev/null --no-check-certificate $url 2>&1; true")
            val waited = System.currentTimeMillis() - t0 >= WGET_TIMEOUT_SECONDS * 1000
            log("wget $url: ${out.lines().filter { it.isNotBlank() }.takeLast(2)}")
            if (!waited || BLOCKED_MESSAGES.none { it in out }) return BLOCKED_MESSAGES.none { it in out }
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
            val seen = ssh("grep 'src=$clientIp ' /proc/net/nf_conntrack | grep -F 'dst=$host ' | tail -2 || true")
            log("sin respuesta (intento ${attempt + 1} de $NO_ANSWER_TRIES); conntrack: ${seen.lines().filter { it.isNotBlank() }}")
        }
        return BLOCKED_MESSAGES.none { it in out }
    }

    /** IP de openwrt.org (no es un resolutor DNS), resuelta por el router. */
    private fun webIp(): String = ssh("nslookup -type=a openwrt.org 127.0.0.1").lines()
        .dropWhile { !it.startsWith("Name:") }.first { it.startsWith("Address") }.substringAfter(":").trim()

    /** Marca de conntrack de las conexiones del cliente a [port] que contienen [filter] (estado, destino...). */
    private fun clientMarks(port: Int, filter: String): List<Long> =
        ssh("grep 'src=$clientIp ' /proc/net/nf_conntrack | grep ' dport=$port ' | grep -F '$filter' || true").lines()
            .mapNotNull { Regex("mark=(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }

    /**
     * Las marcas de [clientMarks] en cuanto aparezca [expected], o las últimas a los 10 s. En QEMU/KVM
     * la entrada de una conexión recién cerrada a veces no se ve en /proc hasta un momento después.
     */
    private fun clientMarksWith(expected: Long, filter: String): List<Long> {
        var marks = clientMarks(80, filter)
        repeat(10) {
            if (expected !in marks) {
                Thread.sleep(1000)
                marks = clientMarks(80, filter)
            }
        }
        return marks
    }

    /** Las entradas de conntrack del cliente, para los mensajes de error. */
    private fun clientConntrack(): List<String> = ssh("grep 'src=$clientIp ' /proc/net/nf_conntrack || true").lines()

    /**
     * Comprueba el fichero de reglas en uso con el analizador de nftables 1.0.2 de OpenWrt 22.03,
     * si está en la VM (/tmp/nft102, ver docs/desarrollo.md). Se envuelve en una tabla como hace fw4.
     */
    private fun checkWith1_0_2(path: String) {
        if (ssh("test -x /tmp/nft102/usr/sbin/nft && echo SI || echo NO").lines().last() != "SI") {
            log("sin nft 1.0.2 en la VM: no se comprueba la sintaxis de 22.03")
            return
        }
        // Esta copia de nft analiza bien pero se cuelga (segfault) al hablar con el kernel de 25.12:
        // basta con ver que el analizador llega al final del fichero sin errores. Sin comillas
        // dobles: Java en Windows las estropea al pasar el comando a ssh.
        val out = ssh(
            "{ echo 'table inet wrtc_chk {'; cat $path; echo '}'; } > /tmp/wrtc_chk.nft; " +
                "(LD_LIBRARY_PATH=/tmp/nft102/usr/lib /tmp/nft102/usr/sbin/nft --debug=parser -c -f /tmp/wrtc_chk.nft > /tmp/wrtc_chk.log 2>&1) 2>/dev/null; " +
                "echo RESULT err=\$(grep -cE 'Error|syntax error' /tmp/wrtc_chk.log) fin=\$(grep -c 'token .end of file' /tmp/wrtc_chk.log)",
        )
        val result = out.lines().first { it.startsWith("RESULT") }
        val (errors, end) = Regex("err=([0-9]+) fin=([0-9]+)").find(result)!!.destructured
        assertTrue("el analizador de nftables 1.0.2 acepta $path: $result", errors == "0" && end.toInt() >= 1)
        log("sintaxis comprobada con nftables 1.0.2")
    }

    /**
     * El eco de las conexiones largas y su "host puerto": el de [echoTarget] o uno en este PC, al que
     * el router llega por su pasarela WAN. El ServerSocket es null si el eco no es de aquí.
     */
    private fun openEcho(): Pair<ServerSocket?, String> {
        echoTarget?.let { return null to it }
        val echo = startEcho()
        val gateway = ssh("ip -4 route show default").substringAfter("via ").substringBefore(' ').trim()
        return echo to "$gateway ${echo.localPort}"
    }

    /** En un router de verdad, con más tráfico, fw4 puede tardar unos segundos más en dejarlo pasar. */
    private fun internetBackWithin(ms: Long): Boolean {
        val start = System.currentTimeMillis()
        var back = newConnectionWorks()
        while (!back && System.currentTimeMillis() - start < ms) {
            Thread.sleep(1000)
            back = newConnectionWorks()
        }
        log("internet de vuelta tras ${System.currentTimeMillis() - start} ms")
        return back
    }

    /**
     * Servidor de eco en este PC, para conexiones largas: la VM llega a él por su pasarela WAN
     * (en la NAT de VirtualBox, 10.0.x.2 es el propio PC). Los servidores públicos cierran las
     * conexiones inactivas al minuto, y aquí hacen falta más de 90 s. Responde "OK <línea>".
     */
    private fun startEcho(): ServerSocket {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val writer = s.getOutputStream().bufferedWriter()
                        while (true) {
                            val line = runCatching { reader.readLine() }.getOrNull() ?: break
                            writer.write("OK $line\n")
                            writer.flush()
                        }
                    }
                }
            }
        }
        return server
    }

    /**
     * Abre en el cliente una conexión TCP al eco que manda "antes" a los [first] segundos y
     * "despues" a los [second] (contados desde que se abre), y guarda lo que recibe en [out].
     */
    private fun echoConnection(target: String, out: String, first: Int, second: Int) = ssh(
        "rm -f $out; ( (sleep $first; echo antes; sleep ${second - first}; echo despues; sleep 3) | " +
            "$IP_FULL netns exec wrtc_client nc $target > $out 2>&1 ) > /dev/null 2>&1 &",
    )

    /**
     * Como [echoConnection], pero la segunda línea sale cuando la prueba la manda ([sendLine]) y no
     * a una hora fija: así sale después de la última recarga del firewall, que pone sus contadores a
     * cero (si sale antes, la regla la corta pero el contador ya no lo cuenta).
     */
    private fun echoConnectionOnDemand(target: String, out: String, fifo: String) = ssh(
        "rm -f $out $fifo; mkfifo $fifo; ( (sleep 1; echo antes; cat $fifo; sleep 3) | " +
            "$IP_FULL netns exec wrtc_client nc $target > $out 2>&1 ) > /dev/null 2>&1 &",
    )

    /** En segundo plano: si ya nadie lee la FIFO, el echo no se queda esperando. */
    private fun sendLine(fifo: String, line: String) = ssh("(echo $line > $fifo &) ; sleep 1; rm -f $fifo")

    @Test
    fun `proteccion con trafico real`() = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
        client.login(Credentials("root", password))
        val api = ParentalApi(client, log = { if ("recargado=false" !in it) log(it) })
        api.update { _, _ -> ParentalConfig(emptyList()) }
        assertTrue("el cliente debe tener internet al empezar", newConnectionWorks())
        // http://1.1.1.1 redirige a https://1.1.1.1, que es un resolutor DNS cifrado: con filtro se
        // bloquea a propósito. Para "la web sigue" se usa openwrt.org.
        val web = webIp()
        val (echo, echoAddress) = openEcho()
        val echoPort = echoAddress.substringAfter(' ').toInt()

        try {
            // 1. Cuarentena: sin aprobar no sale a internet, pero el router le sigue dando DNS.
            var t0 = System.currentTimeMillis()
            api.update { c, _ -> c.copy(settings = ParentalSettings(quarantine = true, approved = listOf("02:00:00:00:00:01") + keepMacs)) }
            log("cuarentena activada en ${System.currentTimeMillis() - t0} ms")
            val path = ssh("uci -q get firewall.$INCLUDE_SECTION.path").lines().last()
            assertEquals("un solo fichero de reglas, el que está en uso", path, ssh("ls $INCLUDE_DIR/wrtcontrol*.nft").lines().single())
            checkWith1_0_2(path)
            assertTrue("sin aprobar no hay internet", !newConnectionWorks())
            assertTrue("pero el router le resuelve", resolveFromClient("openwrt.org", vm!!).first().isDigit())

            startPing()
            api.update { c, _ -> c.copy(settings = c.settings.copy(approved = c.settings.approved + CLIENT_MAC)) }
            Thread.sleep(1500)
            assertTrue("aprobado tiene internet", repliesDuring(3000) >= 2 && newConnectionWorks())
            api.update { c, _ -> c.copy(settings = c.settings.copy(approved = c.settings.approved - CLIENT_MAC)) }
            Thread.sleep(1500)
            assertEquals("quitar la aprobación corta también lo abierto", 0, repliesDuring(4000))
            stopPing()
            // Asignarlo a un perfil también lo aprueba, y al sacarlo del perfil sigue aprobado.
            api.update { c, _ -> c.copy(profiles = listOf(Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC)))) }
            assertTrue("con perfil tiene internet", newConnectionWorks())
            api.update { c, _ -> c.copy(profiles = emptyList()) }
            assertTrue("al borrar su perfil sigue aprobado", CLIENT_MAC in api.load().settings.approved && newConnectionWorks())
            api.update { _, _ -> ParentalConfig(emptyList()) }
            log("cuarentena comprobada")

            // 2. Perfil con filtro: búsqueda segura, YouTube restringido, DNS cifrado por IP y router protegido.
            // Antes, una conexión TCP abierta: responde ahora, y al endurecer el filtro debe cortarse.
            echoConnectionOnDemand(echoAddress, "/tmp/wrtc_nc.out", NC_FIFO)
            Thread.sleep(3000)
            assertTrue("la conexión de antes funciona", "OK antes" in ssh("cat /tmp/wrtc_nc.out"))
            // conntrack puede tardar un momento en mostrarla: se espera un poco antes de mirar la marca.
            var openMarks = clientMarks(echoPort, "ESTABLISHED")
            repeat(5) { if (openMarks.isEmpty()) { Thread.sleep(1000); openMarks = clientMarks(echoPort, "ESTABLISHED") } }
            if (openMarks.isEmpty()) log("conntrack: ${ssh("grep 'src=$clientIp ' /proc/net/nf_conntrack | grep -v 'dport=53 ' || true").lines().takeLast(5)}")
            assertEquals("y aún no tiene época", listOf(0L), openMarks.distinct())
            val filter = ContentFilter(safeSearch = true, youtube = YoutubeMode.Moderate, services = setOf("tiktok"))
            t0 = System.currentTimeMillis()
            api.update { c, _ -> c.copy(profiles = listOf(Profile("vmtest", "Prueba VM", listOf(CLIENT_MAC), filter = filter, protectRouter = true))) }
            log("filtro con protección aplicado en ${System.currentTimeMillis() - t0} ms")
            checkWith1_0_2(ssh("uci -q get firewall.$INCLUDE_SECTION.path").lines().last())

            for (server in dnsServers) {
                assertEquals("Google seguro vía $server", "216.239.38.120", resolveFromClient("www.google.com", server))
                assertEquals("Google México seguro vía $server", "216.239.38.120", resolveFromClient("www.google.com.mx", server))
                assertEquals("YouTube moderado vía $server", "216.239.38.119", resolveFromClient("www.youtube.com", server))
                assertEquals("TikTok bloqueado vía $server", "NXDOMAIN", resolveFromClient("tiktok.com", server))
            }
            assertTrue("AAAA también segura", "2001:4860:4802:32::78" in nslookupFromClient("www.google.com", vm!!, "aaaa"))
            assertTrue("Bing estricto", "strict.bing.com" in nslookupFromClient("www.bing.com", vm!!))
            assertTrue("DuckDuckGo seguro", "safe.duckduckgo.com" in nslookupFromClient("duckduckgo.com", vm!!))
            assertTrue("otras webs siguen", resolveFromClient("openwrt.org", "8.8.8.8").first().isDigit())
            log("búsqueda segura y YouTube restringido por IPv4, 8.8.8.8 e IPv6")

            assertTrue("DNS cifrado por IP bloqueado", !connects("https://1.1.1.1/"))
            assertTrue("DNS cifrado de Google bloqueado", !connects("https://8.8.8.8/"))
            assertTrue("también en otros puertos de esas IP", !connects("http://1.1.1.1/"))
            assertTrue("la web normal sigue", connects("https://$web/"))
            assertTrue("no abre LuCI", !connects("http://$vm/"))
            assertTrue("el router le sigue resolviendo", resolveFromClient("openwrt.org", vm!!).first().isDigit())
            log("DNS cifrado por IP y acceso al router bloqueados")
            // Los contadores vuelven a cero con cada recarga del firewall: se miran ahora.
            val counted = api.load()
            assertTrue("se contaron los intentos de DNS cifrado", counted.dnsBypassBlocked(counted.profiles.single()) > 0)

            // El primer endurecimiento abre una ventana larga (la caché del dispositivo venía del DNS
            // principal). Uno más, ya con el filtro puesto, la abre de 90 s: la época nueva es la
            // marca libre y la ventana conserva la suya.
            val firstWindow = api.load().profiles.single()
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(filter = it.filter.copy(domains = listOf("example.org"))) } }
            val second = api.load().profiles.single()
            log("épocas: ${firstWindow.cutEpoch}/${firstWindow.cutWindow} -> ${second.cutEpoch}/${second.cutWindow}")
            assertTrue("la época nueva no es la marca de la ventana", second.cutEpoch != firstWindow.cutWindow!!.value)
            assertEquals("la ventana conserva su marca", firstWindow.cutWindow!!.value, second.cutWindow!!.value)

            // Una conexión abierta durante la ventana del corte (como la de un dispositivo que
            // reconecta con la IP que tenía en caché) funciona al principio y se corta al acabarla.
            echoConnection(echoAddress, "/tmp/wrtc_nc2.out", first = 2, second = 100)
            val windowConnectionAt = System.currentTimeMillis()
            // La conexión abierta antes del filtro manda ahora su segunda línea, ya sin más recargas.
            sendLine(NC_FIFO, "despues")
            Thread.sleep(20_000)
            val nc = ssh("cat /tmp/wrtc_nc.out")
            log("conexión abierta antes del filtro: ${nc.lines().filter { it.isNotBlank() }}")
            assertTrue("la conexión abierta antes del filtro se cortó", "OK antes" in nc && "OK despues" !in nc)
            // El contador puede tardar un momento en reflejar el corte: se mira unos segundos.
            var afterCut = api.load()
            repeat(5) {
                if ((afterCut.firewall!!.ownCounters["corte vmtest"] ?: 0) == 0L) {
                    Thread.sleep(1000)
                    afterCut = api.load()
                }
            }
            val counters = afterCut.firewall!!.ownCounters
            assertTrue("la regla de corte actuó: $counters", (counters["corte vmtest"] ?: 0) > 0)
            val window = afterCut.profiles.single().cutWindow!!
            log("ventana del corte: marca ${window.value} hasta ${window.until}")
            assertTrue("las conexiones nuevas funcionan", connects("http://$web/"))
            val marksInWindow = clientMarksWith(window.value.toLong() shl 14, "dst=$web ")
            assertTrue(
                "y en la ventana llevan su marca: $marksInWindow; conntrack: ${clientConntrack()}",
                (window.value.toLong() shl 14) in marksInWindow,
            )
            // Al acabar la ventana (y antes de cualquier horario, que también cortaría), la conexión
            // abierta en ella se corta; las nuevas ya llevan la época del perfil.
            // Se mira el contador de la regla de corte: el servidor puede haber cerrado la conexión
            // inactiva por su cuenta, pero el paquete que manda el cliente al final pasa por la regla.
            Thread.sleep((windowConnectionAt + 95_000 - System.currentTimeMillis()).coerceAtLeast(0))
            val cutsBefore = api.load().firewall!!.ownCounters["corte vmtest"] ?: 0
            Thread.sleep((windowConnectionAt + 106_000 - System.currentTimeMillis()).coerceAtLeast(0))
            val cutsAfter = api.load().firewall!!.ownCounters["corte vmtest"] ?: 0
            val nc2 = ssh("cat /tmp/wrtc_nc2.out")
            log("conexión abierta en la ventana: ${nc2.lines().filter { it.isNotBlank() }}; cortes $cutsBefore -> $cutsAfter")
            assertTrue("en la ventana funcionaba", "OK antes" in nc2)
            assertTrue("y al acabarla se cortó", "OK despues" !in nc2 && cutsAfter > cutsBefore)
            assertTrue("después de la ventana se conecta", connects("http://$web/"))
            val epochMark = afterCut.profiles.single().cutEpoch.toLong() shl 14
            val marksAfter = clientMarksWith(epochMark, "dst=$web ")
            log("marcas tras la ventana: $marksAfter (época $epochMark); conntrack: ${ssh("grep 'src=$clientIp ' /proc/net/nf_conntrack | grep ' dport=80 ' || true").lines().takeLast(4)}")
            assertTrue("con la época del perfil: $marksAfter", epochMark in marksAfter)
            val snapshot = api.load()
            assertEquals(true, snapshot.rulesLoaded)

            // 3. Tiempo extra: un horario en curso bloquea; con tiempo extra hay internet; al quitarlo, vuelve a bloquear.
            val routerNow = snapshot.routerNow
            val start = routerNow.toLocalTime().withSecond(0).withNano(0)
            val schedule = Schedule("vmextra", setOf(routerNow.dayOfWeek), start, start.plusMinutes(20))
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(schedules = listOf(schedule)) } }
            Thread.sleep(1000)
            assertTrue("en horario no hay internet", !connects("http://$web/"))
            api.updateProfiles { profiles, now -> profiles.map { it.copy(extraUntil = now.plusMinutes(10).withNano(0)) } }
            Thread.sleep(1000)
            val extra = api.load()
            log("tiempo extra: ${extra.profiles.single().access(extra.routerNow)}")
            assertTrue("con tiempo extra hay internet", connects("http://$web/"))
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(extraUntil = null) } }
            Thread.sleep(1000)
            assertTrue("sin tiempo extra vuelve el horario", !connects("http://$web/"))
            // Al acabar el tiempo extra, el router vuelve a aplicar el horario solo.
            api.updateProfiles { profiles, now -> profiles.map { it.copy(extraUntil = now.plusSeconds(40).withNano(0)) } }
            val extraAt = System.currentTimeMillis()
            Thread.sleep(1000)
            assertTrue("con tiempo extra corto hay internet", connects("http://$web/"))
            Thread.sleep((extraAt + 45_000 - System.currentTimeMillis()).coerceAtLeast(0))
            assertTrue("al acabar el tiempo extra vuelve el horario sin tocar nada", !connects("http://$web/"))
            log("tiempo extra comprobado")
        } finally {
            stopPing()
            // Si falló antes de mandarla, el cat de la conexión sigue esperando en la FIFO.
            ssh("[ -p $NC_FIFO ] && (echo fin > $NC_FIFO &) ; sleep 1; rm -f $NC_FIFO")
            echo?.close()
            api.update { _, _ -> ParentalConfig(emptyList()) }
        }
        assertTrue("sin ficheros de reglas al terminar", ssh("ls $INCLUDE_DIR/ | grep -c wrtcontrol || true").lines().last() == "0")
        assertTrue("sin secciones de WrtControl", ssh("uci show firewall | grep -c wrtc_ || true").lines().last() == "0")
        assertTrue("el cliente vuelve a tener internet", internetBackWithin(15_000))
    }

    private companion object {
        const val CLIENT_MAC = "02:00:00:00:00:99"

        /** Por donde la prueba manda la segunda línea de la conexión abierta antes del filtro. */
        const val NC_FIFO = "/tmp/wrtc_nc.in"

        /** Lo que dice uclient-fetch cuando la conexión se rechaza o no llega. */
        // "Connection failed": así informa el wget de 24.10 (mbedTLS) de un HTTPS rechazado.
        val BLOCKED_MESSAGES = listOf(
            "Operation not permitted", "Connection refused", "Connection reset", "timed out", "Failed to send request", "Connection failed",
        )

        /** Lo que espera wget a que el servidor conteste, y las veces que se repite si no contesta. */
        const val WGET_TIMEOUT_SECONDS = 4
        const val NO_ANSWER_TRIES = 3

        /** Con setsid, `ip` puede resolver al applet de BusyBox, que no tiene `netns`. */
        const val IP_FULL = "/usr/libexec/ip-full"
    }
}
