package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.DeviceIdentity
import dev.angel.wrtcontrol.openwrt.DeviceKind
import dev.angel.wrtcontrol.openwrt.DeviceRecorder
import dev.angel.wrtcontrol.openwrt.DeviceUsage
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.Presence
import dev.angel.wrtcontrol.openwrt.VendorTable
import dev.angel.wrtcontrol.openwrt.identify
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.withoutExpired
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.PillTabs
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.design.Stat
import dev.angel.wrtcontrol.ui.design.StatStrip
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.parental.formatUntil
import dev.angel.wrtcontrol.ui.rememberVendors
import dev.angel.wrtcontrol.ui.watchAsState
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** La lista de equipos cambia despacio. */
internal val DEVICES_REFRESH = 10.seconds

/** Las personas, para decir de quién es cada uno. */
internal val PROFILES_REFRESH = 30.seconds

/** Lo de cada aparato este mes: nlbwmon lo cuenta cada 30 s. */
internal val DEVICE_USAGE_REFRESH = 1.minutes

/** La pestaña Dispositivos: quién hay en casa. Tocar uno abre su ficha ([onOpen], con su MAC). */
@Composable
fun DevicesScreen(onOpen: (String) -> Unit) {
    val repo = LocalRepository.current
    val state by repo.monitor.devices.watchAsState(DEVICES_REFRESH)
    val parental by repo.monitor.parental.watchAsState(PROFILES_REFRESH)
    val usage by repo.monitor.deviceUsage.watchAsState(DEVICE_USAGE_REFRESH)
    val pauses = parental.data?.let { DevicePauses(it.settings.withoutExpired(it.routerNow).devicePauses, it.routerNow) }
    val vendors = rememberVendors()

    LoadableContent(state) { devices, error ->
        DevicesContent(
            devices,
            vendors,
            error,
            parental.data?.profiles.orEmpty(),
            pauses,
            onOpen = { onOpen(it.mac) },
            usage = (usage.data as? DeviceRecorder.Ready)?.takeIf { it.monthly }?.devices?.associateBy { it.mac },
        )
    }
}

/** Los aparatos sueltos sin internet y la hora del router, para decir hasta cuándo. */
data class DevicePauses(val pauses: Map<String, Pause>, val now: LocalDateTime)

/** Cómo se agrupa la lista. */
private enum class Grouping(val label: Int) {
    All(R.string.devices_group_all),
    Person(R.string.devices_group_person),
    Kind(R.string.devices_group_kind),
}

/**
 * La lista: arriba, cuántos hay y cuántos están en línea; debajo, los aparatos por grupos. [usage]:
 * lo de cada uno este mes (MAC en mayúsculas), si el router lo cuenta.
 */
@Composable
fun DevicesContent(
    devices: List<Device>,
    vendors: VendorTable?,
    error: String? = null,
    profiles: List<Profile> = emptyList(),
    pauses: DevicePauses? = null,
    onOpen: ((Device) -> Unit)? = null,
    usage: Map<String, DeviceUsage>? = null,
) {
    val palette = LocalPalette.current
    var grouping by rememberSaveable { mutableIntStateOf(Grouping.All.ordinal) }
    val owners = remember(profiles) { profiles.flatMap { p -> p.macs.map { it to p.name } }.toMap() }
    val known = devices.any { it.presence != Presence.Unknown }
    val online = devices.count { it.online }
    val groups = groupDevices(devices, Grouping.entries[grouping], owners, vendors)

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
        item {
            val offline = (devices.size - online).toString()
            StatStrip(
                listOfNotNull(
                    Stat(stringResource(R.string.devices_stat_total), devices.size.toString()),
                    Stat(stringResource(R.string.devices_stat_online), online.toString(), palette.good).takeIf { known },
                    Stat(stringResource(R.string.devices_stat_offline), offline, palette.tertiary).takeIf { known },
                ),
            )
        }
        if (devices.isEmpty()) {
            item { Text(stringResource(R.string.devices_none), color = palette.secondary, style = MaterialTheme.typography.bodyMedium) }
        } else {
            item {
                val labels = Grouping.entries.map { stringResource(it.label) }
                PillTabs(labels, grouping, onSelect = { grouping = it }, Modifier.testTag("devices-grouping"))
            }
        }
        groups.forEach { (title, members) ->
            item(key = "group-$grouping-$title") {
                Column {
                    SectionHeader(title.text())
                    GroupCard {
                        members.forEachIndexed { i, device ->
                            val paused = pauses?.pauses?.get(device.mac)?.let { pauseText(it, pauses.now) }
                            val month = usage?.get(device.mac.uppercase())
                            DeviceRow(device, identify(device, vendors), owners[device.mac], paused, month, i == members.lastIndex, onOpen)
                        }
                    }
                }
            }
        }
    }
}

