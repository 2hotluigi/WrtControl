package dev.angel.wrtcontrol.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.data.Link
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.parental.ParentalScreen
import dev.angel.wrtcontrol.ui.parental.ParentalSection
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** El nombre del router, arriba: casi nunca cambia. */
private val NAME_REFRESH = 1.minutes

/** Las pestañas: lo de cada día a la izquierda, lo que se toca poco al final. */
private enum class Tab(@StringRes val label: Int, val icon: Int) {
    Overview(R.string.home_tab_overview, R.drawable.ic_home),
    Devices(R.string.home_tab_devices, R.drawable.ic_devices),
    Family(R.string.home_tab_family, R.drawable.ic_family),
    Security(R.string.home_tab_security, R.drawable.ic_shield),
    More(R.string.home_tab_more, R.drawable.ic_more),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(connection: Connection.SignedIn) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val board by repo.monitor.board.watchAsState(NAME_REFRESH)
    var tab by rememberSaveable { mutableIntStateOf(Tab.Overview.ordinal) }
    val link = repo.transport?.link?.collectAsStateWithLifecycle()?.value ?: Link.Direct

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(board.data?.hostname ?: stringResource(R.string.home_router_fallback))
                        Text(
                            when (link) {
                                Link.Direct -> connection.address
                                Link.Connecting -> stringResource(R.string.home_link_connecting, connection.address)
                                Link.Tunnel -> stringResource(R.string.home_link_tunnel, connection.address)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (link == Link.Tunnel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("router-link"),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { TabLabel(t) },
                        modifier = Modifier.testTag("tab-${t.name}"),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            TabContent(Tab.entries[tab], connection, onTab = { tab = it.ordinal }, onSignOut = { scope.launch { repo.signOut() } })
        }
    }
}

/** Cinco pestañas: que "Dispositivos" no se parta en teléfonos estrechos. */
@Composable
private fun TabLabel(tab: Tab) {
    Text(stringResource(tab.label), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun TabContent(tab: Tab, connection: Connection.SignedIn, onTab: (Tab) -> Unit, onSignOut: () -> Unit) {
    when (tab) {
        Tab.Overview -> OverviewScreen(connection) { link ->
            onTab(
                when (link) {
                    OverviewLink.Devices -> Tab.Devices
                    OverviewLink.Family -> Tab.Family
                    OverviewLink.Security -> Tab.Security
                },
            )
        }
        Tab.Devices -> DevicesScreen(connection.address)
        Tab.Family -> ParentalScreen(connection, ParentalSection.Family)
        Tab.Security -> ParentalScreen(connection, ParentalSection.Security)
        Tab.More -> MoreScreen(connection, onSignOut)
    }
}
