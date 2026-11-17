package dev.angel.wrtcontrol.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.openwrt.WIDTH_160
import dev.angel.wrtcontrol.openwrt.WIDTH_20
import dev.angel.wrtcontrol.openwrt.WIDTH_40
import dev.angel.wrtcontrol.openwrt.WIDTH_80
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.channelFor
import dev.angel.wrtcontrol.openwrt.widthChoices
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine

/** Cómo llama la app a cada ancho de cada banda (por lo que da, no por los MHz), o null si no lo ofrece. */
@StringRes
fun widthLabel(band: WifiBand?, width: Int): Int? = when (band to width) {
    WifiBand.Band2 to WIDTH_20 -> R.string.wifi_width_2_20
    WifiBand.Band2 to WIDTH_40 -> R.string.wifi_width_2_40
    WifiBand.Band5 to WIDTH_80 -> R.string.wifi_width_5_80
    WifiBand.Band5 to WIDTH_160 -> R.string.wifi_width_5_160
    else -> null
}

/** ¿Habla este teléfono con el router por una red de [radio]? */
fun phoneOnRadio(state: WifiState, radio: String, phoneMacs: Set<String>): Boolean =
    state.networks.any { n -> n.radio == radio && n.clients.any { it in phoneMacs } }

/**
 * Lo que supone poner [radio] a [width] MHz. Si esa radio emite alguna red, se reinicia (y avisa);
 * a 160 MHz, además, el router escucha un minuto antes de volver a emitir.
 */
fun widthPreview(state: WifiState, radio: WifiRadio, width: Int, phoneMacs: Set<String>): ChangePreview {
    fun name(mhz: Int?): Txt = widthName(radio.band, mhz)
    val emitting = radio.up && state.networks.any { it.radio == radio.name && it.ifname != null }
    val notes = buildList {
        if (emitting) add(txt(R.string.wifi_country_preview_restart))
        if (emitting && width == WIDTH_160) add(txt(R.string.wifi_width_preview_radar))
        if (emitting && phoneOnRadio(state, radio.name, phoneMacs)) add(txt(R.string.wifi_country_preview_phone))
    }
    val lines = buildList {
        add(PreviewLine(radio.band?.let { txt(it.label) } ?: raw(radio.name), name(radio.width), name(width)))
        channelFor(width, radio.channel)?.let { add(PreviewLine(txt(R.string.wifi_channel_label), raw(radio.channel.orEmpty()), raw(it))) }
    }
    return ChangePreview(txt(R.string.wifi_width_preview_title), lines, notes)
}

/** El nombre de un ancho para la vista previa: el de la opción, o los MHz si la app no lo ofrece. */
private fun widthName(band: WifiBand?, mhz: Int?): Txt = when (mhz) {
    null -> txt(R.string.wifi_width_unknown)
    else -> widthLabel(band, mhz)?.let { txt(it) } ?: txt(R.string.wifi_width_mhz, mhz)
}

/**
 * "Velocidad y alcance": por cada banda en la que la radio da a elegir, lo recomendado o lo más
 * rápido. La máxima de 5 GHz necesita el país (sin él no se puede emitir en esos canales).
 */
@Composable
fun WidthSection(state: WifiState, enabled: Boolean, onPick: (WifiRadio, Int) -> Unit) {
    val radios = state.radios.filter { radio -> widthChoices(radio.band).count { radio.offers(it) } > 1 }
    if (radios.isEmpty()) return
    Text(stringResource(R.string.wifi_width_title), style = MaterialTheme.typography.titleSmall)
    radios.forEach { radio -> RadioWidth(radio, needsCountry = radio.band == WifiBand.Band5 && state.country == null, enabled, onPick) }
}

@Composable
private fun RadioWidth(radio: WifiRadio, needsCountry: Boolean, enabled: Boolean, onPick: (WifiRadio, Int) -> Unit) {
    val choices = widthChoices(radio.band).filter { radio.offers(it) }
    Column {
        Text(radio.band?.let { stringResource(it.label) } ?: radio.name, style = MaterialTheme.typography.bodyLarge)
        choices.forEach { width ->
            WifiOption(
                label = widthLabel(radio.band, width)?.let { stringResource(it) } ?: stringResource(R.string.wifi_width_mhz, width),
                selected = radio.width == width,
                enabled = enabled && !(needsCountry && width == WIDTH_160),
                tag = "wifi-width-${radio.name}-$width",
            ) { if (radio.width != width) onPick(radio, width) }
        }
        WifiHint(stringResource(if (radio.band == WifiBand.Band2) R.string.wifi_width_2_help else R.string.wifi_width_5_help))
        if (needsCountry) WifiHint(stringResource(R.string.wifi_width_needs_country))
        radio.width?.takeIf { it !in choices }?.let { WifiHint(stringResource(R.string.wifi_width_now, it)) }
    }
}

/** Una opción de las que se elige una (ancho, potencia). */
@Composable
internal fun WifiOption(label: String, selected: Boolean, enabled: Boolean, tag: String, onSelect: () -> Unit) {
    val select = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
    Row(Modifier.fillMaxWidth().then(select).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
internal fun WifiHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
