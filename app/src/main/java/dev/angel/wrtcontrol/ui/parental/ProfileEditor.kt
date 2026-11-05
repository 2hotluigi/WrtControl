package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.FilterLevel
import dev.angel.wrtcontrol.parental.MAX_BLOCKED_DOMAINS
import dev.angel.wrtcontrol.parental.MAX_PROFILE_NAME
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.SERVICES
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.cleanProfileName
import dev.angel.wrtcontrol.parental.newId
import dev.angel.wrtcontrol.parental.normalizeDomain
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.ui.ErrorBanner
import java.time.DayOfWeek
import java.time.LocalTime

/** Fila de la lista de dispositivos del editor. */
private data class DeviceOption(val mac: String, val title: String, val detail: String, val note: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var filter by remember { mutableStateOf(initial.filter) }
    var newDomain by remember { mutableStateOf("") }
    var domainError by remember { mutableStateOf<String?>(null) }
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
        // El diálogo gestiona él mismo los márgenes del sistema: si no, el teclado tapa los
        // campos de abajo (p. ej. "Agregar sitio") en vez de encoger la lista.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        // Al dibujar bajo las barras del sistema, sus iconos deben contrastar con el fondo del diálogo.
        val view = LocalView.current
        val dark = isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
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
                                    onSave(initial.copy(name = cleanName, macs = macs.sorted(), schedules = schedules, filter = filter))
                                },
                                enabled = cleanName.isNotEmpty() && !locked,
                                modifier = Modifier.testTag("save-profile"),
                            ) { Text("Guardar") }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
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

                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        SectionTitle(
                            "Filtro de contenido",
                            "Se aplica a lo que sus dispositivos buscan en internet (DNS), aunque usen otro servidor DNS.",
                        )
                    }
                    item {
                        Column(Modifier.selectableGroup()) {
                            FilterLevel.entries.forEach { level ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = filter.level == level,
                                            enabled = !locked,
                                            role = Role.RadioButton,
                                            onClick = { filter = filter.copy(level = level) },
                                        )
                                        .padding(vertical = 6.dp)
                                        .testTag("filter-${level.name}"),
                                ) {
                                    RadioButton(selected = filter.level == level, onClick = null, enabled = !locked)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(level.title(), style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            level.description(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Text("Bloquear servicios", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SERVICES.forEach { service ->
                                val selected = service.id in filter.services
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        filter = filter.copy(services = if (selected) filter.services - service.id else filter.services + service.id)
                                    },
                                    label = { Text(service.name) },
                                    enabled = !locked,
                                    modifier = Modifier.testTag("service-${service.id}"),
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            "Bloquea los dominios principales de cada servicio; con el tiempo las apps pueden usar otros.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { Text("Sitios bloqueados", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(filter.domains, key = { "domain-$it" }) { domain ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { filter = filter.copy(domains = filter.domains - domain) }, enabled = !locked) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Quitar $domain")
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newDomain,
                                onValueChange = { newDomain = it.take(255); domainError = null },
                                label = { Text("Agregar sitio") },
                                placeholder = { Text("ejemplo.com") },
                                singleLine = true,
                                enabled = !locked,
                                isError = domainError != null,
                                supportingText = domainError?.let { { Text(it) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f).testTag("new-domain"),
                            )
                            TextButton(
                                onClick = {
                                    val domain = normalizeDomain(newDomain)
                                    when {
                                        domain == null -> domainError = "Escribe un dominio, por ejemplo ejemplo.com"
                                        filter.domains.size >= MAX_BLOCKED_DOMAINS -> domainError = "Máximo $MAX_BLOCKED_DOMAINS sitios"
                                        else -> {
                                            filter = filter.copy(domains = (filter.domains + domain).distinct().sorted())
                                            newDomain = ""
                                        }
                                    }
                                },
                                enabled = newDomain.isNotBlank() && !locked,
                                modifier = Modifier.testTag("add-domain"),
                            ) { Text("Agregar") }
                        }
                    }
                    if (filter.active && macs.isEmpty()) item {
                        Text(
                            "El filtro se aplicará cuando el perfil tenga dispositivos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
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
