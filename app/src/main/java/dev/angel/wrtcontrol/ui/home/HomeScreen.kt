package dev.angel.wrtcontrol.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.data.Link
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.addons.AddonsScreen
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.parental.ParentalScreen
import dev.angel.wrtcontrol.ui.parental.ParentalSection
import dev.angel.wrtcontrol.ui.rememberVendors
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** El nombre del router, arriba: casi nunca cambia. */
private val NAME_REFRESH = 1.minutes

/**
 * La app con sesión: tres pestañas (Inicio, Dispositivos, Familia), Seguridad en el botón redondo y,
 * encima, las páginas interiores ([MorePage]): las de Inicio (Wi-Fi, invitados, consumo,
 * funciones) y las de Ajustes (el engranaje).
 */
@Composable
fun HomeScreen(connection: Connection.SignedIn) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val board by repo.monitor.board.watchAsState(NAME_REFRESH)
    var section by rememberSaveable { mutableStateOf(Section.Overview) }
    var page by rememberSaveable { mutableStateOf<MorePage?>(null) }
    // La ficha abierta en Dispositivos (su MAC), encima de la lista.
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    val link = repo.transport?.link?.collectAsStateWithLifecycle()?.value ?: Link.Direct

    // Atrás: de una página de Ajustes, a Ajustes; de las demás, y de una ficha, a la pestaña.
    BackHandler(enabled = page != null) { page = page?.parent }
    BackHandler(enabled = page == null && device != null) { device = null }
    val open = device?.takeIf { page == null && section == Section.Devices }

    AppFrame(
        title = board.data?.hostname ?: stringResource(R.string.home_router_fallback),
        subtitle = linkSubtitle(link, connection.address),
        subtitleAccent = link == Link.Tunnel,
        section = section,
        onSection = {
            section = it
            device = null
        },
        pageTitle = page?.let { stringResource(it.title) } ?: open?.let { deviceName(it) },
        onBack = { if (page != null) page = page?.parent else device = null },
        onSettings = { page = MorePage.Settings },
    ) {
        val current = page
        when {
            current != null -> PageContent(current, connection, onPage = { page = it }, onSignOut = { scope.launch { repo.signOut() } })
            open != null -> DeviceScreen(connection.address, open)
            else -> SectionContent(
                section,
                connection,
                onSection = {
                    section = it
                    device = null
                },
                onPage = { page = it },
                onDevice = { device = it },
            )
        }
    }
}

/** La dirección del router y, si va por el acceso remoto, que va por ahí. */
@Composable
private fun linkSubtitle(link: Link, address: String): String = when (link) {
    Link.Direct -> address
    Link.Connecting -> stringResource(R.string.home_link_connecting, address)
    Link.Tunnel -> stringResource(R.string.home_link_tunnel, address)
}

/** El nombre del aparato [mac] para la barra de arriba, o "Dispositivos" mientras no se sabe. */
@Composable
private fun deviceName(mac: String): String {
    val devices by LocalRepository.current.monitor.devices.watchAsState(DEVICES_REFRESH)
    val vendors = rememberVendors()
    return devices.data?.firstOrNull { it.mac == mac }?.let { deviceTitle(it, vendors) } ?: stringResource(R.string.home_tab_devices)
}

@Composable
private fun SectionContent(
    section: Section,
    connection: Connection.SignedIn,
    onSection: (Section) -> Unit,
    onPage: (MorePage) -> Unit,
    onDevice: (String) -> Unit,
) {
    when (section) {
        Section.Overview -> OverviewScreen(connection) { link ->
            when (link) {
                OverviewLink.Devices -> onSection(Section.Devices)
                OverviewLink.Family -> onSection(Section.Family)
                OverviewLink.Security -> onSection(Section.Security)
                OverviewLink.Wifi -> onPage(MorePage.Wifi)
                OverviewLink.Guest -> onPage(MorePage.Guest)
                OverviewLink.Usage -> onPage(MorePage.Usage)
                OverviewLink.Features -> onPage(MorePage.Features)
            }
        }
        Section.Devices -> DevicesScreen(onOpen = onDevice)
        Section.Family -> ParentalScreen(connection, ParentalSection.Family)
        Section.Security -> ParentalScreen(connection, ParentalSection.Security)
    }
}

@Composable
private fun PageContent(page: MorePage, connection: Connection.SignedIn, onPage: (MorePage) -> Unit, onSignOut: () -> Unit) {
    when (page) {
        MorePage.Settings -> SettingsScreen(onPage, onSignOut)
        MorePage.Wifi -> WifiScreen(connection.address)
        MorePage.Guest -> GuestScreen(connection.address)
        MorePage.Usage -> UsageScreen(connection.address)
        MorePage.Features -> AddonsScreen(connection.address)
        MorePage.Router -> StatusScreen()
        MorePage.Pair -> PairScreen(connection.address, connection.username)
    }
}
