package dev.angel.wrtcontrol.ui.home

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.openwrt.WifiEdit
import dev.angel.wrtcontrol.openwrt.WifiSecurity
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.plural
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine

/** Lo que tiene ahora "Tu Wi-Fi", como punto de partida del formulario. */
fun currentWifi(state: WifiState): WifiEdit {
    val first = state.home.firstOrNull { it.enabled } ?: state.home.firstOrNull()
    return WifiEdit(
        ssid = first?.ssid.orEmpty(),
        key = first?.key.orEmpty(),
        security = first?.security ?: WifiSecurity.Wpa2Wpa3,
        radios = state.homeOn,
    )
}

private const val HIDDEN_KEY = "••••••"

/**
 * Lo que cambia al guardar [edit] sobre [state]. [phoneMacs]: este teléfono. Trae avisos (y se
 * enseña antes de aplicar) cuando el cambio saca a alguien del Wi-Fi o lo deja sin protección.
 */
fun wifiPreview(state: WifiState, edit: WifiEdit, phoneMacs: Set<String>): ChangePreview {
    val now = currentWifi(state)
    val keyChanged = edit.security != WifiSecurity.Open && edit.key != now.key
    val credentials = edit.ssid != now.ssid || edit.security != now.security || keyChanged
    // Quién se queda fuera: todos si cambian el nombre o la clave; si no, los de las bandas que se apagan.
    val affected = state.home.filter { it.ifname != null && (credentials || it.radio !in edit.radios) }.flatMap { it.clients }.toSet()
    val notes = buildList {
        val others = (affected - phoneMacs).size
        if (others > 0) add(plural(R.plurals.wifi_preview_clients, others, others))
        if (affected.any { it in phoneMacs } && edit.radios.isNotEmpty()) add(txt(R.string.wifi_preview_phone, edit.ssid))
        if (edit.security == WifiSecurity.Open && now.security != WifiSecurity.Open) add(txt(R.string.wifi_preview_open))
        if (edit.radios.isEmpty() && now.radios.isNotEmpty()) add(txt(R.string.wifi_preview_all_off))
    }
    return ChangePreview(txt(R.string.wifi_preview_title), previewLines(state, now, edit, keyChanged), notes)
}

private fun previewLines(state: WifiState, now: WifiEdit, edit: WifiEdit, keyChanged: Boolean): List<PreviewLine> = buildList {
    if (edit.ssid != now.ssid) add(PreviewLine(txt(R.string.wifi_name_label), raw(now.ssid), raw(edit.ssid)))
    if (edit.security != now.security) {
        add(PreviewLine(txt(R.string.wifi_security_title), txt(now.security.label), txt(edit.security.label)))
    }
    if (keyChanged) add(PreviewLine(txt(R.string.wifi_password_label), raw(HIDDEN_KEY), txt(R.string.wifi_preview_password_new)))
    for (radio in state.radios) {
        val before = radio.name in now.radios
        val after = radio.name in edit.radios
        if (before != after) add(PreviewLine(radio.band?.let { txt(it.label) } ?: raw(radio.name), onOff(before), onOff(after)))
    }
}

private fun onOff(on: Boolean): Txt = txt(if (on) R.string.wifi_preview_on else R.string.wifi_preview_off)

/** ¿Habla este teléfono con el router por "Tu Wi-Fi"? */
fun phoneOnWifi(state: WifiState, phoneMacs: Set<String>): Boolean = state.home.any { n -> n.clients.any { it in phoneMacs } }
