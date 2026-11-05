package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.uciSection

/*
 * Acceso remoto: un servidor WireGuard en el router para que la app (y solo la app, con su propio
 * WireGuard dentro, sin VPN de Android) llegue a él desde fuera de casa.
 *
 * - /etc/config/network: la interfaz `wrtcvpn` (proto wireguard) con la dirección del router en
 *   el túnel, y un par `wireguard_wrtcvpn` por teléfono, con su clave pública, una precompartida y
 *   su dirección /32. La clave privada del teléfono no sale nunca de él.
 * - /etc/config/firewall: una zona `wrtcvpn` cerrada (no reenvía a la LAN ni a internet), una regla
 *   que deja entrar el UDP del túnel desde la WAN y otra que, desde el túnel, solo deja llegar a la
 *   web del router (uhttpd), que es lo que usa la app.
 *
 * Los nombres no empiezan por "wrtc_": esas secciones son del control parental, que borra las que
 * no reconoce.
 */

const val VPN_PREFIX = "wrtcvpn"
const val VPN_INTERFACE = "wrtcvpn"
const val VPN_ZONE = "wrtcvpn"
const val VPN_PEER_TYPE = "wireguard_$VPN_INTERFACE"
private const val VPN_PEER_PREFIX = "${VPN_PREFIX}_p_"
private const val VPN_ZONE_SECTION = "${VPN_PREFIX}_zone"
private const val VPN_RULE_IN = "${VPN_PREFIX}_in"
private const val VPN_RULE_WEB = "${VPN_PREFIX}_web"
const val DEFAULT_VPN_PORT = 51820
private const val PEER_LABEL = "WrtControl · "

/**
 * Nombre de las reglas del acceso remoto. No empieza por "WrtControl · " (RULE_LABEL_PREFIX): el
 * control parental da por suyas las reglas cargadas con ese nombre y comprueba que sean justo las
 * que espera.
 */
const val VPN_RULE_LABEL = "WrtControl acceso remoto · "

/** Un teléfono con acceso remoto. [address]: su IPv4 en el túnel. */
data class RemotePeer(val id: String, val name: String, val publicKey: String, val presharedKey: String?, val address: String)

/** El servidor tal como está en el router (sin su clave privada: ver [remotePrivateKey]). */
data class RemoteServer(
    val port: Int,
    /** IPv4 del router en el túnel y longitud del prefijo ("10.77.0.1", 24). */
    val routerAddress: String,
    val prefixLength: Int,
    /** Clave pública del router; null si la privada falta o no es válida. */
    val publicKey: String?,
    val peers: List<RemotePeer>,
)

internal fun remotePrivateKey(network: List<UciSection>): String? =
    network.firstOrNull { it.name == VPN_INTERFACE && it.type == "interface" }?.text("private_key")?.takeIf(X25519::isKey)

/** Lo que el teléfono necesita para conectarse (lo guarda junto a su clave privada). */
data class RemoteSetup(
    val routerPublicKey: String,
    val port: Int,
    val routerTunnelAddress: String,
    val phoneAddress: String,
    /** Direcciones públicas del router ahora mismo (IPv6 global y, si no hay NAT, IPv4). */
    val publicAddresses: List<String>,
)

internal fun parseRemoteServer(network: List<UciSection>): RemoteServer? {
    val iface = network.firstOrNull { it.name == VPN_INTERFACE && it.type == "interface" } ?: return null
    val address = iface.items("addresses").firstOrNull { '.' in it } ?: return null
    return RemoteServer(
        port = iface.text("listen_port")?.toIntOrNull() ?: DEFAULT_VPN_PORT,
        routerAddress = address.substringBefore('/'),
        prefixLength = address.substringAfter('/', "24").toIntOrNull() ?: 24,
        publicKey = remotePrivateKey(network)?.let(X25519::publicKey),
        peers = network.filter { it.type == VPN_PEER_TYPE && it.name.startsWith(VPN_PEER_PREFIX) }.map { s ->
            RemotePeer(
                id = s.name.removePrefix(VPN_PEER_PREFIX),
                name = s.text("description").orEmpty().removePrefix(PEER_LABEL),
                publicKey = s.text("public_key").orEmpty(),
                presharedKey = s.text("preshared_key"),
                address = s.items("allowed_ips").firstOrNull().orEmpty().substringBefore('/'),
            )
        },
    )
}

