package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.ChannelAdvice
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine

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
    Text(stringResource(R.string.wifi_channel_title), style = MaterialTheme.typography.titleSmall)
    WifiHint(stringResource(R.string.wifi_channel_help))
    radios.forEach { radio -> ChannelRow(radio, searches[radio.name], enabled, onFind, onUse) }
}

@Composable
private fun ChannelRow(
    radio: WifiRadio,
    search: ChannelSearch?,
    enabled: Boolean,
    onFind: (String) -> Unit,
    onUse: (WifiRadio, Int) -> Unit,
) {
    val band = radio.band?.let { stringResource(it.label) } ?: radio.name
    val channel = radio.channel?.takeIf { it.toIntOrNull() != null } ?: stringResource(R.string.wifi_channel_auto)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val now = stringResource(R.string.wifi_channel_now, band, channel)
            Text(now, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { onFind(radio.name) },
                enabled = enabled && search != ChannelSearch.Searching,
                modifier = Modifier.testTag("wifi-channel-find-${radio.name}"),
            ) { Text(stringResource(R.string.wifi_channel_find)) }
        }
        when (search) {
            null -> Unit
            ChannelSearch.Searching -> WifiHint(stringResource(R.string.wifi_channel_searching))
            ChannelSearch.Failed -> Text(
                stringResource(R.string.wifi_channel_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            is ChannelSearch.Found -> ChannelResult(radio, search.advice, enabled, onUse)
        }
    }
}

@Composable
private fun ChannelResult(radio: WifiRadio, advice: ChannelAdvice, enabled: Boolean, onUse: (WifiRadio, Int) -> Unit) {
    val current = advice.currentNeighbors
    val text = when {
        !advice.better -> stringResource(R.string.wifi_channel_best_already, current ?: advice.bestNeighbors)
        current == null -> stringResource(R.string.wifi_channel_better_auto, advice.best, advice.bestNeighbors)
        else -> stringResource(R.string.wifi_channel_better, advice.best, advice.bestNeighbors, current)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("wifi-channel-result-${radio.name}"))
    if (advice.better) {
        Button(onClick = { onUse(radio, advice.best) }, enabled = enabled, modifier = Modifier.testTag("wifi-channel-use-${radio.name}")) {
            Text(stringResource(R.string.wifi_channel_use, advice.best))
        }
    }
}
