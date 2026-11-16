package dev.angel.wrtcontrol.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Android 17 (API 37) no deja a las apps que lo tienen como objetivo hablar con la red local (IPs
 * privadas: 192.168.x, 10.x…) sin este permiso, que se pide en tiempo de ejecución. Sin él, los
 * envíos dan EPERM y las conexiones TCP no llegan: la app no ve el router en casa.
 */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Primera versión de Android que lo exige. */
private const val LOCAL_NETWORK_SDK = 37

fun localNetworkAllowed(context: Context): Boolean =
    (
        Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
            context.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        ).also { LocalNetworkStatus.missing = !it }

/** Si falta el permiso, para explicar los fallos de conexión (sin él, TCP no da error: no llega). */
object LocalNetworkStatus {
    @Volatile
    var missing = false
}

/**
 * Antes de la app, pide el permiso de red local si hace falta y explica para qué. Se puede seguir
 * sin él (solo llegará al router por el acceso remoto). Se vuelve a mirar al volver a la app: puede
 * que lo haya dado en los ajustes.
 */
@Composable
fun LocalNetworkGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var allowed by rememberSaveable { mutableStateOf(localNetworkAllowed(context)) }
    var skipped by rememberSaveable { mutableStateOf(false) }
    // Tras un "no", Android puede no volver a mostrar el diálogo: entonces, a los ajustes.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        LocalNetworkStatus.missing = !granted
        blocked = !granted && activity?.shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK) == false
    }
    LifecycleResumeEffect(Unit) {
        allowed = localNetworkAllowed(context)
        onPauseOrDispose {}
    }
    if (allowed || skipped) {
        content()
        return
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Permiso para la red de casa", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android pide permiso a las apps que hablan con aparatos de tu red local. WrtControl lo necesita " +
                "para llegar a tu router (por ejemplo, 192.168.1.1) y a nada más: no busca ni toca otros aparatos.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (blocked) {
            Text(
                "Android ya no muestra la pregunta. Actívalo en los ajustes de la app: Permisos → Dispositivos cercanos " +
                    "(red local).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { context.startActivity(appSettings(context)) }, modifier = Modifier.testTag("local-network-settings")) {
                Text("Abrir los ajustes")
            }
        } else {
            Button(onClick = { launcher.launch(ACCESS_LOCAL_NETWORK) }, modifier = Modifier.testTag("local-network-allow")) {
                Text("Dar permiso")
            }
        }
        TextButton(onClick = { skipped = true }) { Text("Seguir sin él (solo acceso remoto)") }
    }
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
