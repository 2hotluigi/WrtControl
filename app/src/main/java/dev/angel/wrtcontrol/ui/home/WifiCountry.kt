package dev.angel.wrtcontrol.ui.home

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.WifiCountry
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.PreviewLine
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.SectionHeader
import java.util.Locale

/** Nombre del país [code] en el idioma del teléfono; [fallback] (el del router) si el teléfono no lo conoce. */
fun countryName(code: String, fallback: String? = null, locale: Locale = Locale.getDefault()): String {
    val name = runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()
    return if (name.isNullOrBlank() || name == code) fallback ?: code else name
}

/**
 * El país en el que parece estar el teléfono (por la red móvil, la SIM o, si no, su configuración),
 * siempre que el router lo conozca. No pide ningún permiso.
 */
fun suggestedCountry(context: Context, countries: List<WifiCountry>): String? {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    return listOf(telephony?.networkCountryIso, telephony?.simCountryIso, Locale.getDefault().country)
        .map { it.orEmpty().uppercase(Locale.ROOT) }
        .firstOrNull { code -> countries.any { it.code == code } }
}

/**
 * Lo que supone poner el país [code]: las radios que emiten alguna red se reinician (y avisa). Con
 * el Wi-Fi apagado no molesta a nadie y se aplica sin preguntar: una radio puede estar "levantada"
 * sin emitir nada (así está el router de fábrica con sus redes apagadas).
 */
fun countryPreview(state: WifiState, code: String, phoneMacs: Set<String>): ChangePreview {
    val emitting = state.networks.filter { it.ifname != null }.map { it.radio }.toSet()
    val up = state.radios.filter { it.up && it.name in emitting }
    val notes = buildList {
        if (up.isNotEmpty()) add(txt(R.string.wifi_country_preview_restart))
        if (up.any { it.mayWaitForRadar }) add(txt(R.string.wifi_country_preview_radar))
        if (phoneOnWifi(state, phoneMacs)) add(txt(R.string.wifi_country_preview_phone))
    }
    val before = state.country?.let { raw(countryName(it)) } ?: txt(R.string.wifi_country_none)
    val line = PreviewLine(txt(R.string.wifi_country_title), before, raw(countryName(code)))
    return ChangePreview(txt(R.string.wifi_country_preview_title), listOf(line), notes)
}

/**
 * El país del Wi-Fi: cuál es y cómo cambiarlo; si falta, un aviso que propone el del teléfono
 * ([suggested]). [countries]: los que conoce el router (null: aún cargando).
 */
@Composable
fun CountrySection(state: WifiState, countries: List<WifiCountry>?, suggested: String?, enabled: Boolean, onPick: (String) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = state.country
    // En el idioma en que se ve la app, que puede no ser el de Locale.getDefault().
    val locale = LocalConfiguration.current.locales[0]
    val name = { code: String -> countryName(code, countries.orEmpty().firstOrNull { it.code == code }?.name, locale) }
    if (current == null) {
        val missing = state.radios.any { it.country == null }
        NoticeCard(
            title = stringResource(if (missing) R.string.wifi_country_missing_title else R.string.wifi_country_title),
            text = stringResource(if (missing) R.string.wifi_country_missing_body else R.string.wifi_country_differs),
            modifier = Modifier.testTag("wifi-country-missing"),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (suggested != null) {
                    Button(onClick = { onPick(suggested) }, enabled = enabled, modifier = Modifier.testTag("wifi-country-use")) {
                        Text(stringResource(R.string.wifi_country_use, name(suggested)))
                    }
                }
                TextButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag("wifi-country-pick")) {
                    Text(stringResource(if (suggested != null) R.string.wifi_country_other else R.string.wifi_country_choose))
                }
            }
        }
    } else {
        Column {
            SectionHeader(stringResource(R.string.wifi_country_title))
            GroupCard {
                GroupRow(
                    name(current),
                    chevron = enabled,
                    divider = false,
                    onClick = { if (enabled) picking = true },
                    modifier = Modifier.testTag("wifi-country-pick"),
                )
            }
            GroupFooter(stringResource(R.string.wifi_country_help), Modifier.padding(top = 6.dp))
        }
    }
    if (picking) {
        CountryPicker(countries, onDismiss = { picking = false }) {
            picking = false
            if (it != current) onPick(it)
        }
    }
}

/** Lista de países con buscador, por su nombre en el idioma del teléfono. */
@Composable
private fun CountryPicker(countries: List<WifiCountry>?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    val named = remember(countries, locale) {
        countries.orEmpty().map { it.code to countryName(it.code, it.name, locale) }.sortedBy { it.second }
    }
    val wanted = query.trim()
    val shown = named.filter { (code, name) -> name.contains(wanted, ignoreCase = true) || code.equals(wanted, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_country_choose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    countries == null -> CircularProgressIndicator()
                    countries.isEmpty() -> Text(stringResource(R.string.wifi_country_list_error))
                    else -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.wifi_country_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("wifi-country-search"),
                        )
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(shown, key = { it.first }) { (code, name) ->
                                val row = Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 12.dp)
                                Text("$name ($code)", modifier = row.testTag("wifi-country-$code"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.preview_cancel)) } },
    )
}
