package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.SignalLevel
import dev.angel.wrtcontrol.openwrt.WifiPower
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.signalLevel
import dev.angel.wrtcontrol.openwrt.wifiPower
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine

/** Lo que supone poner la potencia de [radio] en [power]: si emite alguna red, se reinicia (y avisa). */
fun powerPreview(state: WifiState, radio: WifiRadio, power: WifiPower, phoneMacs: Set<String>): ChangePreview {
    val emitting = radio.up && state.networks.any { it.radio == radio.name && it.ifname != null }
    val notes = buildList {
        if (emitting) add(txt(R.string.wifi_country_preview_restart))
        if (emitting && phoneOnRadio(state, radio.name, phoneMacs)) add(txt(R.string.wifi_country_preview_phone))
    }
    val before = txt(wifiPower(radio.txpower, radio.maxPower).label)
    val line = PreviewLine(radio.band?.let { txt(it.label) } ?: raw(radio.name), before, txt(power.label))
    return ChangePreview(txt(R.string.wifi_power_preview_title), listOf(line), notes)
}

/** "Potencia": toda, media o baja en cada radio de la que el router dice el tope. */
@Composable
fun PowerSection(state: WifiState, enabled: Boolean, onPick: (WifiRadio, WifiPower) -> Unit) {
    val radios = state.radios.filter { it.maxPower != null }
    if (radios.isEmpty()) return
    Text(stringResource(R.string.wifi_power_title), style = MaterialTheme.typography.titleSmall)
    WifiHint(stringResource(R.string.wifi_power_help))
    radios.forEach { radio ->
        val current = wifiPower(radio.txpower, radio.maxPower)
        Column {
            Text(radio.band?.let { stringResource(it.label) } ?: radio.name, style = MaterialTheme.typography.bodyLarge)
            WifiPower.entries.forEach { power ->
                val tag = "wifi-power-${radio.name}-${power.name}"
                WifiOption(stringResource(power.label), selected = power == current, enabled = enabled, tag = tag) {
                    if (power != current) onPick(radio, power)
                }
            }
        }
    }
}

/**
 * "Conectados ahora": quién hay en cada banda y cómo le llega la señal al router. [names]: cómo
 * llama la app a cada aparato (MAC → nombre); el que no esté sale con su MAC.
 */
@Composable
fun ClientsSection(state: WifiState, names: Map<String, String>) {
    val networks = state.networks.filter { it.ifname != null && it.clients.isNotEmpty() }
    if (networks.isEmpty()) return
    Text(stringResource(R.string.wifi_clients_title), style = MaterialTheme.typography.titleSmall)
    networks.forEach { network ->
        val band = state.radios.firstOrNull { it.name == network.radio }?.band
        Column {
            WifiHint(band?.let { stringResource(it.label) } ?: network.radio)
            // Los de peor señal primero: son los que hay que mirar.
            network.clients.sortedBy { network.signals[it] ?: 0 }.forEach { mac ->
                ClientRow(names[mac] ?: mac, network.signals[mac])
            }
        }
    }
    val anyWeak = networks.any { n -> n.signals.values.any { signalLevel(it) == SignalLevel.Weak } }
    if (anyWeak) WifiHint(stringResource(R.string.wifi_clients_help))
}

@Composable
private fun ClientRow(name: String, signal: Int?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("wifi-client"), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (signal != null) {
            val level = signalLevel(signal)
            Text(
                stringResource(R.string.wifi_client_signal, stringResource(level.label), signal),
                style = MaterialTheme.typography.bodySmall,
                color = if (level == SignalLevel.Weak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
