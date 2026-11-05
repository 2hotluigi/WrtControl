package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow
import dev.angel.wrtcontrol.ui.Loadable
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.StatusPill
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.formatLoad
import dev.angel.wrtcontrol.ui.fraction
import dev.angel.wrtcontrol.ui.pollingFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.seconds

data class StatusData(val board: BoardInfo, val info: SystemInfo, val interfaces: List<NetInterface>)

class StatusViewModel(repo: RouterRepository) : ViewModel() {
    val state: StateFlow<Loadable<StatusData>> = pollingFlow(5.seconds) {
        repo.use { api ->
            coroutineScope {
                val board = async { api.board() }
                val info = async { api.systemInfo() }
                val interfaces = async { api.interfaces() }
                StatusData(board.await(), info.await(), interfaces.await())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())
}

@Composable
fun StatusScreen(state: Loadable<StatusData>) {
    LoadableContent(state) { data, error ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) item { ErrorBanner("Datos sin actualizar: $error") }
            item { SystemCard(data.board, data.info) }
            item { ResourcesCard(data.info) }
            items(data.interfaces, key = { it.name }) { InterfaceCard(it) }
        }
    }
}

@Composable
private fun SystemCard(board: BoardInfo, info: SystemInfo) {
    InfoCard("Sistema") {
        InfoRow("Modelo", board.model)
        InfoRow("Firmware", board.release.description)
        InfoRow("Kernel", board.kernel)
        InfoRow("Encendido", formatDuration(info.uptime))
    }
}

@Composable
private fun ResourcesCard(info: SystemInfo) {
    val mem = info.memory
    val memUsed = mem.total - mem.available
    InfoCard("Carga y memoria") {
        InfoRow("Carga (1/5/15 min)", info.load.joinToString(" · ") { formatLoad(it) })
        InfoRow("Memoria", "${formatBytes(memUsed)} de ${formatBytes(mem.total)}")
        LinearProgressIndicator(progress = { fraction(memUsed, mem.total) }, modifier = Modifier.fillMaxWidth())
        info.root?.let { root ->
            InfoRow("Almacenamiento", "${formatBytes(root.used * 1024)} de ${formatBytes(root.total * 1024)}")
            LinearProgressIndicator(progress = { fraction(root.used, root.total) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InterfaceCard(iface: NetInterface) {
    InfoCard(
        title = iface.name.uppercase(),
        trailing = { StatusPill(if (iface.up) "activa" else "caída", iface.up) },
    ) {
        InfoRow("Protocolo", iface.proto)
        InfoRow("Dispositivo", iface.l3Device ?: iface.device ?: "—")
        if (iface.ipv4.isNotEmpty()) InfoRow("IPv4", iface.ipv4.joinToString { "${it.address}/${it.mask}" })
        val v6 = iface.ipv6 + iface.ipv6Prefixes
        if (v6.isNotEmpty()) InfoRow("IPv6", v6.joinToString { "${it.address}/${it.mask}" })
        if (iface.dns.isNotEmpty()) InfoRow("DNS", iface.dns.joinToString())
        if (iface.up) iface.uptime?.let { InfoRow("Conectada", formatDuration(it)) }
    }
}
