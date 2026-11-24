package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Los contadores suben a cada momento, pero nadie mira esta pantalla segundo a segundo. */
private val USAGE_REFRESH = 10.seconds

/** Lo guardado por horas y días cambia cada hora: se mira de minuto en minuto. */
private val RECORDER_REFRESH = 1.minutes

/** Instalar o configurar vnStat, con el cambio en curso y su error. */
class UsageViewModel(private val router: RouterAccess, private val monitor: RouterMonitor) : ViewModel() {
    private val changes = ChangeRunner(viewModelScope)

    val progress: ChangeProgress? get() = changes.current

    val error: Txt? get() = changes.error

    fun install() = run(txt(R.string.usage_busy_installing), installs = true) { it.usage.install(it.interfaces()) }

    fun setUp() = run(txt(R.string.usage_busy_setup)) { it.usage.setUp(it.interfaces()) }

    private fun run(label: Txt, installs: Boolean = false, block: suspend (OpenWrtApi) -> Unit) {
        val refresh = {
            // Aunque falle, la instalación pudo quedar a medias: se vuelve a mirar qué hay.
            if (installs) {
                monitor.packages.refresh()
                monitor.capabilities.refresh()
            }
            monitor.usageRecorder.refresh()
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
    var period by rememberSaveable { mutableStateOf(UsagePeriod.Day) }
    val data = state.data
    UsageContent(
        state = if (data == null) state else state.copy(data = data.copy(recorder = recorder.data?.recorder)),
        period = period,
        onPeriod = { period = it },
        actions = RecorderActions(vm.progress, vm.error?.text(), enabled = !LocalAppLocked.current, vm::install, vm::setUp),
    )
}

/** Lo que se puede hacer para que el router guarde el consumo, y cómo va. */
class RecorderActions(
    val progress: ChangeProgress? = null,
    val error: String? = null,
    val enabled: Boolean = true,
    val onInstall: () -> Unit = {},
    val onSetUp: () -> Unit = {},
)

@Composable
fun UsageContent(
    state: Loadable<UsageState>,
    period: UsagePeriod,
    onPeriod: (UsagePeriod) -> Unit,
    actions: RecorderActions = RecorderActions(),
) {
    LoadableContent(state) { data, error ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            actions.progress?.let { item { ApplyingBanner(it) } }
            actions.error?.let { item { ErrorBanner(it) } }
            if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
            val recorder = data.recorder
            if (recorder is Recorder.Ready) item { HistoryCard(recorder.history, period, onPeriod) }
            item {
                val sinceBoot = data.sinceBoot
                if (sinceBoot != null) {
                    SinceBootCard(sinceBoot)
                } else {
                    NoticeCard(stringResource(R.string.usage_unavailable_title), stringResource(R.string.usage_unavailable_body))
                }
            }
            if (recorder != null && recorder !is Recorder.Ready) item { RecorderCard(recorder, actions) }
        }
    }
}

/** Ofrece instalar vnStat o terminar de configurarlo, para ver el consumo por horas y días. */
@Composable
private fun RecorderCard(recorder: Recorder, actions: RecorderActions) {
    val busy = actions.progress != null
    InfoCard(stringResource(R.string.usage_recorder_title), Modifier.testTag("usage-recorder")) {
        when (recorder) {
            is Recorder.Missing -> {
                Text(stringResource(R.string.usage_recorder_missing_body), style = MaterialTheme.typography.bodyMedium)
                if (recorder.fits) {
                    Button(onClick = actions.onInstall, enabled = actions.enabled && !busy, modifier = Modifier.testTag("usage-install")) {
                        Text(stringResource(R.string.usage_recorder_install))
                    }
                } else {
                    Text(
                        stringResource(R.string.usage_recorder_no_space, formatBytes((recorder.freeKiB ?: 0) * KIB)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Recorder.NeedsSetup -> {
                Text(stringResource(R.string.usage_recorder_setup_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions.onSetUp, enabled = actions.enabled && !busy, modifier = Modifier.testTag("usage-setup")) {
                    Text(stringResource(R.string.usage_recorder_setup))
                }
            }
            is Recorder.Ready -> Unit
        }
    }
}

private const val KIB = 1024L

/** Las últimas 24 horas, 7 días o 30 días, con los totales del periodo debajo. */
@Composable
private fun HistoryCard(history: UsageHistory, period: UsagePeriod, onPeriod: (UsagePeriod) -> Unit) {
    val buckets = history.buckets(period)
    val totals = buckets.totals()
    InfoCard(stringResource(R.string.usage_history_title)) {
        if (history.hours.isEmpty() && history.days.isEmpty()) {
            Text(stringResource(R.string.usage_history_empty), style = MaterialTheme.typography.bodyMedium)
            return@InfoCard
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UsagePeriod.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == period,
                    onClick = { onPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, UsagePeriod.entries.size),
                    modifier = Modifier.testTag("usage-period-${p.name}"),
                ) { Text(stringResource(p.label)) }
            }
        }
        UsageChart(buckets, period)
        InfoRow(stringResource(R.string.usage_received), formatBytes(totals.receivedBytes))
        InfoRow(stringResource(R.string.usage_sent), formatBytes(totals.sentBytes))
        InfoRow(stringResource(R.string.usage_total), formatBytes(totals.totalBytes))
        Text(
            stringResource(R.string.usage_history_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lo que cuenta el router sin instalar nada: desde que se encendió. */
@Composable
private fun SinceBootCard(usage: WanUsage) {
    InfoCard(stringResource(R.string.usage_since_boot_title), Modifier.testTag("usage-since-boot")) {
        InfoRow(stringResource(R.string.usage_received), formatBytes(usage.receivedBytes))
        InfoRow(stringResource(R.string.usage_sent), formatBytes(usage.sentBytes))
        InfoRow(stringResource(R.string.usage_total), formatBytes(usage.totalBytes))
        usage.perDayBytes?.let { InfoRow(stringResource(R.string.usage_per_day), formatBytes(it)) }
        InfoRow(stringResource(R.string.usage_since_boot_on_for), formatDuration(usage.sinceSeconds))
        Text(
            stringResource(R.string.usage_since_boot_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
