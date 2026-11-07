package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.PackageTool
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.ubus.RecordedCall
import dev.angel.wrtcontrol.ubus.Reply
import dev.angel.wrtcontrol.ubus.accessDenied
import dev.angel.wrtcontrol.ubus.ok
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lo que añaden los paquetes opcionales al [FakeOpenWrt]: el gestor de paquetes por cgi-exec
 * (con los ACL de luci-app-package-manager o de luci-app-opkg), netifd cargando protocolos solo
 * al reiniciar la red, los permisos que trae cada luci-app y miniupnpd arrancando según su
 * configuración.
 */
class FakeAddons(val fake: FakeOpenWrt = FakeOpenWrt()) {
    /** Con qué se gestionan los paquetes; null: ni luci-app-package-manager ni luci-app-opkg. */
    var tool: PackageTool? = PackageTool.PackageManager

    /** El listado sale como el fichero de estado de opkg en vez de JSON de apk. */
    var opkgStatusFormat = false

    val installed = linkedMapOf("base-files" to "1", "luci-base" to "26.1")

    /** stderr de un `install` que falla (null: funciona). */
    var installFailure: String? = null

    /** miniupnpd no llega a arrancar aunque esté activado. */
    var upnpWontStart = false

    /**
     * miniupnpd se paró a mano (`/etc/init.d/miniupnpd stop`): procd olvida el servicio y su
     * disparador, así que un `uci apply` no lo arranca. `luci.setInitAction start` sí.
     */
    var upnpStopped = false

    val protos = mutableSetOf("dhcp", "dhcpv6", "static", "ppp")

    /** Acciones de luci.setInitAction: "network restart", "sqm enable"... */
    val initActions = mutableListOf<String>()

    /** Comandos de cgi-exec, ya sin escapar. */
    val commands = mutableListOf<String>()

    var upnpRules = mutableListOf<Map<String, Any>>()
    val deletedRules = mutableListOf<String>()

    /** Último saludo de cada par por interfaz: clave pública → hora (0: nunca). */
    var wgHandshakes = mapOf<String, Map<String, Long>>()

    /** netifd no levanta las interfaces WireGuard (p. ej. una clave mal escrita). */
    var wgFails = false

    /** Registro de ddns-scripts de los servicios de WrtControl (/var/log/ddns/<sección>.log). */
    var ddnsLog: String? = null

    /**
     * Como hasta 24.10: el ACL de LuCI no deja `service list`, y ddns no es un servicio de procd
     * (aplicar no lo recarga: solo arranca o para con su init).
     */
    var oldRouter = false

    /** Actualizadores de ddns en marcha cuando [oldRouter] (los que arrancó el último restart). */
    private var ddnsStarted = setOf<String>()

    /**
     * Pares de cada interfaz WireGuard en el kernel. Como netifd: se toman de la configuración al
     * levantar la interfaz (al aparecer o con /sbin/ifup), no cuando cambian solo sus pares.
     */
    val wgKernel = mutableMapOf<String, Set<String>>()

    /** Llamadas a /sbin/ifup. */
    val ifups = mutableListOf<String>()

    /** Cuándo (ms del reloj del falso) se levantó por última vez cada interfaz WireGuard. */
    private val wgUpAt = mutableMapOf<String, Long>()

    /** Direcciones de cada interfaz en `network.interface dump`. */
    var ipv4 = mapOf<String, String>()
    var ipv6 = mapOf<String, List<String>>()

    /** Dirección del router dentro del prefijo delegado a cada interfaz (sin dirección propia en la wan6). */
    var prefixLocal = mapOf<String, String>()

    val router get() = fake.router

    init {
        fake.intercept = ::handle
    }

    private val deps = mapOf(
        "luci-proto-wireguard" to listOf("wireguard-tools", "kmod-wireguard"),
        "luci-app-sqm" to listOf("sqm-scripts"),
        "luci-app-upnp" to listOf("miniupnpd-nftables"),
        "luci-app-ddns" to listOf("ddns-scripts"),
    )

