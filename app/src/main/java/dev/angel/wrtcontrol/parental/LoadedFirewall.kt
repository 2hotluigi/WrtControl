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
    /** Reglas dentro de [BLOCK_CHAIN]. */
    val blockRules: Int = 0,
) {
    /** Nombres de las reglas de WrtControl cargadas (fw4 pone "!fw4: <nombre>" como comentario). */
    val wrtcontrolRules: List<String>
        get() = comments.filter { it.startsWith(FW4_COMMENT + RULE_LABEL_PREFIX) }.map { it.removePrefix(FW4_COMMENT) }

    fun reloadedSince(before: LoadedFirewall): Boolean =
        tableHandle != before.tableHandle || maxRuleHandle > before.maxRuleHandle

    /**
     * fw4 recarga con `flush table`, que vacía las cadenas pero no las borra: al quitar el
     * include, [BLOCK_CHAIN] sigue ahí, vacía y con política accept, hasta reiniciar el
     * firewall. Por eso se mira si tiene reglas y no si existe.
     */
    fun matches(plan: FirewallPlan): Boolean =
        wrtcontrolRules.sorted() == plan.ruleLabels.sorted() && (blockRules > 0) == plan.usesBlockChain

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
    var blockRules = 0
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
            r.str("comment")?.let(comments::add)
            if (r.str("chain") == BLOCK_CHAIN) blockRules++
        }
    }
    return LoadedFirewall(tableHandle, maxHandle, comments, chains, blockRules)
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
