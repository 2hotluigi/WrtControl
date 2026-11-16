package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/* Lo que el router cuenta del enlace de la salida a internet, y cómo se lee (ver LinkHealth.kt). */

@Serializable
internal data class NetworkDeviceJson(val link: Link? = null) {
    @Serializable
    data class Link(
        val carrier: Boolean? = null,
        val speed: Int? = null,
        @SerialName("down_count") val downCount: Int? = null,
    )
}

@Serializable
internal data class DeviceLinkJson(
    @SerialName("link-advertising") val own: List<String> = emptyList(),
    @SerialName("link-partner-advertising") val partner: List<String> = emptyList(),
)

@Serializable
internal data class LogJson(val log: List<Line> = emptyList()) {
    /** [time]: milisegundos UTC. */
    @Serializable
    data class Line(val msg: String = "", val time: Long = 0)
}

private val LINK_MODE = Regex("^(\\d+)base")

/** "1000baseT-F" → 1000; la más rápida de las que anuncia. */
internal fun maxAdvertisedMbps(modes: List<String>): Int? =
    modes.mapNotNull { LINK_MODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()

/** Hace cuántos segundos fue cada "Link is Down" de [device], contando desde [nowSeconds] (UTC). */
internal fun linkDrops(log: List<LogJson.Line>, device: String, nowSeconds: Long): List<Long> =
    log.filter { " $device: Link is Down" in " ${it.msg}" }.map { (nowSeconds - it.time / MILLIS).coerceAtLeast(0) }

private const val MILLIS = 1000

/** La tarjeta de la salida a internet: la de la red con la ruta por defecto o, si está caída, la de "wan". */
internal fun wanDevice(interfaces: List<NetInterface>): String? =
    (interfaces.firstOrNull { it.up && it.hasDefaultRoute && it.ipv4.isNotEmpty() } ?: interfaces.firstOrNull { it.name == "wan" })?.device

internal fun wanLink(
    device: String,
    json: NetworkDeviceJson.Link,
    advertised: DeviceLinkJson?,
    dropsAgoSeconds: List<Long>?,
    internet: Boolean?,
): WanLink {
    val carrier = json.carrier ?: false
    return WanLink(
        device = device,
        carrier = carrier,
        // Sin enlace, el kernel da -1.
        speedMbps = json.speed?.takeIf { carrier && it > 0 },
        dropsSinceBoot = json.downCount,
        ownMaxMbps = advertised?.own?.let(::maxAdvertisedMbps),
        partnerMaxMbps = advertised?.partner?.let(::maxAdvertisedMbps),
        dropsAgoSeconds = dropsAgoSeconds,
        internet = internet,
    )
}

/** Las líneas del registro que se miran para fechar las caídas del enlace. */
private const val LOG_LINES = 500

private const val DNS_TIMEOUT_MS = 3000

/** Dos sitios que no suelen fallar a la vez. */
private val PING_TARGETS = listOf("1.1.1.1", "9.9.9.9")

/** Lee del router el enlace de la salida a internet; null si no hay tarjeta con enlace que mirar. */
internal suspend fun UbusCaller.readWanLink(interfaces: List<NetInterface>): WanLink? = coroutineScope {
    val device = wanDevice(interfaces)
    val link = device?.let { optional { callAs<Map<String, NetworkDeviceJson>>("luci-rpc", "getNetworkDevices") }?.get(it)?.link }
    if (device == null || link == null) return@coroutineScope null
    val status = async { optional { callAs<DeviceLinkJson>("network.device", "status", buildJsonObject { put("name", device) }) } }
    // El registro solo hace falta si se ha caído alguna vez; el ping, si hay enlace.
    val drops = async { if ((link.downCount ?: 0) > 0) linkDropTimes(device) else emptyList() }
    val internet = async { if (link.carrier == true) pingInternet() else null }
    wanLink(device, link, status.await(), drops.await(), internet.await())
}

private suspend fun UbusCaller.linkDropTimes(device: String): List<Long>? {
    val now = optional { routerUnixTime() }
    val log = optional {
        callAs<LogJson>("log", "read", buildJsonObject { put("lines", LOG_LINES); put("stream", false); put("oneshot", true) })
    }
    return if (now == null || log == null) null else linkDrops(log.log, device, now)
}

/**
 * ¿Se llega a internet desde el router? Un ping y, si no contesta, una consulta de DNS: hay redes
 * que no dejan salir el ping (y no por eso están sin internet). null si no deja probar nada.
 */
private suspend fun UbusCaller.pingInternet(): Boolean? {
    val pinged = ping(PING_TARGETS.first()).let { if (it == false) ping(PING_TARGETS.last()) else it }
    return if (pinged == true) true else resolves() ?: pinged
}

/** El DNS del router pone nombre a una dirección de internet: la consulta salió y volvió. */
private suspend fun UbusCaller.resolves(): Boolean? = optional {
    callAs<JsonObject>(
        "network.rrdns",
        "lookup",
        buildJsonObject {
            putJsonArray("addrs") { PING_TARGETS.forEach { add(it) } }
            put("timeout", DNS_TIMEOUT_MS)
        },
    ).isNotEmpty()
}

private suspend fun UbusCaller.ping(target: String): Boolean? =
    optional { exec("/bin/ping", listOf("-c", "1", "-W", "2", target)) }?.let { it.code == 0 }

/** Lo que el router puede no tener o no dejar ver: null en vez de error. Si no se llega a él, sí falla. */
private suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (_: UbusException.AccessDenied) {
    null
} catch (_: UbusException.Status) {
    null
} catch (_: UbusException.Protocol) {
    null
}
