package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.openwrt.Availability
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.openwrt.PackageTool
import dev.angel.wrtcontrol.openwrt.availability
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Paquetes opcionales contra la VM de OpenWrt (como VmIntegrationTest): instala WireGuard desde
 * cero, abre y cierra un puerto por UPnP desde el cliente de pruebas y activa SQM de verdad.
 * Deja SQM y UPnP desactivados, como estaban, y WireGuard instalado.
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*AddonsVmTest*" --rerun
 */
class AddonsVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    /** Contraseña de root (la VM y la CI no tienen; un router de verdad, sí). */
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    /**
     * Este PC visto desde el router, para el "proveedor" de DNS dinámico. En la NAT de VirtualBox
     * es 10.0.3.2; con un router de verdad, p. ej. la IP del PC en la LAN.
     */
    private val pcAddress: String = System.getenv("WRTCONTROL_PC_ADDRESS") ?: "10.0.3.2"

    /** Puerto de ese "proveedor"; fijo si el firewall del PC solo deja entrar a uno (0: cualquiera). */
    private val pcPort: Int = System.getenv("WRTCONTROL_PC_PORT")?.toIntOrNull() ?: 0

    /**
     * O el "proveedor" del internet de pruebas del router ("host:puerto", `netns-client.sh inet-up`:
     * 198.51.100.2:47102), que no depende del firewall del PC.
     */
    private val ddnsTarget: String? = System.getenv("WRTCONTROL_DDNS_TARGET")

    /** El cliente de pruebas es la .99 de la LAN del router (tools/vm/netns-client.sh). */
    private val clientIp: String get() = vm!!.substringBeforeLast('.') + ".99"

    /** IPv4 de la WAN según el propio router (10.0.3.15 en la VM). */
    private fun wanIpv4(): String = ssh(". /lib/functions/network.sh; network_get_ipaddr ip wan; echo \$ip").lines().last()

    private fun ssh(command: String): String {
        var out = ""
        repeat(4) { attempt ->
            val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", command))
                .redirectErrorStream(true).start()
            out = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            if (process.exitValue() != 255) return out.trim()
            log("ssh falló (intento ${attempt + 1}): ${out.trim().lines().lastOrNull()}")
            Thread.sleep(1000)
        }
        return out.trim()
    }

    private fun log(msg: String) = println("[${LocalTime.now().withNano(0)}] $msg")

    /**
     * Las pruebas dejan la función como al instalar el paquete. En un router que ya la usa (el de
     * casa, al que se llega por el túnel de desarrollo) se saltan, para no borrar lo de verdad.
     * [check]: un comando que solo escribe algo si está en uso.
     */
    private fun assumeNotInUse(what: String, check: String) {
        val inUse = ssh("$check || true").trim()
        assumeTrue("El router ya usa $what ($inUse): la prueba lo borraría", inUse.isEmpty())
    }

    /** UPnP como lo deja el paquete: desactivado, sin dirección externa forzada y sin puertos. */
    private fun resetUpnp() = ssh(
        "uci set upnpd.config.enabled=0; uci set upnpd.config.secure_mode=1; uci -q delete upnpd.config.external_ip; " +
            "uci commit upnpd; /etc/init.d/miniupnpd stop; rm -f /var/run/miniupnpd.leases; echo ok",
    )

    private fun api(): Pair<UbusClient, AddonsApi> {
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password))
        return client to AddonsApi(client)
    }

    @Test
    fun `instala WireGuard desde cero y netifd lo carga`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        // Desinstala WireGuard: si el router tiene otro túnel en uso (p. ej. el de desarrollo a
        // distancia), quien lanza la prueba por él se quedaría fuera.
        val otherTunnels = ssh("uci show network | grep proto=.wireguard. | grep -v network.$VPN_INTERFACE.proto || true")
        assumeTrue("El router usa WireGuard ($otherTunnels): no se desinstala", otherTunnels.isBlank())
        val (_, api) = api()
        try {
            installFromScratch(api)
        } finally {
            // Pase lo que pase, las pruebas siguientes encuentran la red como estaba.
            log(settleNetwork())
        }
    }

    /**
     * Reiniciar la red rehace el puente de la LAN y tarda en volver a tener salida a internet. El
     * cliente de pruebas se engancha al puente a mano (netns-client.sh), así que puede quedarse
     * fuera; y lo que arranque mientras tanto (miniupnpd, las pruebas con tráfico) falla sin tener
     * nada que ver. Se espera a la ruta por defecto y se vuelve a enganchar.
     */
    private fun settleNetwork(): String = ssh(
        "IPF=/usr/libexec/ip-full; [ -x \$IPF ] || IPF=ip; n=0; " +
            "while [ \$n -lt 40 ] && ! ip route | grep -q '^default'; do sleep 1; n=\$((n+1)); done; " +
            "\$IPF link show wrtc_v0 >/dev/null 2>&1 && \$IPF link set wrtc_v0 master br-lan up; sleep 2; " +
            "echo \"red lista: ruta por defecto tras \$n s; cliente: \$(\$IPF link show wrtc_v0 2>/dev/null | grep -o 'master [a-z-]*' || echo no hay)\"",
    )

    private suspend fun installFromScratch(api: AddonsApi) {
        // Punto de partida: sin WireGuard y con netifd sin el protocolo.
        // apk desde 25.12; opkg hasta 24.10.
        val wg = "luci-proto-wireguard wireguard-tools kmod-wireguard"
        log(ssh("{ if command -v apk >/dev/null; then apk del $wg; else opkg remove $wg; fi; } 2>&1 | tail -2; /etc/init.d/network restart; sleep 3"))
        val before = api.packages()!!
        assertEquals(PackageTool.PackageManager, before.tool)
        assertEquals(AddonStatus.Missing, api.state().status(Addon.WireGuard, before))
        // Sin el listado de paquetes también se sabe: por los permisos de la sesión.
        assertEquals(Availability.Installable(listOf("luci-proto-wireguard")), capabilities().availability(Feature.RemoteAccess))

        val t0 = System.currentTimeMillis()
        api.install(Addon.WireGuard, before.tool!!)
        log("instalado y cargado en ${System.currentTimeMillis() - t0} ms")

        val after = api.packages()!!
        assertTrue("wireguard-tools" in after && "kmod-wireguard" in after && "luci-proto-wireguard" in after)
        assertEquals(AddonStatus.Installed, api.state().status(Addon.WireGuard, after))
        assertTrue(ssh("ubus call network get_proto_handlers").contains("\"wireguard\""))
        assertEquals(Availability.Ready, capabilities().availability(Feature.RemoteAccess))
    }

    private suspend fun capabilities(): Capabilities {
        val router = OpenWrtApi(api().first)
        return router.capabilities(router.board(), router.systemInfo())
    }

    @Test
    fun `capacidades de un OpenWrt con LuCI - lo de serie esta disponible y lo demas se puede instalar`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val caps = capabilities()
        log("OpenWrt ${caps.version} (${caps.target}), ${caps.memoryBytes / 1024 / 1024} MiB, libres ${caps.freeStorageKiB} KiB")
        assertTrue(caps.version.isNotBlank() && caps.target.isNotBlank())
        assertTrue("el router dice qué objetos de ubus tiene", caps.has("uci") && caps.has("network.interface"))
        assertEquals("fw4", true, caps.features?.firewall4)
        assertTrue("LuCI deja instalar paquetes", caps.canInstallPackages)
        assertTrue(caps.acl.canCall("uci", "apply") && caps.acl.canCall("network.interface", "dump"))
        assertEquals(Availability.Ready, caps.availability(Feature.Parental))
        assertEquals(Availability.Ready, caps.availability(Feature.ContentFilter))
        // Cada paquete opcional: o ya se puede configurar o la app puede instalarlo.
        Feature.entries.filter { it.addon != null }.forEach {
            assertFalse("${it.name}: ${caps.availability(it)}", caps.availability(it) is Availability.Unsupported)
        }
    }

    @Test
    fun `UPnP abre un puerto desde el cliente y la app lo ve y lo cierra`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeNotInUse("UPnP", "uci -q get upnpd.config.enabled | grep -x 1")
        assumeTrue("Hace falta el cliente de pruebas (tools/vm/netns-client.sh up)", ssh("/usr/libexec/ip-full netns list").contains("wrtc_client"))
        val (_, api) = api()
        resetUpnp()
        // Reiniciar la red (lo hace la prueba de WireGuard) rehace br-lan y suelta el veth del cliente.
        ssh("/usr/libexec/ip-full link set wrtc_v0 master br-lan up; sleep 2")
        ssh("command -v upnpc >/dev/null || apk add miniupnpc >/dev/null 2>&1; echo listo")
        val packages = api.packages()!!
        val before = api.state()
        assertEquals(AddonStatus.Installed, before.status(Addon.Upnp, packages))
        // La WAN de la VM tiene IP privada (doble NAT, como con CGNAT): miniupnpd no abre nada así.
        assertEquals(wanIpv4(), before.internetIpv4)
        assertTrue(before.behindNat)
        // Para la prueba se le da una dirección externa pública (solo es un dato para miniupnpd).
        ssh("uci set upnpd.config.external_ip=1.2.3.4; uci commit upnpd")

        try {
            api.setUpnp(enabled = true)
            // null: sin `service list` (24.10 con el ACL de LuCI) la app no sabe si está en marcha.
            assertTrue(api.state().upnp!!.running != false)
            // La prueba sí lo mira: miniupnpd tiene que estar en marcha para abrir el puerto.
            assertTrue("miniupnpd en marcha", ssh("pgrep miniupnpd >/dev/null && echo si || echo no").endsWith("si"))

            val out = ssh("/usr/libexec/ip-full netns exec wrtc_client upnpc -m wrtc_v1 -e wrtcontrol-prueba -a $clientIp 4444 4444 TCP 2>&1 | tail -3")
            log("upnpc: $out")
            val opened = api.state().upnp!!.mappings
            log("puertos: $opened")
            if (opened.none { it.externalPort == 4444 }) {
                // luci.upnp lee la cadena upnp_prerouting de fw4 y el fichero de concesiones.
                val rules = ssh("nft list chain inet fw4 upnp_prerouting 2>&1 | grep 'dport 4444' || true")
                log("tablas: ${ssh("nft list tables")}")
                log("upnp en nft: ${ssh("nft list ruleset | grep -n -i -E 'upnp|4444' | head -20")}")
                log("concesiones: ${ssh("cat \$(uci -q get upnpd.config.upnp_lease_file) 2>&1 | head -5")}")
                log("get_status: ${ssh("ubus call luci.upnp get_status 2>&1 | head -30")}")
                // Snapshot (nov. 2026): miniupnpd pone "counter packets N bytes N" antes del dnat y la
                // expresión de luci.upnp (dport seguido de dnat) ya no reconoce la regla. Fallo de
                // luci-app-upnp, no de la app: el ACL de LuCI solo deja verlo por get_status.
                assumeTrue(
                    "luci.upnp no lista las reglas con contador de miniupnpd (fallo de luci-app-upnp): $rules",
                    !(rules.contains("dnat") && rules.contains("counter")),
                )
            }
            val mapping = opened.single { it.externalPort == 4444 }
            assertEquals(clientIp, mapping.address)
            assertEquals("TCP", mapping.protocol)
            assertEquals("wrtcontrol-prueba", mapping.description)

            api.closeUpnpMapping(mapping)
            Thread.sleep(2000) // delete_rule reinicia miniupnpd
            assertTrue(api.state().upnp!!.mappings.none { it.externalPort == 4444 })
            assertFalse(ssh("nft list chain inet fw4 upnp_prerouting 2>/dev/null").contains("dport 4444"))
        } finally {
            api.setUpnp(enabled = false)
            ssh("uci delete upnpd.config.external_ip; uci commit upnpd")
        }
        assertTrue(api.state().upnp!!.running != true)
    }

    @Test
    fun `si UPnP no arranca el router lo deshace solo y la app lo dice`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeNotInUse("UPnP", "uci -q get upnpd.config.enabled | grep -x 1")
        val (_, api) = api()
        resetUpnp()
        assertEquals(false, api.state().upnp!!.enabled)
        // En 24.10 el ACL de LuCI no deja `service list`: se sabe con `rc list`.
        assertTrue("se sabe si miniupnpd está en marcha", api.state().upnp!!.running != null)
        // miniupnpd no arranca con una dirección externa reservada.
        ssh("uci set upnpd.config.external_ip=203.0.113.10; uci commit upnpd")
        val t0 = System.currentTimeMillis()
        try {
            api.setUpnp(enabled = true)
            org.junit.Assert.fail("tenía que deshacerse")
        } catch (e: dev.angel.wrtcontrol.parental.ChangeRevertedException) {
            log("deshecho en ${System.currentTimeMillis() - t0} ms: ${e.message}")
        } finally {
            ssh("uci delete upnpd.config.external_ip; uci commit upnpd")
        }
        assertEquals("0", ssh("uci get upnpd.config.enabled"))
        assertEquals(false, api.state().upnp!!.running)
        // Y el siguiente cambio ya no choca con un apply pendiente.
        api.setUpnp(secureMode = false)
        api.setUpnp(secureMode = true)
    }

    /** Ejecuta [command] dentro del cliente de pruebas (su namespace de red). */
    private fun client(command: String) = ssh("/usr/libexec/ip-full netns exec wrtc_client $command")

    @Test
    fun `acceso remoto con un WireGuard de verdad desde el cliente`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeNotInUse("el acceso remoto de la app", "uci -q get network.$VPN_INTERFACE")
        assumeTrue("Hace falta el cliente de pruebas (tools/vm/netns-client.sh up)", ssh("/usr/libexec/ip-full netns list").contains("wrtc_client"))
        ssh("/usr/libexec/ip-full link set wrtc_v0 master br-lan up; sleep 2")
        val (client, api) = api()
        assertEquals(AddonStatus.Installed, api.state().status(Addon.WireGuard, api.packages()))

        val phone = X25519.generatePrivateKey()
        val psk = X25519.generatePresharedKey()
        // La clave pública que calcula la app es la misma que la de `wg pubkey`.
        assertEquals(ssh("echo $phone | wg pubkey"), X25519.publicKey(phone))

        try {
            val t0 = System.currentTimeMillis()
            val setup = api.enableRemoteAccess("vmprueba1", "Prueba VM", X25519.publicKey(phone), psk)
            log("acceso remoto activado en ${System.currentTimeMillis() - t0} ms: $setup")
            assertTrue(ssh("wg show $VPN_INTERFACE peers").contains(X25519.publicKey(phone)))

            // El "teléfono": un WireGuard en el cliente que entra por la dirección de la LAN.
            ssh("echo $phone > /tmp/wgc.key; echo $psk > /tmp/wgc.psk")
            client("/usr/libexec/ip-full link add wgc type wireguard")
            client(
                "wg set wgc private-key /tmp/wgc.key peer ${setup.routerPublicKey} preshared-key /tmp/wgc.psk " +
                    "endpoint $vm:${setup.port} allowed-ips ${setup.routerTunnelAddress}/32,1.1.1.1/32 persistent-keepalive 5",
            )
            client("/usr/libexec/ip-full addr add ${setup.phoneAddress}/32 dev wgc")
            client("/usr/libexec/ip-full link set wgc up")
            client("/usr/libexec/ip-full route add ${setup.routerTunnelAddress}/32 dev wgc")
            client("/usr/libexec/ip-full route add 1.1.1.1/32 dev wgc")

            val web = client("wget -q -T 5 -O - http://${setup.routerTunnelAddress}/ 2>&1 | head -c 300")
            log("web por el túnel: ${web.take(120)}")
            assertTrue("la web del router responde por el túnel", web.contains("<html", ignoreCase = true) || web.contains("luci", ignoreCase = true))
            val handshake = client("wg show wgc latest-handshakes").substringAfter('\t').trim().toLongOrNull() ?: 0
            assertTrue("hubo saludo", handshake > 0)

            val sshPort = client("wget -q -T 4 -O - http://${setup.routerTunnelAddress}:22/ 2>&1 | head -c 200")
            log("SSH por el túnel: $sshPort")
            assertFalse("SSH no se ve por el túnel", sshPort.contains("SSH-2.0"))
            val internet = client("ping -c 2 -W 2 1.1.1.1 2>&1 | tail -2")
            log("internet por el túnel: $internet")
            assertTrue("el túnel no da salida a internet", internet.contains("100% packet loss") || internet.contains("unreachable"))

            // La app ve el par conectado.
            val live = api.state().wireguard!!.single { it.name == VPN_INTERFACE }
            assertTrue(live.peers.single().lastHandshake != null)

            // Un segundo teléfono con el servidor ya creado: netifd no recarga los pares por sí solo,
            // la app reinicia la interfaz y el kernel tiene los dos.
            val other = X25519.generatePrivateKey()
            val second = api.enableRemoteAccess("vmprueba2", "Otro", X25519.publicKey(other), X25519.generatePresharedKey())
            assertEquals("10.77.0.3", second.phoneAddress)
            val peers = ssh("wg show $VPN_INTERFACE peers")
            assertTrue(peers.contains(X25519.publicKey(phone)) && peers.contains(X25519.publicKey(other)))
            // Tras el reinicio de la interfaz, el primer teléfono vuelve a entrar.
            var again = ""
            repeat(10) {
                again = client("wget -q -T 4 -O - http://${setup.routerTunnelAddress}/ 2>&1 | head -c 100")
                if (again.contains("<html", ignoreCase = true)) return@repeat
                Thread.sleep(2000)
            }
            assertTrue("el primero sigue entrando: $again", again.contains("<html", ignoreCase = true))

            // El control parental no toca el acceso remoto al aplicar sus cambios.
            val parental = dev.angel.wrtcontrol.parental.ParentalApi(client, log = ::log)
            parental.updateProfiles { p, _ -> p + dev.angel.wrtcontrol.parental.Profile("vmvpn", "VPN", listOf("02:00:00:00:00:98"), pause = dev.angel.wrtcontrol.parental.Pause.Indefinite) }
            parental.updateProfiles { _, _ -> emptyList() }
            assertTrue(ssh("uci -q get network.$VPN_INTERFACE.proto") == "wireguard")
            assertTrue(ssh("wg show $VPN_INTERFACE peers").contains(X25519.publicKey(phone)))

            // Quitar el primero (p. ej. un teléfono robado) lo saca del kernel y deja de entrar.
            api.removeRemotePeer("vmprueba1")
            assertFalse(ssh("wg show $VPN_INTERFACE peers").contains(X25519.publicKey(phone)))
            assertTrue(ssh("wg show $VPN_INTERFACE peers").contains(X25519.publicKey(other)))
            val revoked = client("wget -q -T 4 -O - http://${setup.routerTunnelAddress}/ 2>&1 | head -c 100")
            log("el teléfono quitado: $revoked")
            assertFalse(revoked.contains("<html", ignoreCase = true))
        } finally {
            client("/usr/libexec/ip-full link del wgc")
            ssh("rm -f /tmp/wgc.key /tmp/wgc.psk")
            api.removeRemotePeer("vmprueba1")
            api.removeRemotePeer("vmprueba2")
        }
        assertEquals("", ssh("uci show network | grep $VPN_PREFIX; uci show firewall | grep $VPN_PREFIX"))
        assertFalse(ssh("wg show interfaces").contains(VPN_INTERFACE))
    }

    @Test
    fun `DNS dinamico avisa al proveedor con el nombre, la IP y el token`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeNotInUse("el DNS dinámico de la app", "uci show ddns | grep -E '^ddns.$DDNS_SECTION6?.domain=' | grep -v vmtest.example.org")
        val (_, api) = api()
        assertEquals(AddonStatus.Installed, api.state().status(Addon.Ddns, api.packages()))

        // El "proveedor": el del "internet" de pruebas del router (netns-client.sh inet-up), o un
        // servidor HTTP aquí, al que la VM llega por la puerta de enlace del NAT de VirtualBox.
        val requests = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = if (ddnsTarget != null) null else {
            com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(pcPort), 0).apply {
                createContext("/") { ex ->
                    requests += ex.requestURI.toString()
                    val body = "good".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }
        }
        val provider = ddnsTarget ?: "$pcAddress:${server!!.address.port}"
        if (ddnsTarget != null) ssh(": > $ROUTER_DDNS_LOG")
        // En el router, la primera línea de la petición: "GET /nic/update?... HTTP/1.1".
        fun routerRequest(): String? =
            ssh("head -1 $ROUTER_DDNS_LOG").lines().lastOrNull { it.startsWith("GET ") }?.split(' ')?.get(1)
        fun nextRequest(): String? =
            if (server != null) requests.poll(60, TimeUnit.SECONDS) else
                (1..60).asSequence().map { routerRequest().also { if (it == null) Thread.sleep(1000) } }.firstOrNull { it != null }
        // La WAN de la VM tiene IP privada: ddns-scripts no la envía salvo que se le permita.
        ssh("uci set ddns.global.upd_privateip=1; uci commit ddns")
        // Antes de avisar, ddns-scripts mira qué IP tiene registrada el nombre y, si no la saca,
        // reintenta en vez de avisar. Que dependa del DNS de la WAN no sirve (el de algunos módems
        // ni contesta por un nombre que no existe): dnsmasq da una IP vieja, distinta de la WAN.
        ssh("uci add_list dhcp.@dnsmasq[0].address=/vmtest.example.org/$OLD_DDNS_IP; uci commit dhcp; /etc/init.d/dnsmasq reload")
        try {
            val url = "http://$provider/nic/update?hostname=[DOMAIN]&myip=[IP]&pass=[PASSWORD]"
            api.saveDdns(DdnsEdit(DdnsProvider.Custom, "vmtest.example.org", "secreto123", families = setOf(IpFamily.V4), updateUrl = url))
            log("ddns: ${ssh("uci show ddns.$DDNS_SECTION")}")
            val request = nextRequest()
            log("petición: $request")
            assertEquals("/nic/update?hostname=vmtest.example.org&myip=${wanIpv4()}&pass=secreto123", request)

            var own: DdnsService? = null
            repeat(10) {
                own = api.state().ownDdns.firstOrNull()
                if (own?.lastUpdate != null) return@repeat
                Thread.sleep(1000)
            }
            log("estado: $own")
            assertTrue(own!!.running)
            assertTrue(own!!.lastUpdate != null)
            log("registro: ${ssh("tail -5 /var/log/ddns/$DDNS_SECTION.log")}")
        } finally {
            api.removeDdns()
            ssh("uci set ddns.global.upd_privateip=0; uci commit ddns")
            ssh("uci del_list dhcp.@dnsmasq[0].address=/vmtest.example.org/$OLD_DDNS_IP; uci commit dhcp; /etc/init.d/dnsmasq reload")
            server?.stop(0)
        }
        assertTrue(api.state().ownDdns.isEmpty())
        assertFalse(ssh("ubus call service list").contains(DDNS_SECTION))
    }

    @Test
    fun `SQM se activa de verdad en la WAN y se desactiva`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val (_, api) = api()
        val state = api.state()
        assertEquals(AddonStatus.Installed, state.status(Addon.Sqm, api.packages()))
        val queue = state.sqm!!.first()
        log("cola: $queue, internet: ${state.internetDevice}")
        assertEquals("eth1", state.internetDevice)

        try {
            api.saveSqm(SqmEdit(queue.section, true, "eth1", 80_000, 9_000))
            Thread.sleep(2000)
            val tc = ssh("tc qdisc show dev eth1")
            log("tc: $tc")
            assertTrue("cake en la salida de eth1", tc.contains("cake") && tc.contains("9Mbit"))
            assertEquals("1", ssh("uci get sqm.${queue.section}.enabled"))
        } finally {
            api.saveSqm(SqmEdit(queue.section, queue.enabled, queue.device, queue.downloadKbit, queue.uploadKbit))
        }
        Thread.sleep(2000)
        assertFalse("sin cake al desactivarlo", ssh("tc qdisc show dev eth1").contains("cake"))
        assertEquals(queue, api.state().sqm!!.first())
    }
}

/** La IP "registrada" que da dnsmasq al nombre de la prueba de DNS dinámico (de documentación). */
private const val OLD_DDNS_IP = "203.0.113.7"

/** Donde apunta las peticiones el proveedor de netns-client.sh. */
private const val ROUTER_DDNS_LOG = "/tmp/wrtc_ddns_requests"
