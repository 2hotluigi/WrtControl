package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.DeviceRecorder
import dev.angel.wrtcontrol.openwrt.DeviceUsage
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.LocalChartColors
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.fraction

/** Cuántos aparatos se ven antes de "Ver todos". */
private const val TOP_DEVICES = 5

/**
 * Quién ha gastado más este periodo (nlbwmon), de más a menos, con una barra respecto al que más.
 * [names]: cómo se llama cada aparato (MAC en mayúsculas → nombre); si no se sabe, su MAC.
 */
@Composable
fun DevicesUsageCard(recorder: DeviceRecorder.Ready, names: Map<String, String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    InfoCard(stringResource(R.string.usage_devices_title), Modifier.testTag("usage-devices")) {
        Text(
            stringResource(if (recorder.monthly) R.string.usage_devices_period else R.string.usage_devices_period_other),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val devices = recorder.devices
        if (devices.isEmpty()) {
            Text(stringResource(R.string.usage_devices_empty), style = MaterialTheme.typography.bodyMedium)
            return@InfoCard
        }
        val top = devices.first().totalBytes
        devices.take(if (expanded) devices.size else TOP_DEVICES).forEach { usage ->
            DeviceUsageRow(usage, names[usage.mac] ?: usage.mac, top)
        }
        if (devices.size > TOP_DEVICES) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("usage-devices-more")) {
                Text(
                    if (expanded) {
                        stringResource(R.string.usage_devices_less)
                    } else {
                        stringResource(R.string.usage_devices_more, devices.size)
                    },
                )
            }
        }
    }
}

@Composable
private fun DeviceUsageRow(usage: DeviceUsage, name: String, top: Long) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatBytes(usage.totalBytes), style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { fraction(usage.totalBytes, top) },
            color = LocalChartColors.current.received,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.usage_devices_detail, formatBytes(usage.receivedBytes), formatBytes(usage.sentBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ofrece instalar nlbwmon o terminar de configurarlo, para ver lo que gasta cada aparato. */
@Composable
fun DeviceRecorderCard(recorder: DeviceRecorder, actions: RecorderActions) {
    val busy = actions.progress != null
    InfoCard(stringResource(R.string.usage_devices_offer_title), Modifier.testTag("usage-devices-offer")) {
        when (recorder) {
            is DeviceRecorder.Missing -> {
                Text(stringResource(R.string.usage_devices_missing_body), style = MaterialTheme.typography.bodyMedium)
                if (recorder.fits) {
                    Button(
                        onClick = actions.onInstallDevices,
                        enabled = actions.enabled && !busy,
                        modifier = Modifier.testTag("usage-devices-install"),
                    ) {
                        Text(stringResource(R.string.usage_recorder_install))
                    }
                } else {
                    Text(
                        stringResource(R.string.usage_devices_no_space, formatBytes((recorder.freeKiB ?: 0) * KIB)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            DeviceRecorder.NeedsSetup -> {
                Text(stringResource(R.string.usage_devices_setup_body), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = actions.onSetUpDevices,
                    enabled = actions.enabled && !busy,
                    modifier = Modifier.testTag("usage-devices-setup"),
                ) {
                    Text(stringResource(R.string.usage_recorder_setup))
                }
            }
            is DeviceRecorder.Ready -> Unit
        }
    }
}

private const val KIB = 1024L
