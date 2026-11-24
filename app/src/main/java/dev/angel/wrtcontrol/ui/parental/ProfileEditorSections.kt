package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.parental.ContentFilter
import dev.angel.wrtcontrol.parental.FilterLevel
import dev.angel.wrtcontrol.parental.SERVICES
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.parental.YoutubeMode
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.design.CheckRow
import dev.angel.wrtcontrol.ui.design.FieldRow
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.PlainSwitch
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.design.SectionHeader

/** La marca de lo elegido en una lista en la que se eligen varios. */
@Composable
private fun Check(on: Boolean) {
    if (on) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = LocalPalette.current.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** "Añadir" al final de un campo: azul si se puede. */
@Composable
private fun AddLink(enabled: Boolean, tag: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        stringResource(R.string.profile_add),
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) palette.accent else palette.tertiary,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** Un pie en rojo: lo que está mal en el campo de encima. */
@Composable
private fun ErrorFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPalette.current.bad,
        modifier = Modifier.padding(start = 16.dp, top = 6.dp),
    )
}

/** Los aparatos de la persona: los que hay en casa, marcados los suyos, y añadir uno por su MAC. */
internal fun LazyListScope.devicesSection(
    options: List<DeviceOption>,
    macs: Set<String>,
    enabled: Boolean,
    manualMac: String,
    manualError: String?,
    onToggle: (String) -> Unit,
    onManual: (String) -> Unit,
    onAdd: () -> Unit,
) = item(key = "devices") {
    Column {
        SectionHeader(stringResource(R.string.profile_devices_title))
        GroupCard {
            if (options.isEmpty()) GroupRow(stringResource(R.string.profile_no_devices), labelColor = LocalPalette.current.secondary)
            options.forEach { option ->
                GroupRow(
                    option.title,
                    supporting = listOfNotNull(option.detail, option.note).joinToString("\n"),
                    supportingLines = 3,
                    onClick = { if (enabled) onToggle(option.mac) },
                    modifier = Modifier.testTag("device-${option.mac}"),
                    trailing = { Check(option.mac in macs) },
                )
            }
            FieldRow(
                stringResource(R.string.profile_mac_label),
                manualMac,
                onManual,
                enabled = enabled,
                divider = false,
                trailing = { AddLink(enabled && manualMac.isNotBlank(), onClick = onAdd) },
            )
        }
        if (manualError != null) {
            ErrorFooter(manualError)
        } else {
            GroupFooter(stringResource(R.string.profile_devices_help), Modifier.padding(top = 6.dp))
        }
    }
}

/** Los horarios sin internet: tocar uno lo edita, el interruptor lo activa y la papelera lo quita. */
internal fun LazyListScope.schedulesSection(
    schedules: List<Schedule>,
    enabled: Boolean,
    onEdit: (Schedule) -> Unit,
    onToggle: (Schedule, Boolean) -> Unit,
    onRemove: (Schedule) -> Unit,
    onAdd: () -> Unit,
) = item(key = "schedules") {
    val palette = LocalPalette.current
    Column {
        SectionHeader(stringResource(R.string.profile_schedules_title))
        GroupCard {
            if (schedules.isEmpty()) GroupRow(stringResource(R.string.profile_no_schedules), labelColor = palette.secondary)
            schedules.forEach { s ->
                GroupRow(
                    formatSchedule(s).text(),
                    labelLines = 2,
                    labelColor = if (s.enabled) null else palette.tertiary,
                    onClick = { if (enabled) onEdit(s) },
                    trailing = {
                        PlainSwitch(s.enabled, { onToggle(s, it) }, enabled = enabled)
                        IconButton(onClick = { onRemove(s) }, enabled = enabled) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.profile_remove_schedule),
                                tint = palette.secondary,
                            )
                        }
                    },
                )
            }
            GroupRow(
                stringResource(R.string.profile_add_schedule),
                leading = { RowIcon(R.drawable.ic_add, palette.accent) },
                labelColor = palette.accent,
                divider = false,
                onClick = { if (enabled) onAdd() },
                modifier = Modifier.testTag("add-schedule"),
            )
        }
        GroupFooter(stringResource(R.string.profile_schedules_help), Modifier.padding(top = 6.dp))
    }
}

