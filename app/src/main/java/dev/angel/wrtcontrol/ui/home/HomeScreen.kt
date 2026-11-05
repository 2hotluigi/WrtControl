package dev.angel.wrtcontrol.ui.home

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
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.parental.ParentalScreen
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: Int) {
    Status("Estado", R.drawable.ic_dashboard),
    Devices("Dispositivos", R.drawable.ic_devices),
    Parental("Control", R.drawable.ic_shield),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(connection: Connection.SignedIn) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    // Clave por router: si se conecta a otro, no se mezclan datos del anterior.
    val statusVm: StatusViewModel = viewModel(key = "status:${connection.address}") { StatusViewModel(repo) }
    val status by statusVm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(Tab.Status.ordinal) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(status.data?.board?.hostname ?: "Router")
                        Text(
                            connection.address,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { repo.signOut() } }) {
                        Icon(painterResource(R.drawable.ic_logout), contentDescription = "Desconectar")
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
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.Status -> StatusScreen(status)
                Tab.Devices -> DevicesScreen(connection.address)
                Tab.Parental -> ParentalScreen(connection)
            }
        }
    }
}
