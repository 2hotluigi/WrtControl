package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.MAX_PROFILE_NAME
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.cleanProfileName
import dev.angel.wrtcontrol.parental.newId
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.ui.ErrorBanner
import java.time.DayOfWeek
import java.time.LocalTime

/** Fila de la lista de dispositivos del editor. */
private data class DeviceOption(val mac: String, val title: String, val detail: String, val note: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditor(
    initial: Profile,
    isNew: Boolean,
    devices: List<Device>,
    others: List<Profile>,
    phoneIps: Set<String>,
    busy: String?,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var macs by remember { mutableStateOf(initial.macs.toSet()) }
    var schedules by remember { mutableStateOf(initial.schedules) }
    var manualMac by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cleanName = cleanProfileName(name)
    val owner = others.flatMap { p -> p.macs.map { it to p.name } }.toMap()
    val options = remember(devices, macs, others, phoneIps) {
        val online = devices.map { d ->
            DeviceOption(
                mac = d.mac,
                title = d.name ?: "Sin nombre",
                detail = listOfNotNull(d.ipv4.firstOrNull(), d.mac).joinToString(" · "),
                note = when {
                    d.ipv4.any { it in phoneIps } -> "Este teléfono: si lo pausas, también se queda sin internet"
                    d.mac !in macs && d.mac in owner -> "Ahora en «${owner[d.mac]}»: pasará a este perfil"
                    else -> null
                },
            )
        }
        val offline = macs.filter { mac -> devices.none { it.mac == mac } }.sorted()
            .map { DeviceOption(it, "Desconectado", it, null) }
        online + offline
    }
    val takenScheduleIds = (others.flatMap { p -> p.schedules.map { it.id } } + schedules.map { it.id }).toSet()
    val locked = busy != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (isNew) "Nuevo perfil" else "Editar perfil") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !locked) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Cerrar sin guardar")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    onSave(initial.copy(name = cleanName, macs = macs.sorted(), schedules = schedules))
                                },
                                enabled = cleanName.isNotEmpty() && !locked,
                                modifier = Modifier.testTag("save-profile"),
                            ) { Text("Guardar") }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (busy != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(busy, style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    error?.let { item { ErrorBanner(it) } }

                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(MAX_PROFILE_NAME) },
                            label = { Text("Nombre") },
                            placeholder = { Text("Por ejemplo: Juan") },
                            singleLine = true,
                            enabled = !locked,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth().testTag("profile-name"),
                        )
                    }

                    item { SectionTitle("Dispositivos", "Las reglas se aplican por MAC a los equipos que marques.") }
                    if (options.isEmpty()) item {
                        Text("No hay dispositivos conectados ahora.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(options, key = { it.mac }) { option ->
                        val checked = option.mac in macs
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !locked) { macs = if (checked) macs - option.mac else macs + option.mac }
                                .testTag("device-${option.mac}"),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !locked)
                            Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                Text(option.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                option.note?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = manualMac,
                                onValueChange = { manualMac = it.take(20); manualError = null },
                                label = { Text("Agregar por MAC") },
                                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                                singleLine = true,
                                enabled = !locked,
                                isError = manualError != null,
                                supportingText = manualError?.let { { Text(it) } },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val mac = normalizeMac(manualMac)
                                    if (mac == null) {
                                        manualError = "MAC no válida"
                                    } else {
                                        macs = macs + mac
                                        manualMac = ""
                                    }
                                },
                                enabled = manualMac.isNotBlank() && !locked,
                            ) { Text("Agregar") }
                        }
                    }

                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        SectionTitle(
                            "Horarios sin internet",
                            "Se cuentan con la hora del router y se aplican aunque este teléfono esté apagado.",
                        )
                    }
                    if (schedules.isEmpty()) item {
                        Text("Sin horarios.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(schedules, key = { it.id }) { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { editingSchedule = s },
                        ) {
                            Text(
                                formatSchedule(s),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            Switch(
                                checked = s.enabled,
                                onCheckedChange = { on -> schedules = schedules.map { if (it.id == s.id) it.copy(enabled = on) else it } },
                                enabled = !locked,
                            )
                            IconButton(onClick = { schedules = schedules.filter { it.id != s.id } }, enabled = !locked) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Quitar horario")
                            }
                        }
                    }
                    item {
                        TextButton(
                            onClick = {
                                editingSchedule = Schedule(
                                    id = newId(takenScheduleIds),
                                    days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                                    start = LocalTime.of(21, 0),
                                    end = LocalTime.of(7, 0),
                                )
                            },
                            enabled = !locked,
                            modifier = Modifier.testTag("add-schedule"),
                        ) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                            Text("Agregar horario", modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    if (!isNew) {
                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        item {
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                enabled = !locked,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Eliminar perfil") }
                        }
                    }
                }
            }
        }
    }

    editingSchedule?.let { s ->
        ScheduleDialog(
            initial = s,
            isNew = schedules.none { it.id == s.id },
            onDismiss = { editingSchedule = null },
            onConfirm = { updated ->
                schedules = if (schedules.any { it.id == updated.id }) {
                    schedules.map { if (it.id == updated.id) updated else it }
                } else {
                    schedules + updated
                }
                editingSchedule = null
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar «${initial.name}»?") },
            text = { Text("Sus dispositivos volverán a tener internet sin pausas ni horarios.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SectionTitle(title: String, help: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
