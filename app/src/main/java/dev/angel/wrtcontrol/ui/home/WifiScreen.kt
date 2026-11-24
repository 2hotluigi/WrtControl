package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.angel.wrtcontrol.openwrt.ChannelAdvice
import dev.angel.wrtcontrol.openwrt.WifiCountry
import dev.angel.wrtcontrol.openwrt.WifiEdit
import dev.angel.wrtcontrol.openwrt.WifiPower
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiSchedule
import dev.angel.wrtcontrol.openwrt.WifiSecurity
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.remembered
import dev.angel.wrtcontrol.openwrt.wifiProblem
import dev.angel.wrtcontrol.parental.Schedule
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
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.Proposals
import dev.angel.wrtcontrol.ui.design.ActionRow
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.CheckRow
import dev.angel.wrtcontrol.ui.design.FieldRow
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.design.SwitchRow
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.parental.onWifiBehind
import dev.angel.wrtcontrol.ui.parental.phoneAddresses
import dev.angel.wrtcontrol.ui.rememberVendors
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Quién está conectado cambia a menudo; el resto, solo desde aquí o desde LuCI. */
private val WIFI_REFRESH = 15.seconds

/** Cómo va la búsqueda del canal más libre de una radio. */
sealed interface ChannelSearch {
    data object Searching : ChannelSearch

    data class Found(val advice: ChannelAdvice) : ChannelSearch

    /** El router no pudo escanear, o no hay nada que proponer. */
    data object Failed : ChannelSearch
}

class WifiViewModel(private val router: RouterAccess, private val monitor: RouterMonitor) : ViewModel() {
    private val changes = ChangeRunner(viewModelScope)

    val progress: ChangeProgress? get() = changes.current

    val proposals: Proposals get() = changes

    var error: Txt?
        get() = changes.error
        set(value) {
            changes.error = value
        }

    /**
     * La red que se acaba de poner (otro nombre, contraseña o seguridad), para guardarla en este
     * teléfono. Se ofrece desde que empieza el cambio: si el teléfono iba por ese Wi-Fi, la
     * necesita para volver a entrar antes de que el router lo deshaga.
     */
    var newNetwork by mutableStateOf<WifiEdit?>(null)

    /** Guarda [edit] (antes era [before]); antes enseña [preview] si trae avisos. [phoneOnIt]: ver WifiApi.save. */
    fun save(before: WifiEdit, edit: WifiEdit, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        val offered = newNetwork
        if (offersNewNetwork(before, edit)) newNetwork = edit
        val started = changes.run(txt(R.string.wifi_busy_saving), onFinished = {
            if (changes.error != null) newNetwork = null
            monitor.wifi.refresh()
        }) {
            router.use { it.wifi.save(edit, phoneOnIt) }
        }
        if (!started) newNetwork = offered
    }

    /** Los países que conoce el router: null mientras se leen, vacío si no deja leerlos. */
    var countries by mutableStateOf<List<WifiCountry>?>(null)
        private set

