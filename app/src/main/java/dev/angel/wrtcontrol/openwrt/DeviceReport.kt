package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.SessionAcl
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/*
 * El informe del router: lo que la app lee de él para saber cómo es (las respuestas de ubus y las
 * configuraciones de UCI), anonimizado (ver ReportAnonymizer.kt) para que su dueño lo comparta y
 * la app se pruebe contra routers que no son los nuestros (ROADMAP, Hito G).
 *
 * Solo lo que describe el router. No van los aparatos de la casa (concesiones DHCP, pistas de
 * host, quién está en el Wi-Fi), el registro del sistema ni las redes vecinas: eso es de las
 * personas, no del router.
 */

/** Versión del formato. Subirla si cambia lo de dentro de forma que confundiría a un lector anterior. */
const val REPORT_FORMAT = 1

/** Las configuraciones de UCI que lee la app. */
internal val REPORT_CONFIGS = listOf("network", "wireless", "firewall", "dhcp", "system", "uhttpd", "dropbear", "sqm", "upnpd", "ddns")

/** Una llamada de ubus del informe. [personalNames]: su `name` es de una persona o aparato. */
internal data class ReportCall(
    val target: String,
    val method: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val personalNames: Boolean = false,
)

private val BASE_CALLS = listOf(
    ReportCall("system", "board"),
    ReportCall("system", "info"),
    ReportCall("luci", "getFeatures"),
    ReportCall("network.interface", "dump"),
    ReportCall("network.device", "status"),
    ReportCall("luci-rpc", "getNetworkDevices"),
    ReportCall("luci-rpc", "getWirelessDevices"),
    ReportCall("luci.wireguard", "getWgInstances", personalNames = true),
    ReportCall("luci.upnp", "get_status"),
    ReportCall("rc", "list"),
)

/** Por radio: qué sabe hacer. Ni `assoclist` (quién está) ni `scan` (las redes de los vecinos). */
private val RADIO_METHODS = listOf("info", "freqlist", "txpowerlist")

/** Lo leído del router, tal cual: respuesta o error de cada cosa. */
internal data class RawReport(
    val objects: Result<Set<String>>,
    val acl: Result<SessionAcl>,
    val calls: List<Pair<ReportCall, Result<JsonElement?>>>,
    val uci: Map<String, Result<JsonElement?>>,
)

/** El informe del router conectado (ver arriba). */
class DeviceReportApi(private val ubus: UbusClient) {
    /** Lee el router y devuelve el informe anonimizado, en JSON. [appVersion]: la de la app. */
    suspend fun build(appVersion: String): String = buildReport(ubus.collectReport(), appVersion)
}

/** Lee del router lo que va en el informe. Lo que no deja leer va como error, no hace fallar todo. */
internal suspend fun UbusClient.collectReport(): RawReport = coroutineScope {
    val objects = async { attempt { objects() } }
    val acl = async { attempt { acl() } }
    val base = BASE_CALLS.map { c -> async { c to attempt { call(c.target, c.method, c.args) } } }.awaitAll()
    val radios = (base.firstOrNull { it.first.method == "getWirelessDevices" }?.second?.getOrNull() as? JsonObject)?.keys.orEmpty()
    val perRadio = radios.flatMap { radio ->
        RADIO_METHODS.map { m ->
            val c = ReportCall("iwinfo", m, buildJsonObject { put("device", radio) })
            async { c to attempt { call(c.target, c.method, c.args) } }
        }
    }.awaitAll()
    val uci = REPORT_CONFIGS.map { config ->
        async { config to attempt { call("uci", "get", buildJsonObject { put("config", config) }) } }
    }.awaitAll().toMap()
    RawReport(objects.await(), acl.await(), base + perRadio, uci)
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: UbusException.Network) {
    throw e // sin router no hay informe que valga
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    Result.failure(e)
}

/** Cómo queda en el informe un error: sin textos del teléfono ni del router (podrían llevar datos). */
private fun errorOf(e: Throwable): JsonPrimitive = JsonPrimitive(
    when (e) {
        is UbusException.AccessDenied -> "access denied"
        is UbusException.Status -> "status ${e.code}"
        is UbusException -> e.javaClass.simpleName
        else -> "error"
    },
)

private val reportJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * El informe anonimizado, en JSON. [appVersion]: la versión de la app que lo hace. Falla con un
 * error para el usuario si, después de anonimizar, queda algo que no debería salir del teléfono.
 */
internal fun buildReport(raw: RawReport, appVersion: String): String {
    val a = ReportAnonymizer()
    fun section(result: Result<JsonElement?>, anonymize: (JsonElement) -> JsonElement): JsonElement =
        result.fold(
            onSuccess = { it?.let(anonymize) ?: JsonObject(emptyMap()) },
            onFailure = { JsonObject(mapOf("error" to errorOf(it))) },
        )
    val report = buildJsonObject {
        put("format", REPORT_FORMAT)
        put("app", appVersion)
        put("objects", section(raw.objects.map { JsonArray(it.sorted().map(::JsonPrimitive)) }) { a.json(it) })
        put("acl", section(raw.acl.map { reportJson.encodeToJsonElement(it) }) { a.json(it) })
        put(
            "calls",
            JsonArray(
                raw.calls.map { (c, result) ->
                    buildJsonObject {
                        put("call", "${c.target}.${c.method}")
                        if (c.args.isNotEmpty()) put("args", a.json(c.args))
                        put("result", section(result) { a.json(it, c.personalNames) })
                    }
                },
            ),
        )
        put(
            "uci",
            JsonObject(
                raw.uci.mapValues { (_, result) ->
                    section(result) { got -> ((got as? JsonObject)?.get("values") as? JsonObject)?.let(a::uci) ?: a.json(got) }
                },
            ),
        )
    }
    val text = reportJson.encodeToString(JsonObject.serializer(), report)
    if (a.leaks(text).isNotEmpty()) throw UserFacingException(txt(R.string.report_error_leak))
    return text
}
