package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.describeError
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusException

/** Un fallo, contado al usuario: qué pasó y qué puede hacer. */
fun Throwable.userMessage(): Txt = if (blockedByLocalNetwork()) txt(R.string.error_local_network) else describeError(this)

/**
 * Android 17 sin el permiso de red local: los envíos a IPs privadas dan EPERM, y TCP no conecta (se
 * agota el tiempo). Puede venir envuelto en el error de red del cliente de ubus.
 */
private fun Throwable.blockedByLocalNetwork(): Boolean {
    val io = this as? java.io.IOException ?: (this as? UbusException.Network)?.cause as? java.io.IOException ?: return false
    return io.message?.contains("EPERM") == true || LocalNetworkStatus.missing
}