/** Identificador de par válido como parte de un nombre de sección UCI. */
fun isPeerId(id: String) = Regex("^[a-z0-9]{4,16}$").matches(id)

/**
 * Las secciones del acceso remoto para [server] con [peers]: (network, firewall). [wanZones]: las
 * zonas por las que llega internet; [webPorts]: los de uhttpd.
 */
internal fun remoteSections(
    privateKey: String,
    port: Int,
    routerAddress: String,
    prefixLength: Int,
    peers: List<RemotePeer>,
    wanZones: List<String>,
    webPorts: List<Int>,
): Pair<List<UciSection>, List<UciSection>> {
    val network = listOf(
        uciSection(
            VPN_INTERFACE, "interface",
            "proto" to "wireguard",
            "private_key" to privateKey,
            "listen_port" to port.toString(),
            "addresses" to listOf("$routerAddress/$prefixLength"),
        ),
    ) + peers.map { p ->
        uciSection(
            VPN_PEER_PREFIX + p.id, VPN_PEER_TYPE,
            "description" to PEER_LABEL + p.name,
            "public_key" to p.publicKey,
            "preshared_key" to p.presharedKey,
            "allowed_ips" to listOf("${p.address}/32"),
        )
    }
    val firewall = listOf(
        uciSection(
            VPN_ZONE_SECTION, "zone",
            "name" to VPN_ZONE,
            "network" to listOf(VPN_INTERFACE),
            "input" to "REJECT",
            "output" to "ACCEPT",
            "forward" to "REJECT",
        ),
        uciSection(
            VPN_RULE_WEB, "rule",
            "name" to VPN_RULE_LABEL + "web del router",
            "src" to VPN_ZONE,
            "proto" to "tcp",
            "dest_port" to webPorts.joinToString(" "),
            "target" to "ACCEPT",
        ),
    ) + wanZones.ifEmpty { listOf("wan") }.mapIndexed { i, zone ->
        uciSection(
            if (i == 0) VPN_RULE_IN else "${VPN_RULE_IN}_$i", "rule",
            "name" to VPN_RULE_LABEL + "WireGuard" + if (wanZones.size > 1) " ($zone)" else "",
            "src" to zone,
            "proto" to "udp",
            "dest_port" to port.toString(),
            "target" to "ACCEPT",
        )
    }
    return network to firewall
}

/** Secciones propias que hay que borrar o crear para pasar de [existing] a [desired]. */
internal fun diffRemote(existing: List<UciSection>, desired: List<UciSection>): Pair<List<String>, List<UciSection>> {
    val ours = existing.filter { it.name.startsWith(VPN_PREFIX) }.associateBy { it.name }
    val wanted = desired.associateBy { it.name }
    val deletes = ours.values.filter { wanted[it.name] != it }.map { it.name }
    val adds = wanted.values.filter { ours[it.name] != it }
    return deletes to adds
}

/** Un puerto UDP que no use ya otra interfaz WireGuard del router. */
internal fun freeVpnPort(network: List<UciSection>): Int {
    val used = network.filter { it.type == "interface" && it.text("proto") == "wireguard" && it.name != VPN_INTERFACE }
        .mapNotNull { it.text("listen_port")?.toIntOrNull() }.toSet()
    return generateSequence(DEFAULT_VPN_PORT) { it + 1 }.first { it !in used }
}

