package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.Access
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.access
import dev.angel.wrtcontrol.parental.formatUtcOffset
import dev.angel.wrtcontrol.parental.newId
import dev.angel.wrtcontrol.parental.zoneCheck
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalAppSettings
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.StatusPill
import dev.angel.wrtcontrol.ui.authenticate
import dev.angel.wrtcontrol.ui.canLockApp
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.rememberVendors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun ParentalScreen(connection: Connection.SignedIn, section: ParentalSection) {
    val family = section == ParentalSection.Family
    val repo = LocalRepository.current
    val settings = LocalAppSettings.current
    val vm: ParentalViewModel = viewModel(key = "parental:${connection.address}") { ParentalViewModel(repo) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sin remember: si el teléfono cambia de IP (reinicio del router), se nota en la siguiente recarga.
    val phoneIps = phoneAddresses(context)
    vm.phoneIps = phoneIps
    val appLock by settings.appLock.collectAsState(initial = false)
    val locked = LocalAppLocked.current
    // Sin remember: si el usuario pone un bloqueo de pantalla en el teléfono, se nota al recargar.
    val lockAvailable = canLockApp(context)

    var editing by remember { mutableStateOf<Profile?>(null) }
    var pausing by remember { mutableStateOf<Profile?>(null) }
    var extraFor by remember { mutableStateOf<Profile?>(null) }
    var confirmQuarantine by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<Device?>(null) }
    var showApproved by remember { mutableStateOf(false) }
    var showAdmins by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LoadableContent(state) { data, loadError ->
            val snapshot = data.snapshot
            val zone = zoneCheck(ZoneId.systemDefault(), Instant.now(), snapshot.routerZone, snapshot.routerOffsetSeconds)
            val busy = vm.busy != null

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.busy?.let { item { BusyBanner(it.text()) } }
                if (editing == null && !askPassword) vm.error?.let { e -> item { DismissibleError(e.text()) { vm.error = null } } }
                if (loadError != null) item { ErrorBanner(stringResource(R.string.parental_stale_data, loadError)) }

                if (family) data.backup?.let { backup ->
                    item {
                        val names = backup.profiles.joinToString { it.name }
                        NoticeCard(
                            stringResource(R.string.parental_backup_title),
                            when {
                                snapshot.profiles.isEmpty() && names.isNotEmpty() -> stringResource(
                                    R.string.parental_backup_body_missing_named,
                                    names,
                                )
                                snapshot.profiles.isEmpty() -> stringResource(R.string.parental_backup_body_missing)
                                names.isNotEmpty() -> stringResource(R.string.parental_backup_body_replace_named, names)
                                else -> stringResource(R.string.parental_backup_body_replace)
                            },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.restore(backup) }, enabled = !busy, modifier = Modifier.testTag("restore-backup")) {
                                    Text(stringResource(R.string.parental_restore))
                                }
                                TextButton(
                                    onClick = vm::discardBackup,
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.parental_discard_backup)) }
                            }
                        }
                    }
                }
                if (family && snapshot.newerSchema) item {
                    NoticeCard(
                        stringResource(R.string.parental_newer_schema_title),
                        stringResource(R.string.parental_newer_schema_body),
                    )
                }
                if (!family && connection.emptyPassword) item {
                    NoticeCard(
                        stringResource(R.string.parental_no_password_title),
                        stringResource(R.string.parental_no_password_body),
                    ) {
                        Button(onClick = { vm.error = null; askPassword = true }, enabled = !busy, modifier = Modifier.testTag("set-password")) {
                            Text(stringResource(R.string.parental_set_password))
                        }
                    }
                }
                if (family && snapshot.rulesLoaded == false) item {
                    NoticeCard(
                        stringResource(R.string.parental_rules_inactive_title),
                        stringResource(R.string.parental_rules_inactive_body),
                    ) {
                        Button(onClick = vm::reapply, enabled = !busy) { Text(stringResource(R.string.parental_reapply)) }
                    }
                }
                if (family && zone.mismatch) item {
                    NoticeCard(
                        stringResource(R.string.parental_zone_mismatch_title),
                        stringResource(
                            R.string.parental_zone_mismatch_body,
                            zoneLabel(zone.routerZone),
                            formatUtcOffset(zone.routerOffsetSeconds!!),
                            zoneLabel(zone.phoneZone),
                            formatUtcOffset(zone.phoneOffsetSeconds),
                        ),
                    ) {
                        Button(onClick = { vm.useZone(ZoneId.systemDefault()) }, enabled = !busy) {
                            Text(stringResource(R.string.parental_use_phone_zone))
                        }
                    }
                }
                if (family && snapshot.flowOffloading) item {
                    NoticeCard(
                        stringResource(R.string.parental_flow_offloading_title),
                        stringResource(R.string.parental_flow_offloading_body),
                    )
                }
                if (family && snapshot.markConflicts.isNotEmpty()) item {
                    NoticeCard(
                        stringResource(R.string.parental_mark_conflicts_title),
                        stringResource(R.string.parental_mark_conflicts_body, snapshot.markConflicts.joinToString()),
                    )
                }

                if (family) item {
                    Text(
                        stringResource(if (snapshot.profiles.isEmpty()) R.string.parental_title else R.string.parental_profiles),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (family && snapshot.profiles.isEmpty()) item {
                    Text(
                        stringResource(R.string.parental_empty_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (family) items(snapshot.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        data = data,
                        busy = busy,
                        onEdit = { editing = profile },
                        onPause = { pausing = profile },
                        onResume = { vm.resume(profile) },
                        onExtraTime = { extraFor = profile },
                        onClearExtraTime = { vm.extraTime(profile, null) },
                    )
                }

                if (!family) item {
                    QuarantineCard(
                        data = data,
                        busy = busy,
                        phoneIps = phoneIps,
                        onToggle = { on -> if (on) confirmQuarantine = true else vm.setQuarantine(false) },
                        onApprove = vm::approve,
                        onAssign = { assigning = it },
                        onShowApproved = { showApproved = true },
                    )
                }
                if (!family) item {
                    val lockEnableTitle = stringResource(R.string.parental_lock_enable)
                    val lockDisableTitle = stringResource(R.string.parental_lock_disable)
                    AppLockCard(
                        enabled = appLock,
                        available = lockAvailable,
                        admins = snapshot.settings.admins.size,
                        onShowAdmins = { showAdmins = true },
                        onToggle = { on ->
                            // Para activarlo y para quitarlo hay que identificarse (si el teléfono
                            // ya no tiene bloqueo de pantalla, quitarlo no lo pide: no podría).
                            if (!on && !lockAvailable) {
                                scope.launch { settings.setAppLock(false) }
                            } else {
                                authenticate(context, if (on) lockEnableTitle else lockDisableTitle) { ok ->
                                    if (ok) scope.launch { settings.setAppLock(on) }
                                }
                            }
                        },
                    )
                }
            }

            if (confirmQuarantine && !locked) {
                QuarantineConfirmDialog(
                    known = if (snapshot.settings.approved.isEmpty()) data.devices.size else null,
                    onConfirm = { confirmQuarantine = false; vm.setQuarantine(true) },
                    onDismiss = { confirmQuarantine = false },
                )
            }
            assigning?.takeIf { !locked }?.let { device ->
                AssignDialog(
                    device = device,
                    profiles = snapshot.profiles,
                    onPick = { profile -> assigning = null; vm.assign(device, profile) },
                    onDismiss = { assigning = null },
                )
            }
            if (showApproved && !locked) {
                ApprovedDialog(
                    approved = snapshot.settings.approved.filter { mac -> snapshot.profiles.none { mac in it.macs } },
                    admins = snapshot.settings.admins.toSet(),
                    phoneMacs = data.phoneMacs,
                    devices = data.devices,
                    busy = busy,
                    onRemove = vm::unapprove,
                    onApproveMac = { vm.approveMac(it) },
                    onDismiss = { showApproved = false },
                )
            }
            if (showAdmins && !locked) {
                AdminsDialog(
                    admins = snapshot.settings.admins,
                    phoneMacs = data.phoneMacs,
                    devices = data.devices,
                    busy = busy,
                    onRemove = vm::unapprove,
                    onAdd = vm::addAdmin,
                    onDismiss = { showAdmins = false },
                )
            }
            extraFor?.takeIf { !locked }?.let { profile ->
                val options = extraTimeOptions(profile, snapshot.routerNow)
                // Si el horario terminó mientras el diálogo estaba abierto, ya no hay nada que dar.
                LaunchedEffect(options.isEmpty()) { if (options.isEmpty()) extraFor = null }
                if (options.isNotEmpty()) {
                    ExtraTimeDialog(
                        name = profile.name,
                        options = options,
                        onPick = { option -> extraFor = null; vm.extraTime(profile, option) },
                        onDismiss = { extraFor = null },
                    )
                }
            }
        }

        val data = state.data
        if (data != null && family) {
            val newProfileLabel = stringResource(R.string.parental_new_profile)
            ExtendedFloatingActionButton(
                onClick = { editing = newProfile(data) },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(newProfileLabel) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("new-profile")
                    // El FAB extendido no expone el texto de su etiqueta a accesibilidad (se vio con
                    // uiautomator: nodo clicable sin texto). Sin esto, TalkBack solo diría "botón".
                    .semantics { contentDescription = newProfileLabel },
            )
        }
    }

    val data = state.data
    // Con la app bloqueada no se dibuja ningún diálogo (quedarían encima del candado).
    editing?.takeIf { !locked }?.let { draft ->
        if (data == null) return@let
        ProfileEditor(
            initial = draft,
            isNew = data.snapshot.profiles.none { it.id == draft.id },
            devices = data.devices,
            others = data.snapshot.profiles.filter { it.id != draft.id },
            admins = data.snapshot.settings.admins.toSet(),
            phoneIps = phoneIps,
            busy = vm.busy?.text(),
            error = vm.error?.text(),
            onDismiss = { if (vm.busy == null) { editing = null; vm.error = null } },
            onSave = { profile -> vm.save(profile) { editing = null } },
            onDelete = { vm.delete(draft) { editing = null } },
        )
    }
    pausing?.takeIf { !locked }?.let { profile ->
        PauseDialog(
            name = profile.name,
            onDismiss = { pausing = null },
            onPick = { minutes ->
                pausing = null
                vm.pause(profile, minutes)
            },
        )
    }
    if (askPassword && !locked) {
        PasswordDialog(
            busy = vm.busy != null,
            error = vm.error?.text(),
            onSubmit = { password -> vm.changePassword(password) { askPassword = false } },
            onDismiss = { askPassword = false; vm.error = null },
        )
    }
}

