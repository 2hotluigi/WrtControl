package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.UciValue
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * Copia de los perfiles y ajustes en el teléfono, con el mismo formato que las secciones UCI. Si
 * el router se resetea o alguien borra los perfiles desde LuCI, la app ofrece restaurarlos.
 */

private val BACKUP_TYPES = setOf(PROFILE_TYPE, SCHEDULE_TYPE, SETTINGS_TYPE)

fun backupJson(config: ParentalConfig): String = JsonArray(
    configSections(config).map { s ->
        buildJsonObject {
            put("name", s.name)
            put("type", s.type)
            put("values", s.valuesJson())
        }
    },
).toString()

/** La configuración de una copia, o null si está vacía, no se entiende o es de una versión más nueva. */
fun parseBackup(json: String?): ParentalConfig? {
    if (json.isNullOrBlank()) return null
    val items = runCatching { UbusJson.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
    // Una copia de una versión más nueva no se restaura: se perdería lo que esta no sabe escribir.
    val config = readConfig(items.mapNotNull(::backupSection)).takeUnless { it.newerSchema } ?: return null
    return config.takeIf { it.profiles.isNotEmpty() || it.settings != ParentalSettings() }
}

private fun backupSection(item: JsonElement): UciSection? {
    val obj = item as? JsonObject
    val name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith(SECTION_PREFIX) }
    val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull?.takeIf { it in BACKUP_TYPES }
    val values = obj?.get("values") as? JsonObject
    if (name == null || type == null || values == null) return null
    val options = values.mapNotNull { (k, v) ->
        when (v) {
            is JsonArray -> k to UciValue.Items(v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            is JsonPrimitive -> v.contentOrNull?.let { k to UciValue.Text(it) }
            else -> null
        }
    }.toMap()
    return UciSection(name, type, options)
}
