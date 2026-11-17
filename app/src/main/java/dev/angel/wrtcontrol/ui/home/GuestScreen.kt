package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.data.RouterAccess
import dev.angel.wrtcontrol.data.RouterMonitor
import dev.angel.wrtcontrol.openwrt.GuestEdit
import dev.angel.wrtcontrol.openwrt.GuestWifi
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.guestProblem
import dev.angel.wrtcontrol.openwrt.guestWifi
import dev.angel.wrtcontrol.openwrt.remembered
import dev.angel.wrtcontrol.openwrt.wifiQrText
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.ChangePreviewHost
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ChangeRunner
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.PreviewLine
import dev.angel.wrtcontrol.ui.Proposals
import dev.angel.wrtcontrol.ui.parental.phoneAddresses
import dev.angel.wrtcontrol.ui.watchAsState
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds

private val GUEST_REFRESH = 15.seconds

/** Letras y cifras que no se confunden al dictarlas o copiarlas (sin 0/o, 1/l/i). */
private const val KEY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
private const val KEY_LENGTH = 10
private const val MAX_SSID_BYTES = 32

/** Una contraseña nueva para los invitados: corta de dictar y difícil de adivinar (unos 49 bits). */
fun newGuestKey(random: SecureRandom = SecureRandom()): String =
    (1..KEY_LENGTH).map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("")

/**
 * El nombre que se propone: el del Wi-Fi de casa ([home]) con "invitados" detrás ([suffixed]), si
 * cabe en un nombre de red; si no, [fallback].
 */
fun guestNameFor(home: String, suffixed: String, fallback: String): String =
    suffixed.takeIf { home.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES } ?: fallback

class GuestViewModel(private val router: RouterAccess, private val monitor: RouterMonitor) : ViewModel() {
    private val changes = ChangeRunner(viewModelScope)

    val progress: ChangeProgress? get() = changes.current

    val proposals: Proposals get() = changes

    val error: Txt? get() = changes.error

    /** Guarda [edit]; antes enseña [preview] si reinicia un Wi-Fi que está emitiendo. */
    fun save(edit: GuestEdit, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        changes.run(txt(R.string.guest_busy), onFinished = { monitor.wifi.refresh() }) {
            router.use { it.guestWifi.save(edit, phoneOnIt) }
            // Los servidores DNS de los perfiles se recalculan con la red nueva a la vista: cada uno
            // tiene que saber que en ella no reparte direcciones. Sin perfiles no cambia nada.
            router.use { it.parental.update { config, _ -> config } }
        }
    }
}

/**
 * Lo que cambia al guardar [edit]. Tocar las redes de una radio la reinicia: si hay alguna
 * emitiendo, avisa de que el Wi-Fi de casa también se corta un momento.
 */
fun guestPreview(state: WifiState, current: GuestEdit, edit: GuestEdit, phoneMacs: Set<String>): ChangePreview {
    val emitting = state.networks.any { it.ifname != null }
    val notes = buildList {
        if (emitting) add(txt(R.string.guest_preview_restart))
        if (emitting && phoneOnWifi(state, phoneMacs)) add(txt(R.string.wifi_country_preview_phone))
    }
    fun onOff(on: Boolean) = txt(if (on) R.string.wifi_preview_on else R.string.wifi_preview_off)
    val lines = buildList {
        if (edit.on != current.on) add(PreviewLine(txt(R.string.guest_switch), onOff(current.on), onOff(edit.on)))
        if (edit.ssid != current.ssid) add(PreviewLine(txt(R.string.wifi_name_label), raw(current.ssid), raw(edit.ssid)))
        if (edit.key != current.key) add(PreviewLine(txt(R.string.wifi_password_label), raw(current.key), raw(edit.key)))
    }
    return ChangePreview(txt(R.string.guest_preview_title), lines, notes)
}

