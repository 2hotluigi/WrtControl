package dev.angel.wrtcontrol.ui.addons

import dev.angel.wrtcontrol.text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.addons.Addon
import dev.angel.wrtcontrol.addons.AddonStatus
import dev.angel.wrtcontrol.addons.DDNS_SECTIONS
import dev.angel.wrtcontrol.addons.DdnsEdit
import dev.angel.wrtcontrol.addons.DdnsProvider
import dev.angel.wrtcontrol.addons.DdnsService
import dev.angel.wrtcontrol.addons.IpFamily
import dev.angel.wrtcontrol.addons.RemotePeer
import dev.angel.wrtcontrol.addons.SqmEdit
import dev.angel.wrtcontrol.addons.SqmQueue
import dev.angel.wrtcontrol.addons.UpnpMapping
import dev.angel.wrtcontrol.addons.UpnpState
import dev.angel.wrtcontrol.addons.VPN_INTERFACE
import dev.angel.wrtcontrol.addons.WireGuardInterface
import dev.angel.wrtcontrol.addons.ddnsEditWith
import dev.angel.wrtcontrol.addons.ddnsProblem
import dev.angel.wrtcontrol.addons.fullDomain
import dev.angel.wrtcontrol.addons.sqmProblem
import dev.angel.wrtcontrol.data.Link
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.StatusPill
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.parental.DismissibleError
import dev.angel.wrtcontrol.ui.parental.SwitchRow
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun AddonsScreen(address: String) {
    val repo = LocalRepository.current
    val vm: AddonsViewModel = viewModel(key = "addons:$address") { AddonsViewModel(repo) }
    val outsideVm: OutsideCheckViewModel = viewModel(key = "outside:$address") { OutsideCheckViewModel(repo) }
    val state by vm.state.collectAsStateWithLifecycle()
    val locked = LocalAppLocked.current
    val link = repo.transport?.link?.collectAsStateWithLifecycle()?.value ?: Link.Direct

    var installing by remember { mutableStateOf<Addon?>(null) }
    var editingSqm by remember { mutableStateOf<SqmQueue?>(null) }
    var newSqm by remember { mutableStateOf(false) }
    var removingPeer by remember { mutableStateOf<RemotePeer?>(null) }
    var editingDdns by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LoadableContent(state) { data, loadError ->
            val s = data.state
            val busy = vm.busy != null
            val tool = data.packages?.tool
            fun status(addon: Addon) = s.status(addon, data.packages)

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loadError != null) item { ErrorBanner(stringResource(R.string.addons_stale_data, loadError)) }
                vm.busy?.let { item { BusyBanner(it) } }
                if (installing == null && editingSqm == null && !newSqm && !editingDdns) {
                    vm.error?.let { e -> item { DismissibleError(e.text()) { vm.error = null } } }
                    vm.notice?.let { notice ->
                        item {
                            NoticeCard(stringResource(R.string.addons_ddns_title), notice) {
                                TextButton(onClick = { vm.notice = null }) { Text(stringResource(R.string.addons_got_it)) }
                            }
                        }
                    }
                }
                if (tool == null) item {
                    NoticeCard(
                        stringResource(R.string.addons_no_installer_title),
                        stringResource(R.string.addons_no_installer_body),
                    )
                }

                item { SectionTitle(stringResource(R.string.addons_remote_title)) }
                item {
                    AddonCard(Addon.WireGuard, status(Addon.WireGuard), canInstall = tool != null, busy = busy,
                        onInstall = { vm.error = null; installing = Addon.WireGuard }, onFinish = vm::finishWireGuard) {
                        RemoteAccessContent(data, busy, onEnable = { vm.enableRemoteAccess(phoneName()) }, onRemove = { vm.error = null; removingPeer = it })
                        if (data.phoneHasAccess && outsideVm.available) {
                            OutsideCheckContent(
                                check = outsideVm.check,
                                busy = busy,
                                onCheck = { outsideVm.run(data) },
                                onPublishOnly = { families ->
                                    ddnsEditWith(s, families)?.let { vm.saveDdns(it) { outsideVm.check = null } }
                                },
                            )
                        }
                        WireGuardContent(s.wireguard.orEmpty().filter { it.name != VPN_INTERFACE })
                    }
                }
                item {
                    AddonCard(Addon.Ddns, status(Addon.Ddns), canInstall = tool != null, busy = busy,
                        onInstall = { installing = Addon.Ddns }, onFinish = {}) {
                        DdnsContent(
                            services = s.ddns.orEmpty(),
                            publicAddresses = s.publicAddresses,
                            remote = s.remote != null,
                            busy = busy,
                            onEdit = { vm.error = null; editingDdns = true },
                            onRemove = vm::removeDdns,
                        )
                    }
                }

                item { SectionTitle(stringResource(R.string.addons_home_network_title)) }
                item {
                    AddonCard(Addon.Sqm, status(Addon.Sqm), canInstall = tool != null, busy = busy,
                        onInstall = { installing = Addon.Sqm }, onFinish = {}) {
                        SqmContent(
                            queues = s.sqm.orEmpty(),
                            internetDevice = s.internetDevice,
                            internetDevices = s.internetDevices,
                            busy = busy,
                            onToggle = { q, on -> vm.saveSqm(SqmEdit(q.section, on, q.device, q.downloadKbit, q.uploadKbit)) },
                            onEdit = { vm.error = null; editingSqm = it },
                            onCreate = { vm.error = null; newSqm = true },
                        )
                    }
                }
                item {
                    AddonCard(Addon.Upnp, status(Addon.Upnp), canInstall = tool != null, busy = busy,
                        onInstall = { installing = Addon.Upnp }, onFinish = {}) {
                        s.upnp?.let { UpnpContent(it, s.internetIpv4.takeIf { s.behindNat }, busy, vm::setUpnp, vm::setUpnpSecure, vm::closeMapping) }
                    }
                }
            }

            if (!locked) {
                installing?.let { addon ->
                    InstallDialog(
                        addon = addon,
                        freeKiB = s.freeKiB,
                        onDismiss = { installing = null },
                        onConfirm = {
                            installing = null
                            tool?.let { vm.install(addon, it) }
                        },
                    )
                }
                if (editingDdns) {
                    val own = s.ownDdns.firstOrNull()
                    val section = s.ddnsConfig.orEmpty().firstOrNull { it.name in DDNS_SECTIONS }
                    DdnsDialog(
                        current = own,
                        currentToken = section?.text("password").orEmpty(),
                        currentUrl = section?.text("update_url").orEmpty(),
                        currentUsername = section?.text("username")?.takeIf { it != own?.domain }.orEmpty(),
                        publicAddresses = s.publicAddresses,
                        busy = busy,
                        error = vm.error?.text(),
                        onDismiss = { if (!busy) { editingDdns = false; vm.error = null } },
                        onSave = { edit -> vm.saveDdns(edit) { editingDdns = false } },
                    )
                }
                removingPeer?.let { peer ->
                    val mine = data.tunnel?.peerId == peer.id
                    AlertDialog(
                        onDismissRequest = { removingPeer = null },
                        title = { Text(stringResource(R.string.addons_remove_remote_title)) },
                        text = {
                            Text(
                                if (mine) stringResource(R.string.addons_remove_remote_this_phone)
                                else stringResource(R.string.addons_remove_remote_peer, peer.name),
                            )
                        },
                        confirmButton = {
                            Button(onClick = { removingPeer = null; vm.removeRemotePeer(peer) }) {
                                Text(stringResource(R.string.addons_remove))
                            }
                        },
                        dismissButton = { TextButton(onClick = { removingPeer = null }) { Text(stringResource(R.string.addons_cancel)) } },
                    )
                }
                if (newSqm || editingSqm != null) {
                    SqmDialog(
                        queue = editingSqm,
                        devices = (s.devices + s.internetDevices).distinct(),
                        internetDevice = s.internetDevice,
                        busy = busy,
                        error = vm.error?.text(),
                        onDismiss = { if (!busy) { editingSqm = null; newSqm = false; vm.error = null } },
                        onSave = { edit -> vm.saveSqm(edit) { editingSqm = null; newSqm = false } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun BusyBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AddonCard(
    addon: Addon,
    status: AddonStatus,
    canInstall: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onFinish: () -> Unit,
    content: @Composable () -> Unit,
) {
    val (label, ok) = when (status) {
        AddonStatus.Installed -> stringResource(R.string.addons_status_installed) to true
        AddonStatus.Missing -> stringResource(R.string.addons_status_missing) to null
        AddonStatus.Partial -> stringResource(R.string.addons_status_partial) to false
        AddonStatus.NeedsRestart -> stringResource(R.string.addons_status_needs_restart) to false
    }
    InfoCard(addon.title, modifier = Modifier.testTag("addon-${addon.name}"), trailing = { StatusPill(label, ok) }) {
        Text(addon.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (status) {
            AddonStatus.Installed -> content()
            AddonStatus.Missing -> Button(onClick = onInstall, enabled = canInstall && !busy, modifier = Modifier.testTag("install-${addon.name}")) {
                Text(stringResource(R.string.addons_install))
            }
            AddonStatus.Partial -> {
                Text(
                    stringResource(R.string.addons_partial_help, addon.luciPackage),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onInstall, enabled = canInstall && !busy) { Text(stringResource(R.string.addons_complete_install)) }
            }
            AddonStatus.NeedsRestart -> {
                Text(
                    stringResource(R.string.addons_needs_restart_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onFinish, enabled = !busy) { Text(stringResource(R.string.addons_restart_network)) }
            }
        }
    }
}

@Composable
private fun InstallDialog(addon: Addon, freeKiB: Long?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.addons_install_title, addon.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.addons_install_download, addon.luciPackage))
                freeKiB?.let { Text(stringResource(R.string.addons_install_free_space, formatBytes(it * 1024))) }
                if (addon.restartsNetwork) {
                    Text(stringResource(R.string.addons_install_restarts_network))
                }
                Text(
                    stringResource(R.string.addons_install_firmware_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(
            onClick = onConfirm,
            modifier = Modifier.testTag("confirm-install"),
        ) { Text(stringResource(R.string.addons_install)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.addons_cancel)) } },
    )
}

// ---------- WireGuard ----------

/** Nombre con el que este teléfono aparece en el router. */
private fun phoneName(): String =
    listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.distinct()
        .joinToString(" ").replaceFirstChar { it.uppercase() }

@Composable
private fun OutsideCheckContent(
    check: OutsideCheck?,
    busy: Boolean,
    onCheck: () -> Unit,
    onPublishOnly: (Set<IpFamily>) -> Unit,
) {
    val running = check == OutsideCheck.Running
    Text(
        stringResource(R.string.addons_outside_check_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCheck, enabled = !running, modifier = Modifier.testTag("check-outside")) {
        Text(stringResource(if (running) R.string.addons_outside_checking else R.string.addons_outside_check))
    }
    when (check) {
        is OutsideCheck.Failed -> Text(check.message.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is OutsideCheck.Done -> {
            val advice = check.advice
            val (headline, color) = when {
                advice.ok -> stringResource(R.string.addons_outside_ok) to MaterialTheme.colorScheme.primary
                advice.untested -> stringResource(R.string.addons_outside_untested) to MaterialTheme.colorScheme.onSurface
                else -> stringResource(R.string.addons_outside_fail) to MaterialTheme.colorScheme.error
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium, color = color)
            advice.lines.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
            advice.publishOnly?.let { families ->
                val and = stringResource(R.string.addons_and)
                val names = families.sorted().joinToString(and) { if (it == IpFamily.V6) "IPv6" else "IPv4" }
                Button(onClick = { onPublishOnly(families) }, enabled = !busy) { Text(stringResource(R.string.addons_publish_only, names)) }
            }
        }
        else -> {}
    }
}

@Composable
private fun RemoteAccessContent(data: AddonsData, busy: Boolean, onEnable: () -> Unit, onRemove: (RemotePeer) -> Unit) {
    val s = data.state
    val remote = s.remote
    val live = s.wireguard.orEmpty().firstOrNull { it.name == VPN_INTERFACE }
    val now = System.currentTimeMillis() / 1000
    HorizontalDivider()
    Text(stringResource(R.string.addons_remote_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.addons_remote_tunnel_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
        s.publicAddresses.isEmpty() -> Text(
            stringResource(R.string.addons_remote_no_public_address),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        s.publicAddresses.none { '.' in it } -> Text(
            stringResource(R.string.addons_remote_ipv6_only, s.publicAddresses.first()),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (remote != null) {
        Text(
            stringResource(if (live?.up == false) R.string.addons_remote_port_down else R.string.addons_remote_port, remote.port),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            s.ddnsDomain?.let { stringResource(R.string.addons_remote_lookup_domain, it) }
                ?: stringResource(R.string.addons_remote_no_ddns),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.ddnsDomain == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (remote.foreignPeers.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.addons_remote_foreign_peers,
                    remote.foreignPeers.size,
                    remote.foreignPeers.size,
                    remote.foreignPeers.joinToString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        remote.peers.forEach { peer ->
            val status = when {
                live == null || !live.liveKnown -> null
                else -> live.peers.firstOrNull { it.publicKey == peer.publicKey }?.lastHandshake
                    ?.let { stringResource(R.string.addons_connected_ago, formatDuration((now - it).coerceAtLeast(0))) }
                    ?: stringResource(R.string.addons_not_connected_yet)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (data.tunnel?.peerId == peer.id) stringResource(R.string.addons_peer_this_phone, peer.name) else peer.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(listOfNotNull(peer.address, status).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(peer) }, enabled = !busy) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.addons_remove_remote_of, peer.name),
                    )
                }
            }
        }
    }
    if (!data.phoneHasAccess) {
        Button(onClick = onEnable, enabled = !busy, modifier = Modifier.testTag("enable-remote")) {
            Text(stringResource(if (remote == null) R.string.addons_enable_remote else R.string.addons_add_this_phone))
        }
    }
}

@Composable
private fun WireGuardContent(interfaces: List<WireGuardInterface>) {
    if (interfaces.isEmpty()) return
    val now = System.currentTimeMillis() / 1000
    HorizontalDivider()
    Text(stringResource(R.string.addons_other_tunnels), style = MaterialTheme.typography.titleSmall)
    val notUp = stringResource(R.string.addons_not_up)
    interfaces.forEach { iface ->
        val port = iface.listenPort?.let { stringResource(R.string.addons_port, it) }
        Text(
            buildString {
                append(iface.name)
                port?.let { append(" · $it") }
                if (!iface.up) append(" · $notUp")
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (iface.addresses.isNotEmpty()) Text(iface.addresses.joinToString(), style = MaterialTheme.typography.bodySmall)
        if (iface.peers.isEmpty()) Text(stringResource(R.string.addons_no_peers), style = MaterialTheme.typography.bodySmall)
        iface.peers.forEach { peer ->
            val seen = if (!iface.liveKnown) null else
                peer.lastHandshake?.let { stringResource(R.string.addons_connected_ago, formatDuration((now - it).coerceAtLeast(0))) }
                    ?: stringResource(R.string.addons_never_connected)
            Text(
                peer.description.ifBlank { peer.publicKey.take(8) + "…" } + (seen?.let { ": $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------- DNS dinámico ----------

@Composable
private fun DdnsContent(
    services: List<DdnsService>,
    publicAddresses: List<String>,
    remote: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val owned = services.filter { it.own }
    val own = owned.firstOrNull()
    if (own == null) {
        Text(
            stringResource(if (remote) R.string.addons_ddns_needed_remote else R.string.addons_ddns_help),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onEdit,
            enabled = !busy,
            modifier = Modifier.testTag("ddns-configure"),
        ) { Text(stringResource(R.string.addons_configure)) }
    } else {
        HorizontalDivider()
        Text(own.domain, style = MaterialTheme.typography.titleSmall)
        Text(
            own.provider?.title ?: own.serviceName ?: stringResource(R.string.addons_ddns_custom_provider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        owned.sortedBy { it.ipv6 }.forEach { svc ->
            val family = if (svc.ipv6) "IPv6" else "IPv4"
            val lastUpdate = svc.lastUpdate
            val lastSentIp = svc.lastSentIp
            val update = when {
                lastUpdate == null -> stringResource(R.string.addons_ddns_not_updated)
                lastSentIp != null -> stringResource(R.string.addons_ddns_updated_ip, lastUpdate, lastSentIp)
                else -> stringResource(R.string.addons_ddns_updated, lastUpdate)
            }
            Text(
                stringResource(if (svc.running) R.string.addons_ddns_status else R.string.addons_ddns_status_stopped, family, update),
                style = MaterialTheme.typography.bodyMedium,
            )
            svc.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (svc.ipv6 && publicAddresses.none { ':' in it }) {
                Text(
                    stringResource(R.string.addons_ddns_no_public_ipv6),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.addons_change)) }
            TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.addons_remove)) }
        }
    }
    val others = services.filter { !it.own && it.enabled }
    if (others.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.addons_ddns_luci_title), style = MaterialTheme.typography.titleSmall)
        val customShort = stringResource(R.string.addons_ddns_custom_short)
        others.forEach { Text(it.domain + " · " + (it.serviceName ?: customShort), style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DdnsDialog(
    current: DdnsService?,
    currentToken: String,
    currentUrl: String,
    currentUsername: String,
    publicAddresses: List<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (DdnsEdit) -> Unit,
) {
    var provider by remember { mutableStateOf(current?.provider ?: DdnsProvider.DuckDns) }
    var domain by remember { mutableStateOf(current?.domain.orEmpty()) }
    var token by remember { mutableStateOf(currentToken) }
    var url by remember { mutableStateOf(currentUrl) }
    var username by remember { mutableStateOf(currentUsername) }
    val edit = DdnsEdit(
        provider, domain, token,
        updateUrl = url.takeIf { provider == DdnsProvider.Custom },
        username = username.takeIf { provider == DdnsProvider.Custom && it.isNotBlank() },
    )
    val problem = ddnsProblem(edit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.addons_ddns_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DdnsProvider.entries.forEach { p ->
                        FilterChip(selected = p == provider, onClick = { provider = p }, label = { Text(p.title) })
                    }
                }
                Text(provider.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = domain, onValueChange = { domain = it }, singleLine = true,
                    label = { Text(stringResource(R.string.addons_ddns_name)) },
                    supportingText = {
                        if (domain.isNotBlank() && problem == null) Text(edit.fullDomain())
                        else provider.suffix?.let { Text(stringResource(R.string.addons_ddns_example, it)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("ddns-domain"),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text(stringResource(R.string.addons_ddns_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("ddns-token"),
                )
                if (provider == DdnsProvider.Custom) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text(stringResource(R.string.addons_ddns_update_url)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username, onValueChange = { username = it }, singleLine = true,
                        label = { Text(stringResource(R.string.addons_ddns_username)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val v4 = publicAddresses.any { '.' in it }
                val v6 = publicAddresses.any { ':' in it }
                Text(
                    stringResource(
                        when {
                            v4 && v6 -> R.string.addons_ddns_publish_both
                            v4 -> R.string.addons_ddns_publish_v4
                            else -> R.string.addons_ddns_publish_v6
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (domain.isNotBlank() && token.isNotBlank()) problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(edit) }, enabled = problem == null && !busy, modifier = Modifier.testTag("ddns-save")) {
                Text(stringResource(if (busy) R.string.addons_applying else R.string.addons_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.addons_cancel)) } },
    )
}

// ---------- SQM ----------

@Composable
private fun SqmContent(
    queues: List<SqmQueue>,
    internetDevice: String?,
    internetDevices: Set<String>,
    busy: Boolean,
    onToggle: (SqmQueue, Boolean) -> Unit,
    onEdit: (SqmQueue) -> Unit,
    onCreate: () -> Unit,
) {
    if (queues.isEmpty()) {
        Text(stringResource(R.string.addons_not_configured), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCreate, enabled = !busy) { Text(stringResource(R.string.addons_configure)) }
        return
    }
    queues.forEach { q ->
        HorizontalDivider()
        SwitchRow(
            title = q.device.ifBlank { q.section },
            help = stringResource(R.string.addons_sqm_summary, formatMbit(q.downloadKbit), formatMbit(q.uploadKbit), q.qdisc),
            checked = q.enabled,
            enabled = !busy,
            onChange = { onToggle(q, it) },
            modifier = Modifier.testTag("sqm-${q.section}"),
        )
        if (internetDevice != null && q.device !in internetDevices) {
            Text(
                stringResource(
                    R.string.addons_sqm_wrong_device,
                    q.device.ifBlank { stringResource(R.string.addons_sqm_no_device) },
                    internetDevice,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = { onEdit(q) }, enabled = !busy) { Text(stringResource(R.string.addons_edit)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SqmDialog(
    queue: SqmQueue?,
    devices: List<String>,
    internetDevice: String?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (SqmEdit) -> Unit,
) {
    var device by remember { mutableStateOf(queue?.device?.takeIf { it.isNotBlank() } ?: internetDevice ?: devices.firstOrNull().orEmpty()) }
    var down by remember { mutableStateOf(queue?.downloadKbit?.let(::formatMbitNumber).orEmpty()) }
    var up by remember { mutableStateOf(queue?.uploadKbit?.let(::formatMbitNumber).orEmpty()) }
    val enabled = queue?.enabled ?: true
    val edit = SqmEdit(queue?.section, enabled, device, parseMbit(down) ?: -1, parseMbit(up) ?: -1)
    val problem = when {
        listOf(down, up).any { THOUSANDS.matches(it.trim()) } -> stringResource(R.string.addons_sqm_no_thousands)
        parseMbit(down) == null || parseMbit(up) == null -> stringResource(R.string.addons_sqm_both_speeds)
        else -> sqmProblem(edit)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (queue == null) R.string.addons_sqm_configure_title else R.string.addons_sqm_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.addons_sqm_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.addons_sqm_internet_connection), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(device) + devices).filter { it.isNotBlank() }.distinct().forEach { d ->
                        FilterChip(
                            selected = d == device,
                            onClick = { device = d },
                            label = { Text(if (d == internetDevice) stringResource(R.string.addons_sqm_device_internet, d) else d) },
                        )
                    }
                }
                OutlinedTextField(
                    value = down, onValueChange = { down = it }, singleLine = true,
                    label = { Text(stringResource(R.string.addons_sqm_download)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("sqm-down"),
                )
                OutlinedTextField(
                    value = up, onValueChange = { up = it }, singleLine = true,
                    label = { Text(stringResource(R.string.addons_sqm_upload)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("sqm-up"),
                )
                if (down.isNotBlank() && up.isNotBlank()) problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(edit) }, enabled = problem == null && !busy, modifier = Modifier.testTag("sqm-save")) {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.addons_applying
                            queue == null -> R.string.addons_activate
                            else -> R.string.addons_save
                        },
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.addons_cancel)) } },
    )
}

/** 85000 → "85 Mbit/s"; 1500 → "1,5 Mbit/s"; 0 → "sin límite". */
@Composable
internal fun formatMbit(kbit: Long): String =
    if (kbit <= 0) stringResource(R.string.addons_sqm_unlimited) else stringResource(R.string.addons_sqm_mbit, formatMbitNumber(kbit))

internal fun formatMbitNumber(kbit: Long): String =
    if (kbit % 1000 == 0L) "${kbit / 1000}" else String.format(Locale.ROOT, "%.3f", kbit / 1000.0).trimEnd('0').trimEnd('.').replace('.', ',')

/** "1.000": en español es mil, no uno con tres decimales. Se pide sin puntos para no adivinar. */
private val THOUSANDS = Regex("^\\d{1,3}(\\.\\d{3})+$")

/** "85" → 85000; "1,5" o "1.5" → 1500 (kbit/s). null si no es un número. */
internal fun parseMbit(text: String): Long? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).roundToLong() }

// ---------- UPnP ----------

@Composable
private fun UpnpContent(
    upnp: UpnpState,
    /** IPv4 privada o de CGNAT de la salida a internet (null si es pública). */
    privateWan: String?,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onSecure: (Boolean) -> Unit,
    onClose: (UpnpMapping) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.addons_upnp_enabled),
        help = stringResource(
            when {
                upnp.enabled && upnp.running == false -> R.string.addons_upnp_not_running
                upnp.enabled -> R.string.addons_upnp_on_help
                else -> R.string.addons_upnp_off_help
            },
        ),
        checked = upnp.enabled,
        enabled = !busy,
        onChange = onToggle,
        modifier = Modifier.testTag("upnp-enabled"),
    )
    if (privateWan != null) {
        Text(
            stringResource(R.string.addons_upnp_private_wan, privateWan),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    SwitchRow(
        title = stringResource(R.string.addons_upnp_secure),
        help = stringResource(R.string.addons_upnp_secure_help),
        checked = upnp.secureMode,
        enabled = !busy,
        onChange = onSecure,
    )
    if (!upnp.enabled && upnp.mappings.isEmpty()) return
    HorizontalDivider()
    Text(stringResource(R.string.addons_upnp_open_ports), style = MaterialTheme.typography.titleSmall)
    if (upnp.mappings.isEmpty()) {
        Text(stringResource(R.string.addons_upnp_no_ports), style = MaterialTheme.typography.bodyMedium)
    }
    upnp.mappings.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(m.host ?: m.address, style = MaterialTheme.typography.bodyMedium)
                val expires = m.expiresIn?.let { stringResource(R.string.addons_upnp_expires_in, formatDuration(it)) }
                Text(
                    buildString {
                        append("${m.protocol} ${m.externalPort} → ${m.address}:${m.internalPort}")
                        if (m.description.isNotEmpty()) append(" · ${m.description}")
                        expires?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onClose(m) }, enabled = !busy) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.addons_upnp_close_port, m.externalPort),
                )
            }
        }
    }
}
