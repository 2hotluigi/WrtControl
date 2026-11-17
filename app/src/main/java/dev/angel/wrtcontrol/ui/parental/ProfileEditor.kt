package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import dev.angel.wrtcontrol.parental.YoutubeMode
import dev.angel.wrtcontrol.parental.cleanProfileName
import dev.angel.wrtcontrol.parental.newId
import dev.angel.wrtcontrol.parental.normalizeDomain
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.rememberVendors
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
    /** Teléfonos con la app: nunca se les bloquea la configuración del router. */
    admins: Set<String>,
    phoneIps: Set<String>,
    /** El cambio que se está guardando, si hay alguno: mientras, no se toca nada. */
    progress: ChangeProgress?,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var macs by remember { mutableStateOf(initial.macs.toSet()) }
    var schedules by remember { mutableStateOf(initial.schedules) }
    var filter by remember { mutableStateOf(initial.filter) }
    var protectRouter by remember { mutableStateOf(initial.protectRouter) }
    var newDomain by remember { mutableStateOf("") }
    var domainError by remember { mutableStateOf<String?>(null) }
    var manualMac by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cleanName = cleanProfileName(name)
    val owner = others.flatMap { p -> p.macs.map { it to p.name } }.toMap()
    val unnamed = stringResource(R.string.profile_unnamed)
    val disconnected = stringResource(R.string.profile_disconnected)
    val noteThisPhone = stringResource(R.string.profile_note_this_phone)
    val noteAdmin = stringResource(R.string.profile_note_admin)
    // Plantilla con %1$s: se rellena dentro de remember, donde no se puede llamar a stringResource.
    val noteOtherProfile = stringResource(R.string.profile_note_other_profile)
    // El mismo nombre que en Dispositivos: el que le puso el usuario, el suyo o su fabricante.
    val vendors = rememberVendors()
    val titles = devices.associate { it.mac to deviceTitle(it, vendors) }
    val notes = listOf(unnamed, disconnected, noteThisPhone, noteAdmin, noteOtherProfile)
    val options = remember(devices, titles, macs, others, phoneIps, admins, notes) {
        val online = devices.map { d ->
            DeviceOption(
                mac = d.mac,
                title = titles[d.mac] ?: unnamed,
                detail = listOfNotNull(d.ipv4.firstOrNull(), d.mac).joinToString(" · "),
                note = when {
                    d.ipv4.any { it in phoneIps } -> noteThisPhone
                    d.mac in admins -> noteAdmin
                    d.mac !in macs && d.mac in owner -> noteOtherProfile.format(owner[d.mac])
                    else -> null
                },
            )
        }
        val offline = macs.filter { mac -> devices.none { it.mac == mac } }.sorted()
            .map { DeviceOption(it, disconnected, it, if (it in admins) noteAdmin else null) }
        online + offline
    }
    val takenScheduleIds = (others.flatMap { p -> p.schedules.map { it.id } } + schedules.map { it.id }).toSet()
    val locked = progress != null
    // Los errores se guardan como texto en el estado y se asignan en onClick, fuera de la composición.
    val invalidMac = stringResource(R.string.profile_invalid_mac)
    val invalidSite = stringResource(R.string.profile_invalid_site)
    val maxSites = stringResource(R.string.profile_max_sites, MAX_BLOCKED_DOMAINS)

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
                        title = { Text(stringResource(if (isNew) R.string.profile_new else R.string.profile_edit)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !locked) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.profile_close_without_saving),
                                )
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    onSave(
                                        initial.copy(
                                            name = cleanName,
                                            macs = macs.sorted(),
                                            schedules = schedules,
                                            filter = filter,
                                            protectRouter = protectRouter,
                                        ),
                                    )
                                },
                                enabled = cleanName.isNotEmpty() && !locked,
                                modifier = Modifier.testTag("save-profile"),
                            ) { Text(stringResource(R.string.profile_save)) }
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
                    if (progress != null) item { ApplyingBanner(progress) }
                    error?.let { item { ErrorBanner(it) } }

                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(MAX_PROFILE_NAME) },
                            label = { Text(stringResource(R.string.profile_name)) },
                            placeholder = { Text(stringResource(R.string.profile_name_placeholder)) },
                            singleLine = true,
                            enabled = !locked,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth().testTag("profile-name"),
                        )
                    }

                    item { SectionTitle(stringResource(R.string.profile_devices_title), stringResource(R.string.profile_devices_help)) }
                    if (options.isEmpty()) item {
                        Text(stringResource(R.string.profile_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                label = { Text(stringResource(R.string.profile_add_by_mac)) },
                                placeholder = { Text(MAC_PLACEHOLDER) },
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
                                        manualError = invalidMac
                                    } else {
                                        macs = macs + mac
                                        manualMac = ""
                                    }
                                },
                                enabled = manualMac.isNotBlank() && !locked,
                            ) { Text(stringResource(R.string.profile_add)) }
                        }
                    }

                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        SectionTitle(
                            stringResource(R.string.profile_schedules_title),
                            stringResource(R.string.profile_schedules_help),
                        )
                    }
                    if (schedules.isEmpty()) item {
                        Text(stringResource(R.string.profile_no_schedules), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(schedules, key = { it.id }) { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { editingSchedule = s },
                        ) {
                            Text(
                                formatSchedule(s).text(),
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
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.profile_remove_schedule),
                                )
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
                            Text(stringResource(R.string.profile_add_schedule), modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        SectionTitle(
                            stringResource(R.string.profile_filter_title),
                            stringResource(R.string.profile_filter_help),
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
                                        Text(level.title().text(), style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            level.description().text(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        val included = filter.level != FilterLevel.None
                        SwitchRow(
                            title = stringResource(R.string.profile_safe_search),
                            help = if (included) {
                                stringResource(R.string.profile_safe_search_included)
                            } else {
                                stringResource(R.string.profile_safe_search_help)
                            },
                            checked = filter.safeSearch || included,
                            enabled = !locked && !included,
                            onChange = { filter = filter.copy(safeSearch = it) },
                            modifier = Modifier.padding(top = 8.dp).testTag("safe-search"),
                        )
                    }
                    item {
                        val blocked = "youtube" in filter.services
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.profile_youtube_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    blocked -> stringResource(R.string.profile_youtube_blocked)
                                    filter.level == FilterLevel.Strict && filter.youtube == YoutubeMode.Off ->
                                        stringResource(R.string.profile_youtube_strict_default)
                                    else -> stringResource(R.string.profile_youtube_help)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                YoutubeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = filter.youtube == mode,
                                        onClick = { filter = filter.copy(youtube = mode) },
                                        label = { Text(mode.title().text()) },
                                        enabled = !locked && !blocked,
                                        modifier = Modifier.testTag("youtube-${mode.name}"),
                                    )
                                }
                            }
                        }
                    }
                    item { Text(
                        stringResource(R.string.profile_block_services),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    ) }
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
                            stringResource(R.string.profile_services_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { Text(
                        stringResource(R.string.profile_blocked_sites),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    ) }
                    items(filter.domains, key = { "domain-$it" }) { domain ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { filter = filter.copy(domains = filter.domains - domain) }, enabled = !locked) {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.profile_remove_site, domain),
                                )
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newDomain,
                                onValueChange = { newDomain = it.take(255); domainError = null },
                                label = { Text(stringResource(R.string.profile_add_site)) },
                                placeholder = { Text(stringResource(R.string.profile_site_placeholder)) },
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
                                        domain == null -> domainError = invalidSite
                                        filter.domains.size >= MAX_BLOCKED_DOMAINS -> domainError = maxSites
                                        else -> {
                                            filter = filter.copy(domains = (filter.domains + domain).distinct().sorted())
                                            newDomain = ""
                                        }
                                    }
                                },
                                enabled = newDomain.isNotBlank() && !locked,
                                modifier = Modifier.testTag("add-domain"),
                            ) { Text(stringResource(R.string.profile_add)) }
                        }
                    }
                    if (filter.active && macs.isEmpty()) item {
                        Text(
                            stringResource(R.string.profile_filter_needs_devices),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        SwitchRow(
                            title = stringResource(R.string.profile_protect_router_title),
                            help = stringResource(R.string.profile_protect_router_help),
                            checked = protectRouter,
                            enabled = !locked,
                            onChange = { protectRouter = it },
                            modifier = Modifier.testTag("protect-router"),
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
                            ) { Text(stringResource(R.string.profile_delete)) }
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
            title = { Text(stringResource(R.string.profile_delete_title, initial.name)) },
            text = { Text(stringResource(R.string.profile_delete_help)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.profile_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.profile_cancel)) } },
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
