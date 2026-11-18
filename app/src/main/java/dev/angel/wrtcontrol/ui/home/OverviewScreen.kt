package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.openwrt.GuestWifi
import dev.angel.wrtcontrol.openwrt.guestWifi
import dev.angel.wrtcontrol.openwrt.openWrtSupportNotice
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.LocalStatusColors
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.parental.onWifiBehind
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.seconds

private val NETWORK_REFRESH = 5.seconds
private val PEOPLE_REFRESH = 15.seconds

/** Cada lectura lanza un ping desde el router: sin prisa. */
private val CABLE_REFRESH = 30.seconds

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
    val context = LocalContext.current

    LoadableContent(interfaces) { networks, error ->
        OverviewContent(
            overview = overview(networks, devices.data, parental.data, connection.emptyPassword, link.data?.link),
            error = error,
            versionNotice = board.data?.let { openWrtSupportNotice(it.release.version)?.text() },
            wifi = wifi.data?.let { homeWifi(it, onWifiBehind(context, connection.address)) },
            guest = wifi.data?.let(::guestWifi),
            onOpen = onOpen,
        )
    }
}

@Composable
fun OverviewContent(
    overview: Overview,
    error: String? = null,
    versionNotice: String? = null,
    wifi: HomeWifi? = null,
    guest: GuestWifi? = null,
    onOpen: (OverviewLink) -> Unit = {},
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
        item { InternetCard(overview) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                overview.devices?.let { count ->
                    Tile(
                        pluralStringResource(R.plurals.overview_devices, count, count),
                        stringResource(R.string.overview_devices_label),
                        Modifier.weight(1f).testTag("overview-devices"),
                    ) { onOpen(OverviewLink.Devices) }
                }
                overview.profiles?.let { count ->
                    val title = if (count == 0) {
                        stringResource(R.string.overview_profiles_none)
                    } else {
                        pluralStringResource(R.plurals.overview_profiles, count, count)
                    }
                    val label = when {
                        count == 0 -> stringResource(R.string.overview_profiles_none_label)
                        overview.paused == 0 -> stringResource(R.string.overview_profiles_active)
                        else -> pluralStringResource(R.plurals.overview_profiles_paused, overview.paused, overview.paused)
                    }
                    Tile(title, label, Modifier.weight(1f).testTag("overview-profiles")) { onOpen(OverviewLink.Family) }
                }
            }
        }
        if (wifi != null) item { WifiTiles(wifi, guest, onOpen) }
        overview.attention.forEach { attention -> item(key = attention) { AttentionCard(attention, overview, onOpen) } }
        if (versionNotice != null) item { NoticeCard(stringResource(R.string.status_untested_openwrt), versionNotice) }
        if ((overview.profiles ?: 0) > 0) item {
            Button(onClick = { onOpen(OverviewLink.Family) }, modifier = Modifier.testTag("overview-pause")) {
                Text(stringResource(R.string.overview_pause_action))
            }
        }
    }
}

/** Lo primero que se ve: si hay internet y desde cuándo. */
@Composable
private fun InternetCard(overview: Overview) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (overview.online) colors.primary else colors.errorContainer,
        contentColor = if (overview.online) colors.onPrimary else colors.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("overview-internet"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).background(if (overview.online) LocalStatusColors.current.signal else colors.error, CircleShape))
                Text(
                    stringResource(if (overview.online) R.string.overview_online else R.string.overview_offline),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            val detail = when {
                !overview.online -> overview.linkAdvice?.text() ?: stringResource(R.string.overview_offline_detail)
                else -> listOfNotNull(
                    overview.onlineSeconds?.let { stringResource(R.string.overview_online_since, formatDuration(it)) },
                    overview.cableMbps?.let { stringResource(R.string.overview_cable_speed, it) },
                ).joinToString(" · ").ifEmpty { null }
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** El Wi-Fi de casa y el de invitados, cada uno con su acceso directo. */
@Composable
private fun WifiTiles(wifi: HomeWifi, guest: GuestWifi?, onOpen: (OverviewLink) -> Unit) {
    val (title, label) = when (wifi) {
        is HomeWifi.On -> wifi.ssid to pluralStringResource(R.plurals.overview_wifi_clients, wifi.clients, wifi.clients)
        HomeWifi.Paused -> stringResource(R.string.overview_wifi_paused) to stringResource(R.string.overview_wifi_paused_label)
        HomeWifi.OtherAccessPoints -> stringResource(R.string.overview_wifi_others) to stringResource(R.string.overview_wifi_others_label)
        HomeWifi.Off -> stringResource(R.string.overview_wifi_off) to stringResource(R.string.overview_wifi_off_label)
    }
    val guestLabel = when {
        guest == null -> stringResource(R.string.overview_guest_none)
        !guest.on -> stringResource(R.string.overview_guest_off)
        else -> pluralStringResource(R.plurals.overview_guest_on, guest.clients.size, guest.clients.size)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile(title, label, Modifier.weight(1f).testTag("overview-wifi")) { onOpen(OverviewLink.Wifi) }
        Tile(stringResource(R.string.overview_guest_title), guestLabel, Modifier.weight(1f).testTag("overview-guest")) {
            onOpen(OverviewLink.Guest)
        }
    }
}

@Composable
private fun Tile(title: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttentionCard(attention: Attention, overview: Overview, onOpen: (OverviewLink) -> Unit) {
    if (attention == Attention.Cable) {
        NoticeCard(stringResource(R.string.overview_cable_title), overview.linkAdvice?.text().orEmpty(), Modifier.testTag("overview-Cable"))
        return
    }
    val (title, body) = when (attention) {
        Attention.Cable -> "" to ""
        Attention.NoPassword -> stringResource(R.string.overview_no_password_title) to stringResource(R.string.overview_no_password_body)
        Attention.RulesInactive ->
            stringResource(R.string.overview_rules_inactive_title) to stringResource(R.string.overview_rules_inactive_body)
        Attention.NewDevices ->
            pluralStringResource(R.plurals.overview_new_devices_title, overview.newDevices, overview.newDevices) to
                stringResource(R.string.overview_new_devices_body)
    }
    val (action, link) = when (attention) {
        Attention.Cable -> 0 to OverviewLink.Devices
        Attention.NoPassword -> R.string.overview_no_password_action to OverviewLink.Security
        Attention.RulesInactive -> R.string.overview_rules_inactive_action to OverviewLink.Family
        Attention.NewDevices -> R.string.overview_new_devices_action to OverviewLink.Security
    }
    NoticeCard(title, body, Modifier.testTag("overview-${attention.name}")) {
        FilledTonalButton(onClick = { onOpen(link) }) { Text(stringResource(action)) }
    }
}