/** En línea: el router habló con él hace poco. */
internal val Device.online: Boolean get() = presence == Presence.Online || presence == Presence.Idle

@Composable
private fun DeviceRow(
    device: Device,
    identity: DeviceIdentity,
    owner: String?,
    paused: String?,
    usage: DeviceUsage?,
    last: Boolean,
    onOpen: ((Device) -> Unit)?,
) {
    val palette = LocalPalette.current
    val address = device.ipv4.firstOrNull() ?: device.ipv6.firstOrNull { !it.startsWith("fe80:") }
    // Debajo: la dirección y de quién es o, si no, el fabricante (si el título no lo dice ya).
    val detail = listOfNotNull(address, owner ?: identity.vendor?.takeIf { identity.named }).joinToString(" · ")
    GroupRow(
        identity.title.text(),
        supporting = paused ?: detail.ifEmpty { null },
        value = usage?.let { formatBytes(it.totalBytes) },
        leading = {
            RowIcon(
                identity.kind.icon,
                if (device.presence == Presence.Offline) palette.tertiary else palette.accent,
                badge = when {
                    paused != null -> palette.bad
                    device.online -> palette.good
                    else -> null
                },
            )
        },
        chevron = onOpen != null,
        divider = !last,
        onClick = onOpen?.let { { it(device) } },
        modifier = Modifier.testTag("device-${device.mac}"),
    )
}

/** Un título de grupo: un texto de la app o el nombre de una persona. */
private sealed interface GroupTitle {
    data class Res(val id: Int) : GroupTitle

    data class Name(val name: String) : GroupTitle

    @Composable
    fun text(): String = when (this) {
        is Res -> stringResource(id)
        is Name -> name
    }
}

/** Los grupos de [devices] según [grouping], cada uno con los suyos en el orden de la lista. */
private fun groupDevices(
    devices: List<Device>,
    grouping: Grouping,
    owners: Map<String, String>,
    vendors: VendorTable?,
): List<Pair<GroupTitle, List<Device>>> = when (grouping) {
    Grouping.All -> if (devices.none { it.presence != Presence.Unknown }) {
        listOf(GroupTitle.Res(R.string.devices_section_all) to devices)
    } else {
        listOf(
            GroupTitle.Res(R.string.devices_section_online) to devices.filter { it.online },
            GroupTitle.Res(R.string.devices_section_offline) to devices.filter { !it.online },
        )
    }
    Grouping.Person -> {
        val byOwner = devices.groupBy { owners[it.mac] }
        byOwner.keys.filterNotNull().sorted().map { GroupTitle.Name(it) to byOwner.getValue(it) } +
            listOfNotNull(byOwner[null]?.let { GroupTitle.Res(R.string.devices_section_no_person) to it })
    }
    Grouping.Kind -> DeviceKind.entries.map { kind -> GroupTitle.Res(kind.plural) to devices.filter { identify(it, vendors).kind == kind } }
}.filter { it.second.isNotEmpty() }

@Composable
internal fun pauseText(pause: Pause, now: LocalDateTime): String = when (pause) {
    Pause.Indefinite -> stringResource(R.string.devices_blocked)
    is Pause.Until -> stringResource(R.string.devices_paused_until, formatUntil(pause.end, now).text())
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

/** El nombre del grupo de cada tipo ("Teléfonos"). */
internal val DeviceKind.plural: Int
    get() = when (this) {
        DeviceKind.Phone -> R.string.devices_kind_phone
        DeviceKind.Computer -> R.string.devices_kind_computer
        DeviceKind.Tv -> R.string.devices_kind_tv
        DeviceKind.Network -> R.string.devices_kind_network
        DeviceKind.Smart -> R.string.devices_kind_smart
        DeviceKind.Other -> R.string.devices_kind_other
    }

/** Cómo tiene la dirección: fija, por DHCP (y cuánto le queda) o puesta en el propio aparato. */
@Composable
internal fun leaseText(device: Device): String = when {
    device.fixedIp != null -> stringResource(R.string.devices_fixed_address)
    else -> when (val lease = device.lease) {
        LeaseState.None -> stringResource(if (device.ipv4.isEmpty()) R.string.devices_ipv6_only else R.string.devices_static_ip)
        LeaseState.Permanent -> stringResource(R.string.devices_static_lease)
        is LeaseState.Expires -> stringResource(R.string.devices_lease, formatDuration(lease.seconds))
    }
}
