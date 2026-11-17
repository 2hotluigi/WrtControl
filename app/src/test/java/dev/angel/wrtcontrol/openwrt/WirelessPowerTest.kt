package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ui.home.powerPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La potencia de cada radio y la señal de quien está conectado. */
@OptIn(ExperimentalCoroutinesApi::class)
class WirelessPowerTest {

    @Test
    fun `el nivel se lee por lo lejos que queda del tope`() {
        assertEquals("sin poner es toda", WifiPower.Full, wifiPower(null, 20))
        assertEquals(WifiPower.Full, wifiPower(20, 20))
        assertEquals("un par de dB no cambian nada", WifiPower.Full, wifiPower(18, 20))
        assertEquals(WifiPower.Medium, wifiPower(14, 20))
        assertEquals(WifiPower.Low, wifiPower(8, 20))
        assertEquals("sin saber el tope no se adivina", WifiPower.Full, wifiPower(5, null))
    }

    @Test
    fun `toda quita el txpower, y lo demas lo fija por debajo del tope`() {
        val plain = listOf(uciSection("radio0", "wifi-device", "band" to "2g"))
        val lowered = listOf(uciSection("radio0", "wifi-device", "band" to "2g", "txpower" to "14"))
        assertEquals(listOf(WifiChange.Set("radio0", mapOf("txpower" to "14"))), powerPlan(plain, "radio0", WifiPower.Medium, 20))
        assertEquals(listOf(WifiChange.Set("radio0", mapOf("txpower" to "8"))), powerPlan(plain, "radio0", WifiPower.Low, 20))
        assertEquals("nunca por debajo de 0", listOf(WifiChange.Set("radio0", mapOf("txpower" to "0"))), powerPlan(plain, "radio0", WifiPower.Low, 10))
        assertEquals(listOf(WifiChange.DropOption("radio0", "txpower")), powerPlan(lowered, "radio0", WifiPower.Full, 20))
        assertTrue("ya está así", powerPlan(lowered, "radio0", WifiPower.Medium, 20).isEmpty())
        assertTrue("ya es toda", powerPlan(plain, "radio0", WifiPower.Full, 20).isEmpty())
        assertTrue("sin saber el tope no se toca", powerPlan(plain, "radio0", WifiPower.Medium, null).isEmpty())
        assertTrue(powerPlan(plain, "radio9", WifiPower.Low, 20).isEmpty())
    }

    @Test
    fun `la senal se dice con palabras`() {
        assertEquals(SignalLevel.Excellent, signalLevel(-40))
        assertEquals(SignalLevel.Good, signalLevel(-60))
        assertEquals(SignalLevel.Fair, signalLevel(-72))
        assertEquals(SignalLevel.Weak, signalLevel(-80))
    }

    private val factory = listOf(
        uciSection("radio0", "wifi-device", "band" to "2g", "channel" to "1", "htmode" to "HE20"),
        uciSection("default_radio0", "wifi-iface", "device" to "radio0", "network" to "lan", "mode" to "ap", "ssid" to "Casa", "encryption" to "none"),
    )

    private fun TestScope.api(fake: FakeOpenWrt): WifiApi {
        fake.clock = { testScheduler.currentTime }
        factory.forEach { s -> fake.section(s.name, s.type, *s.options.map { (k, v) -> k to (v as UciValue.Text).value }.toTypedArray()) }
        return WifiApi(fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `el router dice el tope y la senal de cada conectado, y la potencia se cambia y se quita`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)
        fake.wifiClients = mapOf("phy0-ap0" to listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"))
        fake.wifiSignals = mapOf("AA:00:00:00:00:01" to -48)

        val state = api.state()
        val radio = state.radios.single()
        assertEquals(20, radio.maxPower)
        assertNull(radio.txpower)
        val network = state.networks.single()
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), network.clients)
        assertEquals("el que no tiene señal medida no sale con 0", mapOf("AA:00:00:00:00:01" to -48), network.signals)

        // La red emite: bajar la potencia la reinicia.
        val preview = powerPreview(state, radio, WifiPower.Medium, emptySet())
        assertEquals(listOf(txt(R.string.wifi_country_preview_restart)), preview.notes)
        assertEquals(txt(R.string.wifi_power_full), preview.lines.single().before)

        api.setPower("radio0", WifiPower.Medium, phoneOnIt = false)
        assertEquals("14", fake.option("radio0", "txpower"))
        assertEquals(WifiPower.Medium, api.state().radios.single().let { wifiPower(it.txpower, it.maxPower) })

        api.setPower("radio0", WifiPower.Full, phoneOnIt = false)
        assertNull(fake.option("radio0", "txpower"))
    }
}
