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
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalAppSettings
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.StatusPill
import dev.angel.wrtcontrol.ui.authenticate
import dev.angel.wrtcontrol.ui.canLockApp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun ParentalScreen(connection: Connection.SignedIn) {
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
                vm.busy?.let { item { BusyBanner(it) } }
                if (editing == null && !askPassword) vm.error?.let { item { DismissibleError(it) { vm.error = null } } }
                if (loadError != null) item { ErrorBanner("Datos sin actualizar: $loadError") }

                data.backup?.let { backup ->
                    item {
                        val names = backup.profiles.joinToString { it.name }
                        val copy = "Este teléfono guardó una copia" + (if (names.isNotEmpty()) " ($names)" else "")
                        NoticeCard(
                            "Los perfiles ya no están en el router",
                            if (snapshot.profiles.isEmpty()) {
                                "$copy. Puede que el router se haya restablecido o que alguien los borrara desde LuCI."
                            } else {
                                "$copy de antes de que desaparecieran. Restaurarla sustituye los perfiles que hay ahora."
                            },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.restore(backup) }, enabled = !busy, modifier = Modifier.testTag("restore-backup")) {
                                    Text("Restaurar")
                                }
                                TextButton(onClick = vm::discardBackup, enabled = !busy) { Text("Descartar la copia") }
                            }
                        }
                    }
                }
                if (connection.emptyPassword) item {
                    NoticeCard(
                        "El router no tiene contraseña",
                        "Cualquiera conectado a tu red puede abrir su configuración y quitar estas reglas.",
                    ) {
                        Button(onClick = { vm.error = null; askPassword = true }, enabled = !busy, modifier = Modifier.testTag("set-password")) {
                            Text("Poner contraseña")
                        }
                    }
                }
                if (snapshot.rulesLoaded == false) item {
                    NoticeCard(
                        "Las reglas de WrtControl no están activas",
                        "El firewall del router no tiene cargadas las reglas de los perfiles (quizá se reinició con un error " +
                            "o alguien borró el archivo). Vuelve a aplicarlas.",
                    ) {
                        Button(onClick = vm::reapply, enabled = !busy) { Text("Volver a aplicar") }
                    }
                }
                if (zone.mismatch) item {
                    NoticeCard(
                        "La hora del router no coincide con la de este teléfono",
                        "El router usa ${zoneLabel(zone.routerZone)} (${formatUtcOffset(zone.routerOffsetSeconds!!)}) y el " +
                            "teléfono ${zoneLabel(zone.phoneZone)} (${formatUtcOffset(zone.phoneOffsetSeconds)}). " +
                            "Los horarios se cuentan con la hora del router.",
                    ) {
                        Button(onClick = { vm.useZone(ZoneId.systemDefault()) }, enabled = !busy) {
                            Text("Usar la zona del teléfono")
                        }
                    }
                }
                if (snapshot.flowOffloading) item {
                    NoticeCard(
                        "Descarga de flujos activada",
                        "Con flow offloading, las conexiones que ya estaban abiertas pueden seguir hasta que se cierren aunque " +
                            "pauses, bloquees un servicio, quites una aprobación o bloquees el DNS cifrado. Las nuevas sí se bloquean.",
                    )
                }
                if (snapshot.markConflicts.isNotEmpty()) item {
                    NoticeCard(
                        "Otras reglas usan la misma marca",
                        "Estas reglas del firewall cambian la marca 0x08000000 que usa WrtControl y podrían interferir " +
                            "con las pausas y horarios: ${snapshot.markConflicts.joinToString()}.",
                    )
                }

                item {
                    Text(
                        if (snapshot.profiles.isEmpty()) "Control parental" else "Perfiles",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (snapshot.profiles.isEmpty()) item {
                    Text(
                        "Crea un perfil por persona y asígnale sus dispositivos. Después podrás pausarle internet " +
                            "o programar horarios sin conexión, como la hora de dormir.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(snapshot.profiles, key = { it.id }) { profile ->
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

                item {
                    Text("Protección", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
                }
                item {
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
                item {
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
                                authenticate(context, if (on) "Activar el bloqueo de WrtControl" else "Quitar el bloqueo de WrtControl") { ok ->
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
        if (data != null) {
            ExtendedFloatingActionButton(
                onClick = { editing = newProfile(data) },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Nuevo perfil") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("new-profile")
                    // El FAB extendido no expone el texto de su etiqueta a accesibilidad (se vio con
                    // uiautomator: nodo clicable sin texto). Sin esto, TalkBack solo diría "botón".
                    .semantics { contentDescription = "Nuevo perfil" },
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
            busy = vm.busy,
            error = vm.error,
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
            error = vm.error,
            onSubmit = { password -> vm.changePassword(password) { askPassword = false } },
            onDismiss = { askPassword = false; vm.error = null },
        )
    }
}

private fun zoneLabel(zone: String?): String = zone?.replace('_', ' ') ?: "una zona desconocida"

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
    val names = profile.macs.map { mac -> data.devices.firstOrNull { it.mac == mac }?.name ?: mac }
    val bypass = data.snapshot.dnsBypassBlocked(profile)

    Card(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onEdit).testTag("profile-${profile.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(status.pill, status.blocked?.not())
            }
            Text(
                when (names.size) {
                    0 -> "Sin dispositivos"
                    1 -> "1 dispositivo: ${names.single()}"
                    else -> "${names.size} dispositivos: ${names.joinToString()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            formatFilter(profile.filter)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (data.snapshot.filterRunning(profile) == false) {
                Text(
                    "El filtro no está funcionando en el router. Vuelve a guardar el perfil para reactivarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (bypass > 0) {
                Text(
                    "Se bloquearon conexiones a servidores de DNS cifrado ($bypass paquetes desde el último cambio): algo intentó " +
                        "saltarse el filtro, por ejemplo el «DNS seguro» del navegador.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            profile.schedules.forEach { s ->
                Text(
                    formatSchedule(s) + if (s.enabled) "" else " (desactivado)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                when {
                    profile.pause != null -> FilledTonalButton(onClick = onResume, enabled = !busy) { Text("Reanudar") }
                    access is Access.Blocked -> FilledTonalButton(onClick = onExtraTime, enabled = !busy, modifier = Modifier.testTag("extra-${profile.id}")) {
                        Text("Más tiempo")
                    }
                    else -> FilledTonalButton(onClick = onPause, enabled = !busy && profile.macs.isNotEmpty()) { Text("Pausar internet") }
                }
                if (access is Access.ExtraTime) {
                    TextButton(onClick = onClearExtraTime, enabled = !busy, modifier = Modifier.testTag("clear-extra-${profile.id}")) {
                        Text("Quitar tiempo extra")
                    }
                } else {
                    TextButton(onClick = onEdit, enabled = !busy) { Text("Editar") }
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
                "Si el cambio no funciona, el router lo deshace solo.",
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
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cerrar") }
    }
}

private val PAUSE_OPTIONS = listOf(30L to "30 minutos", 60L to "1 hora", 120L to "2 horas", null to "Hasta que lo reanude")

@Composable
private fun PauseDialog(name: String, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pausar internet de $name") },
        text = {
            Column {
                Text(
                    "Se corta también lo que esté usando en ese momento. Podrá seguir conectado a la red de casa.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PAUSE_OPTIONS.forEach { (minutes, label) ->
                    TextButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth().testTag("pause-${minutes ?: "indef"}")) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
