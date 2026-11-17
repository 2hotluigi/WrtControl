package dev.angel.wrtcontrol.openwrt

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * La potencia con la que emite cada radio (`txpower`, en dBm). Sin poner, el router usa toda la
 * que el país permite, que es lo normal. Bajarla sirve cuando el Wi-Fi llega de sobra (un piso
 * pequeño) o hay otros puntos de acceso propios cerca y conviene que no se pisen.
 *
 * La app no enseña los dBm: ofrece toda, media (6 dB menos: una cuarta parte de la potencia, más
 * o menos la mitad de alcance al aire libre) o baja (12 dB menos). El tope lo dice el router
 * (`iwinfo txpowerlist`). Subir la potencia del router no hace que los aparatos emitan más fuerte:
 * de poco sirve que el teléfono oiga al router si el router no oye al teléfono.
 */

/** Cuánta potencia. [below]: los dB por debajo del tope. */
enum class WifiPower(@param:StringRes val label: Int, val below: Int) {
    Full(R.string.wifi_power_full, 0),
    Medium(R.string.wifi_power_medium, MEDIUM_DB),
    Low(R.string.wifi_power_low, LOW_DB),
}

private const val MEDIUM_DB = 6
private const val LOW_DB = 12

/** Hasta cuántos dB por debajo del tope sigue contando como "toda", y como "media". */
private const val FULL_MARGIN = 2
private const val MEDIUM_MARGIN = 9

/** El nivel de una radio con `txpower` [txpower] (null: sin poner, toda) y tope [max]. */
fun wifiPower(txpower: Int?, max: Int?): WifiPower = when {
    txpower == null || max == null || txpower >= max - FULL_MARGIN -> WifiPower.Full
    txpower >= max - MEDIUM_MARGIN -> WifiPower.Medium
    else -> WifiPower.Low
}

/** La potencia más alta de `iwinfo txpowerlist`, o null si no dice ninguna. */
internal fun parseMaxPower(reply: JsonObject): Int? =
    (reply["results"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("dbm") as? JsonPrimitive)?.intOrNull }.maxOrNull()

/**
 * Lo que hay que cambiar para que [radio] emita con [power]. "Toda" quita el `txpower` (el router
 * usa el tope, también si cambia con el país); lo demás lo fija [max] menos lo que toque. Vacío si
 * ya está así o no se sabe el tope.
 */
internal fun powerPlan(sections: List<UciSection>, radio: String, power: WifiPower, max: Int?): List<WifiChange> {
    val section = sections.firstOrNull { it.type == "wifi-device" && it.name == radio }
    val current = section?.text("txpower")
    val target = max?.let { (it - power.below).coerceAtLeast(0).toString() }
    return when {
        section == null -> emptyList()
        power == WifiPower.Full -> listOfNotNull(WifiChange.DropOption(radio, "txpower").takeIf { current != null })
        target == null || target == current -> emptyList()
        else -> listOf(WifiChange.Set(radio, mapOf("txpower" to target)))
    }
}

/** Cómo llega un aparato al router, por la fuerza de su señal. */
enum class SignalLevel(@param:StringRes val label: Int) {
    Excellent(R.string.wifi_signal_excellent),
    Good(R.string.wifi_signal_good),
    Fair(R.string.wifi_signal_fair),
    Weak(R.string.wifi_signal_weak),
}

private const val EXCELLENT_DBM = -55
private const val GOOD_DBM = -67
private const val FAIR_DBM = -75

/** El nivel de una señal de [dbm] dBm: -55 o más es muy buena; por debajo de -75, débil. */
fun signalLevel(dbm: Int): SignalLevel = when {
    dbm >= EXCELLENT_DBM -> SignalLevel.Excellent
    dbm >= GOOD_DBM -> SignalLevel.Good
    dbm >= FAIR_DBM -> SignalLevel.Fair
    else -> SignalLevel.Weak
}
