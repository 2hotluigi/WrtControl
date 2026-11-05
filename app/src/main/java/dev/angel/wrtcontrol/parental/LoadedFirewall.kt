package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.exec
import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Lo que hay cargado ahora mismo en la tabla `inet fw4` del kernel. */
data class LoadedFirewall(
    val tableHandle: Long?,
    /** Los handles de nftables solo crecen: si sube el máximo, fw4 recargó las reglas. */
    val maxRuleHandle: Long,
    val comments: List<String>,
    val chains: Set<String>,
    /** Paquetes que contó cada regla propia, sumados por etiqueta ("doh k3x9q2", "cuarentena"...). */
    val ownCounters: Map<String, Long> = emptyMap(),
) {
    /** Versión del fichero de reglas propio que está cargado (ver [NftInclude.marker]), o null. */
    val includeVersion: String?
        get() = comments.firstOrNull { it.startsWith("${OWN_COMMENT}v=") }?.removePrefix("${OWN_COMMENT}v=")

    /** Nombres de las reglas de WrtControl cargadas (fw4 pone "!fw4: <nombre>" como comentario). */
    val wrtcontrolRules: List<String>
        get() = comments.filter { it.startsWith(FW4_COMMENT + RULE_LABEL_PREFIX) }.map { it.removePrefix(FW4_COMMENT) }

    fun reloadedSince(before: LoadedFirewall): Boolean =
        tableHandle != before.tableHandle || maxRuleHandle > before.maxRuleHandle

    /**
     * Se comparan conjuntos de nombres: una sección con `proto 'tcp udp'` da dos reglas de
     * nftables con el mismo comentario, y cuántas exactamente depende de la versión de fw4.
     *
     * Las reglas propias se reconocen por la versión que lleva su primera regla. fw4 recarga con
     * `flush table`, que vacía las cadenas pero no las borra: sin el include, las cadenas siguen
     * ahí, vacías y con política accept, hasta reiniciar el firewall.
     */
    fun matches(plan: RouterPlan): Boolean =
        wrtcontrolRules.toSet() == plan.ruleLabels.toSet() && includeVersion == plan.include?.version

    private companion object {
        const val FW4_COMMENT = "!fw4: "
    }
}

/** Interpreta `nft --terse --json list ruleset`; solo mira la tabla `inet fw4`. */
fun parseLoadedFirewall(json: String): LoadedFirewall {
    val items = (UbusJson.parseToJsonElement(json) as? JsonObject)?.get("nftables") as? JsonArray
        ?: throw UbusException.Protocol("El listado del firewall no tiene el formato esperado")
    var tableHandle: Long? = null
    var maxHandle = 0L
    val counters = mutableMapOf<String, Long>()
    val comments = mutableListOf<String>()
    val chains = mutableSetOf<String>()

    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.isFw4(tableKey: String) = str("family") == "inet" && str(tableKey) == "fw4"

    for (item in items) {
        val obj = item as? JsonObject ?: continue
        (obj["table"] as? JsonObject)?.let { t ->
            if (t.isFw4("name")) tableHandle = (t["handle"] as? JsonPrimitive)?.longOrNull
        }
        (obj["chain"] as? JsonObject)?.let { c ->
            if (c.isFw4("table")) c.str("name")?.let(chains::add)
        }
        (obj["rule"] as? JsonObject)?.let { r ->
            if (!r.isFw4("table")) return@let
            (r["handle"] as? JsonPrimitive)?.longOrNull?.let { if (it > maxHandle) maxHandle = it }
            val comment = r.str("comment")
            comment?.let(comments::add)
            if (comment != null && comment.startsWith(OWN_COMMENT)) {
                val packets = (r["expr"] as? JsonArray).orEmpty().sumOf { e ->
                    (((e as? JsonObject)?.get("counter") as? JsonObject)?.get("packets") as? JsonPrimitive)?.longOrNull ?: 0L
                }
                val tag = comment.removePrefix(OWN_COMMENT)
                counters[tag] = (counters[tag] ?: 0L) + packets
            }
        }
    }
    return LoadedFirewall(tableHandle, maxHandle, comments, chains, counters)
}

/**
 * Lee el firewall cargado con el mismo comando que usa la página de estado de LuCI (es el
 * único que permite su ACL). Devuelve null si este usuario no tiene permiso.
 */
suspend fun UbusCaller.loadedFirewall(): LoadedFirewall? {
    val result = try {
        exec("/usr/sbin/nft", listOf("--terse", "--json", "list", "ruleset"))
    } catch (_: UbusException.AccessDenied) {
        return null
    } catch (e: UbusException.Status) {
        if (e.code == 6) return null // el ACL de ficheros no permite este comando
        throw e
    }
    if (result.code != 0) throw UbusException.Protocol("nft terminó con código ${result.code}: ${result.stderr.take(200)}")
    return parseLoadedFirewall(result.stdout)
}
