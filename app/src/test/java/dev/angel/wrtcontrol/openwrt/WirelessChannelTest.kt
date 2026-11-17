package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ui.home.channelPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El canal menos ocupado: qué se propone con lo que oye el router y qué se cambia. */
@OptIn(ExperimentalCoroutinesApi::class)
class WirelessChannelTest {

    private fun radio(band: WifiBand, channel: String?, htmode: String?) = WifiRadio("r", band, enabled = true, up = true, channel = channel, htmode = htmode)

    /** Lo que oía el GL-MT6000 de la casa el 17-11-2026: los Deco en el 5 (muy fuertes) y vecinos en el 1, 2 y 4. */
    private val house2g = listOf(1 to -74, 1 to -75, 1 to -76, 2 to -56, 2 to -59, 2 to -59, 2 to -72, 4 to -71, 5 to -16, 5 to -16, 5 to -18)
        .map { (channel, signal) -> SeenNetwork(channel, signal) }

    /** Y en 5 GHz: los Deco en el 40 y casi nada arriba. */
    private val house5g = listOf(36 to -78, 36 to -78, 36 to -78, 36 to -95, 40 to -12, 40 to -12, 40 to -12, 40 to -77, 56 to -94, 100 to -84, 149 to -82)
        .map { (channel, signal) -> SeenNetwork(channel, signal) }

    @Test
    fun `en 2,4 GHz se propone el 1, el 6 o el 11 que menos pise a los vecinos`() {
        val all = (1..11).toList()
        val advice = adviseChannel(radio(WifiBand.Band2, "1", "HE20"), all, house2g)!!
        assertEquals("el 11 no tiene a nadie cerca", 11, advice.best)
        assertEquals(0, advice.bestNeighbors)
        assertEquals("en el 1 estorban todos los que están a menos de 5 canales", 11, advice.currentNeighbors)
        assertTrue(advice.better)
    }

    @Test
    fun `en 5 GHz cuenta el bloque entero y solo se propone lo que el pais permite`() {
        val withCountry = listOf(36, 40, 44, 48, 52, 100, 149, 153)
        val advice = adviseChannel(radio(WifiBand.Band5, "36", "HE80"), withCountry, house5g)!!
        assertEquals("el bloque del 149 solo tiene una red, y lejos", 149, advice.best)
        assertEquals(1, advice.bestNeighbors)
        assertEquals("las del 36 y las del 40 están en el mismo bloque de 80 MHz", 8, advice.currentNeighbors)
        assertTrue(advice.better)

        val noCountry = withCountry.filter { it < 149 }
        val stuck = adviseChannel(radio(WifiBand.Band5, "36", "HE80"), noCountry, house5g)!!
        assertEquals(36, stuck.best)
        assertFalse("sin país no hay adónde ir", stuck.better)

        // A 160 MHz los bloques son 36-64 y 100-128.
        val wide = adviseChannel(radio(WifiBand.Band5, "36", "HE160"), withCountry, house5g)!!
        assertEquals(100, wide.best)
        assertEquals(9, wide.currentNeighbors)
    }

    @Test
    fun `no se cambia por poco, en automatico se propone uno fijo y sin candidatos no hay consejo`() {
        val all = (1..11).toList()
        val similar = listOf(SeenNetwork(1, -70), SeenNetwork(6, -72))
        val stay = adviseChannel(radio(WifiBand.Band2, "6", "HE20"), listOf(1, 6), similar)!!
        assertFalse("la diferencia no llega al 20 %", stay.better)

        val auto = adviseChannel(radio(WifiBand.Band2, "auto", "HE20"), all, house2g)!!
        assertEquals(11, auto.best)
        assertNull(auto.currentNeighbors)
        assertTrue(auto.better)

        val empty = adviseChannel(radio(WifiBand.Band2, "3", "HE20"), all, emptyList())!!
        assertEquals("sin vecinos, el más bajo", 1, empty.best)
        assertFalse(empty.better)

        assertNull(adviseChannel(radio(WifiBand.Band6, "1", "HE80"), all, emptyList()))
        assertNull("ningún candidato permitido", adviseChannel(radio(WifiBand.Band2, "3", "HE20"), listOf(3, 4), house2g))
    }

    private val factory = listOf(
        uciSection("radio0", "wifi-device", "band" to "2g", "channel" to "1", "htmode" to "HE20"),
        uciSection("default_radio0", "wifi-iface", "device" to "radio0", "network" to "lan", "mode" to "ap", "ssid" to "OpenWrt", "encryption" to "none"),
        uciSection("radio1", "wifi-device", "band" to "5g", "channel" to "36", "htmode" to "HE80"),
    )

    private fun TestScope.api(fake: FakeOpenWrt): WifiApi {
        fake.clock = { testScheduler.currentTime }
        factory.forEach { s -> fake.section(s.name, s.type, *s.options.map { (k, v) -> k to (v as UciValue.Text).value }.toTypedArray()) }
        return WifiApi(fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `el router escanea, la app propone y el cambio se escribe`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)
        fake.wifiScan = mapOf("radio0" to house2g.map { it.channel to it.signal })

        val advice = api.channelAdvice("radio0")!!
        assertEquals(11, advice.best)

        // La red de 2,4 GHz está emitiendo: cambiar de canal la reinicia.
        val state = api.state()
        val preview = channelPreview(state, state.radios.single { it.name == "radio0" }, advice.best, emptySet())
        assertEquals(listOf(txt(R.string.wifi_country_preview_restart)), preview.notes)

        api.setChannel("radio0", advice.best, phoneOnIt = false)
        assertEquals("11", fake.option("radio0", "channel"))
        assertTrue("ya está ahí: nada que hacer", channelPlan(listOf(uciSection("radio0", "wifi-device", "channel" to "11")), "radio0", 11).isEmpty())
    }

    @Test
    fun `si el router no puede escanear no hay consejo, y si no vuelve a emitir se deshace`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)
        fake.wifiScan = null
        assertNull(api.channelAdvice("radio0"))

        // Un canal que el país no permite: la radio no arranca.
        fake.wifiBadChannels = setOf("149")
        val result = runCatching { api.setChannel("radio1", 149, phoneOnIt = false) }
        advanceUntilIdle()
        assertTrue(result.exceptionOrNull() is ChangeRevertedException)
        assertEquals("36", fake.option("radio1", "channel"))
    }
}
