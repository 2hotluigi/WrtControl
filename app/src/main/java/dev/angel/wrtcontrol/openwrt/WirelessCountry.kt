package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * El país (`country` de cada radio) dice qué canales y cuánta potencia permite la ley. Sin él, el
 * router usa "00" (el mundo): lo más restrictivo de todos, con la mayor parte de 5 GHz sin poder
 * emitir. La lista de países la da el propio router (`iwinfo countrylist`, que el ACL de LuCI deja
 * leer), así que solo se ofrece lo que su base de datos conoce.
 */

/** Un código de país de verdad: dos letras, y no "00". */
internal val COUNTRY_CODE = Regex("^[A-Z]{2}$")

/** Un país que conoce el router. [name]: como lo llama él (en inglés), por si el teléfono no lo conoce. */
data class WifiCountry(val code: String, val name: String)

/** Los países de `iwinfo countrylist`, sin "00" ni los códigos de prueba, por orden de código. */
internal fun parseCountries(reply: JsonObject): List<WifiCountry> =
    (reply["results"] as? JsonArray).orEmpty().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val code = (entry["code"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val iso = (entry["iso3166"] as? JsonPrimitive)?.contentOrNull
        val name = (entry["country"] as? JsonPrimitive)?.contentOrNull
        if (COUNTRY_CODE.matches(code) && iso == code) WifiCountry(code, name ?: code) else null
    }.distinctBy { it.code }.sortedBy { it.code }

/** ¿Es [code] un país que se puede poner? */
fun isCountryCode(code: String): Boolean = COUNTRY_CODE.matches(code)

/** Lo que hay que cambiar para que todas las radios tengan el país [code]. */
internal fun countryPlan(sections: List<UciSection>, code: String): List<WifiChange.Set> =
    sections.filter { it.type == "wifi-device" && it.text("country") != code }.map { WifiChange.Set(it.name, mapOf("country" to code)) }

/**
 * ¿Volvió a emitir todo lo que emitía? [wasUp]: las radios que emitían antes del cambio. Tienen
 * que estar levantadas y sin cambios pendientes (un canal que el país nuevo no permite no arranca).
 */
internal fun radiosBack(wasUp: Set<String>, devices: Map<String, JsonObject>): Boolean = wasUp.all { radio ->
    val status = devices[radio]
    (status?.get("up") as? JsonPrimitive)?.contentOrNull == "true" && (status["pending"] as? JsonPrimitive)?.contentOrNull != "true"
}
