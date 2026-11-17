package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.DeviceIdentity
import dev.angel.wrtcontrol.openwrt.DeviceKind
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.MAX_LABEL_LENGTH
import dev.angel.wrtcontrol.openwrt.Presence
import dev.angel.wrtcontrol.openwrt.VendorTable
import dev.angel.wrtcontrol.openwrt.identify
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.withoutExpired
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.LocalStatusColors
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.parental.formatUntil
import dev.angel.wrtcontrol.ui.parental.phoneAddresses
import dev.angel.wrtcontrol.ui.rememberVendors
import dev.angel.wrtcontrol.ui.watchAsState
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds

/** La lista de equipos cambia despacio. */
private val DEVICES_REFRESH = 10.seconds

/** De quién es cada aparato solo cambia desde la app. */
private val PROFILES_REFRESH = 30.seconds

@Composable
fun DevicesScreen(address: String) {
    val repo = LocalRepository.current
    val vm: DevicesViewModel = viewModel(key = "devices:$address") { DevicesViewModel(repo, repo.monitor) }
    val state by repo.monitor.devices.watchAsState(DEVICES_REFRESH)
    // Sin las personas, la lista se ve igual: solo falta decir de quién es cada aparato.
    val parental by repo.monitor.parental.watchAsState(PROFILES_REFRESH)
    val profiles = parental.data?.profiles.orEmpty()
    val canAssign = parental.data?.newerSchema == false
    val pauses = parental.data?.let { DevicePauses(it.settings.withoutExpired(it.routerNow).devicePauses, it.routerNow) }
    val vendors = rememberVendors()
    val locked = LocalAppLocked.current
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    LoadableContent(state) { devices, error ->
        DevicesContent(devices, vendors, error, profiles, pauses, onOpen = { editing = it.mac })
        val device = devices.firstOrNull { it.mac == editing }?.takeIf { !locked }
        if (device != null) {
            // Sin remember: si el teléfono cambia de IP, se nota al abrir el diálogo.
            val phoneMacs = phoneAddresses(context).let { ips -> devices.filter { d -> d.ipv4.any { it in ips } }.map { it.mac }.toSet() }
            DeviceDialog(
                device = device,
                identity = identify(device, vendors),
                profiles = profiles,
                canAssign = canAssign,
                busy = vm.busy?.text(),
                error = vm.error?.text(),
                onSave = { change -> vm.save(device, change, phoneMacs) { editing = null } },
                // Con el control parental a la vista y sin ser este teléfono: a sí mismo no se corta.
                internet = pauses?.takeIf { canAssign && device.mac !in phoneMacs }?.let { p ->
                    InternetControl(
                        status = p.pauses[device.mac]?.let { pauseText(it, p.now) },
                        onPause = { minutes -> vm.pause(device, minutes, phoneMacs) { editing = null } },
                        onResume = { vm.resume(device, phoneMacs) { editing = null } },
                    )
                },
                thisPhone = device.mac in phoneMacs,
                onDismiss = {
                    vm.error = null
                    editing = null
                },
            )
        }
    }
}

/** Los aparatos sueltos sin internet y la hora del router, para decir hasta cuándo. */
data class DevicePauses(val pauses: Map<String, Pause>, val now: LocalDateTime)

/** Lo que se puede hacer con el internet de un aparato. [status]: cómo está si está cortado, o null. */
class InternetControl(val status: String?, val onPause: (minutes: Long?) -> Unit, val onResume: () -> Unit)

@Composable
private fun pauseText(pause: Pause, now: LocalDateTime): String = when (pause) {
    Pause.Indefinite -> stringResource(R.string.devices_blocked)
    is Pause.Until -> stringResource(R.string.devices_paused_until, formatUntil(pause.end, now).text())
}

private val PAUSE_MINUTES = listOf(
    30L to R.string.parental_pause_30_min,
    60L to R.string.parental_pause_1_hour,
    120L to R.string.parental_pause_2_hours,
)