    /** De qué paquete sale el permiso de LuCI para cada configuración u objeto ubus. */
    private val aclFrom = mapOf("sqm" to "luci-app-sqm", "upnpd" to "luci-app-upnp", "ddns" to "luci-app-ddns")

    fun install(vararg names: String) {
        for (name in names) {
            (listOf(name) + deps[name].orEmpty()).forEach { installed[it] = "1.0" }
            defaults(name)
        }
    }

    /** Lo que deja cada paquete en /etc/config al instalarse, como los reales. */
    private fun defaults(name: String) {
        val pkgs = listOf(name) + deps[name].orEmpty()
        if ("sqm-scripts" in pkgs && fake.committed["eth1"] == null) {
            fake.section(
                "eth1", "queue", "enabled" to "0", "interface" to "eth1", "download" to "85000", "upload" to "10000",
                "qdisc" to "cake", "script" to "piece_of_cake.qos",
            )
        }
        if ("miniupnpd-nftables" in pkgs && fake.committed["config"] == null) {
            fake.section("config", "upnpd", "enabled" to "0", "enable_upnp" to "1", "enable_natpmp" to "1", "secure_mode" to "1")
        }
        if ("ddns-scripts" in pkgs && fake.committed["myddns_ipv4"] == null) {
            fake.section("global", "ddns", "upd_privateip" to "0", config = "ddns")
            fake.section("myddns_ipv4", "service", "enabled" to "0", "lookup_host" to "yourhost.example.com", "domain" to "yourhost.example.com")
        }
    }

    private fun ddnsEnabled(): List<String> =
        if ("ddns-scripts" !in installed) emptyList() else fake.committed
            .filter { (_, v) -> v[".type"]?.jsonPrimitive?.content == "service" && v["enabled"]?.jsonPrimitive?.content == "1" }
            .keys.toList()

    private fun ddnsRunning(): List<String> = if (oldRouter) ddnsStarted.toList() else ddnsEnabled()

    private fun wgInterfaces() = fake.committed.filter { (_, v) ->
        v[".type"]?.jsonPrimitive?.content == "interface" && v["proto"]?.jsonPrimitive?.content == "wireguard"
    }.keys

    private fun committedPeers(iface: String) = fake.committed.values
        .filter { it[".type"]?.jsonPrimitive?.content == "wireguard_$iface" }
        .map { it["public_key"]?.jsonPrimitive?.content.orEmpty() }.toSet()

    /** netifd levanta las interfaces nuevas (con los pares de ese momento) y quita las borradas. */
    private fun syncKernel() {
        if ("wireguard" !in protos || wgFails) {
            wgKernel.clear()
            return
        }
        val configured = wgInterfaces()
        wgKernel.keys.retainAll(configured)
        configured.filter { it !in wgKernel }.forEach {
            wgKernel[it] = committedPeers(it)
            wgUpAt[it] = fake.clock()
        }
    }

    private fun upnpRunning(): Boolean =
        "miniupnpd-nftables" in installed && fake.option("config", "enabled") == "1" && !upnpWontStart && !upnpStopped

