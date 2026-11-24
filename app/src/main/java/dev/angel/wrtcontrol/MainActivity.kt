package dev.angel.wrtcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import dev.angel.wrtcontrol.data.parsePairingLink
import dev.angel.wrtcontrol.ui.AppLockGate
import dev.angel.wrtcontrol.ui.AppRoot
import dev.angel.wrtcontrol.ui.LocalNetworkGate
import dev.angel.wrtcontrol.ui.WrtTheme
import dev.angel.wrtcontrol.ui.design.LocalPalette

/** FragmentActivity: la pide BiometricPrompt para el bloqueo con huella o PIN. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WrtApp
        // Solo al abrirse con el enlace: no al girar la pantalla ni al volver desde Recientes, que
        // entrega otra vez el intent con el que se abrió.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) offerPairing(intent)
        setContent {
            WrtTheme {
                // El fondo y el color del texto de la paleta para todo: también para lo que se ve
                // antes de entrar (el permiso de red local), que si no sale en negro sobre gris.
                val palette = LocalPalette.current
                Surface(Modifier.fillMaxSize(), color = palette.background, contentColor = palette.label) {
                    AppLockGate(app.settings) { LocalNetworkGate { AppRoot(app.repository, app.settings, app.vendors) } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerPairing(intent)
    }

    /** La app se abrió con el código QR de otro teléfono (ver data/Pairing.kt): se propone ese router. */
    private fun offerPairing(intent: Intent?) {
        val link = parsePairingLink(intent?.dataString) ?: return
        (application as WrtApp).repository.offerPairing(link)
    }
}
