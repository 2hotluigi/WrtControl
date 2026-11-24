package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.RouterAccess
import dev.angel.wrtcontrol.data.RouterMonitor
import dev.angel.wrtcontrol.data.UsageState
import dev.angel.wrtcontrol.openwrt.DeviceRecorder
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.openwrt.Recorder
import dev.angel.wrtcontrol.openwrt.UsageHistory
import dev.angel.wrtcontrol.openwrt.UsagePeriod
import dev.angel.wrtcontrol.openwrt.WanUsage
import dev.angel.wrtcontrol.openwrt.totals
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ChangeRunner
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalChartColors
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.OfferGroup
import dev.angel.wrtcontrol.ui.design.PillTabs
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.design.Stat
import dev.angel.wrtcontrol.ui.design.StatStrip
import dev.angel.wrtcontrol.ui.design.ValueRows
import dev.angel.wrtcontrol.ui.deviceTitle
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.rememberVendors
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Los contadores suben a cada momento, pero nadie mira esta pantalla segundo a segundo. */
private val USAGE_REFRESH = 10.seconds

/** Lo guardado por horas y días cambia cada hora: se mira de minuto en minuto. */
private val RECORDER_REFRESH = 1.minutes

/** Instalar o configurar vnStat y nlbwmon, con el cambio en curso y su error. */
class UsageViewModel(private val router: RouterAccess, private val monitor: RouterMonitor) : ViewModel() {
    private val changes = ChangeRunner(viewModelScope)

    val progress: ChangeProgress? get() = changes.current

    val error: Txt? get() = changes.error

    fun install() = run(txt(R.string.usage_busy_installing), installs = true) { it.usage.install(it.interfaces()) }

    fun setUp() = run(txt(R.string.usage_busy_setup)) { it.usage.setUp(it.interfaces()) }

    fun installDevices() = run(txt(R.string.usage_busy_installing_devices), installs = true) { it.deviceUsage.install() }

    fun setUpDevices() = run(txt(R.string.usage_busy_setup_devices)) { it.deviceUsage.setUp() }

    private fun run(label: Txt, installs: Boolean = false, block: suspend (OpenWrtApi) -> Unit) {
        val refresh = {
            // Aunque falle, la instalación pudo quedar a medias: se vuelve a mirar qué hay.
            if (installs) {
                monitor.packages.refresh()
                monitor.capabilities.refresh()
            }
            monitor.usageRecorder.refresh()
            monitor.deviceUsage.refresh()
        }
        changes.run(label, onFinished = refresh) { router.use { block(it) } }
    }
}

/** Más → Uso de internet: cuánto se ha descargado y subido. */
@Composable
fun UsageScreen(address: String) {
    val repo = LocalRepository.current
    val vm: UsageViewModel = viewModel(key = "usage:$address") { UsageViewModel(repo, repo.monitor) }
    val state by repo.monitor.usage.watchAsState(USAGE_REFRESH)
    val recorder by repo.monitor.usageRecorder.watchAsState(RECORDER_REFRESH)
    val perDevice by repo.monitor.deviceUsage.watchAsState(RECORDER_REFRESH)
    val devices by repo.monitor.devices.watchAsState(RECORDER_REFRESH)
    val vendors = rememberVendors()
    var period by rememberSaveable { mutableStateOf(UsagePeriod.Day) }
    val data = state.data
    UsageContent(
        state = if (data == null) state else state.copy(data = data.copy(recorder = recorder.data?.recorder, devices = perDevice.data)),
        period = period,
        onPeriod = { period = it },
        actions = RecorderActions(
            vm.progress,
            vm.error?.text(),
            enabled = !LocalAppLocked.current,
            onInstall = vm::install,
            onSetUp = vm::setUp,
            onInstallDevices = vm::installDevices,
            onSetUpDevices = vm::setUpDevices,
        ),
        deviceNames = devices.data.orEmpty().associate { it.mac.uppercase() to deviceTitle(it, vendors) },
    )
}

/** Lo que se puede hacer para que el router guarde el consumo, y cómo va. */
class RecorderActions(
    val progress: ChangeProgress? = null,
    val error: String? = null,
    val enabled: Boolean = true,
    val onInstall: () -> Unit = {},
    val onSetUp: () -> Unit = {},
    val onInstallDevices: () -> Unit = {},
    val onSetUpDevices: () -> Unit = {},
)

