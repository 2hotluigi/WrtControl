package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.BuildConfig
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.StatusData
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.openwrt.openWrtSupportNotice
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.MeterRow
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.design.Stat
import dev.angel.wrtcontrol.ui.design.StatStrip
import dev.angel.wrtcontrol.ui.design.ValueRows
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.formatLoad
import dev.angel.wrtcontrol.ui.formatPercent
import dev.angel.wrtcontrol.ui.fraction
import dev.angel.wrtcontrol.ui.userMessage
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Carga y memoria cambian a cada momento. */
private val STATUS_REFRESH = 5.seconds

/** Solo cambia al instalar paquetes (y entonces se vuelve a mirar al momento). */
private val CAPABILITIES_REFRESH = 5.minutes

@Composable
fun StatusScreen() {
    val repository = LocalRepository.current
    val monitor = repository.monitor
    val state by monitor.status.watchAsState(STATUS_REFRESH)
    val capabilities by monitor.capabilities.watchAsState(CAPABILITIES_REFRESH)
    // Qué router es: se averigua una vez por sesión, así que se mira poco.
    val identity by monitor.identity.watchAsState(CAPABILITIES_REFRESH)
    val router = identity.data
    val verified by remember(router) { router?.let(repository::verifiedFeatures) ?: flowOf(null) }.collectAsState(null)
    // Lo que ya está en marcha en el router también "funciona aquí": lo apunta followRouter al leer
    // los paquetes y el control parental. Se leen aquí sin prisa por si no se ha abierto su pantalla.
    monitor.addons.watchAsState(CAPABILITIES_REFRESH)
    monitor.parental.watchAsState(CAPABILITIES_REFRESH)
    StatusContent(state, capabilities.data, verified, rememberReport())
}

/** El informe del router: se prepara al pulsar (lee el router y lo anonimiza) y se comparte. */
@Composable
private fun rememberReport(): ReportUi {
    val repository = LocalRepository.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Txt?>(null) }
    return ReportUi(busy, error?.text()) {
        if (busy) return@ReportUi
        busy = true
        error = null
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { repository.use { it.report.build(BuildConfig.VERSION_NAME) } }
                shareReport(context, json)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
}

/** [verified]: las funciones que ya funcionan en este router (ver CapabilitiesCard); null si no se sabe. */
@Composable
fun StatusContent(
    state: Loadable<StatusData>,
    capabilities: Capabilities? = null,
    verified: Set<Feature>? = null,
    report: ReportUi? = null,
) {
    LoadableContent(state) { data, error ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
            openWrtSupportNotice(data.board.release.version)?.let { notice ->
                item { NoticeCard(stringResource(R.string.status_untested_openwrt), notice.text()) }
            }
            item { Summary(data.info) }
            item { SystemCard(data.board, data.info) }
            // Si no se pudo averiguar, no se enseña: lo demás de la pantalla no depende de ello.
            if (capabilities != null) item { CapabilitiesCard(capabilities, verified) }
            items(data.interfaces, key = { it.name }) { InterfaceCard(it) }
            if (report != null) item { ReportCard(report) }
        }
    }
}

/** Arriba, lo que más se mira: cuánto lleva encendido, la carga y la memoria. */
@Composable
private fun Summary(info: SystemInfo) {
    val mem = info.memory
    StatStrip(
        listOf(
            Stat(stringResource(R.string.status_uptime), formatDuration(info.uptime)),
            Stat(stringResource(R.string.status_load_short), formatLoad(info.load.first())),
            Stat(stringResource(R.string.status_memory), formatPercent(fraction(mem.total - mem.available, mem.total))),
        ),
    )
}

@Composable
private fun SystemCard(board: BoardInfo, info: SystemInfo) {
    val mem = info.memory
    val memUsed = mem.total - mem.available
    Column {
        SectionHeader(stringResource(R.string.status_system))
        GroupCard {
            ValueRows(
                listOf(
                    stringResource(R.string.status_model) to board.model,
                    stringResource(R.string.status_firmware) to board.release.description,
                    stringResource(R.string.status_kernel) to board.kernel,
                    stringResource(R.string.status_load) to info.load.joinToString(" · ") { formatLoad(it) },
                ),
            )
            HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LocalPalette.current.separator)
            MeterRow(
                stringResource(R.string.status_memory),
                formatPercent(fraction(memUsed, mem.total)),
                stringResource(R.string.status_used_of, formatBytes(memUsed), formatBytes(mem.total)),
                fraction(memUsed, mem.total),
                divider = info.root != null,
            )
            info.root?.let { root ->
                MeterRow(
                    stringResource(R.string.status_storage),
                    formatPercent(fraction(root.used, root.total)),
                    stringResource(R.string.status_used_of, formatBytes(root.used * KIB), formatBytes(root.total * KIB)),
                    fraction(root.used, root.total),
                    divider = false,
                )
            }
        }
    }
}

/** Una conexión del router (WAN, LAN...): si está activa y sus direcciones. */
@Composable
private fun InterfaceCard(iface: NetInterface) {
    val palette = LocalPalette.current
    val v6 = iface.ipv6 + iface.ipv6Prefixes.map { IpAddress(it.address, it.mask) }
    val rows = listOfNotNull(
        stringResource(R.string.status_protocol) to iface.proto,
        stringResource(R.string.status_device) to (iface.l3Device ?: iface.device ?: "—"),
        iface.ipv4.takeIf { it.isNotEmpty() }?.let { v4 -> "IPv4" to v4.joinToString { "${it.address}/${it.mask}" } },
        v6.takeIf { it.isNotEmpty() }?.let { "IPv6" to it.joinToString { a -> "${a.address}/${a.mask}" } },
        iface.dns.takeIf { it.isNotEmpty() }?.let { "DNS" to it.joinToString() },
        iface.uptime?.takeIf { iface.up }?.let { stringResource(R.string.status_connected_for) to formatDuration(it) },
    )
    Column {
        SectionHeader(iface.name.uppercase())
        GroupCard {
            GroupRow(
                stringResource(R.string.status_iface_state),
                value = stringResource(if (iface.up) R.string.status_iface_up else R.string.status_iface_down),
                valueColor = if (iface.up) palette.good else palette.bad,
            )
            ValueRows(rows)
        }
    }
}