    private fun handle(call: RecordedCall): Reply? {
        syncKernel()
        val a = call.args
        fun arg(key: String) = a[key]?.jsonPrimitive?.content
        return when (call.name) {
            "uci.get" -> aclFrom[arg("config")]?.let { pkg -> if (pkg in installed) null else accessDenied }
            "system.info" -> ok(
                """{"localtime":${fake.localTime},"uptime":100,"root":{"total":102400,"free":71680,"used":30720,"avail":71680}}""",
            )
            "network.interface.dump" -> ok(buildJsonObject {
                putJsonArray("interface") {
                    fake.interfaces.forEach { (name, dev) ->
                        addJsonObject {
                            put("interface", name); put("up", true); put("device", dev); put("l3_device", dev)
                            putJsonArray("route") {
                                if (name.startsWith("wan")) addJsonObject { put("target", "0.0.0.0"); put("mask", 0) }
                            }
                            putJsonArray("ipv4-address") {
                                ipv4[name]?.let { addJsonObject { put("address", it); put("mask", 24) } }
                            }
                            putJsonArray("ipv6-address") {
                                ipv6[name].orEmpty().forEach { addJsonObject { put("address", it); put("mask", 64) } }
                            }
                            putJsonArray("ipv6-prefix-assignment") {
                                prefixLocal[name]?.let { local ->
                                    addJsonObject {
                                        put("address", local.substringBeforeLast(':') + ":"); put("mask", 64)
                                        putJsonObject("local-address") { put("address", local); put("mask", 64) }
                                    }
                                }
                            }
                        }
                    }
                    // Las interfaces WireGuard que netifd tiene levantadas, con su tiempo desde el último arranque.
                    wgKernel.keys.forEach { name ->
                        addJsonObject {
                            put("interface", name); put("up", true); put("l3_device", name)
                            put("uptime", (fake.clock() - (wgUpAt[name] ?: 0L)) / 1000)
                        }
                    }
                }
            }.toString())
            "network.get_proto_handlers" -> ok(buildJsonObject { protos.forEach { putJsonObject(it) {} } }.toString())
            "file.exec" -> if (arg("command") != "/sbin/ifup") null else {
                val iface = a["params"]!!.jsonArray.first().jsonPrimitive.content
                ifups += iface
                if (iface in wgInterfaces() && "wireguard" in protos && !wgFails) {
                    wgKernel[iface] = committedPeers(iface)
                    wgUpAt[iface] = fake.clock()
                }
                ok("""{"code":0}""")
            }
            "luci.setInitAction" -> {
                initActions += "${arg("name")} ${arg("action")}"
                if (arg("name") == "ddns" && arg("action") in setOf("start", "restart")) ddnsStarted = ddnsEnabled().toSet()
                if (arg("name") == "ddns" && arg("action") == "stop") ddnsStarted = emptySet()
                if (arg("name") == "network" && arg("action") == "restart" && "wireguard-tools" in installed) protos += "wireguard"
                if (arg("name") == "miniupnpd") when (arg("action")) {
                    "start", "restart" -> upnpStopped = false
                    "stop" -> upnpStopped = true
                }
                ok("""{"result":true}""")
            }
            "service.list" -> if (oldRouter) accessDenied else when (arg("name")) {
                "miniupnpd" -> ok(buildJsonObject {
                    if ("miniupnpd-nftables" in installed) putJsonObject("miniupnpd") {
                        putJsonObject("instances") { if (upnpRunning()) putJsonObject("instance1") { put("running", true) } }
                    }
                }.toString())
                // ddns: una instancia de procd por servicio activado, con el nombre de su sección.
                "ddns" -> ok(buildJsonObject {
                    if ("ddns-scripts" in installed) putJsonObject("ddns") {
                        putJsonObject("instances") { ddnsRunning().forEach { putJsonObject(it) { put("running", true) } } }
                    }
                }.toString())
                else -> null
            }
            "luci.ddns.get_services_status" -> if ("luci-app-ddns" !in installed) accessDenied else ok(buildJsonObject {
                val running = ddnsRunning()
                fake.committed.filter { (_, v) -> v[".type"]?.jsonPrimitive?.content == "service" }.keys.forEach { name ->
                    putJsonObject(name) {
                        if (name in running) { put("pid", 1234); put("last_update", "2026-11-05 18:08") }
                        put("next_update", if (name in running) "Verify" else "Disabled")
                    }
                }
            }.toString())
            "luci.ddns.get_services_log" -> if ("luci-app-ddns" !in installed) accessDenied else
                ok(buildJsonObject { put("result", ddnsLog ?: "File not found or empty") }.toString())
            "luci.upnp.get_status" -> if ("luci-app-upnp" !in installed) accessDenied else ok(buildJsonObject {
                putJsonArray("rules") {
                    upnpRules.forEach { r ->
                        addJsonObject {
                            r.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
                        }
                    }
                }
            }.toString())
            "luci.upnp.delete_rule" -> if ("luci-app-upnp" !in installed) accessDenied else {
                val token = arg("token")!!
                deletedRules += token
                upnpRules.removeAll { it["num"].toString() == token }
                ok("""{"result":"OK"}""")
            }
            // Como `wg show all dump`: las interfaces WireGuard que netifd levantó con su configuración.
            "luci.wireguard.getWgInstances" -> if ("luci-proto-wireguard" !in installed) accessDenied else ok(buildJsonObject {
                wgKernel.forEach { (dev, keys) ->
                    putJsonObject(dev) {
                        put("name", dev)
                        put("listen_port", fake.option(dev, "listen_port") ?: "0")
                        putJsonArray("peers") {
                            keys.forEach { key ->
                                addJsonObject {
                                    put("public_key", key)
                                    put("latest_handshake", "${wgHandshakes[dev]?.get(key) ?: 0}")
                                    put("transfer_rx", "1000"); put("transfer_tx", "2000")
                                }
                            }
                        }
                    }
                }
            }.toString())
            "luci.wireguard.generateKeyPair" -> if ("luci-proto-wireguard" !in installed) accessDenied else {
                val priv = X25519.generatePrivateKey()
                ok("""{"keys":{"priv":"$priv","pub":"${X25519.publicKey(priv)}"}}""")
            }
            else -> if (call.target == "cgi-exec") cgi(call.method) else null
        }
    }

