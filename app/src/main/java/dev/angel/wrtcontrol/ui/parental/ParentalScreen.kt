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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.formatUtcOffset
import dev.angel.wrtcontrol.parental.newId
import dev.angel.wrtcontrol.parental.zoneCheck
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.StatusPill
import java.time.Instant
import java.time.ZoneId

@Composable
fun ParentalScreen(routerAddress: String) {
    val repo = LocalRepository.current
    val vm: ParentalViewModel = viewModel(key = "parental:$routerAddress") { ParentalViewModel(repo) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val phoneIps = remember { phoneAddresses(context) }

    var editing by remember { mutableStateOf<Profile?>(null) }
    var pausing by remember { mutableStateOf<Profile?>(null) }

    Box(Modifier.fillMaxSize()) {
        LoadableContent(state) { data, loadError ->
            val snapshot = data.snapshot
            val zone = zoneCheck(ZoneId.systemDefault(), Instant.now(), snapshot.routerZone, snapshot.routerOffsetSeconds)

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.busy?.let { item { BusyBanner(it) } }
                if (editing == null) vm.error?.let { item { DismissibleError(it) { vm.error = null } } }
                if (loadError != null) item { ErrorBanner("Datos sin actualizar: $loadError") }

                if (zone.mismatch) item {
                    NoticeCard(
                        "La hora del router no coincide con la de este teléfono",
                        "El router usa ${zoneLabel(zone.routerZone)} (${formatUtcOffset(zone.routerOffsetSeconds!!)}) y el " +
                            "teléfono ${zoneLabel(zone.phoneZone)} (${formatUtcOffset(zone.phoneOffsetSeconds)}). " +
                            "Los horarios se cuentan con la hora del router.",
                    ) {
                        Button(onClick = { vm.useZone(ZoneId.systemDefault()) }, enabled = vm.busy == null) {
                            Text("Usar la zona del teléfono")
                        }
                    }
                }
                if (snapshot.flowOffloading) item {
                    NoticeCard(
                        "Descarga de flujos activada",
                        "Con flow offloading, las conexiones que ya estaban abiertas al pausar pueden seguir hasta que se cierren. " +
                            "Las nuevas sí se bloquean.",
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
                        busy = vm.busy != null,
                        onEdit = { editing = profile },
                        onPause = { pausing = profile },
                        onResume = { vm.resume(profile) },
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
    editing?.let { draft ->
        if (data == null) return@let
        ProfileEditor(
            initial = draft,
            isNew = data.snapshot.profiles.none { it.id == draft.id },
            devices = data.devices,
            others = data.snapshot.profiles.filter { it.id != draft.id },
            phoneIps = phoneIps,
            busy = vm.busy,
            error = vm.error,
            onDismiss = { if (vm.busy == null) { editing = null; vm.error = null } },
            onSave = { profile -> vm.save(profile) { editing = null } },
            onDelete = { vm.delete(draft) { editing = null } },
        )
    }
    pausing?.let { profile ->
        PauseDialog(
            name = profile.name,
            onDismiss = { pausing = null },
            onPick = { minutes ->
                pausing = null
                vm.pause(profile, minutes)
            },
        )
    }
}

private fun zoneLabel(zone: String?): String = zone?.replace('_', ' ') ?: "una zona desconocida"

private fun newProfile(data: ParentalData): Profile =
    Profile(newId(data.snapshot.profiles.map { it.id }.toSet()), "", emptyList())

@Composable
private fun ProfileCard(
    profile: Profile,
    data: ParentalData,
    busy: Boolean,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val now = data.snapshot.routerNow
    val status = accessText(profile, now)
    val names = profile.macs.map { mac -> data.devices.firstOrNull { it.mac == mac }?.name ?: mac }

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
            profile.schedules.forEach { s ->
                Text(
                    formatSchedule(s) + if (s.enabled) "" else " (desactivado)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (profile.pause != null) {
                    FilledTonalButton(onClick = onResume, enabled = !busy) { Text("Reanudar") }
                } else {
                    FilledTonalButton(onClick = onPause, enabled = !busy && profile.macs.isNotEmpty()) { Text("Pausar internet") }
                }
                TextButton(onClick = onEdit, enabled = !busy) { Text("Editar") }
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
