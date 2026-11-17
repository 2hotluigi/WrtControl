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
import kotlinx.serialization.json.put

/* Lo que se le pregunta al router sobre el Wi-Fi (ver Wireless.kt). Todo con el ACL de LuCI. */

/** `luci-rpc getWirelessDevices`: radio → su estado. */
internal suspend fun UbusCaller.wirelessDevices(): Map<String, JsonObject> =
    callAs<JsonObject>("luci-rpc", "getWirelessDevices").mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()

/** Lee el Wi-Fi. Con [withClients], también quién está conectado a cada red (si el router deja verlo). */
internal suspend fun UbusCaller.readWifi(withClients: Boolean): WifiState = coroutineScope {
    val sections = async { uciSections("wireless") }
    val devices = async { wirelessDevices() }
    val bare = parseWifi(sections.await(), devices.await())
    if (!withClients) return@coroutineScope bare
    val clients = bare.networks.mapNotNull { it.ifname }.map { ifname -> async { assoclist(ifname)?.let { ifname to it } } }
    val htmodes = bare.radios.map { radio -> async { htmodes(radio.name)?.let { radio.name to it } } }
    parseWifi(sections.await(), devices.await(), clients.awaitAll().filterNotNull().toMap(), htmodes.awaitAll().filterNotNull().toMap())
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

/** Las MAC conectadas a [ifname], o null si el router no deja verlo. */
private suspend fun UbusCaller.assoclist(ifname: String): List<String>? = iwinfo("assoclist", ifname)?.let { reply ->
    val clients = (reply["results"] as? JsonArray).orEmpty()
    clients.mapNotNull { ((it as? JsonObject)?.get("mac") as? JsonPrimitive)?.contentOrNull?.uppercase() }
}

/** Las redes que oye [radio] ahora (`iwinfo scan`, unos segundos), o null si no se puede escanear. */
internal suspend fun UbusCaller.scan(radio: String): List<SeenNetwork>? = iwinfo("scan", radio)?.let(::parseScan)

/** Los canales en los que [radio] puede emitir con su país (`iwinfo freqlist`), o null si no se sabe. */
internal suspend fun UbusCaller.allowedChannels(radio: String): List<Int>? = iwinfo("freqlist", radio)?.let(::parseAllowedChannels)
