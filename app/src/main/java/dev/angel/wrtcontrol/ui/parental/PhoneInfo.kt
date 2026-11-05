package dev.angel.wrtcontrol.ui.parental

import android.content.Context
import android.net.ConnectivityManager
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
