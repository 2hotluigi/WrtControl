package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.rememberVendors

/** Interruptor con título y explicación, como los de ajustes. */
@Composable
internal fun SwitchRow(
    title: String,
    help: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Cuarentena de dispositivos nuevos, como en Firewalla: con el interruptor activado, lo que se
 * conecte por primera vez no sale a internet hasta aprobarlo aquí o asignarlo a un perfil.
 */
@Composable
internal fun QuarantineCard(
    data: ParentalData,
    busy: Boolean,
    phoneIps: Set<String>,
    onToggle: (Boolean) -> Unit,
    onApprove: (Device) -> Unit,
    onAssign: (Device) -> Unit,
    onShowApproved: () -> Unit,
) {
    val settings = data.snapshot.settings
    val vendors = rememberVendors()
    Card(Modifier.fillMaxWidth().testTag("quarantine-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                title = stringResource(R.string.protection_quarantine_title),
                help = stringResource(R.string.protection_quarantine_help),
                checked = settings.quarantine,
                enabled = !busy,
                onChange = onToggle,
                modifier = Modifier.testTag("quarantine-switch"),
            )
            if (settings.quarantine) {
                val pending = data.pending
                HorizontalDivider()
                if (pending.isEmpty()) {
                    Text(
                        stringResource(R.string.protection_no_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        pluralStringResource(R.plurals.protection_pending_count, pending.size, pending.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    pending.forEach { d ->
                        Column(Modifier.fillMaxWidth().testTag("pending-${d.mac}")) {
                            Text(deviceTitle(d, vendors), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(d.ipv4.firstOrNull(), d.mac).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (d.ipv4.any { it in phoneIps }) {
                                Text(
                                    stringResource(R.string.protection_this_phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { onApprove(d) },
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.protection_approve)) }
                                if (data.snapshot.profiles.isNotEmpty()) {
                                    TextButton(
                                        onClick = { onAssign(d) },
                                        enabled = !busy,
                                    ) { Text(stringResource(R.string.protection_assign_to_profile)) }
                                }
                            }
                        }
                    }
                }
                val extra = (settings.approved + settings.admins).distinct().count { mac -> data.snapshot.profiles.none { mac in it.macs } }
                TextButton(onClick = onShowApproved, enabled = !busy) {
                    Text(pluralStringResource(R.plurals.protection_approved_without_profile_count, extra, extra))
                }
            }
        }
    }
}

/** [known]: dispositivos que se aprobarán ahora (la primera vez), o null al volver a activarla. */
@Composable
internal fun QuarantineConfirmDialog(known: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protection_quarantine_confirm_title)) },
        text = {
            Text(
                if (known != null) {
                    pluralStringResource(R.plurals.protection_quarantine_confirm_first, known, known)
                } else {
                    stringResource(R.string.protection_quarantine_confirm_again)
                },
            )
        },
        confirmButton = { TextButton(
            onClick = onConfirm,
            modifier = Modifier.testTag("quarantine-confirm"),
        ) { Text(stringResource(R.string.protection_activate)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.protection_cancel)) } },
    )
}

@Composable
internal fun AssignDialog(device: Device, profiles: List<Profile>, onPick: (Profile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protection_assign_title, deviceTitle(device, rememberVendors()))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                profiles.forEach { p ->
                    TextButton(onClick = { onPick(p) }, modifier = Modifier.fillMaxWidth()) {
                        Text(p.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.protection_cancel)) } },
    )
}

/**
 * Dispositivos aprobados que no están en ningún perfil, con la opción de quitarles la aprobación
 * y de aprobar uno por su MAC (p. ej. con IP fija en otra red, que no sale en la lista).
 */
@Composable
internal fun ApprovedDialog(
    approved: List<String>,
    admins: Set<String>,
    phoneMacs: Set<String>,
    devices: List<Device>,
    busy: Boolean,
    onRemove: (String) -> Unit,
    onApproveMac: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manual by remember { mutableStateOf("") }
    val manualMac = normalizeMac(manual)
    val vendors = rememberVendors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protection_approved_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (approved.isEmpty()) Text(stringResource(R.string.protection_none))
                approved.forEach { mac ->
                    val d = devices.firstOrNull { it.mac == mac }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                d?.let { deviceTitle(it, vendors) } ?: stringResource(R.string.protection_disconnected),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            when {
                                mac in phoneMacs -> Text(
                                    stringResource(R.string.protection_this_phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                mac in admins -> Text(
                                    stringResource(R.string.protection_admin_phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        // Este teléfono queda aprobado en cualquier cambio que haga: quitarlo no tendría efecto.
                        if (mac !in phoneMacs) TextButton(
                            onClick = { onRemove(mac) },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.protection_remove)) }
                    }
                }
                Text(
                    stringResource(R.string.protection_unapprove_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.take(20) },
                        label = { Text(stringResource(R.string.protection_approve_by_mac)) },
                        placeholder = { Text(MAC_PLACEHOLDER) },
                        singleLine = true,
                        enabled = !busy,
                        isError = manual.isNotBlank() && manualMac == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { manualMac?.let(onApproveMac); manual = "" },
                        enabled = !busy && manualMac != null,
                    ) { Text(stringResource(R.string.protection_approve)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.protection_close)) } },
    )
}

