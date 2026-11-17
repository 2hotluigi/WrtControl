package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException

/*
 * Nombre de un aparato que no lo dice por DHCP, preguntándoselo por mDNS. El router no trae nada
 * de mDNS, pero sí el nslookup de BusyBox (el de la página de diagnóstico de LuCI, que su ACL deja
 * ejecutar), y un aparato con mDNS contesta también si se le pregunta a él directamente en el
 * puerto 5353. Así no hay que instalar nada ni estar en el Wi-Fi de casa.
 *
 * Se pregunta, por orden, hasta dar con un nombre:
 * 1. Su nombre de equipo: el PTR de su dirección ("twohotraspberry.local").
 * 2. Qué servicios anuncia y, del primero que suela llevar un nombre legible (Google Cast,
 *    AirPlay, una impresora…), cómo se llama ahí ("OTT-Xview+-AV1-<id>._googlecast._tcp.local").
 *
 * Medido en la casa (2026-11-16): de 4 aparatos sin nombre, uno contestó (una caja de tele con
 * Google Cast); una cámara no tiene mDNS y dos no contestan a preguntas directas. Es poco, pero
 * no cuesta nada. El nslookup de BusyBox solo enseña el primer texto de un TXT, así que el nombre
 * que se le pone a un Chromecast ("Tele de la sala", en `fn=`) no se puede leer: sale su modelo.
 *
 * Lo que contesta un aparato no es de fiar (cualquiera en la red puede decir lo que quiera): solo
 * se usa como nombre a enseñar, limpio ([cleanLabel]), y nunca vuelve al router como argumento.
 */

private const val MDNS_PORT = 5353
private const val META_QUERY = "_services._dns-sd._udp.local"

/** Servicios cuyo nombre suele decir qué aparato es, del más fiable al menos. */
private val NAMED_SERVICES = listOf(
    "_googlecast._tcp",
    "_airplay._tcp",
    "_raop._tcp",
    "_ipp._tcp",
    "_ipps._tcp",
    "_printer._tcp",
    "_hap._tcp",
    "_device-info._tcp",
    "_smb._tcp",
    "_workstation._tcp",
)

/** El identificador que Google Cast añade al modelo. */
private val CAST_ID = Regex("-[0-9a-fA-F]{32}$")

/** "host [aa:bb:cc:dd:ee:ff]", como se anuncia un equipo con Avahi. */
private val WORKSTATION_MAC = Regex("\\s*\\[[0-9a-fA-F:]+]$")

/** Un nombre de equipo que es solo un identificador (el de un Chromecast): no dice nada. */
private val OPAQUE_HOST = Regex("^[0-9a-fA-F-]{16,}$")

/** "\032" y parecidos: como se escriben en DNS los caracteres que no caben en un nombre. */
private val DECIMAL_ESCAPE = Regex("\\\\(\\d{3})")

/** Lo que contestó el aparato a una pregunta. [refused]: no tiene mDNS (puerto cerrado). */
internal data class MdnsAnswer(val names: List<String>, val refused: Boolean = false)

/** Los nombres de las líneas "pregunta<tab>name = respuesta" del nslookup de BusyBox. */
internal fun parseMdnsAnswer(output: String): MdnsAnswer = MdnsAnswer(
    names = output.lineSequence().mapNotNull { line -> line.substringAfter("name = ", "").trim().takeIf { it.isNotEmpty() } }.toList(),
    refused = "refused" in output.lowercase(),
)

/** "twohotraspberry.local" → "twohotraspberry"; null si no dice nada (un identificador). */
internal fun mdnsHostName(answer: String): String? {
    val host = answer.trim().trimEnd('.').removeSuffix(".local")
    return cleanLabel(unescape(host)).takeIf { it.isNotEmpty() && !OPAQUE_HOST.matches(it) && '.' !in it }
}

/** De los servicios que anuncia ([types], "_googlecast._tcp.local"), el primero que suele llevar nombre. */
internal fun namedService(types: List<String>): String? {
    val announced = types.map { it.trim().trimEnd('.').removeSuffix(".local").lowercase() }.toSet()
    return NAMED_SERVICES.firstOrNull { it in announced }
}

/** El nombre legible de [instance] ("Sala._airplay._tcp.local") dentro del servicio [type]. */
internal fun mdnsServiceName(instance: String, type: String): String? {
    val full = instance.trim().trimEnd('.')
    val at = full.lowercase().lastIndexOf(".$type")
    val label = unescape(if (at > 0) full.substring(0, at) else "")
    val name = when (type) {
        "_googlecast._tcp" -> label.replace(CAST_ID, "").replace('-', ' ')
        "_raop._tcp" -> label.substringAfter('@')
        "_workstation._tcp" -> label.replace(WORKSTATION_MAC, "")
        else -> label
    }
    return cleanLabel(name).takeIf { it.isNotEmpty() }
}

private fun unescape(name: String): String =
    DECIMAL_ESCAPE.replace(name) { it.groupValues[1].toInt().toChar().toString() }.replace("\\", "")

/** La pregunta inversa de [ip]: "192.168.1.20" → "20.1.168.192.in-addr.arpa". */
private fun reverseName(ip: String): String = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"

/** Pregunta el PTR de [name] al aparato [ip] por mDNS. null si el router no deja usar nslookup. */
private suspend fun UbusCaller.mdnsPtr(name: String, ip: String): MdnsAnswer? {
    val result = try {
        exec("/usr/bin/nslookup", listOf("-type=ptr", "-timeout=1", "-retry=1", "-port=$MDNS_PORT", name, ip))
    } catch (_: UbusException.AccessDenied) {
        null
    } catch (_: UbusException.Status) {
        null
    }
    return result?.let { parseMdnsAnswer(it.stdout + "\n" + it.stderr) }
}

/**
 * Cómo dice llamarse por mDNS el aparato que tiene [ip], o null si no contesta, no tiene mDNS o el
 * router no deja preguntar. Tarda como mucho unos segundos (un segundo por pregunta sin respuesta).
 */
suspend fun UbusCaller.mdnsName(ip: String): String? {
    val address = ip.trim().takeIf { parseIpv4(it) != null }
    val host = address?.let { mdnsPtr(reverseName(it), it) }
    val named = host?.names?.firstNotNullOfOrNull(::mdnsHostName)
    return when {
        // Sin permiso o con el puerto cerrado, las demás preguntas darían lo mismo.
        address == null || host == null || host.refused || named != null -> named
        else -> mdnsServiceName(address)
    }
}

/** El nombre con que [address] anuncia el primero de sus servicios que suele llevar uno. */
private suspend fun UbusCaller.mdnsServiceName(address: String): String? {
    val type = mdnsPtr(META_QUERY, address)?.names?.let(::namedService)
    return type?.let { known -> mdnsPtr("$known.local", address)?.names?.firstNotNullOfOrNull { mdnsServiceName(it, known) } }
}