/** Una /24 privada para el túnel que no choque con las redes del router. */
internal fun freeVpnSubnet(interfaces: List<NetInterface>): String {
    val taken = interfaces.filter { it.name != VPN_INTERFACE }.flatMap { it.ipv4 }.map { ipv4Range(it.address, it.mask) }
    val candidates = (77..99).map { "10.$it.0.0" } + (77..99).map { "172.31.$it.0" }
    return candidates.first { c ->
        val r = ipv4Range(c, 24)
        taken.none { it.first <= r.last && r.first <= it.last }
    }
}

/** Primera dirección libre del túnel (de .2 en adelante; .1 es el router). */
internal fun nextPeerAddress(routerAddress: String, prefixLength: Int, used: Set<String>): String {
    val range = ipv4Range(routerAddress, prefixLength)
    val router = ipv4ToLong(routerAddress)
    return (range.first + 1 until range.last).asSequence()
        .filter { it != router }
        .map(::longToIpv4)
        .firstOrNull { it !in used }
        ?: throw IllegalStateException("No quedan direcciones libres en el túnel del router.")
}

/**
 * Direcciones con las que se llega al router desde internet: las IPv6 globales (no las locales
 * fe80::/10 ni las privadas fc00::/7) y la IPv4 de la WAN si es pública. La de la salida a
 * internet va primero.
 */
internal fun publicAddresses(interfaces: List<NetInterface>): List<String> {
    val ordered = interfaces.filter { it.up && it.hasDefaultRoute } + interfaces.filter { it.up && !it.hasDefaultRoute }
    val v6 = ordered.flatMap { it.ipv6 }.map { it.address }.filter(::isGlobalIpv6)
    val v4 = interfaces.filter { it.up && it.hasDefaultRoute }.flatMap { it.ipv4 }.map { it.address }.filter { !isPrivateIpv4(it) }
    return (v6 + v4).distinct()
}

internal fun isGlobalIpv6(address: String): Boolean {
    val first = address.substringBefore(':').toIntOrNull(16) ?: return false
    if (address.startsWith("::")) return false
    return first in 0x2000..0x3fff // 2000::/3, el espacio global de unidifusión
}

private fun ipv4ToLong(a: String): Long = a.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLong() and 0xff) }

private fun longToIpv4(v: Long): String = (3 downTo 0).joinToString(".") { ((v shr (it * 8)) and 0xff).toString() }

private fun ipv4Range(address: String, mask: Int): LongRange {
    val ip = ipv4ToLong(address)
    val size = 1L shl (32 - mask.coerceIn(0, 32))
    val start = ip and (size - 1).inv() and 0xffffffffL
    return start..(start + size - 1)
}

/** Puertos de la web del router (uhttpd): los que usa la app. Sin configuración, 80 y 443. */
internal fun webPorts(uhttpd: List<UciSection>?): List<Int> =
    uhttpd.orEmpty().filter { it.type == "uhttpd" }
        .flatMap { it.items("listen_http") + it.items("listen_https") }
        .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
        .filter { it in 1..65535 }.distinct().sorted()
        .ifEmpty { listOf(80, 443) }

/**
 * Zonas por las que llega internet: las que contienen la interfaz con la ruta por defecto (wan,
 * wan6). Si no se encuentra, las que hacen NAT; y si tampoco, "wan".
 */
internal fun internetZones(firewall: List<UciSection>, interfaces: List<NetInterface>): List<String> {
    val zones = firewall.filter { it.type == "zone" && it.text("enabled") != "0" }
    val wanIfaces = interfaces.filter { it.hasDefaultRoute }.map { it.name }.toSet()
    val byIface = zones.filter { z -> z.items("network").any { it in wanIfaces } }.mapNotNull { it.text("name") }
    if (byIface.isNotEmpty()) return byIface.distinct()
    val masq = zones.filter { it.text("masq") in setOf("1", "on", "true", "yes") }.mapNotNull { it.text("name") }
    return masq.distinct().ifEmpty { listOf("wan") }
}
