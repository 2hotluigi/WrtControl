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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.normalizeMac

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
    Card(Modifier.fillMaxWidth().testTag("quarantine-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                title = "Dispositivos nuevos sin internet",
                help = "Lo que se conecte por primera vez a tu red no tendrá internet hasta que lo apruebes aquí. " +
                    "Así un teléfono nuevo o con otra MAC no se salta los perfiles.",
                checked = settings.quarantine,
                enabled = !busy,
                onChange = onToggle,
                modifier = Modifier.testTag("quarantine-switch"),
            )
            if (settings.quarantine) {
                val pending = data.pending
                HorizontalDivider()
                if (pending.isEmpty()) {
                    Text("No hay dispositivos esperando.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        if (pending.size == 1) "1 dispositivo esperando" else "${pending.size} dispositivos esperando",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    pending.forEach { d ->
                        Column(Modifier.fillMaxWidth().testTag("pending-${d.mac}")) {
                            Text(d.name ?: "Sin nombre", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(d.ipv4.firstOrNull(), d.mac).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (d.ipv4.any { it in phoneIps }) {
                                Text("Este teléfono", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { onApprove(d) }, enabled = !busy) { Text("Aprobar") }
                                if (data.snapshot.profiles.isNotEmpty()) {
                                    TextButton(onClick = { onAssign(d) }, enabled = !busy) { Text("Asignar a un perfil") }
                                }
                            }
                        }
                    }
                }
                val extra = (settings.approved + settings.admins).distinct().count { mac -> data.snapshot.profiles.none { mac in it.macs } }
                TextButton(onClick = onShowApproved, enabled = !busy) {
                    Text(if (extra == 1) "1 dispositivo aprobado sin perfil" else "$extra dispositivos aprobados sin perfil")
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
        title = { Text("¿Dejar sin internet a los dispositivos nuevos?") },
        text = {
            Text(
                if (known != null) {
                    "Se aprobarán los $known dispositivos que el router conoce ahora, así la TV, la impresora y los demás " +
                        "siguen igual. Los que se conecten después podrán unirse a la red, pero no tendrán internet hasta que los " +
                        "apruebes en esta pantalla. Si un dispositivo de casa estaba apagado, apruébalo cuando aparezca."
                } else {
                    "Los dispositivos que ya aprobaste siguen aprobados. Los demás, también los que se conectaron mientras " +
                        "estaba desactivada, no tendrán internet hasta que los apruebes en esta pantalla."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("quarantine-confirm")) { Text("Activar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun AssignDialog(device: Device, profiles: List<Profile>, onPick: (Profile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿A qué perfil pertenece «${device.name ?: device.mac}»?") },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aprobados sin perfil") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (approved.isEmpty()) Text("Ninguno.")
                approved.forEach { mac ->
                    val d = devices.firstOrNull { it.mac == mac }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(d?.name ?: if (d == null) "Desconectado" else "Sin nombre", style = MaterialTheme.typography.bodyMedium)
                            Text(mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            when {
                                mac in phoneMacs -> Text("Este teléfono", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                mac in admins -> Text("Teléfono con la app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        // Este teléfono queda aprobado en cualquier cambio que haga: quitarlo no tendría efecto.
                        if (mac !in phoneMacs) TextButton(onClick = { onRemove(mac) }, enabled = !busy) { Text("Quitar") }
                    }
                }
                Text(
                    "Al quitar la aprobación, ese dispositivo se queda sin internet hasta que lo vuelvas a aprobar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.take(20) },
                        label = { Text("Aprobar por MAC") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
                        enabled = !busy,
                        isError = manual.isNotBlank() && manualMac == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { manualMac?.let(onApproveMac); manual = "" }, enabled = !busy && manualMac != null) { Text("Aprobar") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Teléfonos con WrtControl") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Nunca se les bloquea la configuración del router, aunque estén en un perfil protegido, y con la " +
                        "cuarentena siempre tienen internet. Se añaden solos al hacer cualquier cambio desde la app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (admins.isEmpty()) Text("Ninguno todavía.")
                admins.forEach { mac ->
                    val d = devices.firstOrNull { it.mac == mac }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(d?.name ?: if (d == null) "Desconectado" else "Sin nombre", style = MaterialTheme.typography.bodyMedium)
                            Text(mac, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            if (mac in phoneMacs) Text("Este teléfono", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        // Este teléfono vuelve a añadirse en cualquier cambio que haga: quitarlo no tendría efecto.
                        if (mac !in phoneMacs) TextButton(onClick = { onRemove(mac) }, enabled = !busy) { Text("Quitar") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.take(20) },
                        label = { Text("Agregar por MAC") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
                        enabled = !busy,
                        isError = manual.isNotBlank() && manualMac == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { manualMac?.let(onAdd); manual = "" }, enabled = !busy && manualMac != null) { Text("Agregar") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Protección del propio control: bloqueo de la app con huella o PIN. */
@Composable
internal fun AppLockCard(enabled: Boolean, available: Boolean, admins: Int, onToggle: (Boolean) -> Unit, onShowAdmins: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SwitchRow(
                title = "Pedir huella o PIN para abrir WrtControl",
                help = if (available) {
                    "Así nadie más puede quitar las reglas desde este teléfono."
                } else {
                    "Configura un bloqueo de pantalla en el teléfono para poder usarlo."
                },
                checked = enabled,
                enabled = available || enabled,
                onChange = onToggle,
                modifier = Modifier.testTag("app-lock-switch"),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onShowAdmins, modifier = Modifier.testTag("show-admins")) {
                Text(if (admins == 1) "1 teléfono con WrtControl" else "$admins teléfonos con WrtControl")
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
        title = { Text("Contraseña del router") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Es la del usuario con el que entra esta app, y también la de LuCI y SSH. Guárdala bien: " +
                        "la necesitarás para entrar desde otro teléfono o el ordenador.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("Nueva contraseña") },
                    singleLine = true,
                    enabled = !busy,
                    isError = tooShort,
                    supportingText = if (tooShort) { { Text("Al menos $MIN_PASSWORD caracteres") } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.testTag("password-new"),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text("Repítela") },
                    singleLine = true,
                    enabled = !busy,
                    isError = mismatch,
                    supportingText = if (mismatch) { { Text("No coinciden") } } else null,
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
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } },
    )
}

private const val MIN_PASSWORD = 8

@Composable
internal fun ExtraTimeDialog(name: String, options: List<ExtraOption>, onPick: (ExtraOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Más tiempo para $name") },
        text = {
            Column {
                Text(
                    "Tendrá internet aunque esté en horario. Al acabar el tiempo, el router vuelve a aplicar el horario solo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.forEachIndexed { i, option ->
                    TextButton(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth().testTag("extra-$i")) {
                        Text(option.label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
