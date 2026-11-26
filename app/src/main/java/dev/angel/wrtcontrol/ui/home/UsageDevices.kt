package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.angel.wrtcontrol.ui.LocalChartColors
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.MeterBar
import dev.angel.wrtcontrol.ui.design.OfferGroup
import dev.angel.wrtcontrol.ui.design.SectionHeader
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
    val palette = LocalPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val devices = recorder.devices
    val more = devices.size > TOP_DEVICES
    Column(Modifier.testTag("usage-devices")) {
        SectionHeader(stringResource(R.string.usage_devices_title))
        GroupCard {
            if (devices.isEmpty()) {
                GroupRow(stringResource(R.string.usage_devices_empty), labelLines = 4, divider = false)
            } else {
                val top = devices.first().totalBytes
                val shown = devices.take(if (expanded) devices.size else TOP_DEVICES)
                shown.forEachIndexed { i, usage ->
                    DeviceUsageRow(usage, names[usage.mac] ?: usage.mac, top, divider = i < shown.lastIndex || more)
                }
            }
            if (more) {
                GroupRow(
                    if (expanded) {
                        stringResource(R.string.usage_devices_less)
                    } else {
                        stringResource(R.string.usage_devices_more, devices.size)
                    },
                    labelColor = palette.accent,
                    divider = false,
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("usage-devices-more"),
                )
            }
        }
        GroupFooter(
            stringResource(if (recorder.monthly) R.string.usage_devices_period else R.string.usage_devices_period_other),
            Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DeviceUsageRow(usage: DeviceUsage, name: String, top: Long, divider: Boolean) {
    val palette = LocalPalette.current
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatBytes(usage.totalBytes), style = MaterialTheme.typography.bodyLarge, color = palette.secondary)
            }
            MeterBar(fraction(usage.totalBytes, top), color = LocalChartColors.current.received)
            Text(
                stringResource(R.string.usage_devices_detail, formatBytes(usage.receivedBytes), formatBytes(usage.sentBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
            )
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = palette.separator)
    }
}

/**
 * Ofrece instalar nlbwmon o terminar de configurarlo, para ver lo que gasta cada aparato; o, si ya
 * cuenta pero no lo de IPv6 (va en relay), ajustarlo.
 */
@Composable
fun DeviceRecorderCard(recorder: DeviceRecorder, actions: RecorderActions) {
    val enabled = actions.enabled && actions.progress == null
    when (recorder) {
        is DeviceRecorder.Missing -> OfferGroup(
            stringResource(R.string.usage_devices_offer_title),
            stringResource(R.string.usage_devices_missing_body),
            action = stringResource(R.string.usage_recorder_install).takeIf { recorder.fits },
            onAction = actions.onInstallDevices,
            enabled = enabled,
            problem = noSpace(R.string.usage_devices_no_space, recorder.fits, recorder.freeKiB),
            actionTag = "usage-devices-install",
            modifier = Modifier.testTag("usage-devices-offer"),
        )
        DeviceRecorder.NeedsSetup -> OfferGroup(
            stringResource(R.string.usage_devices_offer_title),
            stringResource(R.string.usage_devices_setup_body),
            action = stringResource(R.string.usage_recorder_setup),
            onAction = actions.onSetUpDevices,
            enabled = enabled,
            actionTag = "usage-devices-setup",
            modifier = Modifier.testTag("usage-devices-offer"),
        )
        is DeviceRecorder.Ready -> if (recorder.missesIpv6) {
            OfferGroup(
                stringResource(R.string.usage_devices_ipv6_title),
                stringResource(R.string.usage_devices_ipv6_body),
                action = stringResource(R.string.usage_devices_ipv6_action),
                onAction = actions.onSetUpDevices,
                enabled = enabled,
                actionTag = "usage-devices-ipv6",
                modifier = Modifier.testTag("usage-devices-ipv6-offer"),
            )
        }
    }
}