/** El filtro: el nivel y la búsqueda segura. */
internal fun LazyListScope.filterSection(filter: ContentFilter, enabled: Boolean, onChange: (ContentFilter) -> Unit) {
    item(key = "filter") {
        Column {
            SectionHeader(stringResource(R.string.profile_filter_title))
            GroupCard {
                FilterLevel.entries.forEachIndexed { i, level ->
                    GroupRow(
                        level.title().text(),
                        supporting = level.description().text(),
                        supportingLines = 3,
                        divider = i < FilterLevel.entries.lastIndex,
                        onClick = { if (enabled) onChange(filter.copy(level = level)) },
                        modifier = Modifier.testTag("filter-${level.name}"),
                        trailing = { Check(filter.level == level) },
                    )
                }
            }
            GroupFooter(stringResource(R.string.profile_filter_help), Modifier.padding(top = 6.dp))
        }
    }
    item(key = "safe-search") {
        val included = filter.level != FilterLevel.None
        Column {
            GroupCard {
                dev.angel.wrtcontrol.ui.design.SwitchRow(
                    stringResource(R.string.profile_safe_search),
                    checked = filter.safeSearch || included,
                    onChange = { onChange(filter.copy(safeSearch = it)) },
                    modifier = Modifier.testTag("safe-search"),
                    divider = false,
                    enabled = enabled && !included,
                )
            }
            GroupFooter(
                stringResource(if (included) R.string.profile_safe_search_included else R.string.profile_safe_search_help),
                Modifier.padding(top = 6.dp),
            )
        }
    }
    item(key = "youtube") { YoutubeGroup(filter, enabled, onChange) }
}

@Composable
private fun YoutubeGroup(filter: ContentFilter, enabled: Boolean, onChange: (ContentFilter) -> Unit) {
    val blocked = "youtube" in filter.services
    Column {
        SectionHeader(stringResource(R.string.profile_youtube_title))
        GroupCard {
            YoutubeMode.entries.forEachIndexed { i, mode ->
                CheckRow(
                    mode.title().text(),
                    selected = filter.youtube == mode,
                    onClick = { onChange(filter.copy(youtube = mode)) },
                    modifier = Modifier.testTag("youtube-${mode.name}"),
                    enabled = enabled && !blocked,
                    divider = i < YoutubeMode.entries.lastIndex,
                )
            }
        }
        GroupFooter(
            when {
                blocked -> stringResource(R.string.profile_youtube_blocked)
                filter.level == FilterLevel.Strict && filter.youtube == YoutubeMode.Off ->
                    stringResource(R.string.profile_youtube_strict_default)
                else -> stringResource(R.string.profile_youtube_help)
            },
            Modifier.padding(top = 6.dp),
        )
    }
}

/** Servicios enteros que se bloquean (TikTok, juegos...), marcados los elegidos. */
internal fun LazyListScope.servicesSection(
    filter: ContentFilter,
    enabled: Boolean,
    onChange: (ContentFilter) -> Unit,
) = item(key = "services") {
    Column {
        SectionHeader(stringResource(R.string.profile_block_services))
        GroupCard {
            SERVICES.forEachIndexed { i, service ->
                val selected = service.id in filter.services
                GroupRow(
                    service.name,
                    divider = i < SERVICES.lastIndex,
                    onClick = {
                        val services = if (selected) filter.services - service.id else filter.services + service.id
                        if (enabled) onChange(filter.copy(services = services))
                    },
                    modifier = Modifier.testTag("service-${service.id}"),
                    trailing = { Check(selected) },
                )
            }
        }
        GroupFooter(stringResource(R.string.profile_services_help), Modifier.padding(top = 6.dp))
    }
}

/** Sitios sueltos que se bloquean, y añadir otro. [warning]: lo que hay que saber debajo. */
internal fun LazyListScope.sitesSection(
    domains: List<String>,
    enabled: Boolean,
    newDomain: String,
    error: String?,
    warning: String?,
    onNewDomain: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) = item(key = "sites") {
    val palette = LocalPalette.current
    Column {
        SectionHeader(stringResource(R.string.profile_blocked_sites))
        GroupCard {
            domains.forEach { domain ->
                GroupRow(
                    domain,
                    trailing = {
                        IconButton(onClick = { onRemove(domain) }, enabled = enabled) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.profile_remove_site, domain),
                                tint = palette.secondary,
                            )
                        }
                    },
                )
            }
            FieldRow(
                stringResource(R.string.profile_site_label),
                newDomain,
                onNewDomain,
                Modifier.testTag("new-domain"),
                enabled = enabled,
                divider = false,
                trailing = { AddLink(enabled && newDomain.isNotBlank(), "add-domain", onAdd) },
            )
        }
        when {
            error != null -> ErrorFooter(error)
            warning != null -> GroupFooter(warning, Modifier.padding(top = 6.dp))
        }
    }
}
