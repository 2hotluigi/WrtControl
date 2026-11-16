package dev.angel.wrtcontrol.data

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import dev.angel.wrtcontrol.addons.NoMobileDataException
import dev.angel.wrtcontrol.addons.OutsideLink
import dev.angel.wrtcontrol.addons.OutsideNetwork
import dev.angel.wrtcontrol.addons.ProbeOutcome
import dev.angel.wrtcontrol.addons.isGlobalIpv6
import dev.angel.wrtcontrol.go.wrtcvpn.Wrtcvpn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.coroutines.resume

/** Lo que se espera a que se levanten los datos móviles. */
private const val MOBILE_DATA_WAIT_MS = 10_000

/** Lo que se espera al saludo: WireGuard lo repite cada 5 s, así que salen tres. */
private const val PROBE_WAIT_MS = 11_000L

/**
 * Los datos móviles del teléfono para la prueba desde fuera, con el Wi-Fi puesto: se piden con
 * `requestNetwork` (Android los levanta aunque el Wi-Fi sea la red por defecto) y cada socket se ata
 * a ellos con `bindSocket`. Solo esta prueba va por ahí; lo demás de la app sigue por el Wi-Fi.
 */
class CellularOutsideNetwork(private val connectivity: ConnectivityManager) : OutsideNetwork {

    override suspend fun <T> withMobileData(block: suspend (OutsideLink) -> T): T {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network = suspendCancellableCoroutine<Network?> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                connectivity.requestNetwork(request, cb, MOBILE_DATA_WAIT_MS)
            } ?: throw NoMobileDataException(
                "No se pudo usar los datos móviles. Actívalos (puedes seguir en el Wi-Fi: la prueba solo gasta unos KB) y repite.",
            )
            val ipv6 = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                .any { it.address is Inet6Address && isGlobalIpv6(RouterTransport.ipText(it.address)) }
            return withContext(Dispatchers.IO) { block(MobileLink(network, ipv6)) }
        } finally {
            // Al soltar la petición, Android puede volver a apagar los datos móviles.
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
    }

    /**
     * [hasIpv6]: los datos móviles tienen una IPv6 global. Sin ella, Android deja enviar igualmente
     * a una IPv6 sin dar error, y la prueba parecería un router que no contesta. La IPv4 no se mira
     * así: con 464XLAT no aparece entre las direcciones y aun así funciona.
     */
    private class MobileLink(private val network: Network, private val hasIpv6: Boolean) : OutsideLink {
        override fun resolve(host: String): List<InetAddress> = network.getAllByName(host).toList()

        override fun probe(config: TunnelConfig, endpoint: String): ProbeOutcome {
            if (endpoint.startsWith("[") && !hasIpv6) return ProbeOutcome.CannotSend
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                // Una copia del descriptor que se queda (y cierra) la librería de Go.
                val fd = ParcelFileDescriptor.fromDatagramSocket(socket).detachFd()
                val result = Wrtcvpn.probe(
                    config.privateKey, config.routerPublicKey, config.presharedKey, endpoint, fd.toLong(), PROBE_WAIT_MS,
                )
                return when (result) {
                    Wrtcvpn.ProbeReached -> ProbeOutcome.Reached
                    Wrtcvpn.ProbeCannotSend -> ProbeOutcome.CannotSend
                    else -> ProbeOutcome.NoReply
                }
            }
        }
    }
}

/** La prueba desde fuera, o null si la librería del túnel no se puede cargar. */
fun outsideNetwork(connectivity: ConnectivityManager?): OutsideNetwork? = try {
    Class.forName("dev.angel.wrtcontrol.go.wrtcvpn.Wrtcvpn")
    connectivity?.let(::CellularOutsideNetwork)
} catch (_: Throwable) {
    null
}
