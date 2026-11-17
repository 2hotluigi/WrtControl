package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Estado de la concesión DHCPv4 de un equipo. */
sealed interface LeaseState {
    /** Sin concesión DHCPv4: IP fija o solo visto en la tabla de vecinos. */
    data object None : LeaseState

    data object Permanent : LeaseState

    data class Expires(val seconds: Long) : LeaseState
}

/** Si el aparato está ahora en la red, según la tabla de vecinos del router. */
enum class Presence {
    /** El router acaba de hablar con él. */
    Online,

    /** Sigue en la tabla, pero lleva un rato sin tráfico: dormido, o se acaba de ir. */
    Idle,

    /** No contesta o ya no está en la tabla. */
    Offline,

    /** El router no deja ver la tabla de vecinos. */
    Unknown,
}

data class Device(
    val mac: String,
    /** Cómo llamarlo: el nombre que le puso el usuario ([label]) o, si no, el que dice el aparato. */
    val name: String?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val lease: LeaseState,
    /** El nombre que le puso el usuario, guardado en el router (ver [DeviceRecord]). */
    val label: String? = null,
    /** La dirección que el router le reserva siempre, si tiene. */
    val fixedIp: String? = null,
    val presence: Presence = Presence.Unknown,
)

/**
 * No está en la red: sale en la lista solo porque el usuario le puso nombre o le fijó la dirección.
 * No cuenta como conectado ni espera aprobación.
 */
val Device.remembered: Boolean get() = presence == Presence.Offline && lease == LeaseState.None

internal fun leaseState(expires: JsonPrimitive?): LeaseState = when {
    expires == null -> LeaseState.Permanent
    expires.booleanOrNull == false -> LeaseState.Permanent
    else -> expires.longOrNull?.let { LeaseState.Expires(it) } ?: LeaseState.Permanent
}

/** [ip] como número, o null si no es una IPv4 bien escrita. */
internal fun parseIpv4(ip: String): Long? = ipv4ToLong(ip.trim())

/**
 * Redes de casa: "lan" y las demás interfaces sin la ruta por defecto (invitados, IoT...).
 * Sin las otras, un equipo con IP fija en el Wi-Fi de invitados no saldría en la lista y,
 * con la cuarentena, no habría forma de aprobarlo.
 */
internal fun lanNetworks(interfaces: List<NetInterface>): List<IpAddress> =
    interfaces.filter { it.name == "lan" || (!it.hasDefaultRoute && !it.name.startsWith("wan")) }.flatMap { it.ipv4 }

/** ¿Puede [ip] ser la dirección de un aparato de casa? De una red de casa, y ni la del router ni la de la red o la de difusión. */
fun usableLanAddress(ip: String, interfaces: List<NetInterface>): Boolean {
    val address = ipv4ToLong(ip)
    val net = lanNetworks(interfaces).firstOrNull { ipv4InSubnet(ip, it) }
    val ofRouter = interfaces.any { i -> i.ipv4.any { it.address == ip } }
    if (address == null || net == null || ofRouter) return false
    val hostBits = MAX_MASK - net.mask
    val last = (1L shl hostBits) - 1
    val host = address and last
    return hostBits >= 2 && host != 0L && host != last
}

private const val MAX_MASK = 32

private fun ipv4ToLong(ip: String): Long? {
    val parts = ip.split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        value = value * 256 + n
    }
    return value
}

fun ipv4InSubnet(ip: String, net: IpAddress): Boolean {
    val a = ipv4ToLong(ip) ?: return false
    val b = ipv4ToLong(net.address) ?: return false
    if (net.mask !in 0..32) return false
    val shift = 32 - net.mask
    return (a shr shift) == (b shr shift)
}

/** Acumulador mutable mientras se combinan las fuentes. */
private class DeviceBuilder(val mac: String) {
    var name: String? = null
    val ipv4 = LinkedHashSet<String>()
    val ipv6 = LinkedHashSet<String>()
    var lease: LeaseState = LeaseState.None

    fun build(record: DeviceRecord?, presence: Presence) =
        Device(mac, record?.label ?: name, ipv4.toList(), ipv6.toList(), lease, record?.label, record?.fixedIp, presence)
}

/** Los estados de `ip neigh` en los que el vecino contesta. */
private val ALIVE = setOf("REACHABLE", "DELAY", "PROBE", "PERMANENT")

