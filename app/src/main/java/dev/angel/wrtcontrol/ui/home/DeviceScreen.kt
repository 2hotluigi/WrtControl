package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.DeviceIdentity
import dev.angel.wrtcontrol.openwrt.DeviceRecorder
import dev.angel.wrtcontrol.openwrt.DeviceUsage
import dev.angel.wrtcontrol.openwrt.Presence
import dev.angel.wrtcontrol.openwrt.identify
import dev.angel.wrtcontrol.openwrt.remembered
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.withoutExpired
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangePreviewHost
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.CircleAction
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.design.Stat
import dev.angel.wrtcontrol.ui.design.StatStrip
import dev.angel.wrtcontrol.ui.design.SwitchRow
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.parental.phoneAddresses
import dev.angel.wrtcontrol.ui.rememberVendors
import dev.angel.wrtcontrol.ui.watchAsState

/** Los ratos sin internet que se ofrecen, con su nombre corto. */
private val PAUSE_MINUTES = listOf(30L to R.string.device_pause_30, 60L to R.string.device_pause_60, 120L to R.string.device_pause_120)

/** La ficha de un aparato ([mac]): una pantalla, encima de la lista. [onGone]: ya no está en la lista. */
@Composable
fun DeviceScreen(address: String, mac: String) {
    val repo = LocalRepository.current
    val vm: DevicesViewModel = viewModel(key = "devices:$address") { DevicesViewModel(repo, repo.monitor) }
    val state by repo.monitor.devices.watchAsState(DEVICES_REFRESH)
    val parental by repo.monitor.parental.watchAsState(PROFILES_REFRESH)
    val usage by repo.monitor.deviceUsage.watchAsState(DEVICE_USAGE_REFRESH)
    val vendors = rememberVendors()
    val locked = LocalAppLocked.current
    val context = LocalContext.current

    LoadableContent(state) { devices, loadError ->
        val device = devices.firstOrNull { it.mac == mac }
        if (device == null) {
            Text(stringResource(R.string.device_gone), color = LocalPalette.current.secondary, modifier = Modifier.padding(24.dp))
            return@LoadableContent
        }
        // Sin remember: si el teléfono cambia de IP, se nota. Los que solo se recuerdan no cuentan:
        // su dirección guardada puede ser ahora la de este teléfono.
        val ips = phoneAddresses(context)
        val phoneMacs = devices.filter { d -> !d.remembered && d.ipv4.any { it in ips } }.map { it.mac }.toSet()
        val profiles = parental.data?.profiles.orEmpty()
        val canAssign = parental.data?.newerSchema == false
        val pauses = parental.data?.let { DevicePauses(it.settings.withoutExpired(it.routerNow).devicePauses, it.routerNow) }
        val identity = identify(device, vendors)
        val thisPhone = device.mac in phoneMacs
        DeviceDetail(
            device = device,
            identity = identity,
            profiles = profiles,
            canAssign = canAssign,
            progress = vm.progress,
            error = vm.error?.text() ?: loadError,
            usage = (usage.data as? DeviceRecorder.Ready)?.takeIf { it.monthly }?.let { ready ->
                ready.devices.firstOrNull { it.mac == device.mac.uppercase() } ?: DeviceUsage(device.mac, 0, 0)
            },
            // Con el control parental a la vista y sin ser este teléfono: a sí mismo no se corta.
            internet = pauses?.takeIf { canAssign && !thisPhone }?.let { p ->
                InternetControl(
                    status = p.pauses[device.mac]?.let { pauseText(it, p.now) },
                    // Un rato sin internet se pasa solo; bloquearlo dura hasta que alguien lo quite: se avisa antes.
                    onPause = { minutes ->
                        val preview = if (minutes == null) blockPreview(identity.title) else null
                        vm.pause(device, minutes, phoneMacs, preview) {}
                    },
                    onResume = { vm.resume(device, phoneMacs) {} },
                )
            },
            thisPhone = thisPhone,
            enabled = !locked,
            onSave = { change -> vm.save(device, change, phoneMacs, devicePreview(identity.title, device, change, profiles)) {} },
        )
    }
    ChangePreviewHost(vm.proposals)
}

