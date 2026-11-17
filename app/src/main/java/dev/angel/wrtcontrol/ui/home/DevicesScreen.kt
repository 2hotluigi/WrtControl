package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.DeviceIdentity
import dev.angel.wrtcontrol.openwrt.DeviceKind
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.VendorTable
import dev.angel.wrtcontrol.openwrt.identify
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.LocalVendors
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** La lista de equipos cambia despacio. */
private val DEVICES_REFRESH = 10.seconds

@Composable
fun DevicesScreen() {
    val state by LocalRepository.current.monitor.devices.watchAsState(DEVICES_REFRESH)
    val table = LocalVendors.current
    // La lista de fabricantes se lee fuera del hilo de la pantalla; mientras, los nombres salen sin ella.
    val vendors by produceState<VendorTable?>(null, table) { value = withContext(Dispatchers.Default) { table?.load() } }

    LoadableContent(state) { devices, error -> DevicesContent(devices, vendors, error) }
}

@Composable
fun DevicesContent(devices: List<Device>, vendors: VendorTable?, error: String? = null) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
        item {
            Text(
                pluralStringResource(R.plurals.devices_count, devices.size, devices.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (devices.isEmpty()) {
            item { Text(stringResource(R.string.devices_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(devices, key = { it.mac }) { DeviceRow(it, identify(it, vendors)) }
    }
}

@Composable
private fun DeviceRow(device: Device, identity: DeviceIdentity) {
    var showHint by rememberSaveable(device.mac) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("device-${device.mac}")) {
        ListItem(
            leadingContent = {
                Icon(painterResource(identity.kind.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            headlineContent = { Text(identity.title.text()) },
            supportingContent = {
                val address = device.ipv4.firstOrNull() ?: device.ipv6.firstOrNull { !it.startsWith("fe80:") } ?: "—"
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Con nombre propio, el fabricante va debajo; sin él, ya está en el título.
                    if (identity.named && identity.vendor != null) Text(identity.vendor, style = MaterialTheme.typography.bodySmall)
                    if (identity.privateMac) {
                        Text(
                            stringResource(R.string.devices_private_mac),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.clickable { showHint = !showHint },
                        )
                    }
                    if (showHint) Text(stringResource(R.string.devices_private_mac_hint), style = MaterialTheme.typography.bodySmall)
                    Text(address, style = MaterialTheme.typography.bodySmall)
                    Text(device.mac, style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = {
                Text(leaseText(device), style = MaterialTheme.typography.labelSmall)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

private val DeviceKind.icon: Int
    get() = when (this) {
        DeviceKind.Phone -> R.drawable.ic_kind_phone
        DeviceKind.Computer -> R.drawable.ic_kind_computer
        DeviceKind.Tv -> R.drawable.ic_kind_tv
        DeviceKind.Network -> R.drawable.ic_kind_network
        DeviceKind.Smart -> R.drawable.ic_kind_smart
        DeviceKind.Other -> R.drawable.ic_devices
    }

@Composable
private fun leaseText(device: Device): String = when (val lease = device.lease) {
    LeaseState.None -> stringResource(if (device.ipv4.isEmpty()) R.string.devices_ipv6_only else R.string.devices_static_ip)
    LeaseState.Permanent -> stringResource(R.string.devices_static_lease)
    is LeaseState.Expires -> stringResource(R.string.devices_lease, formatDuration(lease.seconds))
}