    init {
        loadSchedule()
        viewModelScope.launch {
            countries = try {
                router.use { it.wifi.countries() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Pone el país [code]; antes enseña [preview] si el cambio reinicia un Wi-Fi que está emitiendo. */
    fun setCountry(code: String, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        changes.run(txt(R.string.wifi_country_busy), onFinished = { monitor.wifi.refresh() }) {
            router.use { it.wifi.setCountry(code, phoneOnIt) }
        }
    }

    /** Pone [radio] a [width] MHz; antes enseña [preview] si esa radio está emitiendo. */
    fun setWidth(radio: String, width: Int, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        changes.run(txt(R.string.wifi_width_busy), onFinished = { monitor.wifi.refresh() }) {
            router.use { it.wifi.setWidth(radio, width, phoneOnIt) }
        }
    }

    /** Pone la potencia de [radio] en [power]; antes enseña [preview] si esa radio está emitiendo. */
    fun setPower(radio: String, power: WifiPower, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        changes.run(txt(R.string.wifi_power_busy), onFinished = { monitor.wifi.refresh() }) {
            router.use { it.wifi.setPower(radio, power, phoneOnIt) }
        }
    }

    /** El horario del Wi-Fi: null mientras se lee o si el router no deja ([scheduleUnavailable]). */
    var schedule by mutableStateOf<WifiSchedule?>(null)
        private set

    var scheduleUnavailable by mutableStateOf(false)
        private set

    private fun loadSchedule() {
        viewModelScope.launch {
            val read = try {
                router.use { it.wifiSchedule.read() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            schedule = read
            scheduleUnavailable = read == null
        }
    }

    /** Guarda el horario [schedules] (vacío: sin horario). */
    fun setSchedule(schedules: List<Schedule>) {
        changes.run(txt(R.string.wifi_schedule_busy), onFinished = {
            loadSchedule()
            monitor.wifi.refresh()
        }) {
            router.use { it.wifiSchedule.save(schedules) }
        }
    }

    /** Enciende el Wi-Fi que apagó el horario, hasta la próxima hora de apagar. */
    fun turnOnNow() {
        changes.run(txt(R.string.wifi_schedule_turning_on), onFinished = { monitor.wifi.refresh() }) {
            router.use { it.wifiSchedule.turnOnNow() }
        }
    }

    /** Cómo va la búsqueda del canal más libre de cada radio. */
    var channelSearches by mutableStateOf<Map<String, ChannelSearch>>(emptyMap())
        private set

    /** Pide al router que escanee con [radio] y proponga canal. */
    fun findChannel(radio: String) {
        channelSearches = channelSearches + (radio to ChannelSearch.Searching)
        viewModelScope.launch {
            val advice = try {
                router.use { it.wifi.channelAdvice(radio) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            channelSearches = channelSearches + (radio to (advice?.let { ChannelSearch.Found(it) } ?: ChannelSearch.Failed))
        }
    }

    /** Pasa [radio] al canal [channel]; antes enseña [preview] si esa radio está emitiendo. */
    fun setChannel(radio: String, channel: Int, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        // Con el canal cambiado, lo que se encontró ya no describe lo que hay.
        val done = {
            channelSearches = channelSearches - radio
            monitor.wifi.refresh()
        }
        changes.run(txt(R.string.wifi_channel_busy), onFinished = done) {
            router.use { it.wifi.setChannel(radio, channel, phoneOnIt) }
        }
    }
}

@Composable
fun WifiScreen(address: String) {
    val repo = LocalRepository.current
    val vm: WifiViewModel = viewModel(key = "wifi:$address") { WifiViewModel(repo, repo.monitor) }
    val state by repo.monitor.wifi.watchAsState(WIFI_REFRESH)
    // Para saber si este teléfono está en el Wi-Fi que se cambia (por su MAC según el router).
    val devices by repo.monitor.devices.watchAsState(WIFI_REFRESH)
    val context = LocalContext.current
    val locked = LocalAppLocked.current
    val vendors = rememberVendors()

    LoadableContent(state) { wifi, loadError ->
        val ips = phoneAddresses(context)
        val phoneMacs = devices.data.orEmpty().filter { d -> !d.remembered && d.ipv4.any { it in ips } }.map { it.mac }.toSet()
        WifiContent(
            state = wifi,
            otherAccessPoints = onWifiBehind(context, address),
            loadError = loadError,
            progress = vm.progress,
            error = vm.error?.text(),
            enabled = !locked,
            onSave = { edit -> vm.save(currentWifi(wifi), edit, phoneOnWifi(wifi, phoneMacs), wifiPreview(wifi, edit, phoneMacs)) },
            newNetwork = vm.newNetwork,
            onNetworkAdded = { vm.newNetwork = null },
            countries = vm.countries,
            suggestedCountry = vm.countries?.let { suggestedCountry(context, it) },
            onCountry = { code -> vm.setCountry(code, phoneOnWifi(wifi, phoneMacs), countryPreview(wifi, code, phoneMacs)) },
            onWidth = { radio, width ->
                vm.setWidth(radio.name, width, phoneOnRadio(wifi, radio.name, phoneMacs), widthPreview(wifi, radio, width, phoneMacs))
            },
            names = devices.data.orEmpty().associate { it.mac to deviceTitle(it, vendors) },
            onPower = { radio, power ->
                vm.setPower(radio.name, power, phoneOnRadio(wifi, radio.name, phoneMacs), powerPreview(wifi, radio, power, phoneMacs))
            },
            schedule = vm.schedule,
            scheduleUnavailable = vm.scheduleUnavailable,
            onSchedule = vm::setSchedule,
            onTurnOn = vm::turnOnNow,
            channelSearches = vm.channelSearches,
            onFindChannel = vm::findChannel,
            onChannel = { radio, channel ->
                val preview = channelPreview(wifi, radio, channel, phoneMacs)
                vm.setChannel(radio.name, channel, phoneOnRadio(wifi, radio.name, phoneMacs), preview)
            },
        )
    }
    ChangePreviewHost(vm.proposals)
}

/** "Tu Wi-Fi": nombre, seguridad, contraseña y bandas, y por qué avisar si está apagado o abierto. */
@Composable
fun WifiContent(
    state: WifiState,
    otherAccessPoints: Boolean = false,
    newNetwork: WifiEdit? = null,
    onNetworkAdded: () -> Unit = {},
    loadError: String? = null,
    progress: ChangeProgress? = null,
    error: String? = null,
    enabled: Boolean = true,
    onSave: (WifiEdit) -> Unit = {},
    countries: List<WifiCountry>? = emptyList(),
    suggestedCountry: String? = null,
    onCountry: (String) -> Unit = {},
    onWidth: (WifiRadio, Int) -> Unit = { _, _ -> },
    channelSearches: Map<String, ChannelSearch> = emptyMap(),
    onFindChannel: (String) -> Unit = {},
    onChannel: (WifiRadio, Int) -> Unit = { _, _ -> },
    names: Map<String, String> = emptyMap(),
    onPower: (WifiRadio, WifiPower) -> Unit = { _, _ -> },
    schedule: WifiSchedule? = null,
    scheduleUnavailable: Boolean = false,
    onSchedule: (List<Schedule>) -> Unit = {},
    onTurnOn: () -> Unit = {},
) {
    val current = currentWifi(state)
    // Con cada lectura nueva del router (otro teléfono, LuCI), el formulario vuelve a lo que hay.
    var ssid by rememberSaveable(current) { mutableStateOf(current.ssid) }
    var key by rememberSaveable(current) { mutableStateOf(current.key) }
    var security by rememberSaveable(current) { mutableStateOf(current.security) }
    var radios by rememberSaveable(current) { mutableStateOf(current.radios.toList()) }
    val edit = WifiEdit(ssid.trim(), key, security, radios.toSet())
    val problem = wifiProblem(edit)
    val busy = progress != null || !enabled

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        if (loadError != null) ErrorBanner(stringResource(R.string.common_stale_data, loadError))
        if (state.radios.isEmpty()) {
            GroupCard { GroupRow(stringResource(R.string.wifi_none), labelLines = 3, divider = false) }
            return@Column
        }
        WifiNotices(state, current, otherAccessPoints)
        NetworkGroup(ssid, key, security, enabled = !busy, onSsid = { ssid = it }, onKey = { key = it })
        SecurityPicker(security, enabled = !busy) { security = it }
        Bands(state, radios.toSet(), enabled = !busy) { radio, on -> radios = if (on) radios + radio else radios - radio }
        // Junto al botón, que es donde se está mirando al guardar.
        SaveStatus(problem?.takeIf { edit != current }?.text(), error, progress)
        if (newNetwork != null) AddNetworkCard(newNetwork, onDone = onNetworkAdded)
        ActionRow(
            stringResource(R.string.wifi_save),
            Modifier.testTag("wifi-save"),
            enabled = !busy && problem == null && edit != current,
        ) { onSave(edit) }
        ClientsSection(state, names)
        ScheduleSection(state, schedule, scheduleUnavailable, enabled = !busy, onChange = onSchedule, onTurnOn = onTurnOn)
        CountrySection(state, countries, suggestedCountry, enabled = !busy, onPick = onCountry)
        WidthSection(state, enabled = !busy, onPick = onWidth)
        ChannelSection(state, channelSearches, enabled = !busy, onFind = onFindChannel, onUse = onChannel)
        PowerSection(state, enabled = !busy, onPick = onPower)
    }
}

/** Lo que hay que saber junto al botón de guardar: por qué no se puede, el último error o cómo va. */
@Composable
internal fun SaveStatus(problem: String?, error: String?, progress: ChangeProgress?) {
    if (problem != null) {
        Text(
            problem,
            color = LocalPalette.current.bad,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (error != null) ErrorBanner(error)
    if (progress != null) ApplyingBanner(progress)
}

/** El nombre de la red y su contraseña (sin contraseña si es abierta). */
@Composable
private fun NetworkGroup(
    ssid: String,
    key: String,
    security: WifiSecurity,
    enabled: Boolean,
    onSsid: (String) -> Unit,
    onKey: (String) -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.wifi_network_section))
        GroupCard {
            FieldRow(
                stringResource(R.string.wifi_field_name),
                ssid,
                onSsid,
                Modifier.testTag("wifi-ssid"),
                enabled = enabled,
                divider = security != WifiSecurity.Open,
            )
            if (security != WifiSecurity.Open) PasswordRow(key, enabled, onKey)
        }
    }
}

/** Una fila por radio, con cuántos hay conectados a su red ahora. */
@Composable
private fun Bands(state: WifiState, on: Set<String>, enabled: Boolean, onChange: (radio: String, on: Boolean) -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.wifi_bands_title))
        GroupCard {
            state.radios.forEachIndexed { i, radio ->
                val network = state.home.firstOrNull { it.radio == radio.name }
                val clients = network?.clients?.size?.takeIf { network.ifname != null }
                SwitchRow(
                    radio.band?.let { stringResource(it.label) } ?: radio.name,
                    checked = radio.name in on,
                    onChange = { onChange(radio.name, it) },
                    modifier = Modifier.testTag("wifi-band-${radio.name}"),
                    divider = i < state.radios.lastIndex,
                    supporting = clients?.let { pluralStringResource(R.plurals.wifi_band_clients, it, it) },
                    enabled = enabled,
                )
            }
        }
        GroupFooter(stringResource(R.string.wifi_bands_help), Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Hint(text: Int) {
    WifiHint(stringResource(text))
}

@Composable
private fun WifiNotices(state: WifiState, current: WifiEdit, otherAccessPoints: Boolean) {
    when {
        // Este teléfono está por Wi-Fi en la red de casa y el router no emite: lo dan otros aparatos.
        current.radios.isEmpty() && otherAccessPoints -> Hint(R.string.wifi_off_others)
        current.radios.isEmpty() -> NoticeCard(stringResource(R.string.wifi_off_title), stringResource(R.string.wifi_off_body))
        current.security == WifiSecurity.Open -> {
            NoticeCard(stringResource(R.string.wifi_open_title), stringResource(R.string.wifi_open_body))
        }
    }
    if (state.homeDiffers) Hint(R.string.wifi_differs)
}

@Composable
private fun SecurityPicker(selected: WifiSecurity, enabled: Boolean, onSelect: (WifiSecurity) -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.wifi_security_title))
        GroupCard {
            WifiSecurity.entries.forEachIndexed { i, option ->
                CheckRow(
                    stringResource(option.label),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    divider = i < WifiSecurity.entries.lastIndex,
                )
            }
        }
    }
}

/** La contraseña, con puntos hasta que se pide verla. */
@Composable
private fun PasswordRow(key: String, enabled: Boolean, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    FieldRow(
        stringResource(R.string.wifi_password_label),
        key,
        onChange,
        Modifier.testTag("wifi-key"),
        enabled = enabled,
        hidden = !visible,
        password = true,
        divider = false,
        trailing = {
            Text(
                stringResource(if (visible) R.string.wifi_password_hide else R.string.wifi_password_show),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalPalette.current.accent,
                modifier = Modifier.clickable { visible = !visible }.padding(vertical = 8.dp),
            )
        },
    )
}
