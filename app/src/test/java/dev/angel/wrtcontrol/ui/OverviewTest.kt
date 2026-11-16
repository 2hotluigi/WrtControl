package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.Route
import dev.angel.wrtcontrol.parental.ParentalSettings
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.ui.home.Attention
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
    fun `sin contraseña va lo primero`() {
        val devices = listOf(device("AA:00:00:00:00:01"))
        val o = overview(listOf(wan), devices, snapshot(settings = ParentalSettings(quarantine = true)), emptyPassword = true)
        assertEquals(listOf(Attention.NoPassword, Attention.NewDevices), o.attention)
    }
}
