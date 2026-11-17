package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.plural
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ui.home.countryName
import dev.angel.wrtcontrol.ui.home.countryPreview
import dev.angel.wrtcontrol.ui.home.currentWifi
import dev.angel.wrtcontrol.ui.home.phoneOnWifi
import dev.angel.wrtcontrol.ui.home.wifiPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El Wi-Fi de casa: cómo se lee, qué se le deja guardar y qué se cambia en `wireless`. */
@OptIn(ExperimentalCoroutinesApi::class)
class WirelessTest {

    /** Como sale de fábrica (y como está el GL-MT6000 de la casa): las dos redes "OpenWrt", abiertas y apagadas. */
    private val factory = listOf(
        uciSection("radio0", "wifi-device", "band" to "2g", "channel" to "1"),
        uciSection("default_radio0", "wifi-iface", "device" to "radio0", "network" to "lan", "mode" to "ap", "ssid" to "OpenWrt", "encryption" to "none", "disabled" to "1"),
        uciSection("radio1", "wifi-device", "band" to "5g", "channel" to "36"),
        uciSection("default_radio1", "wifi-iface", "device" to "radio1", "network" to "lan", "mode" to "ap", "ssid" to "OpenWrt", "encryption" to "none", "disabled" to "1"),
    )

    private fun devices(vararg emitting: Pair<String, String>): Map<String, JsonObject> = listOf("radio0", "radio1").associateWith { radio ->
        buildJsonObject {
            put("up", true)
            put("pending", false)
            putJsonArray("interfaces") {
                emitting.filter { it.first.endsWith(radio) }.forEach { (section, ifname) -> addJsonObject { put("section", section); put("ifname", ifname) } }
            }
        }
    }

    @Test
    fun `de fabrica - apagado, abierto y con las dos bandas`() {
        val state = parseWifi(factory, devices())
        assertEquals(listOf(WifiBand.Band2, WifiBand.Band5), state.radios.map { it.band })
        assertEquals(listOf("default_radio0", "default_radio1"), state.home.map { it.section })
        assertTrue(state.homeOn.isEmpty())
        assertEquals(WifiEdit("OpenWrt", "", WifiSecurity.Open, emptySet()), currentWifi(state))
        assertFalse(state.homeDiffers)
    }

    @Test
    fun `la seguridad se lee aunque lleve el cifrado detras, y la desconocida no se adivina`() {
        assertEquals(WifiSecurity.Wpa2, wifiSecurity("psk2+ccmp"))
        assertEquals(WifiSecurity.Wpa2Wpa3, wifiSecurity("sae-mixed"))
        assertEquals(WifiSecurity.Open, wifiSecurity(null))
        assertNull(wifiSecurity("wep-open"))
        assertNull(wifiSecurity("wpa2"))
    }

    @Test
    fun `nombre y clave validos e invalidos`() {
        fun problem(ssid: String, key: String, security: WifiSecurity = WifiSecurity.Wpa2Wpa3) = wifiProblem(WifiEdit(ssid, key, security, setOf("radio0")))
        assertNull(problem("Casa", "12345678"))
        assertNull(problem("Casa", "a".repeat(64).replace('a', 'f')))
        assertNull(problem("Casa", "", WifiSecurity.Open))
        assertEquals(txt(R.string.wifi_error_ssid_empty), problem(" ", "12345678"))
        assertEquals(txt(R.string.wifi_error_ssid_long), problem("ñ".repeat(17), "12345678"))
        assertEquals(txt(R.string.wifi_error_key), problem("Casa", "1234567"))
        assertEquals(txt(R.string.wifi_error_key), problem("Casa", "contraseña1"))
        assertEquals(txt(R.string.wifi_error_key), problem("Casa", "x".repeat(64)))
    }

