package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Valor de una opción UCI: `option` (un texto) o `list` (varios). */
sealed interface UciValue {
    data class Text(val value: String) : UciValue

    data class Items(val values: List<String>) : UciValue
}

/** Sección UCI sin los campos internos de rpcd (`.name`, `.type`, `.index`...). */
data class UciSection(val name: String, val type: String, val options: Map<String, UciValue>) {

    fun text(key: String): String? = when (val v = options[key]) {
        is UciValue.Text -> v.value
        is UciValue.Items -> v.values.firstOrNull()
        null -> null
    }

    /** Como hace fw4, un `option` con espacios también cuenta como lista. */
    fun items(key: String): List<String> = when (val v = options[key]) {
        is UciValue.Text -> v.value.split(' ', '\t').filter { it.isNotEmpty() }
        is UciValue.Items -> v.values
        null -> emptyList()
    }

    fun valuesJson(): JsonObject = buildJsonObject {
        for ((key, value) in options) when (value) {
            is UciValue.Text -> put(key, value.value)
            is UciValue.Items -> putJsonArray(key) { value.values.forEach { add(it) } }
        }
    }
}

/** Atajo para construir secciones: los `List<String>` se guardan como `list`. */
fun uciSection(name: String, type: String, vararg options: Pair<String, Any?>): UciSection =
    UciSection(
        name, type,
        options.mapNotNull { (key, value) ->
            when (value) {
                null -> null
                is String -> key to UciValue.Text(value)
                is List<*> -> key to UciValue.Items(value.map { it.toString() })
                else -> key to UciValue.Text(value.toString())
            }
        }.toMap(),
    )

/** Decodifica la respuesta de `uci get` sin sección: `{ "values": { nombre: { ".type": ..., opción: ... } } }`. */
fun parseUciSections(response: JsonObject): List<UciSection> {
    val values = response["values"] as? JsonObject ?: return emptyList()
    return values.mapNotNull { (name, element) ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val type = (obj[".type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val index = (obj[".index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
        val options = obj.filterKeys { !it.startsWith(".") }.mapValues { (_, v) ->
            when (v) {
                is JsonArray -> UciValue.Items(v.map { (it as JsonPrimitive).content })
                else -> UciValue.Text((v as JsonPrimitive).content)
            }
        }
        index to UciSection(name, type, options)
    }.sortedBy { it.first }.map { it.second }
}

// ---------- llamadas uci / file / luci ----------

suspend fun UbusCaller.uciSections(config: String, type: String? = null): List<UciSection> {
    val args = buildJsonObject {
        put("config", config)
        if (type != null) put("type", type)
    }
    return parseUciSections(callAs<JsonObject>("uci", "get", args))
}

suspend fun UbusCaller.uciAdd(config: String, section: UciSection) {
    call("uci", "add", buildJsonObject {
        put("config", config)
        put("type", section.type)
        put("name", section.name)
        put("values", section.valuesJson())
    })
}

suspend fun UbusCaller.uciDelete(config: String, section: String) {
    call("uci", "delete", buildJsonObject {
        put("config", config)
        put("section", section)
    })
}

suspend fun UbusCaller.uciSet(config: String, section: String, values: Map<String, String>) {
    call("uci", "set", buildJsonObject {
        put("config", config)
        put("section", section)
        putJsonObject("values") { values.forEach { (k, v) -> put(k, v) } }
    })
}

/** Aplica los cambios pendientes de la sesión. Con [rollback], el router los deshace solo si no se confirman a tiempo. */
suspend fun UbusCaller.uciApply(rollback: Boolean, timeoutSeconds: Int) {
    call("uci", "apply", buildJsonObject {
        put("rollback", rollback)
        put("timeout", timeoutSeconds)
    })
}

suspend fun UbusCaller.uciConfirm() {
    call("uci", "confirm")
}

suspend fun UbusCaller.uciRollback() {
    call("uci", "rollback")
}

suspend fun UbusCaller.fileWrite(path: String, data: String) {
    call("file", "write", buildJsonObject {
        put("path", path)
        put("data", data)
    })
}

suspend fun UbusCaller.fileRemove(path: String) {
    call("file", "remove", buildJsonObject { put("path", path) })
}

@Serializable
data class ExecResult(val code: Int = -1, val stdout: String = "", val stderr: String = "")

/** Solo funciona con los comandos exactos que permite el ACL (p. ej. el listado del firewall de LuCI). */
suspend fun UbusCaller.exec(command: String, params: List<String>): ExecResult =
    callAs("file", "exec", buildJsonObject {
        put("command", command)
        putJsonArray("params") { params.forEach { add(it) } }
    })

/** Hora local del router en segundos (lo que marca su reloj, como si fuera UTC). */
suspend fun UbusCaller.routerLocalTime(): Long = callAs<SystemInfo>("system", "info").localtime

/** Hora UTC del router (luci.getUnixtime), o null si el ACL no lo permite. */
suspend fun UbusCaller.routerUnixTime(): Long? = try {
    callAs<JsonObject>("luci", "getUnixtime")["result"]?.let { (it as? JsonPrimitive)?.longOrNull }
} catch (_: UbusException.AccessDenied) {
    null
}

/** Zonas horarias que conoce LuCI: nombre (p. ej. "America/Mexico_City") → cadena TZ ("CST6"). */
suspend fun UbusCaller.routerTimezones(): Map<String, String> =
    callAs<JsonObject>("luci", "getTimezones").mapNotNull { (zone, info) ->
        val tz = (info as? JsonObject)?.get("tzstring")?.let { (it as? JsonPrimitive)?.contentOrNull }
        tz?.let { zone to it }
    }.toMap()

/** Opciones de la primera sección `system` (hostname, zonename, timezone...). */
suspend fun UbusCaller.systemSection(): UciSection? = uciSections("system", "system").firstOrNull()

/** Instancias de un servicio de procd (nombre → en marcha), o null si el ACL no lo permite. */
suspend fun UbusCaller.serviceInstances(service: String): Map<String, Boolean>? {
    val response = try {
        callAs<JsonObject>("service", "list", buildJsonObject { put("name", service) })
    } catch (_: UbusException.AccessDenied) {
        return null
    }
    val instances = (response[service] as? JsonObject)?.get("instances") as? JsonObject ?: return emptyMap()
    return instances.mapValues { (_, v) -> ((v as? JsonObject)?.get("running") as? JsonPrimitive)?.content == "true" }
}

enum class Lookup { Resolved, NxDomain, Failed, Unavailable }

/**
 * Pregunta [domain] al DNS del router en [port] con el nslookup de BusyBox, el mismo que usa la
 * página de diagnóstico de LuCI (su ACL lo permite). [Lookup.Unavailable] si no hay permiso.
 */
suspend fun UbusCaller.lookup(domain: String, port: Int = 53): Lookup {
    val result = try {
        exec("/usr/bin/nslookup", listOf("-type=a", "-timeout=2", "-retry=1", "-port=$port", domain, "127.0.0.1"))
    } catch (_: UbusException.AccessDenied) {
        return Lookup.Unavailable
    } catch (e: UbusException.Status) {
        if (e.code == 6) return Lookup.Unavailable
        throw e
    }
    val out = result.stdout + "\n" + result.stderr
    return when {
        "NXDOMAIN" in out -> Lookup.NxDomain
        out.lines().any { it.startsWith("Name:") } -> Lookup.Resolved
        else -> Lookup.Failed
    }
}
