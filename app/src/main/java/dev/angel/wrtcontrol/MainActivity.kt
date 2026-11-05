package dev.angel.wrtcontrol

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.angel.wrtcontrol.ui.AppLockGate
import dev.angel.wrtcontrol.ui.AppRoot
import dev.angel.wrtcontrol.ui.WrtTheme

/** FragmentActivity: la pide BiometricPrompt para el bloqueo con huella o PIN. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WrtApp
        setContent {
            WrtTheme {
                AppLockGate(app.settings) { AppRoot(app.repository, app.settings) }
            }
        }
    }
}