    /** Los dos scripts de LuCI con sus ACL: lo que no encaja da 403, lo que no existe 404. */
    private fun cgi(line: String): Reply {
        commands += line
        val parts = line.split(' ')
        val script = parts.first()
        val args = parts.drop(1)
        val wanted = tool ?: return Reply.Raw(403, "Access to command denied by ACL")
        if (script != wanted.script) {
            return if (script in PackageTool.entries.map { it.script }) Reply.Raw(404, "Executable not found")
            else Reply.Raw(403, "Access to command denied by ACL")
        }
        val action = args.firstOrNull()
        // ACL de 22.03/23.05: "update *" (con algún argumento); 24.10+: "update" a secas.
        val allowed = when (action) {
            "list-installed" -> args.size == 1
            "update" -> if (wanted == PackageTool.Opkg) args.size >= 2 else args.size == 1
            "install" -> args.size >= 2
            else -> false
        }
        if (!allowed) return Reply.Raw(403, "Access to command denied by ACL")
        return when (action) {
            "list-installed" -> Reply.Raw(200, if (opkgStatusFormat) opkgStatus() else apkJson())
            "update" -> Reply.Raw(200, """{ "code": 0, "stdout": "OK: 11212 distinct packages available" }""")
            else -> {
                val pkgs = args.drop(1).filter { !it.startsWith("-") }
                installFailure?.let { return Reply.Raw(200, """{ "code": 1, "stderr": ${kotlinx.serialization.json.JsonPrimitive(it)} }""") }
                install(*pkgs.toTypedArray())
                Reply.Raw(200, """{ "code": 0, "stdout": "OK" }""")
            }
        }
    }

    private fun apkJson() = installed.entries.joinToString(",\n", "[\n", "\n]") { (n, v) ->
        """  { "package": "$n-$v", "name": "$n", "version": "$v", "description": "x" }"""
    }

    private fun opkgStatus() = installed.entries.joinToString("\n\n") { (n, v) ->
        "Package: $n\nVersion: $v\nDepends: libc\nStatus: install user installed\nArchitecture: x86_64\nInstalled-Time: 1"
    } + "\n\nPackage: old-thing\nVersion: 1\nStatus: deinstall ok not-installed\n"
}
