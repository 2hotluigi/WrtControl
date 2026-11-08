package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.openwrt.PackageTool
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

    /** UPnP como lo deja el paquete: desactivado, sin dirección externa forzada y sin puertos. */
    private fun resetUpnp() = ssh(
        "uci set upnpd.config.enabled=0; uci set upnpd.config.secure_mode=1; uci -q delete upnpd.config.external_ip; " +
            "uci commit upnpd; /etc/init.d/miniupnpd stop; rm -f /var/run/miniupnpd.leases; echo ok",
    )

    private fun api(): Pair<UbusClient, AddonsApi> {
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val client = UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", ""))
        return client to AddonsApi(client)
    }

    @Test
    fun `instala WireGuard desde cero y netifd lo carga`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val (_, api) = api()
        // Punto de partida: sin WireGuard y con netifd sin el protocolo.
        // apk desde 25.12; opkg hasta 24.10.
        val wg = "luci-proto-wireguard wireguard-tools kmod-wireguard"
        log(ssh("{ if command -v apk >/dev/null; then apk del $wg; else opkg remove $wg; fi; } 2>&1 | tail -2; /etc/init.d/network restart; sleep 3"))
        val before = api.packages()!!
        assertEquals(PackageTool.PackageManager, before.tool)
        assertEquals(AddonStatus.Missing, api.state().status(Addon.WireGuard, before))

        val t0 = System.currentTimeMillis()
        api.install(Addon.WireGuard, before.tool!!)
        log("instalado y cargado en ${System.currentTimeMillis() - t0} ms")

        val after = api.packages()!!
        assertTrue("wireguard-tools" in after && "kmod-wireguard" in after && "luci-proto-wireguard" in after)
        assertEquals(AddonStatus.Installed, api.state().status(Addon.WireGuard, after))
        assertTrue(ssh("ubus call network get_proto_handlers").contains("\"wireguard\""))
    }

    @Test
    fun `UPnP abre un puerto desde el cliente y la app lo ve y lo cierra`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
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
        assertEquals("10.0.3.15", before.internetIpv4)
        assertTrue(before.behindNat)
        // Para la prueba se le da una dirección externa pública (solo es un dato para miniupnpd).
        ssh("uci set upnpd.config.external_ip=1.2.3.4; uci commit upnpd")

        try {
            api.setUpnp(enabled = true)
            assertEquals(true, api.state().upnp!!.running)

            val out = ssh("/usr/libexec/ip-full netns exec wrtc_client upnpc -m wrtc_v1 -e wrtcontrol-prueba -a 192.168.56.99 4444 4444 TCP 2>&1 | tail -3")
            log("upnpc: $out")
            val opened = api.state().upnp!!.mappings
            log("puertos: $opened")
            val mapping = opened.single { it.externalPort == 4444 }
            assertEquals("192.168.56.99", mapping.address)
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
        assertEquals(false, api.state().upnp!!.running)
    }

    @Test
    fun `si UPnP no arranca el router lo deshace solo y la app lo dice`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        val (_, api) = api()
        resetUpnp()
        assertEquals(false, api.state().upnp!!.enabled)
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
                    "endpoint 192.168.56.2:${setup.port} allowed-ips ${setup.routerTunnelAddress}/32,1.1.1.1/32 persistent-keepalive 5",
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
        val (_, api) = api()
        assertEquals(AddonStatus.Installed, api.state().status(Addon.Ddns, api.packages()))

        // El "proveedor": un servidor HTTP aquí, al que la VM llega por la puerta de enlace del NAT de VirtualBox.
        val requests = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            requests += ex.requestURI.toString()
            val body = "good".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        // La WAN de la VM tiene IP privada: ddns-scripts no la envía salvo que se le permita.
        ssh("uci set ddns.global.upd_privateip=1; uci commit ddns")
        try {
            val url = "http://10.0.3.2:${server.address.port}/nic/update?hostname=[DOMAIN]&myip=[IP]&pass=[PASSWORD]"
            api.saveDdns(DdnsEdit(DdnsProvider.Custom, "vmtest.example.org", "secreto123", families = setOf(IpFamily.V4), updateUrl = url))
            log("ddns: ${ssh("uci show ddns.$DDNS_SECTION")}")
            val request = requests.poll(60, TimeUnit.SECONDS)
            log("petición: $request")
            assertEquals("/nic/update?hostname=vmtest.example.org&myip=10.0.3.15&pass=secreto123", request)

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
            server.stop(0)
        }
        assertTrue(api.state().ownDdns.isEmpty())
        assertFalse(ssh("ubus call service list '{\"name\":\"ddns\"}'").contains(DDNS_SECTION))
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
