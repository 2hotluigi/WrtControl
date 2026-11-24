package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.RowIcon

/**
 * Reiniciar el router y restablecerlo de fábrica, cada uno con su confirmación. Mientras se hace
 * una, la fila dice por dónde va. [viaTunnel]: se está fuera de casa, y las confirmaciones lo avisan.
 */
@Composable
fun RouterPowerGroup(
    state: PowerState,
    access: PowerAccess,
    viaTunnel: Boolean,
    onReboot: () -> Unit,
    onReset: () -> Unit,
) {
    val palette = LocalPalette.current
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        GroupCard {
            RebootRow(state, access.reboot != false) { confirm = REBOOT }
            ResetRow(state, access.reset != false) { confirm = RESET }
        }
        val failed = state as? PowerState.Failed
        if (failed != null) {
            Text(
                failed.message.text(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.bad,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        } else {
            GroupFooter(stringResource(R.string.power_footer), Modifier.padding(top = 6.dp))
        }
    }
    when (confirm) {
        REBOOT -> RebootDialog(viaTunnel, onDismiss = { confirm = null }) {
            confirm = null
            onReboot()
        }
        RESET -> ResetDialog(viaTunnel, onDismiss = { confirm = null }) {
            confirm = null
            onReset()
        }
    }
}

/** Reiniciar; mientras, cómo va. [allowed]: false si el router no deja. */
@Composable
private fun RebootRow(state: PowerState, allowed: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val rebooting = state as? PowerState.Rebooting
    val (title, detail) = when {
        rebooting?.late == true -> R.string.power_late to R.string.power_late_detail
        rebooting != null -> R.string.power_rebooting to R.string.power_rebooting_detail
        !allowed -> R.string.power_reboot_title to R.string.power_unavailable
        state == PowerState.Back -> R.string.power_back to R.string.power_reboot_detail
        else -> R.string.power_reboot_title to R.string.power_reboot_detail
    }
    GroupRow(
        stringResource(title),
        supporting = stringResource(detail),
        supportingLines = 3,
        leading = { if (rebooting != null) Spinner() else RowIcon(R.drawable.ic_restart, palette.accent) },
        labelColor = if (!allowed || state == PowerState.Resetting) palette.tertiary else null,
        onClick = { if (allowed && !state.busy) onClick() },
        modifier = Modifier.testTag("power-reboot"),
    )
}

/** Restablecer de fábrica, en rojo; mientras, que se está haciendo. */
@Composable
private fun ResetRow(state: PowerState, allowed: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val resetting = state == PowerState.Resetting
    GroupRow(
        stringResource(if (resetting) R.string.power_resetting else R.string.power_reset_title),
        supporting = stringResource(if (allowed) R.string.power_reset_detail else R.string.power_unavailable),
        supportingLines = 3,
        leading = { if (resetting) Spinner() else RowIcon(R.drawable.ic_warning, palette.bad) },
        labelColor = if (!allowed || state.busy) palette.tertiary else palette.bad,
        divider = false,
        onClick = { if (allowed && !state.busy) onClick() },
        modifier = Modifier.testTag("power-reset"),
    )
}

private const val REBOOT = "reboot"
private const val RESET = "reset"

/** La rueda de "se está haciendo", donde iría el icono de la fila (quieta en las capturas). */
@Composable
private fun Spinner() {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        val modifier = Modifier.size(22.dp)
        if (LocalInspectionMode.current) {
            CircularProgressIndicator(progress = { SPINNER_STILL }, modifier = modifier, strokeWidth = 2.5.dp)
        } else {
            CircularProgressIndicator(modifier = modifier, strokeWidth = 2.5.dp)
        }
    }
}

private const val SPINNER_STILL = 0.3f

@Composable
private fun RebootDialog(viaTunnel: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_reboot_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.power_reboot_confirm_body))
                if (viaTunnel) Text(stringResource(R.string.power_reboot_confirm_tunnel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("power-reboot-confirm")) {
                Text(stringResource(R.string.power_reboot_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.power_cancel)) } },
    )
}

/** Borrar todo pide marcar antes que se entiende: un toque sin querer no basta. */
@Composable
private fun ResetDialog(viaTunnel: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val palette = LocalPalette.current
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_reset_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.power_reset_confirm_body))
                Text(stringResource(R.string.power_reset_confirm_after))
                if (viaTunnel) Text(stringResource(R.string.power_reset_confirm_tunnel), color = palette.bad)
                Row(
                    Modifier
                        .toggleable(understood, role = Role.Checkbox, onValueChange = { understood = it })
                        .testTag("power-reset-understood"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = understood,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = palette.bad),
                        modifier = Modifier.padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                    )
                    Text(stringResource(R.string.power_reset_understood))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = understood, modifier = Modifier.testTag("power-reset-confirm")) {
                Text(stringResource(R.string.power_reset_confirm), color = if (understood) palette.bad else palette.tertiary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.power_cancel)) } },
    )
}
