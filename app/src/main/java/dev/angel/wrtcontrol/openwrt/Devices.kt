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

data class Device(
    val mac: String,
    val name: String?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val lease: LeaseState,
)

internal fun leaseState(expires: JsonPrimitive?): LeaseState = when {
    expires == null -> LeaseState.Permanent
    expires.booleanOrNull == false -> LeaseState.Permanent
    else -> expires.longOrNull?.let { LeaseState.Expires(it) } ?: LeaseState.Permanent
}

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

    fun build() = Device(mac, name, ipv4.toList(), ipv6.toList(), lease)
}

/**
 * Combina las pistas de host y las concesiones DHCP en la lista de clientes de la LAN.
 * Excluye las direcciones del propio router y los vecinos del lado WAN.
 */
fun buildDeviceList(
    hints: Map<String, HostHint>,
    leases: DhcpLeases,
    interfaces: List<NetInterface>,
): List<Device> {
    val routerIps = interfaces.flatMap { i -> i.ipv4.map { it.address } }.toSet()
    // Redes de casa: "lan" y las demás interfaces sin la ruta por defecto (invitados, IoT...).
    // Sin las otras, un equipo con IP fija en el Wi-Fi de invitados no saldría en la lista y,
    // con la cuarentena, no habría forma de aprobarlo.
    val lanNets = interfaces.filter { it.name == "lan" || (!it.hasDefaultRoute && !it.name.startsWith("wan")) }.flatMap { it.ipv4 }
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

    return devices.values.map { it.build() }.sortedWith(
        compareBy<Device, Long?>(nullsLast()) { d -> d.ipv4.firstOrNull()?.let(::ipv4ToLong) }.thenBy { it.mac },
    )
}
