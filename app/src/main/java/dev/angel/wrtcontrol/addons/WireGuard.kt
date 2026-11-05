package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.UciSection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * WireGuard (kmod-wireguard + wireguard-tools + luci-proto-wireguard). Las interfaces son
 * secciones `interface` de /etc/config/network con `proto wireguard`, y sus pares, secciones de
 * tipo `wireguard_<interfaz>`. El estado en vivo (último saludo, tráfico) sale de
 * `luci.wireguard getWgInstances`, que lee `wg show all dump`.
 */

data class WireGuardInterface(
    val name: String,
    val listenPort: Int?,
    val addresses: List<String>,
    val peers: List<WireGuardPeer>,
    /** La interfaz existe en el kernel (netifd la levantó). */
    val up: Boolean,
)

data class WireGuardPeer(
    val section: String,
    val description: String,
    val publicKey: String,
    val allowedIps: List<String>,
    /** Hora UTC (segundos) del último saludo; null si nunca ha conectado. */
    val lastHandshake: Long?,
    val received: Long,
    val sent: Long,
)

@Serializable
internal data class WgInstance(
    val name: String = "",
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("listen_port") val listenPort: String = "",
    val peers: List<WgPeerStatus> = emptyList(),
)

@Serializable
internal data class WgPeerStatus(
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("latest_handshake") val latestHandshake: String = "0",
    @SerialName("transfer_rx") val transferRx: String = "0",
    @SerialName("transfer_tx") val transferTx: String = "0",
)

/** Interfaces WireGuard de [network] con lo que diga [live] (`getWgInstances`, por dispositivo). */
internal fun parseWireGuard(network: List<UciSection>, live: Map<String, WgInstance>?): List<WireGuardInterface> =
    network.filter { it.type == "interface" && it.text("proto") == "wireguard" }.map { iface ->
        val status = live?.get(iface.name)
        WireGuardInterface(
            name = iface.name,
            listenPort = iface.text("listen_port")?.toIntOrNull(),
            addresses = iface.items("addresses"),
            up = status != null,
            peers = network.filter { it.type == "wireguard_${iface.name}" }.map { peer ->
                val key = peer.text("public_key").orEmpty()
                val p = status?.peers?.firstOrNull { it.publicKey == key }
                WireGuardPeer(
                    section = peer.name,
                    description = peer.text("description").orEmpty(),
                    publicKey = key,
                    allowedIps = peer.items("allowed_ips"),
                    lastHandshake = p?.latestHandshake?.toLongOrNull()?.takeIf { it > 0 },
                    received = p?.transferRx?.toLongOrNull() ?: 0,
                    sent = p?.transferTx?.toLongOrNull() ?: 0,
                )
            },
        )
    }

@Serializable
internal data class WgKeyPair(val keys: Keys = Keys()) {
    @Serializable
    data class Keys(val priv: String = "", val pub: String = "")
}
