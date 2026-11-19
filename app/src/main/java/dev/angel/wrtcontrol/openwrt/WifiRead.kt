package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/* Lo que se le pregunta al router sobre el Wi-Fi (ver Wireless.kt). Todo con el ACL de LuCI. */

/*
 * Un router sin radios (un PC con OpenWrt, la VM) no tiene /etc/config/wireless, y tanto `uci get`
 * como `luci-rpc getWirelessDevices` contestan "no existe": es que no tiene Wi-Fi, no un error.
 */
private suspend fun <T> noWifiIfMissing(empty: T, read: suspend () -> T): T = try {
    read()
} catch (e: UbusException.Status) {
    if (e.code == UBUS_NOT_FOUND) empty else throw e
}

/** `luci-rpc getWirelessDevices`: radio → su estado. */
internal suspend fun UbusCaller.wirelessDevices(): Map<String, JsonObject> = noWifiIfMissing(emptyMap()) {
    callAs<JsonObject>("luci-rpc", "getWirelessDevices").mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
}

/** Las secciones de `/etc/config/wireless`. */
internal suspend fun UbusCaller.wirelessSections(): List<UciSection> = noWifiIfMissing(emptyList()) { uciSections("wireless") }

/** Lee el Wi-Fi. Con [withClients], también quién está conectado a cada red (si el router deja verlo). */
internal suspend fun UbusCaller.readWifi(withClients: Boolean): WifiState = coroutineScope {
    val sections = async { wirelessSections() }
    val devices = async { wirelessDevices() }
    val home = async { homeLayout().home }
    val bare = parseWifi(sections.await(), devices.await(), home = home.await())
    if (!withClients) return@coroutineScope bare
    val asked = bare.networks.mapNotNull { it.ifname }.map { ifname -> async { assoclist(ifname)?.let { ifname to it } } }
    val htmodes = bare.radios.map { radio -> async { htmodes(radio.name)?.let { radio.name to it } } }
    val powers = bare.radios.map { radio -> async { maxPower(radio.name)?.let { radio.name to it } } }
    val clients = asked.awaitAll().filterNotNull().toMap()
    val extras = WifiExtras(
        signals = clients.mapValues { (_, list) -> list.mapNotNull { (mac, signal) -> signal?.let { mac to it } }.toMap() },
        maxPower = powers.awaitAll().filterNotNull().toMap(),
    )
    val macs = clients.mapValues { (_, list) -> list.map { it.first } }
    parseWifi(sections.await(), devices.await(), macs, htmodes.awaitAll().filterNotNull().toMap(), extras, home.await())
}

/** Una pregunta a `iwinfo` sobre [device] (radio o interfaz), o null si el router no deja hacerla. */
private suspend fun UbusCaller.iwinfo(method: String, device: String): JsonObject? = try {
    callAs<JsonObject>("iwinfo", method, buildJsonObject { put("device", device) })
} catch (e: CancellationException) {
    throw e
} catch (_: UbusException) {
    null
}

/** Los estándares y anchos que admite [radio] (`iwinfo info`), o null si el router no deja verlo. */
internal suspend fun UbusCaller.htmodes(radio: String): List<String>? =
    (iwinfo("info", radio)?.get("htmodes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Los conectados a [ifname] (MAC y señal en dBm, si la dice), o null si el router no deja verlo. */
private suspend fun UbusCaller.assoclist(ifname: String): List<Pair<String, Int?>>? = iwinfo("assoclist", ifname)?.let { reply ->
    (reply["results"] as? JsonArray).orEmpty().mapNotNull { element ->
        val client = element as? JsonObject
        val mac = (client?.get("mac") as? JsonPrimitive)?.contentOrNull?.uppercase()
        // Sin señal medida, iwinfo da 0: no es una señal.
        val signal = (client?.get("signal") as? JsonPrimitive)?.intOrNull?.takeIf { it < 0 }
        mac?.let { it to signal }
    }
}

/** El tope de potencia de [radio] en dBm (`iwinfo txpowerlist`), o null si no se sabe. */
internal suspend fun UbusCaller.maxPower(radio: String): Int? = iwinfo("txpowerlist", radio)?.let(::parseMaxPower)

/** Las redes que oye [radio] ahora (`iwinfo scan`, unos segundos), o null si no se puede escanear. */
internal suspend fun UbusCaller.scan(radio: String): List<SeenNetwork>? = iwinfo("scan", radio)?.let(::parseScan)

/** Los canales en los que [radio] puede emitir con su país (`iwinfo freqlist`), o null si no se sabe. */
internal suspend fun UbusCaller.allowedChannels(radio: String): List<Int>? = iwinfo("freqlist", radio)?.let(::parseAllowedChannels)