@Composable
fun DevicesContent(
    devices: List<Device>,
    vendors: VendorTable?,
    error: String? = null,
    profiles: List<Profile> = emptyList(),
    pauses: DevicePauses? = null,
    onOpen: ((Device) -> Unit)? = null,
) {
    val owners = remember(profiles) { profiles.flatMap { p -> p.macs.map { it to p.name } }.toMap() }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
        item {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    pluralStringResource(R.plurals.devices_count, devices.size, devices.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (onOpen != null && devices.isNotEmpty()) {
                    Text(
                        stringResource(R.string.devices_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (devices.isEmpty()) {
            item { Text(stringResource(R.string.devices_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(devices, key = { it.mac }) {
            val paused = pauses?.pauses?.get(it.mac)?.let { pause -> pauseText(pause, pauses.now) }
            DeviceRow(it, identify(it, vendors), owners[it.mac], paused, onOpen)
        }
    }
}

@Composable
private fun DeviceRow(device: Device, identity: DeviceIdentity, owner: String?, paused: String?, onOpen: ((Device) -> Unit)?) {
    var showHint by rememberSaveable(device.mac) { mutableStateOf(false) }
    val open = if (onOpen != null) Modifier.clickable { onOpen(device) } else Modifier
    Card(Modifier.fillMaxWidth().testTag("device-${device.mac}").then(open)) {
        ListItem(
            leadingContent = {
                Icon(painterResource(identity.kind.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            headlineContent = { Text(identity.title.text()) },
            supportingContent = {
                val address = device.ipv4.firstOrNull() ?: device.ipv6.firstOrNull { !it.startsWith("fe80:") } ?: "—"
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PresenceText(device.presence)
                    if (paused != null) Text(paused, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    if (owner != null) Text(stringResource(R.string.devices_owner, owner), style = MaterialTheme.typography.bodySmall)
                    // Con nombre propio, el fabricante va debajo; sin él, ya está en el título.
                    if (identity.named && identity.vendor != null) Text(identity.vendor, style = MaterialTheme.typography.bodySmall)
                    if (identity.privateMac) {
                        Text(
                            stringResource(R.string.devices_private_mac),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.clickable { showHint = !showHint },
                        )
                    }
                    if (showHint) Text(stringResource(R.string.devices_private_mac_hint), style = MaterialTheme.typography.bodySmall)
                    Text(address, style = MaterialTheme.typography.bodySmall)
                    Text(device.mac, style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = {
                Text(leaseText(device), style = MaterialTheme.typography.labelSmall)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Si el router no deja ver quién está (o no se sabe), no se dice nada. */
@Composable
private fun PresenceText(presence: Presence) {
    val status = LocalStatusColors.current
    val (text, color) = when (presence) {
        Presence.Online -> R.string.devices_presence_online to status.ok
        Presence.Idle -> R.string.devices_presence_idle to MaterialTheme.colorScheme.onSurfaceVariant
        Presence.Offline -> R.string.devices_presence_offline to MaterialTheme.colorScheme.onSurfaceVariant
        Presence.Unknown -> return
    }
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = color)
}

/**
 * Lo que se puede dejar puesto en un aparato: su nombre, que el router le dé siempre la misma
 * dirección y de qué persona es. Sin [profiles] se dice dónde crearlas; con [canAssign] false (el
 * router lo configuró una versión más nueva de la app), esa parte no sale.
 */
@Composable
fun DeviceDialog(
    device: Device,
    identity: DeviceIdentity,
    profiles: List<Profile>,
    canAssign: Boolean,
    busy: String?,
    error: String?,
    onSave: (DeviceChange) -> Unit,
    internet: InternetControl? = null,
    thisPhone: Boolean = false,
    onDismiss: () -> Unit,
) {
    val currentOwner = profiles.firstOrNull { device.mac in it.macs }?.id.orEmpty()
    // La dirección que se reserva es la que tiene ahora: no hay que saber qué es una IP para fijarla.
    val address = device.fixedIp ?: device.ipv4.firstOrNull()
    var label by rememberSaveable(device.mac) { mutableStateOf(device.label.orEmpty()) }
    var fixed by rememberSaveable(device.mac) { mutableStateOf(device.fixedIp != null) }
    var owner by rememberSaveable(device.mac) { mutableStateOf(currentOwner) }
    val saving = busy != null

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(identity.title.text()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) ErrorBanner(error)
                if (busy != null) {
                    Text(busy, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(MAX_LABEL_LENGTH) },
                    label = { Text(stringResource(R.string.devices_edit_name_label)) },
                    supportingText = { Text(stringResource(R.string.devices_edit_name_help)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("device-name"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.devices_edit_fixed_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (address != null) stringResource(R.string.devices_edit_fixed_help, address)
                            else stringResource(R.string.devices_edit_fixed_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = fixed,
                        onCheckedChange = { fixed = it },
                        enabled = !saving && address != null,
                        modifier = Modifier.testTag("device-fixed"),
                    )
                }
                if (canAssign && profiles.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.devices_edit_person_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.devices_edit_person_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OwnerOption(stringResource(R.string.devices_edit_person_none), owner.isEmpty(), !saving) { owner = "" }
                    profiles.forEach { p -> OwnerOption(p.name, owner == p.id, !saving) { owner = p.id } }
                } else if (canAssign) {
                    Text(
                        stringResource(R.string.devices_edit_no_profiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InternetSection(internet, thisPhone, enabled = !saving)
                Text(device.mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        DeviceChange(
                            label = label.trim().ifEmpty { null },
                            fixedIp = address?.takeIf { fixed },
                            owner = owner.takeIf { it != currentOwner },
                        ),
                    )
                },
                enabled = !saving,
                modifier = Modifier.testTag("device-save"),
            ) { Text(stringResource(R.string.devices_edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.devices_edit_cancel)) }
        },
    )
}

/** Cortado: cómo está y el botón para devolverlo. Con internet: un rato sin él, o bloquearlo. */
@Composable
private fun InternetSection(internet: InternetControl?, thisPhone: Boolean, enabled: Boolean) {
    if (internet == null && !thisPhone) return
    HorizontalDivider()
    Text(stringResource(R.string.devices_internet_title), style = MaterialTheme.typography.titleSmall)
    when {
        internet == null -> Text(
            stringResource(R.string.devices_internet_this_phone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        internet.status != null -> {
            Text(internet.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = internet.onResume, enabled = enabled, modifier = Modifier.testTag("device-resume")) {
                Text(stringResource(R.string.devices_internet_resume))
            }
        }
        else -> PauseButtons(internet, enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PauseButtons(internet: InternetControl, enabled: Boolean) {
    Text(
        stringResource(R.string.devices_internet_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PAUSE_MINUTES.forEach { (minutes, label) ->
            OutlinedButton(onClick = { internet.onPause(minutes) }, enabled = enabled) { Text(stringResource(label)) }
        }
        FilledTonalButton(onClick = { internet.onPause(null) }, enabled = enabled, modifier = Modifier.testTag("device-block")) {
            Text(stringResource(R.string.devices_internet_block))
        }
    }
}

@Composable
private fun OwnerOption(name: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(name, modifier = Modifier.padding(start = 8.dp))
    }
}

internal val DeviceKind.icon: Int
    get() = when (this) {
        DeviceKind.Phone -> R.drawable.ic_kind_phone
        DeviceKind.Computer -> R.drawable.ic_kind_computer
        DeviceKind.Tv -> R.drawable.ic_kind_tv
        DeviceKind.Network -> R.drawable.ic_kind_network
        DeviceKind.Smart -> R.drawable.ic_kind_smart
        DeviceKind.Other -> R.drawable.ic_devices
    }

@Composable
private fun leaseText(device: Device): String = when {
    device.fixedIp != null -> stringResource(R.string.devices_fixed_address)
    else -> when (val lease = device.lease) {
        LeaseState.None -> stringResource(if (device.ipv4.isEmpty()) R.string.devices_ipv6_only else R.string.devices_static_ip)
        LeaseState.Permanent -> stringResource(R.string.devices_static_lease)
        is LeaseState.Expires -> stringResource(R.string.devices_lease, formatDuration(lease.seconds))
    }
}
