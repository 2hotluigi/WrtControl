package dev.angel.wrtcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.VendorTable
import dev.angel.wrtcontrol.openwrt.identify
import dev.angel.wrtcontrol.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La lista de fabricantes, leída fuera del hilo de la pantalla; mientras, null. */
@Composable
fun rememberVendors(): VendorTable? {
    val table = LocalVendors.current
    val vendors by produceState<VendorTable?>(null, table) { value = withContext(Dispatchers.Default) { table?.load() } }
    return vendors
}

/**
 * Cómo llamar a [device] en cualquier pantalla: el nombre que le puso el usuario, el que dice el
 * aparato o, si no dice ninguno, lo que se sepa de él ("Aparato Samsung").
 */
@Composable
fun deviceTitle(device: Device, vendors: VendorTable?): String = identify(device, vendors).title.text()
