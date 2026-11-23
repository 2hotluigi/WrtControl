package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.UsageState
import dev.angel.wrtcontrol.openwrt.UsageHistory
import dev.angel.wrtcontrol.openwrt.UsagePeriod
import dev.angel.wrtcontrol.openwrt.WanUsage
import dev.angel.wrtcontrol.openwrt.totals
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow
import dev.angel.wrtcontrol.ui.LoadableContent
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatDuration
import dev.angel.wrtcontrol.ui.watchAsState
import kotlin.time.Duration.Companion.seconds

/** Los contadores suben a cada momento, pero nadie mira esta pantalla segundo a segundo. */
private val USAGE_REFRESH = 10.seconds

/** Más → Uso de internet: cuánto se ha descargado y subido. */
@Composable
fun UsageScreen() {
    val state by LocalRepository.current.monitor.usage.watchAsState(USAGE_REFRESH)
    var period by rememberSaveable { mutableStateOf(UsagePeriod.Day) }
    UsageContent(state, period) { period = it }
}

@Composable
fun UsageContent(state: Loadable<UsageState>, period: UsagePeriod, onPeriod: (UsagePeriod) -> Unit) {
    LoadableContent(state) { data, error ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) item { ErrorBanner(stringResource(R.string.common_stale_data, error)) }
            data.history?.let { history -> item { HistoryCard(history, period, onPeriod) } }
            item {
                val sinceBoot = data.sinceBoot
                if (sinceBoot != null) {
                    SinceBootCard(sinceBoot)
                } else {
                    NoticeCard(stringResource(R.string.usage_unavailable_title), stringResource(R.string.usage_unavailable_body))
                }
            }
        }
    }
}

/** Las últimas 24 horas, 7 días o 30 días, con los totales del periodo debajo. */
@Composable
private fun HistoryCard(history: UsageHistory, period: UsagePeriod, onPeriod: (UsagePeriod) -> Unit) {
    val buckets = history.buckets(period)
    val totals = buckets.totals()
    InfoCard(stringResource(R.string.usage_history_title)) {
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