@Composable
fun UsageContent(
    state: Loadable<UsageState>,
    period: UsagePeriod,
    onPeriod: (UsagePeriod) -> Unit,
    actions: RecorderActions = RecorderActions(),
    deviceNames: Map<String, String> = emptyMap(),
) {
    LoadableContent(state) { data, error ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            actions.progress?.let { item { ApplyingBanner(it) } }
            actions.error?.let { item { ErrorBanner(it) } }
            if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
            val recorder = data.recorder
            if (recorder is Recorder.Ready) item { History(recorder.history, period, onPeriod) }
            val perDevice = data.devices
            if (perDevice is DeviceRecorder.Ready) item { DevicesUsageCard(perDevice, deviceNames) }
            item { SinceBoot(data.sinceBoot) }
            if (recorder != null && recorder !is Recorder.Ready) item { RecorderCard(recorder, actions) }
            if (perDevice != null && perDevice !is DeviceRecorder.Ready) item { DeviceRecorderCard(perDevice, actions) }
        }
    }
}

/** Ofrece instalar vnStat o terminar de configurarlo, para ver el consumo por horas y días. */
@Composable
private fun RecorderCard(recorder: Recorder, actions: RecorderActions) {
    val enabled = actions.enabled && actions.progress == null
    when (recorder) {
        is Recorder.Missing -> OfferGroup(
            stringResource(R.string.usage_recorder_title),
            stringResource(R.string.usage_recorder_missing_body),
            action = stringResource(R.string.usage_recorder_install).takeIf { recorder.fits },
            onAction = actions.onInstall,
            enabled = enabled,
            problem = noSpace(R.string.usage_recorder_no_space, recorder.fits, recorder.freeKiB),
            actionTag = "usage-install",
            modifier = Modifier.testTag("usage-recorder"),
        )
        Recorder.NeedsSetup -> OfferGroup(
            stringResource(R.string.usage_recorder_title),
            stringResource(R.string.usage_recorder_setup_body),
            action = stringResource(R.string.usage_recorder_setup),
            onAction = actions.onSetUp,
            enabled = enabled,
            actionTag = "usage-setup",
            modifier = Modifier.testTag("usage-recorder"),
        )
        is Recorder.Ready -> Unit
    }
}

internal const val KIB = 1024L

/** Si no cabe, cuánto sitio queda ([message] con el libre en %s); si cabe, null. */
@Composable
internal fun noSpace(message: Int, fits: Boolean, freeKiB: Long?): String? =
    if (fits) null else stringResource(message, formatBytes((freeKiB ?: 0) * KIB))

/** Las últimas 24 horas, 7 días o 30 días: las pestañas, la gráfica y los totales del periodo. */
@Composable
private fun History(history: UsageHistory, period: UsagePeriod, onPeriod: (UsagePeriod) -> Unit) {
    if (history.hours.isEmpty() && history.days.isEmpty()) {
        Column {
            SectionHeader(stringResource(R.string.usage_history_title))
            GroupCard { GroupRow(stringResource(R.string.usage_history_empty), labelLines = 4, divider = false) }
        }
        return
    }
    val buckets = history.buckets(period)
    val totals = buckets.totals()
    Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
        PillTabs(
            UsagePeriod.entries.map { stringResource(it.label) },
            period.ordinal,
            onSelect = { onPeriod(UsagePeriod.entries[it]) },
            Modifier.testTag("usage-period"),
        )
        GroupCard { UsageChart(buckets, period, Modifier.padding(16.dp)) }
        Column {
            StatStrip(
                listOf(
                    Stat(stringResource(R.string.usage_received), formatBytes(totals.receivedBytes), LocalChartColors.current.received),
                    Stat(stringResource(R.string.usage_sent), formatBytes(totals.sentBytes), LocalChartColors.current.sent),
                    Stat(stringResource(R.string.usage_total), formatBytes(totals.totalBytes)),
                ),
            )
            GroupFooter(stringResource(R.string.usage_history_note), Modifier.padding(top = 6.dp))
        }
    }
}

/** Lo que cuenta el router sin instalar nada: desde que se encendió (o por qué no se puede). */
@Composable
private fun SinceBoot(usage: WanUsage?) {
    Column(Modifier.testTag("usage-since-boot")) {
        SectionHeader(stringResource(R.string.usage_since_boot_title))
        GroupCard {
            if (usage == null) {
                GroupRow(
                    stringResource(R.string.usage_unavailable_title),
                    supporting = stringResource(R.string.usage_unavailable_body),
                    supportingLines = 4,
                    divider = false,
                )
            } else {
                ValueRows(
                    listOfNotNull(
                        stringResource(R.string.usage_received) to formatBytes(usage.receivedBytes),
                        stringResource(R.string.usage_sent) to formatBytes(usage.sentBytes),
                        stringResource(R.string.usage_total) to formatBytes(usage.totalBytes),
                        usage.perDayBytes?.let { stringResource(R.string.usage_per_day) to formatBytes(it) },
                        stringResource(R.string.usage_since_boot_on_for) to formatDuration(usage.sinceSeconds),
                    ),
                )
            }
        }
        if (usage != null) GroupFooter(stringResource(R.string.usage_since_boot_note), Modifier.padding(top = 6.dp))
    }
}
