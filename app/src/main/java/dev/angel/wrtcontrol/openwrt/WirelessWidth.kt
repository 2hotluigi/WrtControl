package dev.angel.wrtcontrol.openwrt

/*
 * El ancho del canal (`htmode` de cada radio: "HE80" es Wi-Fi 6 a 80 MHz). Más ancho es más
 * velocidad cerca del router, pero menos alcance y más choques con las redes vecinas. La app no
 * enseña el número: ofrece en cada banda lo razonable y dice para qué sirve.
 *
 * - 2,4 GHz: 20 MHz (lo recomendado) o 40 MHz. Con redes vecinas, el propio router se queda en 20.
 * - 5 GHz: 80 MHz (lo recomendado) o 160 MHz. A 160 siempre pisa canales que comparte con radares
 *   (DFS): el router escucha un minuto antes de emitir y cambia de canal si detecta uno. Solo cabe
 *   en dos bloques (canales 36 a 64 y 100 a 128), y hace falta el país, porque sin él no se puede
 *   emitir en esos canales.
 *
 * Qué anchos admite cada radio lo dice `iwinfo info` (`htmodes`). Al cambiar el ancho se conserva
 * el estándar que tenía (HE, VHT, HT…).
 */

private val WIDTH = Regex("\\d+")

/** Los MHz de un `htmode` ("HE80" → 80, "HT40+" → 40), o null si no lo dice. */
fun htmodeWidth(htmode: String?): Int? = htmode?.let { WIDTH.find(it)?.value?.toIntOrNull() }

/** Estándares de más nuevo a más viejo, para elegir cuando el actual no tiene ese ancho. */
private val FAMILIES = listOf("EHT", "HE", "VHT", "HT")

/**
 * El `htmode` de [width] MHz para una radio que ahora tiene [current] y admite [supported] (vacío:
 * no se sabe, se confía en el estándar actual). null si la radio no llega a ese ancho.
 */
internal fun htmodeFor(current: String?, width: Int, supported: List<String>): String? {
    val family = current?.takeWhile { it.isLetter() }?.takeIf { it in FAMILIES }
    if (supported.isEmpty()) return family?.let { "$it$width" }
    return (listOfNotNull(family) + FAMILIES).map { "$it$width" }.firstOrNull { it in supported }
}

/** Los anchos que ofrece la app en cada banda, del recomendado al más rápido. */
fun widthChoices(band: WifiBand?): List<Int> = when (band) {
    WifiBand.Band2 -> listOf(WIDTH_20, WIDTH_40)
    WifiBand.Band5 -> listOf(WIDTH_80, WIDTH_160)
    else -> emptyList()
}

const val WIDTH_20 = 20
const val WIDTH_40 = 40
const val WIDTH_80 = 80
const val WIDTH_160 = 160

/** Los canales de 5 GHz donde empieza un bloque en el que caben 160 MHz. */
private val BLOCKS_160 = listOf(36..64, 100..128)

/** El canal por el que empezar a 160 MHz si el actual no vale. */
private const val FIRST_160_CHANNEL = "36"

/**
 * Lo que hay que cambiar para que [radio] emita a [width] MHz. A 160, si el canal está fuera de
 * los bloques donde cabe, también el canal. Vacío si ya está así o la radio no llega.
 */
internal fun widthPlan(sections: List<UciSection>, radio: String, width: Int, supported: List<String>): List<WifiChange.Set> {
    val section = sections.firstOrNull { it.type == "wifi-device" && it.name == radio }
    val htmode = section?.let { htmodeFor(it.text("htmode"), width, supported) }
    val values = buildMap {
        if (htmode != null && section.text("htmode") != htmode) put("htmode", htmode)
        if (htmode != null) channelFor(width, section.text("channel"))?.let { put("channel", it) }
    }
    return if (values.isEmpty()) emptyList() else listOf(WifiChange.Set(radio, values))
}

/** El canal al que hay que pasar para emitir a [width] MHz desde [channel], o null si el actual vale (o es "auto"). */
fun channelFor(width: Int, channel: String?): String? {
    val number = channel?.toIntOrNull() ?: return null
    return FIRST_160_CHANNEL.takeIf { width == WIDTH_160 && BLOCKS_160.none { number in it } }
}
