package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.ubus.FakeRouter
import dev.angel.wrtcontrol.ubus.RecordedCall
import dev.angel.wrtcontrol.ubus.Reply
import dev.angel.wrtcontrol.ubus.accessDenied
import dev.angel.wrtcontrol.ubus.ok
import dev.angel.wrtcontrol.ubus.status
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
 * apply pendiente, fw4 recargando con handles nuevos (o sin cargar si [fw4Accepts] es false) y
 * las instancias de dnsmasq que salen de la configuración `dhcp`.
 */
class FakeOpenWrt(
    /** Hora local del router en segundos (como si fuera UTC) y su desfase con UTC. */
    var localTime: Long = LocalDateTime.of(2026, 11, 4, 22, 20).toEpochSecond(ZoneOffset.UTC),
    var offsetSeconds: Long = -21_600,
) {
    /** Secciones confirmadas de todas las configuraciones; cada una lleva su configuración en ".config". */
    val committed = linkedMapOf<String, MutableMap<String, JsonElement>>()
    val files = mutableMapOf<String, String>()
    var fw4Accepts = true

    /** rpcd sin copia que restaurar (otra sesión hizo `uci apply` sin reversión): rollback no cambia nada. */
    var snapshotLost = false

    /**
     * El ACL de LuCI no deja `uci rollback` (luci-base.json solo trae apply y confirm): da "acceso
     * denegado" y el router lo deshace solo al vencer el plazo del apply. Con true, como un ACL que sí lo deja.
     */
    var rollbackAllowed = false

    /** Hora de las pruebas en ms (el tiempo virtual de runTest) para que venza el plazo de un apply. */
    var clock: () -> Long = { 0L }
    private var applyDeadline: Long? = null

    /** El DNS para familias no contesta desde el router (p. ej. el proveedor lo bloquea). */
    var familyDnsDown = false
    var pendingApply: String? = null
    var tzStrings = mapOf("America/Mexico_City" to "CST6", "UTC" to "GMT0")

    /** Interfaces de `network.interface dump`: nombre → dispositivo. */
    var interfaces = mapOf("lan" to "br-lan", "wan" to "eth1")

    /** Paquetes que cuenta cada regla propia al recargar ("doh j1" → 3). */
    var ownPackets = mapOf<String, Long>()

    /**
     * Tipos de registros propios de las instancias que dnsmasq no aplica ("cname", "hostrecord"),
     * como un dnsmasq que no los admite por instancia: la búsqueda segura no queda forzada.
     */
    var ignoredRecords = setOf<String>()

    /** El ACL no deja listar directorios (`file.list` da "permiso denegado"). */
    var fileListDenied = false

    /**
     * Se llama con cada llamada antes de atenderla: si devuelve una respuesta, se da esa en vez de
     * la normal. Sirve para simular fallos o cambios de otra sesión a mitad de una operación.
     */
    var intercept: ((RecordedCall) -> Reply?)? = null

    private val staged = mutableMapOf<String, MutableList<(MutableMap<String, MutableMap<String, JsonElement>>) -> Unit>>()
    private var snapshot: Map<String, Map<String, JsonElement>>? = null
    private var sessions = 0
    private var handles = 100L

    /** fw4 recarga con `flush table`: una cadena que dejó de incluirse sigue existiendo, vacía. */
    private val ownChains = linkedSetOf<String>()
    private var loaded: JsonObject = render()

    val router = FakeRouter(::handle)
    val calls get() = router.calls.map { it.name }

    fun section(name: String, type: String, vararg options: Pair<String, Any>, config: String? = null) {
        committed[name] = linkedMapOf<String, JsonElement>(".type" to JsonPrimitive(type), ".config" to JsonPrimitive(config ?: configOf(type))).apply {
            for ((k, v) in options) put(k, if (v is List<*>) JsonArray(v.map { JsonPrimitive(it.toString()) }) else JsonPrimitive(v.toString()))
        }
        reload()
    }

    /** Valor de texto de una opción de una sección confirmada. */
    fun option(section: String, key: String): String? = committed[section]?.get(key)?.let {
        if (it is JsonArray) it.joinToString(" ") { e -> e.jsonPrimitive.content } else it.jsonPrimitive.content
    }

    fun list(section: String, key: String): List<String> =
        (committed[section]?.get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun configOf(type: String) = when (type) {
        "system" -> "system"
        "dnsmasq", "dhcp", "host", "odhcpd", "cname", "hostrecord" -> "dhcp"
        "uhttpd" -> "uhttpd"
        "dropbear" -> "dropbear"
        "queue" -> "sqm"
        "upnpd", "perm_rule" -> "upnpd"
        "interface" -> "network"
        "service" -> "ddns"
        else -> if (type.startsWith("wireguard_")) "network" else "firewall"
    }

    private fun handle(call: RecordedCall): Reply = synchronized(this) {
        expireApply()
        intercept?.invoke(call)?.let { return@synchronized it }
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
                cfg[arg("name")!!] = linkedMapOf<String, JsonElement>(
                    ".type" to JsonPrimitive(arg("type")!!),
                    ".config" to JsonPrimitive(arg("config")!!),
                ).apply { putAll(a["values"]!!.jsonObject) }
            }.let { ok("""{"section":"${arg("name")}"}""") }
            "uci.delete" -> stage(call.sid) { cfg -> cfg.remove(arg("section")!!) }.let { ok() }
            "uci.set" -> stage(call.sid) { cfg -> cfg[arg("section")!!]?.putAll(a["values"]!!.jsonObject) }.let { ok() }
            "uci.apply" -> apply(call.sid, a["rollback"]?.jsonPrimitive?.content == "true", a["timeout"]?.jsonPrimitive?.content?.toLong() ?: 30)
            "uci.confirm" -> when (pendingApply) {
                null -> status(5)
                call.sid -> { pendingApply = null; snapshot = null; applyDeadline = null; ok() }
                else -> status(6)
            }
            "uci.rollback" -> when {
                !rollbackAllowed -> accessDenied
                pendingApply == null -> status(5)
                pendingApply == call.sid -> { revertApply(); ok() }
                else -> status(6)
            }
            "system.info" -> ok("""{"localtime":$localTime,"uptime":100}""")
            "luci.getUnixtime" -> ok("""{"result":${localTime - offsetSeconds}}""")
            "luci.getTimezones" -> ok(buildJsonObject { tzStrings.forEach { (z, tz) -> putJsonObject(z) { put("tzstring", tz) } } }.toString())
            "service.list" -> ok(serviceList().toString())
            "file.write" -> { files[arg("path")!!] = arg("data")!!; ok() }
            "file.remove" -> { files.remove(arg("path")!!); ok() }
            "file.list" -> if (fileListDenied) status(6) else {
                val dir = arg("path")!!.trimEnd('/') + "/"
                ok(buildJsonObject {
                    putJsonArray("entries") {
                        files.keys.filter { it.startsWith(dir) && '/' !in it.removePrefix(dir) }
                            .forEach { addJsonObject { put("name", it.removePrefix(dir)); put("type", "file") } }
                    }
                }.toString())
            }
            "network.interface.dump" -> ok(buildJsonObject {
                putJsonArray("interface") {
                    interfaces.forEach { (name, dev) ->
                        addJsonObject { put("interface", name); put("up", true); put("device", dev); put("l3_device", dev) }
                    }
                }
            }.toString())
            "file.exec" -> exec(arg("command")!!, a["params"]!!.jsonArray.map { it.jsonPrimitive.content })
            else -> status(3)
        }
    }

    private fun exec(command: String, params: List<String>): Reply {
        val stdout = when (command) {
            "/usr/sbin/nft" -> loaded.toString()
            "/usr/bin/nslookup" -> nslookup(params)
            else -> return status(6)
        }
        return ok(buildJsonObject { put("code", 0); put("stdout", stdout) }.toString())
    }

    /** Como el nslookup de BusyBox contra una de las instancias de dnsmasq. */
    private fun nslookup(params: List<String>): String {
        val port = params.firstOrNull { it.startsWith("-port=") }?.removePrefix("-port=")?.toInt() ?: 53
        val domain = params.first { !it.startsWith("-") }
        val resolved = "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:$port\n\nName:\t$domain\nAddress: 192.0.2.10\n"
        val noServer = ";; connection timed out; no servers could be reached\n"
        if (port == 53) return resolved
        val instance = committed.values.firstOrNull { it[".type"]?.jsonPrimitive?.content == "dnsmasq" && it["port"]?.jsonPrimitive?.content == "$port" }
            ?: return noServer
        val blocked = (instance["address"] as? JsonArray)?.map { it.jsonPrimitive.content.trim('/') }.orEmpty()
        if (blocked.any { domain == it || domain.endsWith(".$it") }) return "** server can't find $domain: NXDOMAIN\n"
        // Registros propios de la instancia (búsqueda segura): cname → host-record.
        val name = committed.entries.first { it.value === instance }.key
        fun ofInstance(type: String) = if (type in ignoredRecords) emptyList() else committed.values.filter {
            it[".type"]?.jsonPrimitive?.content == type && it["instance"]?.jsonPrimitive?.content == name
        }
        val target = ofInstance("cname").firstOrNull { domain in it["cname"]!!.jsonPrimitive.content.split(',') }
            ?.get("target")?.jsonPrimitive?.content
        if (target != null) {
            val ips = ofInstance("hostrecord").firstOrNull { (it["name"] as JsonArray).any { n -> n.jsonPrimitive.content == target } }
                ?.get("ip")?.let { (it as JsonArray).map { ip -> ip.jsonPrimitive.content } }.orEmpty()
            val v4 = ips.firstOrNull { '.' in it } ?: return noServer
            return "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:$port\n\n$domain\tcanonical name = $target\nName:\t$target\nAddress: $v4\n"
        }
        val servers = (instance["server"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val familyIps = setOf("1.1.1.3", "1.0.0.3", "185.228.168.168", "185.228.169.168")
        val family = servers.any { it in familyIps }
        // Reenvío por dominio (búsqueda segura sin nivel): "/bing.com/1.1.1.3".
        val forwardedToFamily = servers.any { s ->
            val parts = s.trim('/').split('/')
            parts.size == 2 && parts[1] in familyIps && (domain == parts[0] || domain.endsWith("." + parts[0]))
        }
        if ((family || forwardedToFamily) && familyDnsDown) return noServer
        // Como 1.1.1.3 y CleanBrowsing (salvo que el proveedor intercepte el DNS: [dnsIntercepted]).
        if ((family || forwardedToFamily) && !dnsIntercepted) {
            when (domain) {
                "www.google.com" ->
                    return "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:$port\n\n$domain\tcanonical name = forcesafesearch.google.com\n" +
                        "Name:\tforcesafesearch.google.com\nAddress: 216.239.38.120\n"
                "www.bing.com" ->
                    return "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:$port\n\n$domain\tcanonical name = strict.bing.com\n" +
                        "Name:\tstrict.bing.com\nAddress: 150.171.28.16\n"
            }
        }
        return resolved
    }

    /** El proveedor de internet intercepta el DNS: el DNS para familias responde, pero sin filtrar. */
    var dnsIntercepted = false

    private fun serviceList(): JsonObject = buildJsonObject {
        putJsonObject("dnsmasq") {
            putJsonObject("instances") {
                putJsonObject("cfg01411c") { put("running", true) }
                committed.filter { (name, s) -> name.startsWith("wrtc_dns_") && s[".type"]?.jsonPrimitive?.content == "dnsmasq" }
                    .keys.forEach { putJsonObject(it) { put("running", true) } }
            }
        }
    }

    private fun stage(sid: String, op: (MutableMap<String, MutableMap<String, JsonElement>>) -> Unit) {
        staged.getOrPut(sid) { mutableListOf() } += op
    }

    /** Como rpcd al vencer el plazo sin `uci confirm`: restaura la copia y recarga. */
    private fun expireApply() {
        val deadline = applyDeadline ?: return
        if (pendingApply != null && clock() >= deadline) revertApply()
    }

    private fun revertApply() {
        if (!snapshotLost) {
            committed.clear()
            snapshot!!.forEach { (k, v) -> committed[k] = v.toMutableMap() }
        }
        pendingApply = null
        snapshot = null
        applyDeadline = null
        applySystemTimezone()
        reload()
    }

    private fun apply(sid: String, rollback: Boolean, timeoutSeconds: Long = 30): Reply {
        if (pendingApply != null && rollback) return status(6)
        val ops = staged.remove(sid).orEmpty()
        if (ops.isEmpty()) return status(5)
        snapshot = committed.mapValues { it.value.toMap() }
        val working = committed.mapValuesTo(linkedMapOf()) { it.value.toMutableMap() }
        ops.forEach { it(working) }
        committed.clear()
        committed.putAll(working)
        if (rollback) {
            pendingApply = sid
            applyDeadline = clock() + timeoutSeconds * 1000
        }
        applySystemTimezone()
        reload()
        return ok()
    }

    /**
     * Como /etc/init.d/system reload: el desfase sigue a la zona configurada. La hora UTC no
     * cambia, así que la local se mueve lo mismo que el desfase.
     */
    private fun applySystemTimezone() {
        val tz = committed.values.firstOrNull { it[".type"]?.jsonPrimitive?.content == "system" }?.get("timezone")
            ?.jsonPrimitive?.content ?: return
        posixOffset(tz)?.let {
            localTime += it - offsetSeconds
            offsetSeconds = it
        }
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
                    if (values[".config"]?.jsonPrimitive?.content != config) return@forEachIndexed
                    if (type != null && type != sectionType) return@forEachIndexed
                    putJsonObject(name) {
                        put(".name", name)
                        put(".index", index)
                        values.forEach { (k, v) -> if (k != ".config") put(k, v) }
                    }
                }
            }
        }
    }

    /** Como fw4: si acepta la configuración, recarga la tabla con handles nuevos; si no, deja la anterior. */
    private fun reload() {
        if (fw4Accepts) loaded = render()
    }

    /** Como `/etc/init.d/firewall reload` fuera de la app (p. ej. al reiniciar el router). */
    fun reloadFirewall() = reload()


    /** Reglas del fichero incluido: (cadena, comentario). Solo mira los comentarios "!wrtcontrol: ". */
    private fun includedRules(): List<Pair<String, String>> {
        val include = committed["wrtc_include"] ?: return emptyList()
        val content = files[include["path"]?.jsonPrimitive?.content] ?: return emptyList()
        var chain = ""
        return content.lines().mapNotNull { line ->
            Regex("^chain (\\S+) \\{").find(line)?.let { chain = it.groupValues[1]; ownChains += chain }
            Regex("comment \"(!wrtcontrol: [^\"]*)\"").find(line)?.let { chain to it.groupValues[1] }
        }
    }

    private fun render(): JsonObject = buildJsonObject {
        putJsonArray("nftables") {
            addJsonObject { putJsonObject("table") { put("family", "inet"); put("name", "fw4"); put("handle", 1) } }
            addJsonObject { putJsonObject("chain") { put("family", "inet"); put("table", "fw4"); put("name", "forward") } }
            val own = includedRules()
            ownChains.forEach { c ->
                addJsonObject { putJsonObject("chain") { put("family", "inet"); put("table", "fw4"); put("name", c) } }
            }
            for ((chain, comment) in own) {
                addJsonObject {
                    putJsonObject("rule") {
                        put("family", "inet")
                        put("table", "fw4")
                        put("chain", chain)
                        put("handle", ++handles)
                        put("comment", comment)
                        val packets = ownPackets[comment.removePrefix(OWN_COMMENT)] ?: 0
                        put("expr", buildJsonArray { addJsonObject { putJsonObject("counter") { put("packets", packets); put("bytes", packets * 60) } } })
                    }
                }
            }
            for ((_, values) in committed) {
                val type = values[".type"]?.jsonPrimitive?.content
                if (type != "rule" && type != "redirect") continue
                addJsonObject {
                    putJsonObject("rule") {
                        put("family", "inet")
                        put("table", "fw4")
                        put("chain", if (type == "rule") "mangle_forward" else "dstnat_lan")
                        put("handle", ++handles)
                        values["name"]?.let { put("comment", "!fw4: ${it.jsonPrimitive.content}") }
                        put("expr", buildJsonArray { })
                    }
                }
            }
        }
    }
}