/**
 * Salida de `ip -4 neigh show` e `ip -6 neigh show` ("192.168.1.20 dev br-lan lladdr aa:bb:… REACHABLE"):
 * MAC → lo mejor que se sabe de ella entre todas sus direcciones. Los vecinos sin MAC (FAILED,
 * INCOMPLETE) no dicen de quién son: quien no sale aquí, no está.
 */
fun parseNeighbors(output: String): Map<String, Presence> {
    val found = HashMap<String, Presence>()
    for (line in output.lineSequence()) {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        val at = words.indexOf("lladdr")
        val mac = words.getOrNull(at + 1)?.takeIf { at >= 0 }?.uppercase() ?: continue
        val presence = when (words.last()) {
            in ALIVE -> Presence.Online
            "STALE" -> Presence.Idle
            else -> Presence.Offline
        }
        if (presence < (found[mac] ?: Presence.Unknown)) found[mac] = presence
    }
    return found
}

/**
 * Combina las pistas de host y las concesiones DHCP en la lista de clientes de la LAN.
 * Excluye las direcciones del propio router y los vecinos del lado WAN.
 * [records]: lo que el usuario guardó de cada aparato; los que no están ahora salen igual, como
 * desconectados. [neighbors]: la tabla de vecinos ([parseNeighbors]), o null si no se puede ver.
 */
fun buildDeviceList(
    hints: Map<String, HostHint>,
    leases: DhcpLeases,
    interfaces: List<NetInterface>,
    records: List<DeviceRecord> = emptyList(),
    neighbors: Map<String, Presence>? = null,
): List<Device> {
    val routerIps = interfaces.flatMap { i -> i.ipv4.map { it.address } }.toSet()
    val lanNets = lanNetworks(interfaces)
    fun inLan(ip: String) = ip !in routerIps && lanNets.any { ipv4InSubnet(ip, it) }

    val devices = LinkedHashMap<String, DeviceBuilder>()
    fun get(mac: String) = devices.getOrPut(mac.uppercase()) { DeviceBuilder(mac.uppercase()) }

    // 1. Concesiones DHCPv4: la fuente más fiable (MAC real de la tarjeta + nombre).
    for (lease in leases.v4) {
        val d = get(lease.macaddr)
        if (d.name == null) d.name = lease.hostname?.ifBlank { null }
        d.lease = leaseState(lease.expires)
        d.ipv4 += lease.ipaddr
    }

    // 2. Pistas de host (vecinos ARP/NDP): añaden equipos con IP fija en la LAN.
    for ((mac, hint) in hints) {
        val ipv4 = hint.ipaddrs.filter(::inLan)
        val known = devices[mac.uppercase()]
        if (known == null && ipv4.isEmpty()) continue
        val d = known ?: get(mac)
        if (d.name == null) d.name = hint.name?.ifBlank { null }
        d.ipv4 += ipv4
        d.ipv6 += hint.ip6addrs
    }

    // 3. Concesiones DHCPv6. odhcpd deduce la MAC a partir del DUID, y Windows usa un solo DUID
    //    (basado en la MAC de otra tarjeta) para todas sus interfaces. Por eso se asocian a un
    //    equipo ya conocido por MAC, por dirección o por nombre antes de crear uno nuevo.
    for (lease in leases.v6) {
        val addrs = (lease.ip6addrs.ifEmpty { listOfNotNull(lease.ip6addr) }).map { it.substringBefore('/') }
        val name = lease.hostname?.ifBlank { null }
        val known = devices.values.toList()
        val d = lease.macaddr?.let { devices[it.uppercase()] }
            ?: known.firstOrNull { k -> addrs.any { it in k.ipv6 } }
            ?: name?.let { n -> known.firstOrNull { it.name.equals(n, ignoreCase = true) } }
            ?: lease.macaddr?.let(::get)
            ?: continue // sin MAC ni coincidencias no se puede asociar a un equipo
        if (d.name == null) d.name = name
        d.ipv6 += addrs
    }

    val known = records.associateBy { it.mac }
    // Los que tienen nombre o dirección guardados pero ahora no están: se siguen viendo.
    for (record in records) if (record.mac !in devices) get(record.mac).also { d -> record.fixedIp?.let { d.ipv4 += it } }
    fun presence(mac: String) = if (neighbors == null) Presence.Unknown else neighbors[mac] ?: Presence.Offline

    return devices.values.map { it.build(known[it.mac], presence(it.mac)) }.sortedWith(
        compareBy<Device, Long?>(nullsLast()) { d -> d.ipv4.firstOrNull()?.let(::ipv4ToLong) }.thenBy { it.mac },
    )
}
