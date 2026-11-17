package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/*
 * El canal menos ocupado. El router escanea (`iwinfo scan`, que funciona aunque no emita nada) y la
 * app cuenta cuánto estorban las redes de alrededor en cada canal que tendría sentido usar:
 *
 * - 2,4 GHz: solo 1, 6 y 11, los tres que no se pisan entre sí. Un vecino estorba a los canales
 *   que tiene a menos de 5 de distancia, más cuanto más cerca y más fuerte se le oye.
 * - 5 GHz: el primer canal de cada bloque sin radares (36 y 149), o de cada bloque de 160 MHz
 *   (36 y 100) si la radio va a 160. Estorban las redes que emiten dentro del bloque.
 *
 * Solo se proponen canales que el país permite (`iwinfo freqlist`). Las redes del propio usuario
 * (repetidores, otros puntos de acceso) cuentan como vecinas: tampoco conviene pisarlas.
 */

/** Una red que el router oye: su canal principal y con qué fuerza llega (dBm, negativo). */
data class SeenNetwork(val channel: Int, val signal: Int)

/** Las redes de `iwinfo scan`. */
internal fun parseScan(reply: JsonObject): List<SeenNetwork> = (reply["results"] as? JsonArray).orEmpty().mapNotNull { element ->
    val entry = element as? JsonObject
    val channel = (entry?.get("channel") as? JsonPrimitive)?.intOrNull
    val signal = (entry?.get("signal") as? JsonPrimitive)?.intOrNull
    if (channel != null && signal != null) SeenNetwork(channel, signal) else null
}

/** Los canales de `iwinfo freqlist` en los que el país deja emitir. */
internal fun parseAllowedChannels(reply: JsonObject): List<Int> = (reply["results"] as? JsonArray).orEmpty().mapNotNull { element ->
    val entry = element as? JsonObject
    val restricted = (entry?.get("restricted") as? JsonPrimitive)?.booleanOrNull ?: false
    (entry?.get("channel") as? JsonPrimitive)?.intOrNull?.takeIf { !restricted }
}

private val CANDIDATES_2G = listOf(1, 6, 11)
private val CANDIDATES_5G = listOf(36, 149)
private val CANDIDATES_5G_160 = listOf(36, 100)

/** Distancia (en canales) a la que dos redes de 2,4 GHz dejan de pisarse. */
private const val OVERLAP_2G = 5

/** Canales de 5 GHz que ocupa cada ancho, contando desde el primero del bloque (van de 4 en 4). */
private const val CHANNEL_STEP_5G = 4
private const val MHZ_PER_CHANNEL_5G = 20

/** Los canales que tiene sentido proponer a una radio de [band] que va a [width] MHz, de los [allowed]. */
internal fun channelCandidates(band: WifiBand?, width: Int?, allowed: List<Int>): List<Int> = when (band) {
    WifiBand.Band2 -> CANDIDATES_2G
    WifiBand.Band5 -> if (width == WIDTH_160) CANDIDATES_5G_160 else CANDIDATES_5G
    else -> emptyList()
}.filter { it in allowed }

private const val WEAKEST = 100
private const val MIN_WEIGHT = 5
private const val MAX_WEIGHT = 70

/** Cuánto pesa una red según lo fuerte que llega: de 5 (apenas se oye) a 70 (al lado). */
private fun weight(signal: Int): Int = (signal + WEAKEST).coerceIn(MIN_WEIGHT, MAX_WEIGHT)

/** Los canales de 5 GHz del bloque de [width] MHz que empieza en [first]. */
private fun block5g(first: Int, width: Int?): IntRange {
    val channels = ((width ?: WIDTH_80) / MHZ_PER_CHANNEL_5G).coerceAtLeast(1)
    return first..first + (channels - 1) * CHANNEL_STEP_5G
}

/** El primer canal del bloque de [width] MHz en el que cae [channel] (36 para el 44 a 80 MHz). */
private fun blockStart5g(channel: Int, width: Int?): Int {
    val base = if (channel >= FIRST_UPPER_5G) FIRST_UPPER_5G else if (channel >= FIRST_MIDDLE_5G) FIRST_MIDDLE_5G else FIRST_LOWER_5G
    val span = ((width ?: WIDTH_80) / MHZ_PER_CHANNEL_5G).coerceAtLeast(1) * CHANNEL_STEP_5G
    return base + (channel - base) / span * span
}

private const val FIRST_LOWER_5G = 36
private const val FIRST_MIDDLE_5G = 100
private const val FIRST_UPPER_5G = 149

/** Las redes de [seen] que estorban en [channel], con lo que pesa cada una. */
private fun interferers(band: WifiBand?, channel: Int, width: Int?, seen: List<SeenNetwork>): List<Int> = when (band) {
    // Más cerca en canal, más estorba: el mismo canal cuenta entero; a 4 de distancia, una quinta parte.
    WifiBand.Band2 -> seen.map { it to abs(it.channel - channel) }.filter { (_, distance) -> distance < OVERLAP_2G }
        .map { (network, distance) -> weight(network.signal) * (OVERLAP_2G - distance) / OVERLAP_2G }
    else -> block5g(blockStart5g(channel, width), width).let { block -> seen.filter { it.channel in block }.map { weight(it.signal) } }
}

/**
 * El consejo para una radio. [best]: el canal más libre. [bestNeighbors] y [currentNeighbors]:
 * cuántas redes estorban en él y en el actual (null si el actual es "auto"). [better]: merece la
 * pena cambiar.
 */
data class ChannelAdvice(val best: Int, val bestNeighbors: Int, val currentNeighbors: Int?, val better: Boolean)

/** Cuánto menos ocupado tiene que estar otro canal para proponer el cambio: un 20 %. */
private const val WORTH_IT_PERCENT = 80
private const val PERCENT = 100

/**
 * Qué canal conviene a [radio] con lo que se oye ([seen]) y lo que el país permite ([allowed]).
 * null si no hay nada que proponer (banda sin candidatos, o ninguno permitido).
 */
internal fun adviseChannel(radio: WifiRadio, allowed: List<Int>, seen: List<SeenNetwork>): ChannelAdvice? {
    val current = radio.channel?.toIntOrNull()
    val candidates = channelCandidates(radio.band, radio.width, allowed)
    fun load(channel: Int) = interferers(radio.band, channel, radio.width, seen)
    // A igualdad, el actual (no cambiar por cambiar) y luego el más bajo.
    val best = candidates.minWithOrNull(compareBy<Int> { load(it).sum() }.thenBy { it != current }.thenBy { it })
    return best?.let {
        val currentLoad = current?.let(::load)
        val better = currentLoad == null || (it != current && load(it).sum() * PERCENT < currentLoad.sum() * WORTH_IT_PERCENT)
        ChannelAdvice(it, load(it).size, currentLoad?.size, better)
    }
}

/** Lo que hay que cambiar para que [radio] emita en [channel]. */
internal fun channelPlan(sections: List<UciSection>, radio: String, channel: Int): List<WifiChange.Set> =
    sections.filter { it.type == "wifi-device" && it.name == radio && it.text("channel") != channel.toString() }
        .map { WifiChange.Set(radio, mapOf("channel" to channel.toString())) }