    @Test
    fun `encender con nombre y clave toca solo las redes de casa`() {
        val plan = wifiPlan(factory, WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2Wpa3, setOf("radio1")))
        val wanted = mapOf("ssid" to "Casa", "encryption" to "sae-mixed", "key" to "clave-segura")
        assertEquals(
            listOf(WifiChange.Set("default_radio0", wanted), WifiChange.Set("default_radio1", wanted + ("disabled" to "0"))),
            plan,
        )
    }

    @Test
    fun `sin red de casa en una radio se crea, y una radio apagada se enciende`() {
        val sections = listOf(uciSection("radio0", "wifi-device", "band" to "2g", "disabled" to "1"))
        val plan = wifiPlan(sections, WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2, setOf("radio0")))
        assertEquals(WifiChange.Set("radio0", mapOf("disabled" to "0")), plan[0])
        val added = (plan[1] as WifiChange.Add).section
        assertEquals("home_radio0", added.name)
        assertEquals(listOf("radio0", "ap", "lan", "Casa", "psk2", "clave-segura", "0"), listOf("device", "mode", "network", "ssid", "encryption", "key", "disabled").map { added.text(it) })
    }

    @Test
    fun `quitar la clave la borra, y sin cambios no hay nada que hacer`() {
        val secured = factory.map { s ->
            if (s.type == "wifi-iface") uciSection(s.name, s.type, "device" to s.text("device"), "network" to "lan", "ssid" to "Casa", "encryption" to "psk2", "key" to "clave-segura") else s
        }
        val open = wifiPlan(secured, WifiEdit("Casa", "", WifiSecurity.Open, setOf("radio0", "radio1")))
        assertTrue(WifiChange.DropOption("default_radio0", "key") in open)
        assertTrue(WifiChange.Set("default_radio0", mapOf("encryption" to "none")) in open)
        assertTrue(wifiPlan(secured, WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2, setOf("radio0", "radio1"))).isEmpty())
    }

    @Test
    fun `la vista previa avisa a quien se queda fuera, y encender sin mas no pregunta`() {
        val on = factory.map { s -> if (s.type == "wifi-iface") uciSection(s.name, s.type, "device" to s.text("device"), "network" to "lan", "ssid" to "Casa", "encryption" to "sae-mixed", "key" to "clave-segura") else s }
        val phone = "AA:00:00:00:00:01"
        val state = parseWifi(on, devices("default_radio0" to "phy0-ap0", "default_radio1" to "phy1-ap0"), mapOf("phy0-ap0" to listOf("BB:00:00:00:00:01"), "phy1-ap0" to listOf(phone, "BB:00:00:00:00:02")))
        assertTrue(phoneOnWifi(state, setOf(phone)))

        val renamed = wifiPreview(state, currentWifi(state).copy(ssid = "Casa nueva"), setOf(phone))
        assertEquals(listOf(plural(R.plurals.wifi_preview_clients, 2, 2), txt(R.string.wifi_preview_phone, "Casa nueva")), renamed.notes)

        // Apagar la de 2,4 GHz solo saca a quien está en ella.
        val off24 = wifiPreview(state, currentWifi(state).copy(radios = setOf("radio1")), setOf(phone))
        assertEquals(listOf(plural(R.plurals.wifi_preview_clients, 1, 1)), off24.notes)

        val fromFactory = parseWifi(factory, devices())
        val first = wifiPreview(fromFactory, WifiEdit("OpenWrt", "", WifiSecurity.Open, setOf("radio1")), emptySet())
        assertTrue("encender una banda sin nadie conectado no necesita aviso", first.notes.isEmpty())
    }

    private fun TestScope.api(fake: FakeOpenWrt): WifiApi {
        fake.clock = { testScheduler.currentTime }
        factory.forEach { s -> fake.section(s.name, s.type, *s.options.map { (k, v) -> k to (v as UciValue.Text).value }.toTypedArray()) }
        return WifiApi(fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `guardar enciende el Wi-Fi y lo confirma cuando el router ya emite`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)

        api.save(WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2Wpa3, setOf("radio1")), phoneOnIt = false)

        assertEquals("Casa", fake.option("default_radio1", "ssid"))
        assertEquals("0", fake.option("default_radio1", "disabled"))
        assertEquals("1", fake.option("default_radio0", "disabled"))
        val state = api.state()
        assertEquals(setOf("radio1"), state.homeOn)
        assertEquals("phy1-ap0", state.home.single { it.radio == "radio1" }.ifname)
    }

    @Test
    fun `si el router no vuelve a emitir, se deshace`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)
        fake.wifiBroken = true

        val result = runCatching { api.save(WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2Wpa3, setOf("radio1")), phoneOnIt = false) }
        advanceUntilIdle()

        assertTrue(result.exceptionOrNull() is ChangeRevertedException)
        assertEquals("OpenWrt", fake.option("default_radio1", "ssid"))
        assertEquals("1", fake.option("default_radio1", "disabled"))
    }

    @Test
    fun `una clave que no vale no llega al router`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)
        val result = runCatching { api.save(WifiEdit("Casa", "corta", WifiSecurity.Wpa2, setOf("radio1")), phoneOnIt = false) }
        assertTrue(result.exceptionOrNull() is UserFacingException)
        assertTrue(fake.calls.none { it == "uci.set" })
        assertEquals(JsonPrimitive("OpenWrt"), fake.committed["default_radio1"]!!["ssid"])
    }

    private fun withCountry(vararg byRadio: Pair<String, String>) = factory.map { s ->
        val country = byRadio.toMap()[s.name]
        if (country == null) s else uciSection(s.name, s.type, "band" to s.text("band"), "channel" to s.text("channel"), "country" to country)
    }

    @Test
    fun `el pais - falta de fabrica, 00 no cuenta y tiene que ser el mismo en todas las radios`() {
        assertNull(parseWifi(factory, devices()).country)
        assertNull("00 es el mundo", parseWifi(withCountry("radio0" to "00", "radio1" to "00"), devices()).country)
        assertNull("solo una radio lo tiene", parseWifi(withCountry("radio0" to "MX"), devices()).country)
        assertNull("no coinciden", parseWifi(withCountry("radio0" to "MX", "radio1" to "US"), devices()).country)
        assertEquals("MX", parseWifi(withCountry("radio0" to "MX", "radio1" to "mx"), devices()).country)

        assertEquals(listOf("radio1"), countryPlan(withCountry("radio0" to "MX"), "MX").map { it.section })
        assertTrue(countryPlan(withCountry("radio0" to "MX", "radio1" to "MX"), "MX").isEmpty())
        assertTrue(isCountryCode("MX"))
        assertFalse(isCountryCode("00"))
        assertFalse(isCountryCode("mx"))
    }

    @Test
    fun `los canales de radar y auto en 5 GHz pueden tardar, los demas no`() {
        fun radio(band: WifiBand, channel: String?) = WifiRadio("r", band, enabled = true, up = true, channel = channel)
        assertFalse(radio(WifiBand.Band5, "36").mayWaitForRadar)
        assertTrue(radio(WifiBand.Band5, "100").mayWaitForRadar)
        assertTrue(radio(WifiBand.Band5, "auto").mayWaitForRadar)
        assertFalse(radio(WifiBand.Band5, "149").mayWaitForRadar)
        assertFalse(radio(WifiBand.Band2, "auto").mayWaitForRadar)
    }

    @Test
    fun `la lista de paises del router sale sin el mundo ni los codigos de prueba`() = runTest {
        val api = api(FakeOpenWrt())
        assertEquals(listOf("ES", "MX", "US"), api.countries().map { it.code })
        assertEquals("México", countryName("MX", locale = java.util.Locale.forLanguageTag("es")))
        assertEquals("lo que diga el router si el teléfono no lo conoce", "Debug", countryName("DB", "Debug"))
    }

    @Test
    fun `poner el pais lo escribe en todas las radios y, con el Wi-Fi apagado, no pregunta`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)

        // Como de fábrica: las radios están levantadas, pero con sus redes apagadas no emiten nada.
        assertTrue(countryPreview(api.state(), "MX", emptySet()).notes.isEmpty())
        api.setCountry("MX", phoneOnIt = false)

        assertEquals("MX", fake.option("radio0", "country"))
        assertEquals("MX", fake.option("radio1", "country"))
        assertEquals("MX", api.state().country)
    }

    @Test
    fun `con el Wi-Fi emitiendo avisa del reinicio, y si no vuelve con ese pais se deshace`() = runTest {
        val fake = FakeOpenWrt()
        val api = api(fake)
        api.save(WifiEdit("Casa", "clave-segura", WifiSecurity.Wpa2Wpa3, setOf("radio0")), phoneOnIt = false)
        val state = api.state()
        assertEquals(listOf(txt(R.string.wifi_country_preview_restart)), countryPreview(state, "US", emptySet()).notes)

        api.setCountry("US", phoneOnIt = false)
        assertEquals("US", api.state().country)

        fake.wifiBadCountries = setOf("ES")
        val result = runCatching { api.setCountry("ES", phoneOnIt = false) }
        advanceUntilIdle()
        assertTrue(result.exceptionOrNull() is ChangeRevertedException)
        assertEquals("US", fake.option("radio0", "country"))
        assertEquals("US", fake.option("radio1", "country"))
    }
}
