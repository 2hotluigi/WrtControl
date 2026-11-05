package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.ubus.FakeRouter
import dev.angel.wrtcontrol.ubus.RecordedCall
import dev.angel.wrtcontrol.ubus.Reply
import dev.angel.wrtcontrol.ubus.ok
import dev.angel.wrtcontrol.ubus.status
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * OpenWrt simulado con lo que hace el rpcd real (uci.c, session.c) en lo que importa aquí:
 * cambios pendientes por sesión, `uci apply` con reversión, sesión reclamada mientras hay un
 * apply pendiente, y fw4 recargando con handles nuevos (o sin cargar si [fw4Accepts] es false).
 */
class FakeOpenWrt(
    /** Hora local del router en segundos (como si fuera UTC) y su desfase con UTC. */
    var localTime: Long = LocalDateTime.of(2026, 11, 4, 22, 20).toEpochSecond(ZoneOffset.UTC),
    var offsetSeconds: Long = -21_600,
) {
    val committed = linkedMapOf<String, MutableMap<String, JsonElement>>()
    val files = mutableMapOf<String, String>()
    var fw4Accepts = true

    /** rpcd sin copia que restaurar (otra sesión hizo `uci apply` sin reversión): rollback no cambia nada. */
    var snapshotLost = false
    var pendingApply: String? = null
    var tzStrings = mapOf("America/Mexico_City" to "CST6", "UTC" to "GMT0")

    private val staged = mutableMapOf<String, MutableList<(MutableMap<String, MutableMap<String, JsonElement>>) -> Unit>>()
    private var snapshot: Map<String, Map<String, JsonElement>>? = null
    private var sessions = 0
    private var handles = 100L
    private var loaded: JsonObject = render()

    val router = FakeRouter(::handle)
    val calls get() = router.calls.map { it.name }

    fun section(name: String, type: String, vararg options: Pair<String, Any>) {
        committed[name] = linkedMapOf<String, JsonElement>(".type" to JsonPrimitive(type)).apply {
            for ((k, v) in options) put(k, if (v is List<*>) JsonArray(v.map { JsonPrimitive(it.toString()) }) else JsonPrimitive(v.toString()))
        }
        reload()
    }

    private fun handle(call: RecordedCall): Reply = synchronized(this) {
        val a = call.args
        fun arg(key: String) = a[key]?.jsonPrimitive?.content
        when (call.name) {
            "session.login" -> {
                // rpcd devuelve la sesión del apply pendiente a cualquier login del mismo usuario.
                val sid = pendingApply ?: "tx${++sessions}"
                ok("""{"ubus_rpc_session":"$sid","timeout":300,"expires":300}""")
            }
            "session.access" -> ok("""{"access":true}""")
            "uci.get" -> ok(uciGet(call.sid, arg("config")!!, arg("type")).toString())
            "uci.add" -> stage(call.sid) { cfg ->
                cfg[arg("name")!!] = linkedMapOf<String, JsonElement>(".type" to JsonPrimitive(arg("type")!!)).apply {
                    putAll(a["values"]!!.jsonObject)
                }
            }.let { ok("""{"section":"${arg("name")}"}""") }
            "uci.delete" -> stage(call.sid) { cfg -> cfg.remove(arg("section")!!) }.let { ok() }
            "uci.set" -> stage(call.sid) { cfg -> cfg[arg("section")!!]?.putAll(a["values"]!!.jsonObject) }.let { ok() }
            "uci.apply" -> apply(call.sid, a["rollback"]?.jsonPrimitive?.content == "true")
            "uci.confirm" -> when (pendingApply) {
                null -> status(5)
                call.sid -> { pendingApply = null; snapshot = null; ok() }
                else -> status(6)
            }
            "uci.rollback" -> when (pendingApply) {
                null -> status(5)
                call.sid -> {
                    if (!snapshotLost) {
                        committed.clear()
                        snapshot!!.forEach { (k, v) -> committed[k] = v.toMutableMap() }
                    }
                    pendingApply = null
                    snapshot = null
                    reload()
                    ok()
                }
                else -> status(6)
            }
            "system.info" -> ok("""{"localtime":$localTime,"uptime":100}""")
            "luci.getUnixtime" -> ok("""{"result":${localTime - offsetSeconds}}""")
            "luci.getTimezones" -> ok(buildJsonObject { tzStrings.forEach { (z, tz) -> putJsonObject(z) { put("tzstring", tz) } } }.toString())
            "file.write" -> { files[arg("path")!!] = arg("data")!!; ok() }
            "file.remove" -> { files.remove(arg("path")!!); ok() }
            "file.exec" -> ok(buildJsonObject { put("code", 0); put("stdout", loaded.toString()) }.toString())
            else -> status(3)
        }
    }

    private fun stage(sid: String, op: (MutableMap<String, MutableMap<String, JsonElement>>) -> Unit) {
        staged.getOrPut(sid) { mutableListOf() } += op
    }

    private fun apply(sid: String, rollback: Boolean): Reply {
        if (pendingApply != null && rollback) return status(6)
        val ops = staged.remove(sid).orEmpty()
        if (ops.isEmpty()) return status(5)
        snapshot = committed.mapValues { it.value.toMap() }
        val working = committed.mapValuesTo(linkedMapOf()) { it.value.toMutableMap() }
        ops.forEach { it(working) }
        committed.clear()
        committed.putAll(working)
        if (rollback) pendingApply = sid
        applySystemTimezone()
        reload()
        return ok()
    }

    /** Como /etc/init.d/system reload: el desfase sigue a la zona configurada. */
    private fun applySystemTimezone() {
        val tz = committed.values.firstOrNull { it[".type"]?.jsonPrimitive?.content == "system" }?.get("timezone")
            ?.jsonPrimitive?.content ?: return
        posixOffset(tz)?.let { offsetSeconds = it }
    }

    /** Desfase de una cadena TZ sin horario de verano ("CST6", "<-06>6", "GMT0"); con reglas, null. */
    private fun posixOffset(tz: String): Long? {
        if (',' in tz) return null
        val m = Regex("^(?:<[^>]+>|[A-Za-z]+)([+-]?)(\\d+)(?::(\\d+))?$").find(tz) ?: return null
        val seconds = m.groupValues[2].toLong() * 3600 + m.groupValues[3].ifEmpty { "0" }.toLong() * 60
        return if (m.groupValues[1] == "-") seconds else -seconds // en POSIX el signo va al revés
    }

    private fun uciGet(sid: String, config: String, type: String?): JsonObject {
        val view = committed.mapValuesTo(linkedMapOf()) { it.value.toMutableMap() }
        staged[sid]?.forEach { it(view) }
        return buildJsonObject {
            putJsonObject("values") {
                view.entries.forEachIndexed { index, (name, values) ->
                    val sectionType = values[".type"]!!.jsonPrimitive.content
                    val inConfig = if (config == "system") sectionType == "system" else sectionType != "system"
                    if (!inConfig || (type != null && type != sectionType)) return@forEachIndexed
                    putJsonObject(name) {
                        put(".name", name)
                        put(".index", index)
                        values.forEach { (k, v) -> put(k, v) }
                    }
                }
            }
        }
    }

    /** Como fw4: si acepta la configuración, recarga la tabla con handles nuevos; si no, deja la anterior. */
    private fun reload() {
        if (fw4Accepts) loaded = render()
    }

    /** fw4 recarga con `flush table`: una cadena que dejó de incluirse sigue existiendo, vacía. */
    private var blockChainCreated = false

    private fun render(): JsonObject = buildJsonObject {
        putJsonArray("nftables") {
            addJsonObject { putJsonObject("table") { put("family", "inet"); put("name", "fw4"); put("handle", 1) } }
            addJsonObject { putJsonObject("chain") { put("family", "inet"); put("table", "fw4"); put("name", "forward") } }
            val include = committed["wrtc_include"]
            val active = include != null && files.containsKey(include["path"]?.jsonPrimitive?.content)
            if (active) blockChainCreated = true
            if (blockChainCreated) {
                addJsonObject { putJsonObject("chain") { put("family", "inet"); put("table", "fw4"); put("name", BLOCK_CHAIN) } }
            }
            if (active) repeat(2) {
                addJsonObject {
                    putJsonObject("rule") {
                        put("family", "inet")
                        put("table", "fw4")
                        put("chain", BLOCK_CHAIN)
                        put("handle", ++handles)
                        put("comment", "!wrtcontrol: bloqueo")
                    }
                }
            }
            for ((_, values) in committed) {
                if (values[".type"]?.jsonPrimitive?.content != "rule") continue
                addJsonObject {
                    putJsonObject("rule") {
                        put("family", "inet")
                        put("table", "fw4")
                        put("chain", "mangle_forward")
                        put("handle", ++handles)
                        values["name"]?.let { put("comment", "!fw4: ${it.jsonPrimitive.content}") }
                        put("expr", buildJsonArray { })
                    }
                }
            }
        }
    }
}
