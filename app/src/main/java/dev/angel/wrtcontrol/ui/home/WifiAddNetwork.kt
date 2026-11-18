package dev.angel.wrtcontrol.ui.home

import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.WifiEdit
import dev.angel.wrtcontrol.openwrt.WifiSecurity
import dev.angel.wrtcontrol.ui.NoticeCard

/*
 * Tras cambiar el nombre o la contraseña del Wi-Fi, este teléfono no conoce la red nueva: hay que
 * buscarla en Ajustes y escribir la clave. Desde Android 11, `ACTION_WIFI_ADD_NETWORKS` enseña un
 * diálogo del sistema ("¿Guardar esta red?") con la red ya rellenada: un toque. En versiones
 * anteriores no existe, y el botón abre los ajustes de Wi-Fi.
 */

/** ¿Hay que ofrecer guardar la red al pasar de [before] a [after]? Si se cambió cómo se entra y sigue encendida. */
fun offersNewNetwork(before: WifiEdit, after: WifiEdit): Boolean =
    after.radios.isNotEmpty() && (before.ssid != after.ssid || before.key != after.key || before.security != after.security)

/** Cómo se describe [edit] a Android para guardarla. */
@RequiresApi(Build.VERSION_CODES.Q)
private fun suggestion(edit: WifiEdit): WifiNetworkSuggestion {
    val builder = WifiNetworkSuggestion.Builder().setSsid(edit.ssid)
    when (edit.security) {
        // Con WPA2/WPA3, la red acepta las dos: se guarda como WPA2, que entra igual.
        WifiSecurity.Wpa2Wpa3, WifiSecurity.Wpa2 -> builder.setWpa2Passphrase(edit.key)
        WifiSecurity.Wpa3 -> builder.setWpa3Passphrase(edit.key)
        WifiSecurity.Open -> Unit
    }
    return builder.build()
}

/** "Guarda la red en este teléfono": la red [edit], que se acaba de poner en el router. */
@Composable
fun AddNetworkCard(edit: WifiEdit, onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onDone() }
    NoticeCard(
        stringResource(R.string.wifi_add_title, edit.ssid),
        stringResource(R.string.wifi_add_body),
        Modifier.testTag("wifi-add-network"),
    ) {
        FilledTonalButton(onClick = {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
                    Settings.EXTRA_WIFI_NETWORK_LIST,
                    arrayListOf(suggestion(edit)),
                )
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            launcher.launch(intent)
        }) {
            Text(stringResource(R.string.wifi_add_action))
        }
    }
}
