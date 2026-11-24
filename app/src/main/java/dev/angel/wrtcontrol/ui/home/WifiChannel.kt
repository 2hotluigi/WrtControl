package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.ChannelAdvice
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.SectionHeader

/** Lo que supone pasar [radio] al canal [channel]: si emite alguna red, se reinicia (y avisa). */
fun channelPreview(state: WifiState, radio: WifiRadio, channel: Int, phoneMacs: Set<String>): ChangePreview {
    val emitting = radio.up && state.networks.any { it.radio == radio.name && it.ifname != null }
    val notes = buildList {
        if (emitting) add(txt(R.string.wifi_country_preview_restart))
        if (emitting && phoneOnRadio(state, radio.name, phoneMacs)) add(txt(R.string.wifi_country_preview_phone))
    }
    val before = radio.channel?.takeIf { it.toIntOrNull() != null }?.let { raw(it) } ?: txt(R.string.wifi_channel_auto)
    val line = PreviewLine(radio.band?.let { txt(it.label) } ?: raw(radio.name), before, raw(channel.toString()))
    return ChangePreview(txt(R.string.wifi_channel_preview_title), listOf(line), notes)
}

/**
 * "Canal": en qué canal está cada banda y un botón para que el router busque el más libre.
 * [searches]: cómo va la búsqueda de cada radio.
 */
@Composable
fun ChannelSection(
    state: WifiState,
    searches: Map<String, ChannelSearch>,
    enabled: Boolean,
    onFind: (String) -> Unit,
    onUse: (WifiRadio, Int) -> Unit,
) {
    val radios = state.radios.filter { it.band == WifiBand.Band2 || it.band == WifiBand.Band5 }
    if (radios.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.wifi_channel_title))
        GroupCard {
            radios.forEachIndexed { i, radio ->
                ChannelRow(radio, searches[radio.name], enabled, onFind, onUse, divider = i < radios.lastIndex)
            }
        }
        GroupFooter(stringResource(R.string.wifi_channel_help), Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ChannelRow(
    radio: WifiRadio,
    search: ChannelSearch?,
    enabled: Boolean,
    onFind: (String) -> Unit,
    onUse: (WifiRadio, Int) -> Unit,
    divider: Boolean,
) {
    val palette = LocalPalette.current
    val band = radio.band?.let { stringResource(it.label) } ?: radio.name
    val channel = radio.channel?.takeIf { it.toIntOrNull() != null } ?: stringResource(R.string.wifi_channel_auto)
    val canFind = enabled && search != ChannelSearch.Searching
    Column {
        GroupRow(
            band,
            value = channel,
            divider = false,
            trailing = {
                Text(
                    stringResource(R.string.wifi_channel_find),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (canFind) palette.accent else palette.tertiary,
                    modifier = Modifier
                        .clickable(enabled = canFind) { onFind(radio.name) }
                        .padding(vertical = 8.dp)
                        .testTag("wifi-channel-find-${radio.name}"),
                )
            },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (search) {
                null -> Unit
                ChannelSearch.Searching -> Text(
                    stringResource(R.string.wifi_channel_searching),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary,
                )
                ChannelSearch.Failed -> Text(
                    stringResource(R.string.wifi_channel_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.bad,
                )
                is ChannelSearch.Found -> ChannelResult(radio, search.advice, enabled, onUse)
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = palette.separator)
    }
}

@Composable
private fun ChannelResult(radio: WifiRadio, advice: ChannelAdvice, enabled: Boolean, onUse: (WifiRadio, Int) -> Unit) {
    val palette = LocalPalette.current
    val current = advice.currentNeighbors
    val text = when {
        !advice.better -> stringResource(R.string.wifi_channel_best_already, current ?: advice.bestNeighbors)
        current == null -> stringResource(R.string.wifi_channel_better_auto, advice.best, advice.bestNeighbors)
        else -> stringResource(R.string.wifi_channel_better, advice.best, advice.bestNeighbors, current)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = palette.secondary,
        modifier = Modifier.testTag("wifi-channel-result-${radio.name}"),
    )
    if (advice.better) {
        Text(
            stringResource(R.string.wifi_channel_use, advice.best),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) palette.accent else palette.tertiary,
            modifier = Modifier
                .clickable(enabled = enabled) { onUse(radio, advice.best) }
                .padding(vertical = 4.dp)
                .testTag("wifi-channel-use-${radio.name}"),
        )
    }
}
