package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.PackageInstallException
import dev.angel.wrtcontrol.openwrt.PackageTool
import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.ubus.Credentials
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AddonsApiTest {

    private fun TestScope.api(f: FakeAddons): AddonsApi {
        f.fake.clock = { testScheduler.currentTime }
        return AddonsApi(f.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `detecta lo instalado con el listado de paquetes`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-sqm")
        f.installed["miniupnpd-nftables"] = "2.3" // sin luci-app-upnp
        val api = api(f)

        val packages = api.packages()!!
        val state = api.state()

        assertEquals(PackageTool.PackageManager, packages.tool)
        assertEquals(AddonStatus.Installed, state.status(Addon.Sqm, packages))
        assertEquals("sin su parte de LuCI la app no tiene permiso", AddonStatus.Partial, state.status(Addon.Upnp, packages))
        assertNull(state.upnp)
        assertEquals(AddonStatus.Missing, state.status(Addon.WireGuard, packages))
        assertEquals(AddonStatus.Missing, state.status(Addon.Ddns, packages))
        assertEquals(listOf(SqmQueue("eth1", false, "eth1", 85000, 10000, "cake", "piece_of_cake.qos")), state.sqm)
        assertEquals("eth1", state.internetDevice)
        assertEquals(listOf("eth1", "br-lan"), state.devices)
        assertEquals(71680L, state.freeKiB)
    }

    @Test
    fun `con opkg de 22_03 usa opkg-call y el argumento que pide su ACL para actualizar`() = runTest {
        val f = FakeAddons()
        f.tool = PackageTool.Opkg
        f.opkgStatusFormat = true
        val api = api(f)

        val packages = api.packages()!!
        assertEquals(PackageTool.Opkg, packages.tool)
        assertTrue("base-files" in packages)
        assertFalse("lo desinstalado no cuenta", "old-thing" in packages)

        api.install(Addon.Upnp, packages.tool!!)
        assertEquals(
            listOf(
                "/usr/libexec/opkg-call update --force-removal-of-dependent-packages",
                "/usr/libexec/opkg-call install luci-app-upnp",
            ),
            f.commands.filter { it.split(' ')[1] in setOf("update", "install") },
        )
        assertTrue("luci-app-upnp" in f.installed)
    }

    @Test
    fun `sin la pagina de software de LuCI no hay listado y se detecta por la configuracion`() = runTest {
        val f = FakeAddons()
        f.tool = null
        f.install("luci-app-sqm")
        val api = api(f)

        assertNull(api.packages())
        val state = api.state()
        assertEquals(AddonStatus.Installed, state.status(Addon.Sqm, null))
        assertEquals(AddonStatus.Missing, state.status(Addon.Upnp, null))
    }

    @Test
    fun `instalar WireGuard actualiza, instala y reinicia la red para que netifd lo cargue`() = runTest {
        val f = FakeAddons()
        val api = api(f)

        api.install(Addon.WireGuard, PackageTool.PackageManager)

        assertEquals(
            listOf("/usr/libexec/package-manager-call update", "/usr/libexec/package-manager-call install luci-proto-wireguard"),
            f.commands,
        )
        assertEquals(listOf("network restart"), f.initActions)
        assertEquals(AddonStatus.Installed, api.state().status(Addon.WireGuard, api.packages()))
    }

    @Test
    fun `WireGuard instalado sin cargar pide reiniciar la red y no la reinicia si ya esta cargado`() = runTest {
        val f = FakeAddons()
        f.install("luci-proto-wireguard") // como si se hubiera instalado desde la consola
        val api = api(f)

        assertEquals(AddonStatus.NeedsRestart, api.state().status(Addon.WireGuard, api.packages()))
        api.finishWireGuard()
        assertEquals(listOf("network restart"), f.initActions)
        api.finishWireGuard()
        assertEquals("ya estaba cargado", listOf("network restart"), f.initActions)
        assertEquals(AddonStatus.Installed, api.state().status(Addon.WireGuard, api.packages()))
    }

    @Test
    fun `si el gestor de paquetes falla se explica el motivo`() = runTest {
        val f = FakeAddons()
        f.installFailure = "ERROR: sqm-scripts-1.7.2-r1: No space left on device"
        val api = api(f)

        val e = try {
            api.install(Addon.Sqm, PackageTool.PackageManager)
            fail("tenía que fallar")
            null
        } catch (e: PackageInstallException) {
            e
        }
        assertTrue(e!!.message, e.message!!.contains("espacio libre"))
        assertTrue(e.output.contains("No space left"))
        assertFalse("luci-app-sqm" in f.installed)
    }

    @Test
    fun `activar SQM en la conexion a internet lo aplica con reversion y lo deja arrancando con el router`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-sqm")
        f.fake.interfaces = mapOf("lan" to "br-lan", "wan" to "pppoe-wan")
        val api = api(f)

        api.saveSqm(SqmEdit("eth1", enabled = true, device = "pppoe-wan", downloadKbit = 90_000, uploadKbit = 18_000))

        assertEquals(
            listOf(SqmQueue("eth1", true, "pppoe-wan", 90_000, 18_000, "cake", "piece_of_cake.qos")),
            api.state().sqm,
        )
        assertTrue(f.initActions.contains("sqm enable"))
        assertTrue("confirmado", f.fake.calls.contains("uci.confirm"))
        assertNull(f.fake.pendingApply)
    }

    @Test
    fun `si ya estaba asi no se aplica nada`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-sqm", "luci-app-upnp")
        val api = api(f)

        api.saveSqm(SqmEdit("eth1", enabled = false, device = "eth1", downloadKbit = 85_000, uploadKbit = 10_000))
        api.setUpnp(enabled = false, secureMode = true)

        assertFalse("rpcd respondería \"sin datos\"", f.fake.calls.contains("uci.set"))
        assertFalse(f.fake.calls.contains("uci.confirm"))
        assertNull(f.fake.pendingApply)
    }

    @Test
    fun `una cola nueva se llama como su dispositivo y lleva cake`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-sqm")
        val api = api(f)

        api.saveSqm(SqmEdit(null, enabled = true, device = "pppoe-wan", downloadKbit = 50_000, uploadKbit = 0))

        assertEquals("pppoe-wan", f.fake.option("pppoe_wan", "interface"))
        assertEquals("cake", f.fake.option("pppoe_wan", "qdisc"))
        assertEquals("0", f.fake.option("pppoe_wan", "upload"))
        assertEquals("1", f.fake.option("pppoe_wan", "enabled"))
    }

    @Test
    fun `no deja dos colas activas en el mismo dispositivo ni velocidades que corten la conexion`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-sqm")
        val api = api(f)
        api.saveSqm(SqmEdit("eth1", true, "eth1", 85_000, 10_000))

        try {
            api.saveSqm(SqmEdit(null, true, "eth1", 10_000, 1_000))
            fail("dos colas activas en eth1")
        } catch (_: SqmConflictException) {
        }
        assertNull("no dejó nada a medias", f.fake.pendingApply)
        assertEquals(
            "Con menos de 256 kbit/s el router se quedaría casi sin conexión.",
            sqmProblem(SqmEdit("eth1", true, "eth1", 100, 10_000)),
        )
        assertNull("0 es sin limitar ese sentido", sqmProblem(SqmEdit("eth1", true, "eth1", 85_000, 0)))
        assertEquals("Indica al menos una de las dos velocidades.", sqmProblem(SqmEdit("eth1", true, "eth1", 0, 0)))
        assertEquals("Ese nombre de dispositivo no es válido.", sqmProblem(SqmEdit("eth1", true, "eth1; reboot", 1000, 1000)))
    }

    @Test
    fun `activar UPnP comprueba que miniupnpd arranca y si no lo deja como estaba`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-upnp")
        val api = api(f)

        api.setUpnp(enabled = true)
        val on = api.state().upnp!!
        assertTrue(on.enabled)
        assertEquals(true, on.running)
        assertTrue(f.initActions.contains("miniupnpd enable"))

        api.setUpnp(enabled = false)
        assertEquals(false, api.state().upnp!!.running)

        f.upnpWontStart = true
        try {
            api.setUpnp(enabled = true)
            fail("tenía que deshacerse")
        } catch (e: ChangeRevertedException) {
            assertEquals("UPnP no arrancó en el router, así que se dejó como estaba.", e.message)
        }
        assertEquals("0", f.fake.option("config", "enabled"))
    }

    @Test
    fun `hasta 24_10 sin service list UPnP se arranca o para a mano`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-upnp")
        f.oldRouter = true
        val api = api(f)

        api.setUpnp(enabled = true)
        assertTrue(f.initActions.contains("miniupnpd restart"))
        api.setUpnp(enabled = false)
        assertTrue(f.initActions.contains("miniupnpd stop"))
    }

    @Test
    fun `si miniupnpd estaba parado a mano se arranca al activar UPnP`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-upnp")
        f.upnpStopped = true
        val api = api(f)

        api.setUpnp(enabled = true)

        assertTrue(f.initActions.contains("miniupnpd start"))
        assertEquals(true, api.state().upnp!!.running)
    }

    @Test
    fun `los puertos abiertos por UPnP se ven con su dispositivo y se cierran por su numero`() = runTest {
        val f = FakeAddons()
        f.install("luci-app-upnp")
        f.fake.section("config", "upnpd", "enabled" to "1", "secure_mode" to "1")
        f.upnpRules = mutableListOf(
            mapOf("num" to "1", "proto" to "UDP", "extport" to 3074, "intaddr" to "192.168.1.20", "intport" to 3074,
                "descr" to "Xbox ", "expires" to 3500, "host_hint" to "xbox"),
            mapOf("num" to "2", "proto" to "TCP", "extport" to 51413, "intaddr" to "192.168.1.30", "intport" to 51413, "descr" to ""),
        )
        val api = api(f)

        val mappings = api.state().upnp!!.mappings
        assertEquals(
            listOf(
                UpnpMapping("1", "UDP", 3074, "192.168.1.20", 3074, "Xbox", "xbox", 3500),
                UpnpMapping("2", "TCP", 51413, "192.168.1.30", 51413, "", null, null),
            ),
            mappings,
        )
        api.closeUpnpMapping(mappings[1])
        assertEquals(listOf("2"), f.deletedRules)
        assertEquals(1, api.state().upnp!!.mappings.size)
    }

    @Test
    fun `WireGuard muestra sus interfaces con el ultimo saludo de cada par`() = runTest {
        val f = FakeAddons()
        f.install("luci-proto-wireguard")
        f.protos += "wireguard"
        f.fake.section("wg0", "interface", "proto" to "wireguard", "listen_port" to "51820", "addresses" to listOf("10.77.0.1/24"))
        f.fake.section("peer1", "wireguard_wg0", "description" to "Teléfono", "public_key" to "AAA=", "allowed_ips" to listOf("10.77.0.2/32"))
        f.fake.section("peer2", "wireguard_wg0", "description" to "Portátil", "public_key" to "BBB=")
        f.wgHandshakes = mapOf("wg0" to mapOf("AAA=" to 1_700_000_000L, "BBB=" to 0L))
        val api = api(f)

        val wg = api.state().wireguard!!.single()
        assertEquals("wg0", wg.name)
        assertEquals(51820, wg.listenPort)
        assertTrue(wg.up)
        assertEquals(listOf(1_700_000_000L, null), wg.peers.map { it.lastHandshake })
        assertEquals(listOf("Teléfono", "Portátil"), wg.peers.map { it.description })
    }
}
