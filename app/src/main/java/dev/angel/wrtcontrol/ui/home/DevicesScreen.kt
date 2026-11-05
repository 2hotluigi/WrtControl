package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.Loadable
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.pollingFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.seconds

class DevicesViewModel(repo: RouterRepository) : ViewModel() {
    val state: StateFlow<Loadable<List<Device>>> = pollingFlow(10.seconds) { repo.use { it.devices() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())
}

@Composable
fun DevicesScreen(routerAddress: String) {
    val repo = LocalRepository.current
    val vm: DevicesViewModel = viewModel(key = "devices:$routerAddress") { DevicesViewModel(repo) }
    val state by vm.state.collectAsStateWithLifecycle()

    LoadableContent(state) { devices, error ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) item { ErrorBanner("Datos sin actualizar: $error") }
            item {
                Text(
                    "${devices.size} ${if (devices.size == 1) "dispositivo" else "dispositivos"} en la red",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (devices.isEmpty()) {
                item { Text("No se detectan dispositivos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(devices, key = { it.mac }) { DeviceRow(it) }
        }
    }
}

@Composable
private fun DeviceRow(device: Device) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(device.name ?: "Sin nombre") },
            supportingContent = {
                val address = device.ipv4.firstOrNull() ?: device.ipv6.firstOrNull { !it.startsWith("fe80:") } ?: "—"
                Text("$address\n${device.mac}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            },
            trailingContent = {
                Text(leaseText(device), style = MaterialTheme.typography.labelSmall)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

private fun leaseText(device: Device): String = when (val lease = device.lease) {
    LeaseState.None -> if (device.ipv4.isEmpty()) "solo IPv6" else "IP fija"
    LeaseState.Permanent -> "DHCP fijo"
    is LeaseState.Expires -> "DHCP · ${formatDuration(lease.seconds)}"
}
