package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.canFactoryReset
import dev.angel.wrtcontrol.openwrt.canReboot
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.design.ActionRow
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.minutes

/** Lo que deja hacer el ACL casi nunca cambia. */
private val ACCESS_REFRESH = 5.minutes

/**
 * Ajustes (el engranaje de arriba): lo que se toca poco. Los detalles del router, añadir otro
 * teléfono, reiniciarlo o restablecerlo y desconectar. [viaTunnel]: se está fuera de casa.
 */
@Composable
fun SettingsScreen(address: String, viaTunnel: Boolean, onPage: (MorePage) -> Unit, onSignOut: () -> Unit) {
    val repo = LocalRepository.current
    val power: RouterPowerViewModel = viewModel(key = "power:$address") { RouterPowerViewModel(repo) }
    val capabilities by repo.monitor.capabilities.watchAsState(ACCESS_REFRESH)
    val access = capabilities.data?.let { PowerAccess(reboot = it.canReboot, reset = it.canFactoryReset) } ?: PowerAccess()
    SettingsContent(onPage, onSignOut) {
        RouterPowerGroup(power.state, access, viaTunnel, onReboot = power::reboot, onReset = power::factoryReset)
    }
}

/** Ajustes sin el router detrás (para las capturas): [power], el grupo de reiniciar y restablecer. */
@Composable
fun SettingsContent(onPage: (MorePage) -> Unit, onSignOut: () -> Unit, power: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        GroupCard {
            GroupRow(
                stringResource(R.string.more_router_title),
                supporting = stringResource(R.string.more_router_detail),
                leading = { RowIcon(R.drawable.ic_router, palette.accent) },
                chevron = true,
                onClick = { onPage(MorePage.Router) },
                modifier = Modifier.testTag("more-router"),
            )
            GroupRow(
                stringResource(R.string.more_pair_title),
                supporting = stringResource(R.string.more_pair_detail),
                leading = { RowIcon(R.drawable.ic_kind_phone, palette.accent) },
                chevron = true,
                divider = false,
                onClick = { onPage(MorePage.Pair) },
                modifier = Modifier.testTag("more-pair"),
            )
        }
        power()
        Column {
            ActionRow(stringResource(R.string.home_sign_out), destructive = true, onClick = onSignOut)
            GroupFooter(stringResource(R.string.more_sign_out_detail), Modifier.padding(top = 6.dp))
        }
    }
}