/**
 * La ficha con lo ya leído: cómo está, lo que ha gastado este mes, su internet y sus datos. El
 * nombre y la persona se cambian desde su fila; la dirección fija, con su interruptor. Cada cambio
 * se guarda al momento ([onSave]).
 */
@Composable
fun DeviceDetail(
    device: Device,
    identity: DeviceIdentity,
    profiles: List<Profile>,
    canAssign: Boolean,
    progress: ChangeProgress?,
    error: String?,
    usage: DeviceUsage?,
    internet: InternetControl?,
    thisPhone: Boolean,
    enabled: Boolean = true,
    onSave: (DeviceChange) -> Unit = {},
) {
    val palette = LocalPalette.current
    val owner = profiles.firstOrNull { device.mac in it.macs }
    val address = device.fixedIp ?: device.ipv4.firstOrNull()
    val free = enabled && progress == null
    var editingName by rememberSaveable(device.mac) { mutableStateOf(false) }
    var choosingOwner by rememberSaveable(device.mac) { mutableStateOf(false) }
    // Cada cambio deja lo demás como está.
    val current = DeviceChange(label = device.label, fixedIp = device.fixedIp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        Banners(error, progress)
        Header(device, identity, owner?.name)
        usage?.let { UsageStats(it) }
        if (internet != null || thisPhone) InternetSection(internet, free)
        Details(
            device,
            identity,
            owner?.name,
            address,
            ownerRow = if (canAssign) profiles.isNotEmpty() else null,
            onName = { if (free) editingName = true },
            onOwner = { if (free) choosingOwner = true },
        )
        FixedAddress(device, address) { on -> if (free && address != null) onSave(current.copy(fixedIp = address.takeIf { on })) }
    }
    EditDialogs(
        label = device.label.orEmpty(),
        profiles = profiles,
        owner = owner?.id.orEmpty(),
        editingName = editingName,
        choosingOwner = choosingOwner,
        onClose = {
            editingName = false
            choosingOwner = false
        },
        // Del diálogo del nombre llega "" para quitar el que puso alguien; null, que no cambia.
        onSave = { change ->
            val label = when (val name = change.label) {
                null -> current.label
                "" -> null
                else -> name
            }
            onSave(current.copy(label = label, owner = change.owner))
        },
    )
}

@Composable
private fun Banners(error: String?, progress: ChangeProgress?) {
    error?.let { ErrorBanner(it) }
    progress?.let { ApplyingBanner(it) }
}

/** Lo que ha gastado este mes: el total, lo recibido y lo enviado. */
@Composable
private fun UsageStats(usage: DeviceUsage) {
    StatStrip(
        listOf(
            Stat(stringResource(R.string.device_stat_month), formatBytes(usage.totalBytes)),
            Stat(stringResource(R.string.usage_received), formatBytes(usage.receivedBytes)),
            Stat(stringResource(R.string.usage_sent), formatBytes(usage.sentBytes)),
        ),
        Modifier.testTag("device-usage"),
    )
}

/**
 * Nombre, persona y lo que lo identifica. [ownerRow]: si sale la fila de la persona (null: no) y si
 * hay personas a las que asignarlo.
 */
