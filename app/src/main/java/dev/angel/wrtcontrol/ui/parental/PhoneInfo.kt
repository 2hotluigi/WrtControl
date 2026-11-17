package dev.angel.wrtcontrol.ui.parental

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * IPv4 de este teléfono en la red activa. Android no da su MAC (y la de la Wi-Fi suele ser
 * aleatoria), pero el router sí la conoce: coincidiendo la IP se marca "Este teléfono".
 */
fun phoneAddresses(context: Context): Set<String> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
    val network = cm.activeNetwork ?: return emptySet()
    val props = cm.getLinkProperties(network) ?: return emptySet()
    return props.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }.toSet()
}

/**
 * La puerta de enlace de la red de casa en la que está el teléfono (por Wi-Fi o por cable): casi
 * siempre, el router. null con datos móviles o una VPN, donde la puerta de enlace no es ningún
 * router de casa.
 */
fun homeGateway(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm?.activeNetwork
    val capabilities = network?.let(cm::getNetworkCapabilities)
    val home = capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    if (!home) return null
    val routes = cm.getLinkProperties(network)?.routes.orEmpty()
    return routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
}
