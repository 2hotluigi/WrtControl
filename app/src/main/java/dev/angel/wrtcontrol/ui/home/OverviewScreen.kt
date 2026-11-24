package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.openwrt.GuestWifi
import dev.angel.wrtcontrol.openwrt.Recorder
import dev.angel.wrtcontrol.openwrt.UsageBucket
import dev.angel.wrtcontrol.openwrt.UsagePeriod
import dev.angel.wrtcontrol.openwrt.guestWifi
import dev.angel.wrtcontrol.openwrt.openWrtSupportNotice
import dev.angel.wrtcontrol.openwrt.totals
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.design.BigNumber
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.FeatureTile
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.design.Sparkline
import dev.angel.wrtcontrol.ui.design.Stat
import dev.angel.wrtcontrol.ui.design.StatStrip
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.parental.onWifiBehind
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val NETWORK_REFRESH = 5.seconds
private val PEOPLE_REFRESH = 15.seconds

/** Cada lectura lanza un ping desde el router: sin prisa. */
private val CABLE_REFRESH = 30.seconds

/** Lo guardado por horas cambia cada hora. */
private val HISTORY_REFRESH = 1.minutes

/** Inicio: la salud de la red de un vistazo y lo que pide atención. */
@Composable
fun OverviewScreen(connection: Connection.SignedIn, onOpen: (OverviewLink) -> Unit) {
    val monitor = LocalRepository.current.monitor
    val interfaces by monitor.interfaces.watchAsState(NETWORK_REFRESH)
    val devices by monitor.devices.watchAsState(PEOPLE_REFRESH)
    val parental by monitor.parental.watchAsState(PEOPLE_REFRESH)
    val board by monitor.board.watchAsState(PEOPLE_REFRESH)
    val link by monitor.wanLink.watchAsState(CABLE_REFRESH)
    val wifi by monitor.wifi.watchAsState(PEOPLE_REFRESH)
    val recorder by monitor.usageRecorder.watchAsState(HISTORY_REFRESH)
    val context = LocalContext.current

    LoadableContent(interfaces) { networks, error ->
        OverviewContent(
            overview = overview(networks, devices.data, parental.data, connection.emptyPassword, link.data?.link),
            error = error,
            versionNotice = board.data?.let { openWrtSupportNotice(it.release.version)?.text() },
            wifi = wifi.data?.let { homeWifi(it, onWifiBehind(context, connection.address)) },
            guest = wifi.data?.let(::guestWifi),
            lastDay = (recorder.data?.recorder as? Recorder.Ready)?.history?.buckets(UsagePeriod.Day)?.takeIf { it.isNotEmpty() },
            onOpen = onOpen,
        )
    }
}

/**
 * Inicio con lo ya leído. [wifi] y [guest] null: el router no tiene Wi-Fi o aún no se ha leído.
 * [lastDay]: lo gastado en las últimas 24 horas, si el router lo guarda (vnStat).
 */
@Composable
fun OverviewContent(
    overview: Overview,
    error: String? = null,
    versionNotice: String? = null,
    wifi: HomeWifi? = null,
    guest: GuestWifi? = null,
    lastDay: List<UsageBucket>? = null,
    onOpen: (OverviewLink) -> Unit = {},
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
        if (versionNotice != null) item { NoticeCard(stringResource(R.string.status_untested_openwrt), versionNotice) }
        item { InternetCard(overview, lastDay) }
        overview.devices?.let { count -> item { DeviceStats(overview, count, onOpen) } }
        if (overview.attention.isNotEmpty()) item { AttentionCard(overview, onOpen) }
        if (lastDay != null) item { LastDayCard(lastDay) { onOpen(OverviewLink.Usage) } }
        overview.profiles?.let { count -> item { FamilyCard(count, overview.paused) { onOpen(OverviewLink.Family) } } }
        item { FeatureGrid(wifi, guest, onOpen) }
    }
}

/** Lo primero que se ve: si hay internet y desde cuándo, con la curva de las últimas horas. */
@Composable
private fun InternetCard(overview: Overview, lastDay: List<UsageBucket>?) {
    val palette = LocalPalette.current
    val detail = when {
        !overview.online -> overview.linkAdvice?.text() ?: stringResource(R.string.overview_offline_detail)
        else -> listOfNotNull(
            overview.onlineSeconds?.let { stringResource(R.string.overview_online_since, formatDuration(it)) },
            overview.cableMbps?.let { stringResource(R.string.overview_cable_speed, it) },
        ).joinToString(" · ").ifEmpty { null }
    }
    GroupCard(Modifier.testTag("overview-internet")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.overview_internet), style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (overview.online) palette.good else palette.bad))
                    Text(
                        stringResource(if (overview.online) R.string.overview_connected else R.string.overview_disconnected),
                        style = BigNumber,
                        color = palette.label,
                    )
                }
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.secondary) }
            }
            if (overview.online && lastDay != null) {
                Sparkline(lastDay.map { it.totalBytes.toFloat() }, Modifier.padding(start = 12.dp).width(96.dp).height(48.dp))
            }
        }
    }
}

/** Cuántos aparatos hay, cuántos están en línea y cuántos esperan aprobación. */
@Composable
private fun DeviceStats(overview: Overview, devices: Int, onOpen: (OverviewLink) -> Unit) {
    val palette = LocalPalette.current
    val stats = listOfNotNull(
        Stat(stringResource(R.string.overview_stat_devices), devices.toString()),
        overview.onlineDevices?.let { Stat(stringResource(R.string.overview_stat_online), it.toString(), palette.good) },
        overview.newDevices.takeIf { it > 0 }?.let { Stat(stringResource(R.string.overview_stat_new), it.toString(), palette.warning) },
    )
    StatStrip(stats, Modifier.clickable { onOpen(OverviewLink.Devices) }.testTag("overview-devices"))
}