@Composable
fun GuestScreen(address: String) {
    val repo = LocalRepository.current
    val vm: GuestViewModel = viewModel(key = "guest:$address") { GuestViewModel(repo, repo.monitor) }
    val state by repo.monitor.wifi.watchAsState(GUEST_REFRESH)
    val devices by repo.monitor.devices.watchAsState(GUEST_REFRESH)
    val context = LocalContext.current
    val locked = LocalAppLocked.current

    LoadableContent(state) { wifi, loadError ->
        val ips = phoneAddresses(context)
        val phoneMacs = devices.data.orEmpty().filter { d -> !d.remembered && d.ipv4.any { it in ips } }.map { it.mac }.toSet()
        GuestContent(
            state = wifi,
            loadError = loadError,
            progress = vm.progress,
            error = vm.error?.text(),
            enabled = !locked,
            onSave = { current, edit -> vm.save(edit, phoneOnWifi(wifi, phoneMacs), guestPreview(wifi, current, edit, phoneMacs)) },
        )
    }
    ChangePreviewHost(vm.proposals)
}

/**
 * El Wi-Fi de invitados: encenderlo o apagarlo, su nombre y su contraseña (a la vista: es para
 * dársela a la visita) y, si está encendido, el código QR para conectarse. [newKey]: la contraseña
 * que se propone la primera vez.
 */
@Composable
fun GuestContent(
    state: WifiState,
    loadError: String? = null,
    progress: ChangeProgress? = null,
    error: String? = null,
    enabled: Boolean = true,
    newKey: String = rememberSaveable { newGuestKey() },
    onSave: (current: GuestEdit, edit: GuestEdit) -> Unit = { _, _ -> },
) {
    val guest = guestWifi(state)
    val home = currentWifi(state).ssid
    val suggested = guestNameFor(home, stringResource(R.string.guest_default_suffix, home), stringResource(R.string.guest_default_name))
    val current = GuestEdit(guest?.on ?: false, guest?.ssid ?: suggested, guest?.key ?: newKey)
    var on by rememberSaveable(current) { mutableStateOf(current.on) }
    var ssid by rememberSaveable(current) { mutableStateOf(current.ssid) }
    var key by rememberSaveable(current) { mutableStateOf(current.key) }
    val edit = GuestEdit(on, ssid.trim(), key)
    val problem = guestProblem(edit).takeIf { on }
    val busy = progress != null || !enabled
    // Apagado y sin crear, cambiar el nombre no guarda nada: no hay qué guardar hasta encenderlo.
    val changed = edit != current && (edit.on || guest != null)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loadError != null) ErrorBanner(stringResource(R.string.common_stale_data, loadError))
        WifiHint(stringResource(R.string.guest_intro))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.guest_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it }, enabled = !busy, modifier = Modifier.testTag("guest-on"))
        }
        GuestField(R.string.wifi_name_label, ssid, enabled = !busy, tag = "guest-ssid") { ssid = it }
        GuestField(R.string.wifi_password_label, key, enabled = !busy, tag = "guest-key") { key = it }
        if (guest?.on == true && !on) WifiHint(stringResource(R.string.guest_off_keeps))
        GuestStatus(problem?.text(), error, progress)
        Button(
            onClick = { onSave(current, edit) },
            enabled = !busy && changed && problem == null,
            modifier = Modifier.align(Alignment.End).testTag("guest-save"),
        ) { Text(stringResource(R.string.wifi_save)) }
        if (guest?.on == true) GuestAccess(guest)
    }
}

@Composable
private fun GuestField(label: Int, value: String, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Lo que hay que saber junto al botón: por qué no se puede guardar, el último error o cómo va el cambio. */
@Composable
private fun GuestStatus(problem: String?, error: String?, progress: ChangeProgress?) {
    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (error != null) ErrorBanner(error)
    if (progress != null) ApplyingBanner(progress)
}

/** Cómo se conecta la visita: el QR de lo que hay guardado en el router, y cuántos hay ahora. */
@Composable
private fun GuestAccess(guest: GuestWifi) {
    HorizontalDivider()
    Text(stringResource(R.string.guest_qr_title), style = MaterialTheme.typography.titleSmall)
    QrCode(
        wifiQrText(guest.ssid, guest.key),
        stringResource(R.string.guest_qr_description),
        Modifier.size(220.dp).testTag("guest-qr"),
    )
    WifiHint(stringResource(R.string.guest_qr_help, guest.ssid))
    val count = guest.clients.size
    if (count > 0) Text(pluralStringResource(R.plurals.guest_clients, count, count), style = MaterialTheme.typography.bodyMedium)
}
