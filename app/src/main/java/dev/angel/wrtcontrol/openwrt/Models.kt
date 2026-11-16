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
    @SerialName("ipv6-prefix-assignment") val ipv6Prefixes: List<PrefixAssignment> = emptyList(),
    @SerialName("dns-server") val dns: List<String> = emptyList(),
    @SerialName("route") val routes: List<Route> = emptyList(),
) {
    /** ¿Lleva la ruta por defecto (0.0.0.0/0 o ::/0)? Es la salida a internet, no una red de casa. */
    val hasDefaultRoute: Boolean get() = routes.any { it.mask == 0 }

    /**
     * IPv6 del router en esta interfaz, también la que toma de cada prefijo delegado (con solo
     * prefijo, la wan6 no tiene dirección propia y la del router está en la LAN). Es lo que da
     * network_get_ipaddr6, que usan ddns-scripts y LuCI.
     */
    val routerIpv6: List<IpAddress> get() = ipv6 + ipv6Prefixes.mapNotNull { it.localAddress }
}

/** Prefijo IPv6 asignado a una interfaz y la dirección que se queda el router dentro de él. */
@Serializable
data class PrefixAssignment(
    val address: String,
    val mask: Int,
    @SerialName("local-address") val localAddress: IpAddress? = null,
)

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

// ---------- versiones de OpenWrt ----------

/** Qué se sabe de la app con una versión de OpenWrt (ver "Versiones de OpenWrt" en el README). */
enum class OpenWrtSupport {
    /** 24.10 en adelante (y las de desarrollo): las pruebas de la CI corren en ella. */
    Tested,

    /** 22.03 y 23.05: tienen fw4 y las reglas se comprueban, pero la app no se prueba entera. */
    MayWork,

    /** Antes de 22.03 (iptables, sin fw4): el control parental no funciona. */
    Unsupported,

    /** No se reconoce la versión. */
    Unknown,
}

/** Versiones como número (24.10 → 2410): la primera probada y la primera con fw4. */
private const val FIRST_TESTED = 2410
private const val FIRST_FW4 = 2203
private const val MINOR_DIGITS = 100

/** [version] como la da system.board ("24.10.4", "25.12-SNAPSHOT", "SNAPSHOT"...). */
fun openWrtSupport(version: String): OpenWrtSupport {
    val v = version.trim()
    val n = Regex("""^(\d+)\.(\d+)""").find(v)?.destructured?.let { (major, minor) -> major.toInt() * MINOR_DIGITS + minor.toInt() }
    return when {
        v.equals("SNAPSHOT", ignoreCase = true) -> OpenWrtSupport.Tested
        n == null -> OpenWrtSupport.Unknown
        n >= FIRST_TESTED -> OpenWrtSupport.Tested
        n >= FIRST_FW4 -> OpenWrtSupport.MayWork
        else -> OpenWrtSupport.Unsupported
    }
}

/** El aviso para el usuario con esa versión, o null si no hace falta. */
fun openWrtSupportNotice(version: String): String? = when (openWrtSupport(version)) {
    OpenWrtSupport.Tested, OpenWrtSupport.Unknown -> null
    OpenWrtSupport.MayWork ->
        "La app se prueba con OpenWrt 24.10 o superior. Con $version puede funcionar, pero si algo falla, " +
            "actualiza el router."
    OpenWrtSupport.Unsupported ->
        "OpenWrt $version es demasiado antiguo: el control parental necesita el firewall de 22.03 o superior " +
            "(fw4). Lo demás puede funcionar; actualiza el router para usarlo todo."
}
