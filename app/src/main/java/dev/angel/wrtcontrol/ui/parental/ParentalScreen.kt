package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangePreviewHost
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalAppSettings
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.authenticate
import dev.angel.wrtcontrol.ui.canLockApp
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.CircleAction
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.design.SectionHeader
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
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                vm.progress?.let { item { ApplyingBanner(it) } }
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
                        Button(onClick = { vm.useZone(ZoneId.systemDefault(), zone.routerZone) }, enabled = !busy) {
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
                    SectionHeader(stringResource(R.string.parental_people))
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
                if (family) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupCard {
                            GroupRow(
                                stringResource(R.string.parental_new_profile),
                                leading = { RowIcon(R.drawable.ic_add, LocalPalette.current.accent) },
                                labelColor = LocalPalette.current.accent,
                                divider = false,
                                onClick = { if (!busy) editing = newProfile(data) },
                                modifier = Modifier.testTag("new-profile"),
                            )
                        }
                        if (snapshot.profiles.isEmpty()) GroupFooter(stringResource(R.string.parental_empty_hint))
                    }
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
            progress = vm.progress,
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
    ChangePreviewHost(vm.proposals)
}

@Composable
private fun zoneLabel(zone: String?): String = zone?.replace('_', ' ') ?: stringResource(R.string.parental_zone_unknown)

/** Los perfiles nuevos protegen la configuración del router: suelen ser de los niños. */
private fun newProfile(data: ParentalData): Profile =
    Profile(newId(data.snapshot.profiles.map { it.id }.toSet()), "", emptyList(), protectRouter = true)

/**
 * Una persona: su inicial, cómo está su internet, sus aparatos, su filtro y sus horarios; debajo,
 * lo que se puede hacer (pausar o devolver, más tiempo, editar). Tocarla la edita.
 */
@Composable
internal fun ProfileCard(
    profile: Profile,
    data: ParentalData,
    busy: Boolean,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtraTime: () -> Unit,
    onClearExtraTime: () -> Unit,
) {
    val palette = LocalPalette.current
    val now = data.snapshot.routerNow
    val access = profile.access(now)
    val status = accessText(profile, now)
    val vendors = rememberVendors()
    val names = profile.macs.map { mac -> data.devices.firstOrNull { it.mac == mac }?.let { deviceTitle(it, vendors) } ?: mac }
    val tint = when (status.blocked) {
        true -> palette.bad
        false -> palette.good
        null -> palette.secondary
    }

    GroupCard(Modifier.testTag("profile-${profile.id}")) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onEdit).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(profile.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, color = palette.label)
                Text(status.detail?.text() ?: status.pill.text(), style = MaterialTheme.typography.bodyMedium, color = tint)
            }
        }
        ProfileFacts(profile, data, names)
        ProfileActions(profile, access, busy, onEdit, onPause, onResume, onExtraTime, onClearExtraTime)
    }
}

/** Lo que tiene puesto: sus aparatos, su filtro, sus horarios y lo que no va bien. */
@Composable
private fun ProfileFacts(profile: Profile, data: ParentalData, names: List<String>) {
    val palette = LocalPalette.current
    val bypass = data.snapshot.dnsBypassBlocked(profile)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (names.isEmpty()) {
                stringResource(R.string.parental_no_devices)
            } else {
                pluralStringResource(R.plurals.parental_devices, names.size, names.size, names.joinToString())
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondary,
        )
        formatFilter(profile.filter)?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, color = palette.secondary) }
        profile.schedules.forEach { s ->
            val schedule = formatSchedule(s).text()
            Text(
                if (s.enabled) schedule else stringResource(R.string.parental_schedule_disabled, schedule),
                style = MaterialTheme.typography.bodySmall,
                color = if (s.enabled) palette.label else palette.tertiary,
            )
        }
        if (data.snapshot.filterRunning(profile) == false) {
            Text(stringResource(R.string.parental_filter_not_running), style = MaterialTheme.typography.bodySmall, color = palette.bad)
        }
        if (bypass > 0) {
            Text(
                pluralStringResource(R.plurals.parental_dns_bypass, bypass.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), bypass),
                style = MaterialTheme.typography.bodySmall,
                color = palette.warning,
            )
        }
    }
}

/** Pausar o devolver internet, más tiempo (o quitarlo) y editar, como controles redondos. */
@Composable
private fun ProfileActions(
    profile: Profile,
    access: Access,
    busy: Boolean,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtraTime: () -> Unit,
    onClearExtraTime: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        val action = Modifier.weight(1f)
        when {
            profile.pause != null -> CircleAction(
                R.drawable.ic_pause,
                stringResource(R.string.parental_resume),
                null,
                active = true,
                tint = palette.bad,
                modifier = action.testTag("resume-${profile.id}"),
                onClick = { if (!busy) onResume() },
            )
            access is Access.Blocked -> CircleAction(
                R.drawable.ic_timer,
                stringResource(R.string.parental_extra_time),
                null,
                active = false,
                modifier = action.testTag("extra-${profile.id}"),
                onClick = { if (!busy) onExtraTime() },
            )
            profile.macs.isNotEmpty() -> CircleAction(
                R.drawable.ic_pause,
                stringResource(R.string.parental_pause_short),
                null,
                active = false,
                modifier = action.testTag("pause-${profile.id}"),
                onClick = { if (!busy) onPause() },
            )
        }
        if (access is Access.ExtraTime) {
            CircleAction(
                R.drawable.ic_timer,
                stringResource(R.string.parental_clear_extra_short),
                null,
                active = true,
                modifier = action.testTag("clear-extra-${profile.id}"),
                onClick = { if (!busy) onClearExtraTime() },
            )
        }
        CircleAction(
            R.drawable.ic_edit,
            stringResource(R.string.parental_edit),
            null,
            active = false,
            modifier = action.testTag("edit-${profile.id}"),
            onClick = { if (!busy) onEdit() },
        )
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
