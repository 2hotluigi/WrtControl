package dev.angel.wrtcontrol.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.ui.addons.AddonsScreen

private enum class MorePage { Menu, Wifi, Guest, Features, Router, Pair }

/** Lo que no es del día a día: las funciones que se instalan, los detalles del router y salir. */
@Composable
fun MoreScreen(connection: Connection.SignedIn, onSignOut: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(MorePage.Menu) }
    BackHandler(enabled = page != MorePage.Menu) { page = MorePage.Menu }

    when (page) {
        MorePage.Menu -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Entry(R.string.more_wifi_title, R.string.more_wifi_detail, R.drawable.ic_wifi, "more-wifi") { page = MorePage.Wifi }
            Entry(R.string.more_guest_title, R.string.more_guest_detail, R.drawable.ic_wifi, "more-guest") { page = MorePage.Guest }
            Entry(R.string.more_features_title, R.string.more_features_detail, R.drawable.ic_extension, "more-features") {
                page = MorePage.Features
            }
            Entry(R.string.more_router_title, R.string.more_router_detail, R.drawable.ic_dashboard, "more-router") {
                page = MorePage.Router
            }
            Entry(R.string.more_pair_title, R.string.more_pair_detail, R.drawable.ic_kind_phone, "more-pair") {
                page = MorePage.Pair
            }
            Entry(R.string.home_sign_out, R.string.more_sign_out_detail, R.drawable.ic_logout, "more-sign-out", chevron = false) {
                onSignOut()
            }
        }
        MorePage.Wifi -> SubPage(R.string.more_wifi_title, onBack = { page = MorePage.Menu }) { WifiScreen(connection.address) }
        MorePage.Guest -> SubPage(R.string.more_guest_title, onBack = { page = MorePage.Menu }) { GuestScreen(connection.address) }
        MorePage.Features -> SubPage(R.string.more_features_title, onBack = { page = MorePage.Menu }) { AddonsScreen(connection.address) }
        MorePage.Router -> SubPage(R.string.more_router_title, onBack = { page = MorePage.Menu }) { StatusScreen() }
        MorePage.Pair -> SubPage(R.string.more_pair_title, onBack = { page = MorePage.Menu }) {
            PairScreen(connection.address, connection.username)
        }
    }
}

@Composable
private fun Entry(title: Int, detail: Int, icon: Int, tag: String, chevron: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (chevron) {
                Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubPage(title: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("more-back")) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.common_back))
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
