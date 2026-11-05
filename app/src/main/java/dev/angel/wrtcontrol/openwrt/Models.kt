package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// ---------- system.board / system.info ----------

@Serializable
data class BoardInfo(
    val hostname: String = "OpenWrt",
    val model: String = "",
    val system: String = "",
    val kernel: String = "",
    val release: Release = Release(),
) {
    @Serializable
    data class Release(
        val distribution: String = "",
        val version: String = "",
        val revision: String = "",
        val target: String = "",
        val description: String = "",
    )
}

@Serializable
data class SystemInfo(
    val localtime: Long = 0,
    val uptime: Long = 0,
    /** Carga media 1/5/15 min en punto fijo (dividir entre 65536). */
    val load: List<Long> = emptyList(),
    val memory: Memory = Memory(),
    /** Tamaños en KiB. */
    val root: Disk? = null,
    val tmp: Disk? = null,
) {
    @Serializable
    data class Memory(
        val total: Long = 0,
        val free: Long = 0,
        val shared: Long = 0,
        val buffered: Long = 0,
        val available: Long = 0,
        val cached: Long = 0,
    )

    @Serializable
    data class Disk(val total: Long = 0, val free: Long = 0, val used: Long = 0, val avail: Long = 0)
}

// ---------- network.interface dump ----------

@Serializable
data class IpAddress(val address: String, val mask: Int)

@Serializable
data class NetInterface(
    @SerialName("interface") val name: String,
    val up: Boolean = false,
    val proto: String = "",
    val device: String? = null,
    @SerialName("l3_device") val l3Device: String? = null,
    val uptime: Long? = null,
    @SerialName("ipv4-address") val ipv4: List<IpAddress> = emptyList(),
    @SerialName("ipv6-address") val ipv6: List<IpAddress> = emptyList(),
    @SerialName("ipv6-prefix-assignment") val ipv6Prefixes: List<IpAddress> = emptyList(),
    @SerialName("dns-server") val dns: List<String> = emptyList(),
    @SerialName("route") val routes: List<Route> = emptyList(),
) {
    /** ¿Lleva la ruta por defecto (0.0.0.0/0 o ::/0)? Es la salida a internet, no una red de casa. */
    val hasDefaultRoute: Boolean get() = routes.any { it.mask == 0 }
}

@Serializable
data class Route(val target: String = "", val mask: Int = -1)

@Serializable
internal data class InterfaceDump(@SerialName("interface") val interfaces: List<NetInterface> = emptyList())

// ---------- luci-rpc ----------

/** luci-rpc.getHostHints: `{ "AA:BB:..": { name?, ipaddrs?, ip6addrs? } }` */
@Serializable
data class HostHint(
    val name: String? = null,
    val ipaddrs: List<String> = emptyList(),
    val ip6addrs: List<String> = emptyList(),
)

@Serializable
data class DhcpLeases(
    @SerialName("dhcp_leases") val v4: List<Lease4> = emptyList(),
    @SerialName("dhcp6_leases") val v6: List<Lease6> = emptyList(),
) {
    /** `expires` llega como número de segundos o `false` (concesión permanente). */
    @Serializable
    data class Lease4(
        val macaddr: String,
        val ipaddr: String,
        val hostname: String? = null,
        val expires: JsonPrimitive? = null,
    )

    @Serializable
    data class Lease6(
        val macaddr: String? = null,
        val hostname: String? = null,
        val ip6addr: String? = null,
        val ip6addrs: List<String> = emptyList(),
        val expires: JsonPrimitive? = null,
    )
}
