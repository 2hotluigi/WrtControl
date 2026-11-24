package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.SignalLevel
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiPower
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.signalLevel
import dev.angel.wrtcontrol.openwrt.wifiPower
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine
import dev.angel.wrtcontrol.ui.design.CheckRow
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.SectionHeader

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
    radios.forEachIndexed { r, radio ->
        val current = wifiPower(radio.txpower, radio.maxPower)
        val band = radio.band?.let { stringResource(it.label) } ?: radio.name
        Column {
            SectionHeader(stringResource(R.string.wifi_section_band, stringResource(R.string.wifi_power_title), band))
            GroupCard {
                WifiPower.entries.forEachIndexed { i, power ->
                    CheckRow(
                        stringResource(power.label),
                        selected = power == current,
                        onClick = { if (power != current) onPick(radio, power) },
                        modifier = Modifier.testTag("wifi-power-${radio.name}-${power.name}"),
                        enabled = enabled,
                        divider = i < WifiPower.entries.lastIndex,
                    )
                }
            }
            if (r == radios.lastIndex) GroupFooter(stringResource(R.string.wifi_power_help), Modifier.padding(top = 6.dp))
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
    // Los de peor señal primero: son los que hay que mirar.
    val clients = networks.flatMap { network ->
        val band = state.radios.firstOrNull { it.name == network.radio }?.band
        network.clients.sortedBy { network.signals[it] ?: 0 }.map { mac -> Triple(names[mac] ?: mac, band, network.signals[mac]) }
    }
    val anyWeak = networks.any { n -> n.signals.values.any { signalLevel(it) == SignalLevel.Weak } }
    Column {
        SectionHeader(stringResource(R.string.wifi_clients_title))
        GroupCard {
            clients.forEachIndexed { i, (name, band, signal) -> ClientRow(name, band, signal, divider = i < clients.lastIndex) }
        }
        if (anyWeak) GroupFooter(stringResource(R.string.wifi_clients_help), Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ClientRow(name: String, band: WifiBand?, signal: Int?, divider: Boolean) {
    val level = signal?.let { signalLevel(it) }
    GroupRow(
        name,
        value = signal?.let { stringResource(R.string.wifi_client_dbm, it) },
        valueColor = if (level == SignalLevel.Weak) LocalPalette.current.bad else null,
        supporting = listOfNotNull(band?.let { stringResource(it.label) }, level?.let { stringResource(it.label) }).joinToString(" · ")
            .ifEmpty { null },
        divider = divider,
        modifier = Modifier.testTag("wifi-client"),
    )
}
