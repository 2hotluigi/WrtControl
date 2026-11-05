package dev.angel.wrtcontrol.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.AppSettings

val LocalAppSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings no proporcionado") }

/**
 * ¿Está la app bloqueada ahora? Los diálogos son ventanas propias que quedarían encima de la
 * pantalla de bloqueo: mientras sea true, no se dibujan (su estado se conserva y vuelven al
 * desbloquear).
 */
val LocalAppLocked = compositionLocalOf { false }

/** Tras este tiempo en segundo plano, la app vuelve a pedir la huella o el PIN. */
private const val RELOCK_AFTER_MS = 60_000L

private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/** ¿Tiene el teléfono huella, cara o bloqueo de pantalla con los que proteger la app? */
fun canLockApp(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/** Pide la huella, la cara o el PIN del teléfono. [onResult] recibe true si se identificó. */
fun authenticate(context: Context, title: String, onResult: (Boolean) -> Unit) {
    val activity = context as? FragmentActivity ?: return onResult(false)
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("Usa la huella, la cara o el bloqueo de pantalla del teléfono")
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    prompt.authenticate(info)
}

/**
 * Muestra [content] solo después de identificarse, si el bloqueo está activado. Vuelve a pedirlo
 * si la app pasa más de un minuto en segundo plano.
 */
@Composable
fun AppLockGate(settings: AppSettings, content: @Composable () -> Unit) {
    val enabled by settings.appLock.collectAsState(initial = null)
    val unlocked = settings.unlocked
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> settings.stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val stopped = settings.stoppedAt
                    if (stopped != 0L && SystemClock.elapsedRealtime() - stopped > RELOCK_AFTER_MS) settings.unlocked = false
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Con el bloqueo desactivado, esta sesión ya cuenta como identificada: al activarlo (lo que
    // ya pide identificarse) no se vuelve a pedir hasta el siguiente arranque o segundo plano.
    LaunchedEffect(enabled) { if (enabled == false) settings.unlocked = true }

    val context = LocalContext.current
    // Con el bloqueo activado, la vista de apps recientes no enseña la pantalla.
    val window = (context as? Activity)?.window
    DisposableEffect(enabled, window) {
        if (enabled == true) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {}
    }
    // Si el teléfono ya no tiene bloqueo de pantalla, no hay con qué identificarse: sin esto la
    // app quedaría bloqueada para siempre.
    val locked = enabled == true && !unlocked && canLockApp(context)
    Box(Modifier.fillMaxSize()) {
        // La app sigue debajo (así no pierde la pestaña ni lo que estaba haciendo), tapada, sin
        // diálogos y fuera del alcance de los lectores de pantalla mientras está bloqueada.
        if (enabled != null) {
            CompositionLocalProvider(LocalAppLocked provides locked) {
                Box(if (locked) Modifier.clearAndSetSemantics {} else Modifier) { content() }
            }
        }
        when {
            enabled == null -> Surface(Modifier.fillMaxSize()) {} // leyendo el ajuste: no enseñar nada todavía
            locked -> LockScreen(onUnlocked = { settings.unlocked = true })
        }
    }
}

@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    fun ask() {
        if (asking) return
        asking = true
        authenticate(context, "Desbloquear WrtControl") { ok ->
            asking = false
            if (ok) onUnlocked()
        }
    }
    LaunchedEffect(Unit) { ask() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("WrtControl está bloqueada", style = MaterialTheme.typography.titleLarge)
            Text(
                "Identifícate para ver y cambiar el control parental.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = ::ask) { Text("Desbloquear") }
        }
    }
}