/**
 * Teléfonos desde los que se ha usado la app (administradores): nunca se les bloquea la
 * configuración del router y, con la cuarentena, siempre tienen internet. Se pueden quitar (un
 * teléfono perdido, la tableta de un niño en la que se usó una vez) o añadir por MAC (el de otro
 * padre que está en un perfil protegido y por eso no puede abrir la app).
 */
@Composable
internal fun AdminsDialog(
    admins: List<String>,
    phoneMacs: Set<String>,
    devices: List<Device>,
    busy: Boolean,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manual by remember { mutableStateOf("") }
    val manualMac = normalizeMac(manual)
    val vendors = rememberVendors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protection_admins_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.protection_admins_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (admins.isEmpty()) Text(stringResource(R.string.protection_none_yet))
                admins.forEach { mac ->
                    val d = devices.firstOrNull { it.mac == mac }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                d?.let { deviceTitle(it, vendors) } ?: stringResource(R.string.protection_disconnected),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            if (mac in phoneMacs) Text(
                                stringResource(R.string.protection_this_phone),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        // Este teléfono vuelve a añadirse en cualquier cambio que haga: quitarlo no tendría efecto.
                        if (mac !in phoneMacs) TextButton(
                            onClick = { onRemove(mac) },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.protection_remove)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.take(20) },
                        label = { Text(stringResource(R.string.protection_add_by_mac)) },
                        placeholder = { Text(MAC_PLACEHOLDER) },
                        singleLine = true,
                        enabled = !busy,
                        isError = manual.isNotBlank() && manualMac == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { manualMac?.let(onAdd); manual = "" },
                        enabled = !busy && manualMac != null,
                    ) { Text(stringResource(R.string.protection_add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.protection_close)) } },
    )
}

/** Protección del propio control: bloqueo de la app con huella o PIN. */
@Composable
internal fun AppLockCard(enabled: Boolean, available: Boolean, admins: Int, onToggle: (Boolean) -> Unit, onShowAdmins: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SwitchRow(
                title = stringResource(R.string.protection_app_lock_title),
                help = if (available) {
                    stringResource(R.string.protection_app_lock_help)
                } else {
                    stringResource(R.string.protection_app_lock_unavailable)
                },
                checked = enabled,
                enabled = available || enabled,
                onChange = onToggle,
                modifier = Modifier.testTag("app-lock-switch"),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onShowAdmins, modifier = Modifier.testTag("show-admins")) {
                Text(pluralStringResource(R.plurals.protection_admins_count, admins, admins))
            }
        }
    }
}

/** Contraseña nueva para el router (dos veces). */
@Composable
internal fun PasswordDialog(busy: Boolean, error: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second
    val tooShort = first.isNotEmpty() && first.length < MIN_PASSWORD
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.protection_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.protection_password_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(R.string.protection_password_new)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = tooShort,
                    supportingText = if (tooShort) { { Text(stringResource(
                        R.string.protection_password_too_short,
                        MIN_PASSWORD,
                    )) } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.testTag("password-new"),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text(stringResource(R.string.protection_password_repeat)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = mismatch,
                    supportingText = if (mismatch) { { Text(stringResource(R.string.protection_password_mismatch)) } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.testTag("password-repeat"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(first) },
                enabled = !busy && first.length >= MIN_PASSWORD && first == second,
                modifier = Modifier.testTag("password-save"),
            ) { Text(stringResource(R.string.protection_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.protection_cancel)) } },
    )
}

private const val MIN_PASSWORD = 8

/** Ejemplo de MAC en los campos para escribirla: valor técnico, no se traduce. */
internal const val MAC_PLACEHOLDER = "AA:BB:CC:DD:EE:FF"

@Composable
internal fun ExtraTimeDialog(name: String, options: List<ExtraOption>, onPick: (ExtraOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protection_extra_time_title, name)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.protection_extra_time_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.forEachIndexed { i, option ->
                    TextButton(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth().testTag("extra-$i")) {
                        Text(option.label.text(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.protection_cancel)) } },
    )
}
