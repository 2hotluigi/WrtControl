package dev.angel.wrtcontrol.openwrt

/*
 * Las direcciones del informe del router (ver ReportAnonymizer.kt): qué MAC e IP dicen algo de la
 * casa y su seudónimo, que es siempre el mismo para la misma dirección dentro de un informe.
 */

// Ojo: en Android las expresiones las compila ICU, que lee "[:" como el principio de una clase
// POSIX ("[:alpha:]") y falla al cargar el fichero; en la JVM de las pruebas no pasa. De ahí
// "[-:]" y no "[:-]" (IcuRegexTest revisa que ninguna expresión de la app lleve "[:").
internal val MAC = Regex("(?<![0-9A-Fa-f:])([0-9A-Fa-f]{2}[-:]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])")
internal val IPV4 = Regex("(?<![0-9.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9.])")
internal val IPV6 = Regex("(?<![0-9A-Za-z:.])[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:.])")

/** Los seudónimos de MAC empiezan así (dirección administrada localmente, no de un fabricante). */
internal const val MAC_PSEUDONYM = "02:00:00:00:"

/** Las IPv4 públicas se cambian por las de este rango de documentación. */
private const val IPV4_PSEUDONYM = "203.0.113."

private const val HEXTETS = 8
private const val SITE_HEXTETS = 3
private const val SUBNET_HEXTET = 3
private const val OCTET_MAX = 255
private const val BYTE = 0xFF
private const val BYTE_BITS = 8
private const val HEXTET_MAX = 0xFFFF

/** Primeros grupos de los seudónimos de IPv6: 2001:db8::/32 (documentación) y fd00::/16 (ULA). */
private val DOC_PREFIX = listOf(0x2001, 0x0DB8)
private const val ULA_PSEUDONYM = 0xFD00

/** Lo que se pone como identificador de interfaz inventado: ::a:0:0:N. */
private const val IID_MARK = 0xA

/** IPv6 especiales por su primer grupo: valor y máscara. */
private const val MULTICAST = 0xFF00
private const val LINK_LOCAL = 0xFE80
private const val LINK_LOCAL_MASK = 0xFFC0
private const val ULA = 0xFC00
private const val ULA_MASK = 0xFE00

/** IPv4 que no son de internet (o ya son de documentación): estructura de la red, se quedan. */
private val RESERVED_V4 = listOf(
    "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
    "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3",
).map { cidr ->
    val (net, bits) = cidr.split('/')
    ipv4Value(net.split('.').map(String::toInt)) to bits.toInt()
}

private fun ipv4Value(octets: List<Int>): Long = octets.fold(0L) { acc, o -> (acc shl BYTE_BITS) or o.toLong() }

private const val IPV4_BITS = 32

/** Los cuatro números de una IPv4 encontrada, o null si alguno no cabe en un byte. */
internal fun octets(match: MatchResult): List<Int>? = match.groupValues.drop(1).map { it.toInt() }.takeIf { o -> o.all { it <= OCTET_MAX } }

/** ¿Es una IPv4 de internet? Las privadas, de bucle, de enlace, de documentación y las máscaras, no. */
internal fun isPublic(octets: List<Int>): Boolean {
    val value = ipv4Value(octets)
    return RESERVED_V4.none { (net, bits) -> value shr (IPV4_BITS - bits) == net shr (IPV4_BITS - bits) }
}

private fun isMulticast(h: List<Int>) = h[0] and MULTICAST == MULTICAST

private fun isLinkLocal(h: List<Int>) = h[0] and LINK_LOCAL_MASK == LINK_LOCAL

private fun isUla(h: List<Int>) = h[0] and ULA_MASK == ULA

/** ::, ::1 y parecidas: no son de nadie. */
private fun isUnspecifiedOrLoopback(h: List<Int>) = h.dropLast(1).all { it == 0 }

/** Un identificador de interfaz corto (::1, ::1be, lo que reparte DHCPv6): no lleva la MAC. */
private fun isShortId(id: List<Int>) = id.dropLast(1).all { it == 0 }

/** ¿Es ya un seudónimo, o una IPv6 que no dice nada de nadie? */
internal fun isAnonymousIpv6(h: List<Int>): Boolean {
    val id = h.drop(HEXTETS / 2)
    val madeUp = isShortId(id) || (id[0] == IID_MARK && id[1] == 0 && id[2] == 0)
    return isUnspecifiedOrLoopback(h) || isMulticast(h) ||
        h.take(2) == DOC_PREFIX || (h[0] == ULA_PSEUDONYM && h[1] == 0 && madeUp) || (isLinkLocal(h) && madeUp)
}

/** Los seudónimos de las direcciones de un informe. */
internal class AddressPseudonyms {
    private val macs = LinkedHashMap<String, String>()
    private val ipv4 = LinkedHashMap<String, String>()
    private val sites = LinkedHashMap<String, Int>()
    private val interfaceIds = LinkedHashMap<String, Int>()

    fun mac(value: String): String = macs.getOrPut(value.uppercase().replace('-', ':')) {
        val n = macs.size + 1
        MAC_PSEUDONYM + "%02X:%02X".format((n shr BYTE_BITS) and BYTE, n and BYTE)
    }

    fun ipv4(match: MatchResult): String {
        val o = octets(match)
        if (o == null || !isPublic(o)) return match.value
        return ipv4.getOrPut(match.value) { IPV4_PSEUDONYM + "${ipv4.size % OCTET_MAX + 1}" }
    }

    /**
     * Una IPv6 con seudónimo, o null si no es una IPv6 (una hora, una MAC ya cambiada…). El prefijo
     * del sitio pasa a 2001:db8:N (globales) o fd00:0:N (ULA), la subred se queda y el identificador
     * de interfaz, si no es corto, pasa a ::a:0:0:N (suele llevar la MAC).
     */
    fun ipv6(value: String): String? = expandIpv6(value)?.let { h ->
        when {
            isAnonymousIpv6(h) -> value
            isLinkLocal(h) -> compressIpv6(listOf(LINK_LOCAL, 0, 0, 0) + interfaceId(h))
            else -> {
                val site = h.take(SITE_HEXTETS).joinToString(":") { "%x".format(it) }
                val n = sites.getOrPut(site) { sites.size + 1 }
                val prefix = if (isUla(h)) listOf(ULA_PSEUDONYM, 0, n) else DOC_PREFIX + n
                compressIpv6(prefix + h[SUBNET_HEXTET] + interfaceId(h))
            }
        }
    }

    private fun interfaceId(h: List<Int>): List<Int> {
        val id = h.drop(HEXTETS / 2)
        if (isShortId(id)) return id
        val n = interfaceIds.getOrPut(id.joinToString(":") { "%x".format(it) }) { interfaceIds.size + 1 }
        return listOf(IID_MARK, 0, 0, n and HEXTET_MAX)
    }
}
