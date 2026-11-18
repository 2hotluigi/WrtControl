package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.Route
import dev.angel.wrtcontrol.openwrt.WanLink
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiNetwork
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.parental.ParentalSettings
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.ui.home.Attention
import dev.angel.wrtcontrol.ui.home.HomeWifi
import dev.angel.wrtcontrol.ui.home.homeWifi
import dev.angel.wrtcontrol.ui.home.overview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OverviewTest {

    private val now = LocalDateTime.of(2026, 1, 10, 20, 0)
    private val lan = NetInterface("lan", up = true, uptime = 90_000)
    private val wan = NetInterface("wan", up = true, uptime = 3_600, routes = listOf(Route("0.0.0.0", 0)))

    private fun device(mac: String) = Device(mac, null, emptyList(), emptyList(), LeaseState.None)

    private fun snapshot(profiles: List<Profile> = emptyList(), settings: ParentalSettings = ParentalSettings()) = ParentalSnapshot(
        profiles = profiles,
        routerNow = now,
        routerOffsetSeconds = 0,
        routerZone = null,
        flowOffloading = false,
        markConflicts = emptyList(),
        settings = settings,
    )

    @Test
    fun `con salida a internet - conectado y desde cuando`() {
        val o = overview(listOf(lan, wan), null, null, emptyPassword = false)
        assertTrue(o.online)
        assertEquals("cuenta la salida a internet, no la red de casa", 3_600L, o.onlineSeconds)
        assertTrue(o.attention.isEmpty())
    }

    @Test
    fun `con IPv4 e IPv6 cuenta la salida que mas lleva en pie`() {
        val wan6 = NetInterface("wan6", up = true, uptime = 7_200, routes = listOf(Route("::", 0)))
        assertEquals(7_200L, overview(listOf(lan, wan, wan6), null, null, false).onlineSeconds)
    }

    @Test
    fun `sin ruta por defecto o con la WAN caida - sin internet`() {
        assertFalse(overview(listOf(lan), null, null, false).online)
        assertFalse(overview(listOf(lan, wan.copy(up = false)), null, null, false).online)
        assertNull(overview(listOf(lan), null, null, false).onlineSeconds)
    }

    @Test
    fun `lo que aun no se ha leido no se cuenta`() {
        val o = overview(listOf(wan), null, null, false)
        assertNull(o.devices)
        assertNull(o.profiles)
        assertEquals(0, o.paused)
    }

    @Test
    fun `perfiles en pausa - sin fin o con fin que no ha llegado`() {
        val profiles = listOf(
            Profile("a", "Ana", emptyList(), pause = Pause.Indefinite),
            Profile("b", "Beto", emptyList(), pause = Pause.Until(now.plusMinutes(30))),
            Profile("c", "Caro", emptyList(), pause = Pause.Until(now.minusMinutes(1))),
            Profile("d", "Dani", emptyList()),
        )
        val o = overview(listOf(wan), listOf(device("AA:00:00:00:00:01")), snapshot(profiles), false)
        assertEquals(4, o.profiles)
        assertEquals(2, o.paused)
        assertEquals(1, o.devices)
    }

    @Test
    fun `dispositivos nuevos - solo con la cuarentena activa y sin contar los aprobados ni los de un perfil`() {
        val devices = listOf(device("AA:00:00:00:00:01"), device("AA:00:00:00:00:02"), device("AA:00:00:00:00:03"))
        val profiles = listOf(Profile("a", "Ana", listOf("AA:00:00:00:00:02")))
        val on = snapshot(profiles, ParentalSettings(quarantine = true, approved = listOf("AA:00:00:00:00:01")))
        val o = overview(listOf(wan), devices, on, false)
        assertEquals(1, o.newDevices)
        assertEquals(listOf(Attention.NewDevices), o.attention)

        val off = overview(listOf(wan), devices, snapshot(profiles), false)
        assertEquals(0, off.newDevices)
        assertTrue(off.attention.isEmpty())
    }

    @Test
    fun `el cable - sin enlace o sin internet no esta conectado, y lo que falla a ratos pide atencion`() {
        val ok = WanLink("eth1", carrier = true, speedMbps = 1000, dropsSinceBoot = 0, dropsAgoSeconds = emptyList(), internet = true)
        val fine = overview(listOf(wan), null, null, false, ok)
        assertTrue(fine.online)
        assertEquals(1000, fine.cableMbps)
        assertNull(fine.linkAdvice)

        val noInternet = overview(listOf(wan), null, null, false, ok.copy(internet = false))
        assertFalse("la red está en pie, pero el ping no llega", noInternet.online)
        assertTrue("proveedor" in noInternet.linkAdvice!!.es)
        assertTrue("ya lo dice la tarjeta de arriba", noInternet.attention.isEmpty())

        val flapping = overview(listOf(wan), null, null, true, ok.copy(dropsSinceBoot = 9, dropsAgoSeconds = listOf(30, 90, 200)))
        assertTrue(flapping.online)
        assertEquals(listOf(Attention.Cable, Attention.NoPassword), flapping.attention)
    }

    @Test
    fun `sin contraseña va lo primero`() {
        val devices = listOf(device("AA:00:00:00:00:01"))
        val o = overview(listOf(wan), devices, snapshot(settings = ParentalSettings(quarantine = true)), emptyPassword = true)
        assertEquals(listOf(Attention.NoPassword, Attention.NewDevices), o.attention)
    }

    private val radio = WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true)

    private fun home(ifname: String?, enabled: Boolean = true, clients: Int = 0) = WifiNetwork(
        section = "default_radio0", radio = "radio0", ssid = "Casa", encryption = "sae-mixed", key = "clave-de-casa",
        enabled = enabled, networks = listOf("lan"), ifname = ifname, clients = List(clients) { "AA:00:00:00:00:%02X".format(it) },
    )

    @Test
    fun `el Wi-Fi que emite dice su nombre y cuantos hay`() {
        assertEquals(HomeWifi.On("Casa", 3), homeWifi(WifiState(listOf(radio), listOf(home("phy0-ap0", clients = 3))), phoneOnHomeWifi = true))
    }

    @Test
    fun `encendido pero sin emitir es el horario`() {
        val state = WifiState(listOf(radio.copy(up = false)), listOf(home(null)))
        assertEquals(HomeWifi.Paused, homeWifi(state, phoneOnHomeWifi = false))
    }

    @Test
    fun `apagado con el telefono por Wi-Fi en casa lo dan otros aparatos`() {
        val state = WifiState(listOf(radio), listOf(home(null, enabled = false)))
        assertEquals(HomeWifi.OtherAccessPoints, homeWifi(state, phoneOnHomeWifi = true))
        assertEquals(HomeWifi.Off, homeWifi(state, phoneOnHomeWifi = false))
    }

    @Test
    fun `sin radios no hay tarjeta de Wi-Fi`() {
        assertNull(homeWifi(WifiState(emptyList(), emptyList()), phoneOnHomeWifi = true))
    }
}
