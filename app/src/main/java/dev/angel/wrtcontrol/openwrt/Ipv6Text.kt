package dev.angel.wrtcontrol.openwrt

/* Leer y escribir IPv6 en texto, sin preguntar a nadie (InetAddress podría ir al DNS con un nombre). */

private const val HEXTETS = 8
private const val BITS_PER_HEXTET = 16

/** Los ocho grupos de una IPv6 escrita en texto (con o sin "::"), o null si no lo es. */
internal fun expandIpv6(value: String): List<Int>? {
    val halves = value.split("::")
    fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
    val head = groups(halves[0])
    val tail = halves.getOrNull(1)?.let(::groups).orEmpty()
    val missing = HEXTETS - head.size - tail.size
    val shape = value.count { it == ':' } >= 2 && ":::" !in value && halves.size <= 2
    val fits = if (halves.size == 1) missing == 0 else missing >= 1
    val all = head + List(missing.coerceAtLeast(0)) { "0" } + tail
    return all.takeIf { shape && fits && it.all(::isHextet) }?.map { it.toInt(BITS_PER_HEXTET) }
}

private fun isHextet(text: String) = text.length in 1..HEXTETS / 2 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

/** Una IPv6 escrita corta: el tramo de ceros más largo (de dos o más) como "::". */
internal fun compressIpv6(hextets: List<Int>): String {
    var best = -1
    var bestLength = 1
    var i = 0
    while (i < hextets.size) {
        val start = i
        while (i < hextets.size && hextets[i] == 0) i++
        if (i - start > bestLength) {
            best = start
            bestLength = i - start
        }
        if (i == start) i++
    }
    val hex = hextets.map { "%x".format(it) }
    if (best < 0) return hex.joinToString(":")
    return hex.take(best).joinToString(":") + "::" + hex.drop(best + bestLength).joinToString(":")
}