@Composable
private fun zoneLabel(zone: String?): String = zone?.replace('_', ' ') ?: stringResource(R.string.parental_zone_unknown)

/** Los perfiles nuevos protegen la configuración del router: suelen ser de los niños. */
private fun newProfile(data: ParentalData): Profile =
    Profile(newId(data.snapshot.profiles.map { it.id }.toSet()), "", emptyList(), protectRouter = true)

@Composable
private fun ProfileCard(
    profile: Profile,
    data: ParentalData,
    busy: Boolean,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtraTime: () -> Unit,
    onClearExtraTime: () -> Unit,
) {
    val now = data.snapshot.routerNow
    val access = profile.access(now)
    val status = accessText(profile, now)
    val vendors = rememberVendors()
    val names = profile.macs.map { mac -> data.devices.firstOrNull { it.mac == mac }?.let { deviceTitle(it, vendors) } ?: mac }
    val bypass = data.snapshot.dnsBypassBlocked(profile)

    Card(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onEdit).testTag("profile-${profile.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(status.pill.text(), status.blocked?.not())
            }
            Text(
                if (names.isEmpty()) {
                    stringResource(R.string.parental_no_devices)
                } else {
                    pluralStringResource(R.plurals.parental_devices, names.size, names.size, names.joinToString())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.detail?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium) }
            formatFilter(profile.filter)?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
            if (data.snapshot.filterRunning(profile) == false) {
                Text(
                    stringResource(R.string.parental_filter_not_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (bypass > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.parental_dns_bypass,
                        bypass.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        bypass,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            profile.schedules.forEach { s ->
                val schedule = formatSchedule(s).text()
                Text(
                    if (s.enabled) schedule else stringResource(R.string.parental_schedule_disabled, schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                when {
                    profile.pause != null -> FilledTonalButton(
                        onClick = onResume,
                        enabled = !busy,
                    ) { Text(stringResource(R.string.parental_resume)) }
                    access is Access.Blocked -> FilledTonalButton(onClick = onExtraTime, enabled = !busy, modifier = Modifier.testTag("extra-${profile.id}")) {
                        Text(stringResource(R.string.parental_extra_time))
                    }
                    else -> FilledTonalButton(onClick = onPause, enabled = !busy && profile.macs.isNotEmpty()) {
                        Text(stringResource(R.string.parental_pause_internet))
                    }
                }
                if (access is Access.ExtraTime) {
                    TextButton(onClick = onClearExtraTime, enabled = !busy, modifier = Modifier.testTag("clear-extra-${profile.id}")) {
                        Text(stringResource(R.string.parental_clear_extra_time))
                    }
                } else {
                    TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.parental_edit)) }
                }
            }
        }
    }
}

@Composable
private fun BusyBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.parental_busy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
internal fun DismissibleError(message: String, onDismiss: () -> Unit) {
    Column {
        ErrorBanner(message)
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.parental_close)) }
    }
}

private val PAUSE_OPTIONS = listOf(
    30L to R.string.parental_pause_30_min,
    60L to R.string.parental_pause_1_hour,
    120L to R.string.parental_pause_2_hours,
    null to R.string.parental_pause_until_resumed,
)

@Composable
private fun PauseDialog(name: String, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.parental_pause_title, name)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.parental_pause_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PAUSE_OPTIONS.forEach { (minutes, label) ->
                    TextButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth().testTag("pause-${minutes ?: "indef"}")) {
                        Text(stringResource(label), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.parental_cancel)) } },
    )
}