/** Lo que pide atención, una fila por cosa, lo más urgente arriba. */
@Composable
private fun AttentionCard(overview: Overview, onOpen: (OverviewLink) -> Unit) {
    val palette = LocalPalette.current
    GroupCard {
        overview.attention.forEachIndexed { i, attention ->
            val (title, body) = when (attention) {
                Attention.Cable -> stringResource(R.string.overview_cable_title) to overview.linkAdvice?.text()
                Attention.NoPassword ->
                    stringResource(R.string.overview_no_password_title) to stringResource(R.string.overview_no_password_body)
                Attention.RulesInactive ->
                    stringResource(R.string.overview_rules_inactive_title) to stringResource(R.string.overview_rules_inactive_body)
                Attention.NewDevices ->
                    pluralStringResource(R.plurals.overview_new_devices_title, overview.newDevices, overview.newDevices) to
                        stringResource(R.string.overview_new_devices_body)
            }
            val link = when (attention) {
                Attention.Cable -> null
                Attention.NoPassword, Attention.NewDevices -> OverviewLink.Security
                Attention.RulesInactive -> OverviewLink.Family
            }
            val serious = attention == Attention.NoPassword || attention == Attention.RulesInactive
            GroupRow(
                title,
                supporting = body,
                supportingLines = if (attention == Attention.Cable) CABLE_LINES else 2,
                leading = { RowIcon(R.drawable.ic_shield, if (serious) palette.bad else palette.warning) },
                chevron = link != null,
                divider = i < overview.attention.lastIndex,
                onClick = link?.let { { onOpen(it) } },
                modifier = Modifier.testTag("overview-${attention.name}"),
            )
        }
    }
}

/** El consejo del cable se lee entero. */
private const val CABLE_LINES = 5

/** Lo gastado en las últimas 24 horas, con su gráfica; lleva a Uso de internet. */
@Composable
private fun LastDayCard(buckets: List<UsageBucket>, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val totals = buckets.totals()
    GroupCard(Modifier.clickable(onClick = onClick).testTag("overview-usage")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.overview_usage_title), style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatBytes(totals.totalBytes), style = BigNumber, color = palette.label, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.overview_usage_split, formatBytes(totals.receivedBytes), formatBytes(totals.sentBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary,
                )
            }
            UsageChart(buckets, UsagePeriod.Day, showHint = false)
        }
    }
}

/** Los perfiles y cuántos están en pausa; lleva a Familia. */
@Composable
private fun FamilyCard(profiles: Int, paused: Int, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val value = when {
        profiles == 0 -> stringResource(R.string.overview_profiles_none_label)
        paused == 0 -> pluralStringResource(R.plurals.overview_profiles, profiles, profiles)
        else -> pluralStringResource(R.plurals.overview_profiles, profiles, profiles) + " · " +
            pluralStringResource(R.plurals.overview_profiles_paused, paused, paused)
    }
    GroupCard {
        GroupRow(
            stringResource(R.string.home_tab_family),
            supporting = value,
            leading = { RowIcon(R.drawable.ic_family, palette.accent) },
            chevron = true,
            divider = false,
            onClick = onClick,
            modifier = Modifier.testTag("overview-profiles"),
        )
    }
}

/** Las funciones, cada una con su icono y, debajo, cómo está. */
@Composable
private fun FeatureGrid(wifi: HomeWifi?, guest: GuestWifi?, onOpen: (OverviewLink) -> Unit) {
    val palette = LocalPalette.current
    val wifiCaption = when (wifi) {
        is HomeWifi.On -> pluralStringResource(R.plurals.overview_tile_clients, wifi.clients, wifi.clients)
        HomeWifi.Paused -> stringResource(R.string.overview_tile_paused)
        HomeWifi.OtherAccessPoints -> stringResource(R.string.overview_tile_others)
        HomeWifi.Off -> stringResource(R.string.overview_tile_off)
        null -> null
    }
    val guestCaption = when {
        wifi == null -> null
        guest == null -> stringResource(R.string.overview_tile_none)
        !guest.on -> stringResource(R.string.overview_tile_off)
        else -> stringResource(R.string.overview_tile_on)
    }
    GroupCard {
        Row(Modifier.padding(4.dp)) {
            Tile(R.drawable.ic_wifi, R.string.overview_grid_wifi, palette.accent, wifiCaption, OverviewLink.Wifi, onOpen)
            Tile(R.drawable.ic_person, R.string.overview_grid_guest, palette.good, guestCaption, OverviewLink.Guest, onOpen)
            Tile(R.drawable.ic_bar_chart, R.string.overview_grid_usage, palette.accent, null, OverviewLink.Usage, onOpen)
            Tile(R.drawable.ic_extension, R.string.overview_grid_features, palette.warning, null, OverviewLink.Features, onOpen)
        }
    }
}

@Composable
private fun RowScope.Tile(
    icon: Int,
    label: Int,
    tint: Color,
    caption: String?,
    link: OverviewLink,
    onOpen: (OverviewLink) -> Unit,
) {
    val tag = "more-${link.name.lowercase()}"
    FeatureTile(icon, stringResource(label), tint, Modifier.weight(1f).testTag(tag), caption) { onOpen(link) }
}
