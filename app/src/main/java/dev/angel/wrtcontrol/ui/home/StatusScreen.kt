package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.StatusData
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.openwrt.openWrtSupportNotice
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.StatusPill
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.formatLoad
import dev.angel.wrtcontrol.ui.fraction
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.seconds

/** Carga y memoria cambian a cada momento. */
private val STATUS_REFRESH = 5.seconds

@Composable
fun StatusScreen() {
    val state by LocalRepository.current.monitor.status.watchAsState(STATUS_REFRESH)
    StatusContent(state)
}

@Composable
fun StatusContent(state: Loadable<StatusData>) {
    LoadableContent(state) { data, error ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
            openWrtSupportNotice(data.board.release.version)?.let { notice ->
                item { NoticeCard(stringResource(R.string.status_untested_openwrt), notice.text()) }
            }
            item { SystemCard(data.board, data.info) }
            item { ResourcesCard(data.info) }
            items(data.interfaces, key = { it.name }) { InterfaceCard(it) }
        }
    }
}

@Composable
private fun SystemCard(board: BoardInfo, info: SystemInfo) {
    InfoCard(stringResource(R.string.status_system)) {
        InfoRow(stringResource(R.string.status_model), board.model)
        InfoRow(stringResource(R.string.status_firmware), board.release.description)
        InfoRow(stringResource(R.string.status_kernel), board.kernel)
        InfoRow(stringResource(R.string.status_uptime), formatDuration(info.uptime))
    }
}

@Composable
private fun ResourcesCard(info: SystemInfo) {
    val mem = info.memory
    val memUsed = mem.total - mem.available
    InfoCard(stringResource(R.string.status_load_memory)) {
        InfoRow(stringResource(R.string.status_load), info.load.joinToString(" · ") { formatLoad(it) })
        InfoRow(
            stringResource(R.string.status_memory),
            stringResource(R.string.status_used_of, formatBytes(memUsed), formatBytes(mem.total)),
        )
        LinearProgressIndicator(progress = { fraction(memUsed, mem.total) }, modifier = Modifier.fillMaxWidth())
        info.root?.let { root ->
            InfoRow(
                stringResource(R.string.status_storage),
                stringResource(R.string.status_used_of, formatBytes(root.used * 1024), formatBytes(root.total * 1024)),
            )
            LinearProgressIndicator(progress = { fraction(root.used, root.total) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InterfaceCard(iface: NetInterface) {
    InfoCard(
        title = iface.name.uppercase(),
        trailing = { StatusPill(stringResource(if (iface.up) R.string.status_iface_up else R.string.status_iface_down), iface.up) },
    ) {
        InfoRow(stringResource(R.string.status_protocol), iface.proto)
        InfoRow(stringResource(R.string.status_device), iface.l3Device ?: iface.device ?: "—")
        if (iface.ipv4.isNotEmpty()) InfoRow("IPv4", iface.ipv4.joinToString { "${it.address}/${it.mask}" })
        val v6 = iface.ipv6 + iface.ipv6Prefixes.map { IpAddress(it.address, it.mask) }
        if (v6.isNotEmpty()) InfoRow("IPv6", v6.joinToString { "${it.address}/${it.mask}" })
        if (iface.dns.isNotEmpty()) InfoRow("DNS", iface.dns.joinToString())
        if (iface.up) iface.uptime?.let { InfoRow(stringResource(R.string.status_connected_for), formatDuration(it)) }
    }
}
