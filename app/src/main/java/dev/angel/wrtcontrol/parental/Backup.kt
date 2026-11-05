package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.UciValue
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.JsonArray
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

/** La configuración de una copia, o null si está vacía o no se entiende. */
fun parseBackup(json: String?): ParentalConfig? {
    if (json.isNullOrBlank()) return null
    val items = runCatching { UbusJson.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
    val sections = items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (!name.startsWith(SECTION_PREFIX) || type !in BACKUP_TYPES) return@mapNotNull null
        val values = obj["values"] as? JsonObject ?: return@mapNotNull null
        val options = values.mapNotNull { (k, v) ->
            when (v) {
                is JsonArray -> k to UciValue.Items(v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                is JsonPrimitive -> v.contentOrNull?.let { k to UciValue.Text(it) }
                else -> null
            }
        }.toMap()
        UciSection(name, type, options)
    }
    val config = readConfig(sections)
    return config.takeIf { it.profiles.isNotEmpty() || it.settings != ParentalSettings() }
}
