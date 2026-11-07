package dev.angel.wrtcontrol.ui.addons

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.addons.Addon
import dev.angel.wrtcontrol.addons.AddonStatus
import dev.angel.wrtcontrol.addons.SqmEdit
import dev.angel.wrtcontrol.addons.SqmQueue
import dev.angel.wrtcontrol.addons.UpnpMapping
import dev.angel.wrtcontrol.addons.UpnpState
import dev.angel.wrtcontrol.addons.RemotePeer
import dev.angel.wrtcontrol.addons.VPN_INTERFACE
import dev.angel.wrtcontrol.addons.DDNS_SECTIONS
import dev.angel.wrtcontrol.data.Link
import dev.angel.wrtcontrol.addons.DdnsEdit
import dev.angel.wrtcontrol.addons.DdnsProvider
import dev.angel.wrtcontrol.addons.DdnsService
import dev.angel.wrtcontrol.addons.ddnsProblem
import dev.angel.wrtcontrol.addons.fullDomain
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.angel.wrtcontrol.addons.WireGuardInterface
import dev.angel.wrtcontrol.addons.sqmProblem
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
                if (loadError != null) item { ErrorBanner("Datos sin actualizar: $loadError") }
                vm.busy?.let { item { BusyBanner(it) } }
                if (installing == null && editingSqm == null && !newSqm && !editingDdns) {
                    vm.error?.let { item { DismissibleError(it) { vm.error = null } } }
                }
                if (tool == null) item {
                    NoticeCard(
                        "La app no puede instalar paquetes en este router",
                        "Para eso hace falta la página Software de LuCI (luci-app-package-manager, o luci-app-opkg en " +
                            "versiones anteriores a 24.10). Lo que ya esté instalado sí se ve y se configura aquí.",
                    )
                }

                item { SectionTitle("Acceso remoto") }
                item {
                    AddonCard(Addon.WireGuard, status(Addon.WireGuard), canInstall = tool != null, busy = busy,
                        onInstall = { vm.error = null; installing = Addon.WireGuard }, onFinish = vm::finishWireGuard) {
                        RemoteAccessContent(data, busy, onEnable = { vm.enableRemoteAccess(phoneName()) }, onRemove = { vm.error = null; removingPeer = it })
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

                item { SectionTitle("Red de casa") }
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
                        error = vm.error,
                        onDismiss = { if (!busy) { editingDdns = false; vm.error = null } },
                        onSave = { edit -> vm.saveDdns(edit) { editingDdns = false } },
                    )
                }
                removingPeer?.let { peer ->
                    val mine = data.tunnel?.peerId == peer.id
                    AlertDialog(
                        onDismissRequest = { removingPeer = null },
                        title = { Text("Quitar el acceso remoto") },
                        text = {
                            Text(
                                if (mine) "Este teléfono dejará de poder entrar al router desde fuera de casa. En casa sigue " +
                                    "funcionando. Hay que hacerlo desde casa: la app lo quita por la conexión directa."
                                else "${peer.name} dejará de poder entrar al router desde fuera de casa.",
                            )
                        },
                        confirmButton = {
                            Button(onClick = { removingPeer = null; vm.removeRemotePeer(peer) }) { Text("Quitar") }
                        },
                        dismissButton = { TextButton(onClick = { removingPeer = null }) { Text("Cancelar") } },
                    )
                }
                if (newSqm || editingSqm != null) {
                    SqmDialog(
                        queue = editingSqm,
                        devices = (s.devices + s.internetDevices).distinct(),
                        internetDevice = s.internetDevice,
                        busy = busy,
                        error = vm.error,
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
        AddonStatus.Installed -> "instalado" to true
        AddonStatus.Missing -> "no instalado" to null
        AddonStatus.Partial -> "incompleto" to false
        AddonStatus.NeedsRestart -> "sin cargar" to false
    }
    InfoCard(addon.title, modifier = Modifier.testTag("addon-${addon.name}"), trailing = { StatusPill(label, ok) }) {
        Text(addon.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (status) {
            AddonStatus.Installed -> content()
            AddonStatus.Missing -> Button(onClick = onInstall, enabled = canInstall && !busy, modifier = Modifier.testTag("install-${addon.name}")) {
                Text("Instalar")
            }
            AddonStatus.Partial -> {
                Text(
                    "Está instalado, pero falta su parte de LuCI (${addon.luciPackage}), que es la que da permiso a la app para configurarlo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onInstall, enabled = canInstall && !busy) { Text("Completar la instalación") }
            }
            AddonStatus.NeedsRestart -> {
                Text(
                    "Está instalado, pero el router aún no lo cargó. Hay que reiniciar la red: unos segundos sin conexión.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onFinish, enabled = !busy) { Text("Reiniciar la red") }
            }
        }
    }
}

@Composable
private fun InstallDialog(addon: Addon, freeKiB: Long?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instalar ${addon.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("El router descargará ${addon.luciPackage} y lo que necesite de los servidores de OpenWrt. Necesita internet.")
                freeKiB?.let { Text("Espacio libre en el router: ${formatBytes(it * 1024)}.") }
                if (addon.restartsNetwork) {
                    Text("Al terminar se reinicia la red: unos segundos sin internet en casa.")
                }
                Text(
                    "Una actualización del firmware borra los paquetes instalados; la app avisará y podrás volver a instalarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm, modifier = Modifier.testTag("confirm-install")) { Text("Instalar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------- WireGuard ----------

/** Nombre con el que este teléfono aparece en el router. */
private fun phoneName(): String =
    listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.distinct()
        .joinToString(" ").replaceFirstChar { it.uppercase() }

@Composable
private fun RemoteAccessContent(data: AddonsData, busy: Boolean, onEnable: () -> Unit, onRemove: (RemotePeer) -> Unit) {
    val s = data.state
    val remote = s.remote
    val live = s.wireguard.orEmpty().firstOrNull { it.name == VPN_INTERFACE }
    val now = System.currentTimeMillis() / 1000
    HorizontalDivider()
    Text("Acceso remoto", style = MaterialTheme.typography.titleSmall)
    Text(
        "Solo la app usa el túnel, con su propio WireGuard: no ocupa la VPN del teléfono ni toca otras. " +
            "Desde el túnel solo se llega a la web del router, no al resto de la red.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
        s.publicAddresses.isEmpty() -> Text(
            "El router no tiene ahora una dirección pública (ni IPv6 ni IPv4 sin CGNAT): desde fuera no se podrá llegar a él.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        s.publicAddresses.none { '.' in it } -> Text(
            "Se llega por IPv6 (${s.publicAddresses.first()}): la red desde la que entres también tiene que tener IPv6.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (remote != null) {
        Text(
            "WireGuard en el puerto UDP ${remote.port}" + if (live?.up == false) " · sin levantar" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            s.ddnsDomain?.let { "Desde fuera, la app lo buscará en $it." }
                ?: "Sin DNS dinámico, la app usará la última dirección que vio: si cambia mientras estás fuera, no lo encontrará.",
            style = MaterialTheme.typography.bodySmall,
            color = if (s.ddnsDomain == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (remote.foreignPeers.isNotEmpty()) {
            Text(
                "Además hay ${remote.foreignPeers.size} par(es) añadidos fuera de la app (${remote.foreignPeers.joinToString()}): " +
                    "se gestionan desde LuCI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        remote.peers.forEach { peer ->
            val status = when {
                live == null || !live.liveKnown -> null
                else -> live.peers.firstOrNull { it.publicKey == peer.publicKey }?.lastHandshake
                    ?.let { "conectado hace ${formatDuration((now - it).coerceAtLeast(0))}" } ?: "aún no ha conectado"
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(peer.name + if (data.tunnel?.peerId == peer.id) " (este teléfono)" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(peer.address, status).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(peer) }, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Quitar el acceso remoto de ${peer.name}")
                }
            }
        }
    }
    if (!data.phoneHasAccess) {
        Button(onClick = onEnable, enabled = !busy, modifier = Modifier.testTag("enable-remote")) {
            Text(if (remote == null) "Activar el acceso remoto" else "Añadir este teléfono")
        }
    }
}

@Composable
private fun WireGuardContent(interfaces: List<WireGuardInterface>) {
    if (interfaces.isEmpty()) return
    val now = System.currentTimeMillis() / 1000
    HorizontalDivider()
    Text("Otros túneles del router", style = MaterialTheme.typography.titleSmall)
    interfaces.forEach { iface ->
        Text(
            buildString {
                append(iface.name)
                iface.listenPort?.let { append(" · puerto $it") }
                if (!iface.up) append(" · sin levantar")
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (iface.addresses.isNotEmpty()) Text(iface.addresses.joinToString(), style = MaterialTheme.typography.bodySmall)
        if (iface.peers.isEmpty()) Text("Sin pares.", style = MaterialTheme.typography.bodySmall)
        iface.peers.forEach { peer ->
            val seen = if (!iface.liveKnown) null else
                peer.lastHandshake?.let { "conectado hace ${formatDuration((now - it).coerceAtLeast(0))}" } ?: "nunca ha conectado"
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
            if (remote) "El acceso remoto lo necesita: sin un nombre fijo, si tu proveedor le cambia la dirección al router mientras estás fuera, la app no lo encontrará."
            else "Para el acceso remoto: un nombre que siempre apunta al router.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("ddns-configure")) { Text("Configurar") }
    } else {
        HorizontalDivider()
        Text(own.domain, style = MaterialTheme.typography.titleSmall)
        Text(own.provider?.title ?: own.serviceName ?: "Proveedor propio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        owned.sortedBy { it.ipv6 }.forEach { svc ->
            val family = if (svc.ipv6) "IPv6" else "IPv4"
            Text(
                "$family: " + (svc.lastUpdate?.let { "actualizado $it" + (svc.lastSentIp?.let { ip -> " ($ip)" } ?: "") } ?: "aún no se ha actualizado") +
                    if (svc.running) "" else " · parado",
                style = MaterialTheme.typography.bodyMedium,
            )
            svc.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (svc.ipv6 && publicAddresses.none { ':' in it }) {
                Text("El router no tiene ahora una IPv6 pública que publicar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, enabled = !busy) { Text("Cambiar") }
            TextButton(onClick = onRemove, enabled = !busy) { Text("Quitar") }
        }
    }
    val others = services.filter { !it.own && it.enabled }
    if (others.isNotEmpty()) {
        HorizontalDivider()
        Text("Configurados en LuCI", style = MaterialTheme.typography.titleSmall)
        others.forEach { Text("${it.domain} · ${it.serviceName ?: "propio"}", style = MaterialTheme.typography.bodyMedium) }
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
        title = { Text("DNS dinámico") },
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
                    label = { Text("Nombre") },
                    supportingText = { if (domain.isNotBlank() && problem == null) Text(edit.fullDomain()) else provider.suffix?.let { Text("p. ej. micasa$it") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("ddns-domain"),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text("Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("ddns-token"),
                )
                if (provider == DdnsProvider.Custom) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("URL de actualización") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username, onValueChange = { username = it }, singleLine = true,
                        label = { Text("Usuario (si la URL lo pide)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val v4 = publicAddresses.any { '.' in it }
                val v6 = publicAddresses.any { ':' in it }
                Text(
                    when {
                        v4 && v6 -> "Se publicarán la IPv4 y la IPv6 del router: así se llega desde cualquier red."
                        v4 -> "Se publicará la IPv4 del router."
                        else -> "Se publicará la IPv6 del router: con CGNAT, la IPv4 no sirve para entrar desde fuera."
                    },
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
                Text(if (busy) "Aplicando…" else "Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } },
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
        Text("Sin configurar.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCreate, enabled = !busy) { Text("Configurar") }
        return
    }
    queues.forEach { q ->
        HorizontalDivider()
        SwitchRow(
            title = q.device.ifBlank { q.section },
            help = "Bajada ${formatMbit(q.downloadKbit)} · subida ${formatMbit(q.uploadKbit)} · ${q.qdisc}",
            checked = q.enabled,
            enabled = !busy,
            onChange = { onToggle(q, it) },
            modifier = Modifier.testTag("sqm-${q.section}"),
        )
        if (internetDevice != null && q.device !in internetDevices) {
            Text(
                "Esta cola está en ${q.device.ifBlank { "ningún dispositivo" }}, pero la salida a internet es $internetDevice: " +
                    "así no hace nada. Edítala para cambiarla.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = { onEdit(q) }, enabled = !busy) { Text("Editar") }
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
        listOf(down, up).any { THOUSANDS.matches(it.trim()) } -> "Escribe la velocidad sin puntos de miles (p. ej. 1000)."
        parseMbit(down) == null || parseMbit(up) == null -> "Escribe las dos velocidades en Mbit/s."
        else -> sqmProblem(edit)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (queue == null) "Configurar SQM" else "Editar SQM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Haz una prueba de velocidad sin nadie más usando internet y pon un poco menos (85–95 %): si pones " +
                        "más de lo que da la conexión, no sirve de nada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Conexión a internet", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(device) + devices).filter { it.isNotBlank() }.distinct().forEach { d ->
                        FilterChip(
                            selected = d == device,
                            onClick = { device = d },
                            label = { Text(if (d == internetDevice) "$d (internet)" else d) },
                        )
                    }
                }
                OutlinedTextField(
                    value = down, onValueChange = { down = it }, singleLine = true,
                    label = { Text("Bajada (Mbit/s)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("sqm-down"),
                )
                OutlinedTextField(
                    value = up, onValueChange = { up = it }, singleLine = true,
                    label = { Text("Subida (Mbit/s)") },
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
                Text(if (busy) "Aplicando…" else if (queue == null) "Activar" else "Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } },
    )
}

/** 85000 → "85 Mbit/s"; 1500 → "1,5 Mbit/s"; 0 → "sin límite". */
internal fun formatMbit(kbit: Long): String = if (kbit <= 0) "sin límite" else "${formatMbitNumber(kbit)} Mbit/s"

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
        title = "Activado",
        help = when {
            upnp.enabled && upnp.running == false -> "Está activado pero el servicio no está en marcha."
            upnp.enabled -> "Los dispositivos pueden abrir puertos."
            else -> "Ningún dispositivo puede abrir puertos solo."
        },
        checked = upnp.enabled,
        enabled = !busy,
        onChange = onToggle,
        modifier = Modifier.testTag("upnp-enabled"),
    )
    if (privateWan != null) {
        Text(
            "La dirección de internet del router ($privateWan) es privada: hay otro router delante o tu proveedor " +
                "usa CGNAT. Así UPnP no puede abrir puertos hacia internet por IPv4.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    SwitchRow(
        title = "Modo seguro",
        help = "Cada dispositivo solo puede abrir puertos hacia sí mismo. Mejor dejarlo activado.",
        checked = upnp.secureMode,
        enabled = !busy,
        onChange = onSecure,
    )
    if (!upnp.enabled && upnp.mappings.isEmpty()) return
    HorizontalDivider()
    Text("Puertos abiertos", style = MaterialTheme.typography.titleSmall)
    if (upnp.mappings.isEmpty()) {
        Text("Ningún dispositivo ha abierto puertos.", style = MaterialTheme.typography.bodyMedium)
    }
    upnp.mappings.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(m.host ?: m.address, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append("${m.protocol} ${m.externalPort} → ${m.address}:${m.internalPort}")
                        if (m.description.isNotEmpty()) append(" · ${m.description}")
                        m.expiresIn?.let { append(" · caduca en ${formatDuration(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onClose(m) }, enabled = !busy) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Cerrar el puerto ${m.externalPort}")
            }
        }
    }
}