@Composable
private fun Details(
    device: Device,
    identity: DeviceIdentity,
    owner: String?,
    address: String?,
    ownerRow: Boolean?,
    onName: () -> Unit,
    onOwner: () -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.device_section_details))
        GroupCard {
            GroupRow(
                stringResource(R.string.device_row_name),
                value = identity.title.text(),
                chevron = true,
                onClick = onName,
                modifier = Modifier.testTag("device-name-row"),
            )
            if (ownerRow != null) {
                GroupRow(
                    stringResource(R.string.device_row_person),
                    value = owner ?: stringResource(R.string.devices_edit_person_none),
                    supporting = stringResource(R.string.devices_edit_no_profiles).takeIf { !ownerRow },
                    chevron = ownerRow,
                    onClick = { if (ownerRow) onOwner() },
                    modifier = Modifier.testTag("device-person-row"),
                )
            }
            address?.let { GroupRow(stringResource(R.string.device_row_ip), value = it) }
            GroupRow(stringResource(R.string.device_row_mac), value = device.mac, divider = identity.vendor != null)
            identity.vendor?.let { GroupRow(stringResource(R.string.device_row_vendor), value = it, divider = false) }
        }
        if (identity.privateMac) GroupFooter(stringResource(R.string.devices_private_mac_hint), Modifier.padding(top = 6.dp))
    }
}

/** Que el router le dé siempre [address]; sin dirección de casa, no se puede. */
@Composable
private fun FixedAddress(device: Device, address: String?, onChange: (Boolean) -> Unit) {
    Column {
        GroupCard {
            SwitchRow(
                stringResource(R.string.devices_edit_fixed_title),
                checked = device.fixedIp != null,
                onChange = onChange,
                divider = false,
                supporting = address?.let { stringResource(R.string.device_fixed_supporting, it) },
            )
        }
        GroupFooter(
            stringResource(if (address != null) R.string.device_fixed_footer else R.string.devices_edit_fixed_unavailable),
            Modifier.padding(top = 6.dp),
        )
    }
}

/** El icono del aparato en grande y cómo está ("En línea · de Mari"). */
@Composable
private fun Header(device: Device, identity: DeviceIdentity, owner: String?) {
    val palette = LocalPalette.current
    val online = device.online
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(palette.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(identity.kind.icon), contentDescription = null, tint = palette.accent, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(10.dp))
        val presence = when (device.presence) {
            Presence.Online -> stringResource(R.string.devices_presence_online)
            Presence.Idle -> stringResource(R.string.devices_presence_idle)
            Presence.Offline -> stringResource(R.string.devices_presence_offline)
            Presence.Unknown -> null
        }
        val line = listOfNotNull(presence, owner?.let { stringResource(R.string.devices_owner, it) }).joinToString(" · ")
        if (line.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (presence != null) Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) palette.good else palette.tertiary))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
            }
        }
    }
}

/** Cortado: cómo está y el control para devolverle internet. Con internet: un rato sin él, o bloquearlo. */
@Composable
private fun InternetSection(internet: InternetControl?, enabled: Boolean) {
    val palette = LocalPalette.current
    Column {
        SectionHeader(stringResource(R.string.devices_internet_title))
        if (internet == null) {
            GroupCard { GroupRow(stringResource(R.string.devices_internet_this_phone), divider = false) }
            return@Column
        }
        GroupCard {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                val status = internet.status
                if (status != null) {
                    CircleAction(
                        R.drawable.ic_pause,
                        stringResource(R.string.devices_internet_resume),
                        status,
                        active = true,
                        tint = palette.bad,
                        modifier = Modifier.weight(1f).testTag("device-resume"),
                        onClick = { if (enabled) internet.onResume() },
                    )
                } else {
                    PAUSE_MINUTES.forEach { (minutes, label) ->
                        CircleAction(
                            R.drawable.ic_timer,
                            stringResource(label),
                            stringResource(R.string.device_action_pause),
                            active = false,
                            modifier = Modifier.weight(1f).testTag("device-pause-$minutes"),
                            onClick = { if (enabled) internet.onPause(minutes) },
                        )
                    }
                    CircleAction(
                        R.drawable.ic_block,
                        stringResource(R.string.devices_internet_block),
                        stringResource(R.string.device_action_block_caption),
                        active = false,
                        modifier = Modifier.weight(1f).testTag("device-block"),
                        onClick = { if (enabled) internet.onPause(null) },
                    )
                }
            }
        }
        GroupFooter(stringResource(R.string.devices_internet_help), Modifier.padding(top = 6.dp))
    }
}
