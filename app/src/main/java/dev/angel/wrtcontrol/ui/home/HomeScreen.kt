package dev.angel.wrtcontrol.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.angel.wrtcontrol.ui.addons.AddonsScreen
import dev.angel.wrtcontrol.ui.parental.ParentalScreen
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** El nombre del router, arriba: casi nunca cambia. */
private val NAME_REFRESH = 1.minutes

private enum class Tab(@StringRes val label: Int, val icon: Int) {
    Status(R.string.home_tab_status, R.drawable.ic_dashboard),
    Devices(R.string.home_tab_devices, R.drawable.ic_devices),
    Parental(R.string.home_tab_parental, R.drawable.ic_shield),
    Addons(R.string.home_tab_addons, R.drawable.ic_extension),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(connection: Connection.SignedIn) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val board by repo.monitor.board.watchAsState(NAME_REFRESH)
    var tab by rememberSaveable { mutableIntStateOf(Tab.Status.ordinal) }
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
                actions = {
                    IconButton(onClick = { scope.launch { repo.signOut() } }) {
                        Icon(painterResource(R.drawable.ic_logout), contentDescription = stringResource(R.string.home_sign_out))
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
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.Status -> StatusScreen()
                Tab.Devices -> DevicesScreen()
                Tab.Parental -> ParentalScreen(connection)
                Tab.Addons -> AddonsScreen(connection.address)
            }
        }
    }
}
