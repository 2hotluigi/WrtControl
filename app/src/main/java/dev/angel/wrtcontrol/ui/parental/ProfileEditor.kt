package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.MAX_BLOCKED_DOMAINS
import dev.angel.wrtcontrol.parental.MAX_PROFILE_NAME
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.cleanProfileName
import dev.angel.wrtcontrol.parental.newId
import dev.angel.wrtcontrol.parental.normalizeDomain
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.design.ActionRow
import dev.angel.wrtcontrol.ui.design.BarAction
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.CenterTopBar
import dev.angel.wrtcontrol.ui.design.FieldRow
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.SwitchRow
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.rememberVendors
import java.time.DayOfWeek
import java.time.LocalTime

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
    val needsDevices = stringResource(R.string.profile_filter_needs_devices)

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
        Surface(Modifier.fillMaxSize(), color = LocalPalette.current.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                EditorBar(
                    title = stringResource(if (isNew) R.string.profile_new else R.string.profile_edit),
                    canSave = cleanName.isNotEmpty() && !locked,
                    onClose = { if (!locked) onDismiss() },
                    onSave = {
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
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    if (progress != null) item { ApplyingBanner(progress) }
                    error?.let { item { ErrorBanner(it) } }
                    item(key = "name") {
                        GroupCard {
                            FieldRow(
                                stringResource(R.string.profile_name),
                                name,
                                { name = it.take(MAX_PROFILE_NAME) },
                                Modifier.testTag("profile-name"),
                                enabled = !locked,
                                divider = false,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                placeholder = stringResource(R.string.profile_name_placeholder),
                            )
                        }
                    }
                    devicesSection(
                        options,
                        macs,
                        enabled = !locked,
                        manualMac = manualMac,
                        manualError = manualError,
                        onToggle = { mac -> macs = if (mac in macs) macs - mac else macs + mac },
                        onManual = {
                            manualMac = it.take(MAX_MAC_INPUT)
                            manualError = null
                        },
                        onAdd = {
                            val mac = normalizeMac(manualMac)
                            if (mac == null) {
                                manualError = invalidMac
                            } else {
                                macs = macs + mac
                                manualMac = ""
                            }
                        },
                    )
                    schedulesSection(
                        schedules,
                        enabled = !locked,
                        onEdit = { editingSchedule = it },
                        onToggle = { s, on -> schedules = schedules.map { if (it.id == s.id) it.copy(enabled = on) else it } },
                        onRemove = { s -> schedules = schedules.filter { it.id != s.id } },
                        onAdd = {
                            editingSchedule = Schedule(
                                id = newId(takenScheduleIds),
                                days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                                start = LocalTime.of(21, 0),
                                end = LocalTime.of(7, 0),
                            )
                        },
                    )
                    filterSection(filter, enabled = !locked) { filter = it }
                    servicesSection(filter, enabled = !locked) { filter = it }
                    sitesSection(
                        filter.domains,
                        enabled = !locked,
                        newDomain = newDomain,
                        error = domainError,
                        warning = needsDevices.takeIf { filter.active && macs.isEmpty() },
                        onNewDomain = {
                            newDomain = it.take(MAX_DOMAIN_INPUT)
                            domainError = null
                        },
                        onAdd = {
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
                        onRemove = { domain -> filter = filter.copy(domains = filter.domains - domain) },
                    )
                    item(key = "protect") {
                        Column {
                            GroupCard {
                                SwitchRow(
                                    stringResource(R.string.profile_protect_router_title),
                                    checked = protectRouter,
                                    onChange = { protectRouter = it },
                                    modifier = Modifier.testTag("protect-router"),
                                    divider = false,
                                    enabled = !locked,
                                )
                            }
                            GroupFooter(stringResource(R.string.profile_protect_router_help), Modifier.padding(top = 6.dp))
                        }
                    }
                    if (!isNew) item(key = "delete") {
                        ActionRow(stringResource(R.string.profile_delete), destructive = true, enabled = !locked) { confirmDelete = true }
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

/** Fila de la lista de dispositivos del editor. */
internal data class DeviceOption(val mac: String, val title: String, val detail: String, val note: String?)

/** Lo más largo que se deja escribir en el campo de la MAC y en el del sitio. */
private const val MAX_MAC_INPUT = 20
private const val MAX_DOMAIN_INPUT = 255

/** Arriba: cerrar sin guardar, el título y "Guardar". */
@Composable
private fun EditorBar(title: String, canSave: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    val palette = LocalPalette.current
    Box {
        CenterTopBar(title, start = BarAction(R.drawable.ic_close, stringResource(R.string.profile_close_without_saving), onClose))
        Text(
            stringResource(R.string.profile_save),
            style = MaterialTheme.typography.bodyLarge,
            color = if (canSave) palette.accent else palette.tertiary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .clickable(enabled = canSave, onClick = onSave)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("save-profile"),
        )
    }
}
