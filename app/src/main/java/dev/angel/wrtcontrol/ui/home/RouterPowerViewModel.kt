package dev.angel.wrtcontrol.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Lo que tarda en apagarse: antes de eso todavía contesta el de antes. */
private val SHUTDOWN = 10.seconds

/** Cada cuánto se pregunta si ya volvió. */
private val POLL = 5.seconds

/** Pasado esto sin contestar, se avisa de qué mirar (pero se sigue esperando). */
private val LATE = 4.minutes

/** Pasado esto, se deja de preguntar. */
private val GIVE_UP = 20.minutes

/** Reiniciar o restablecer el router, desde Ajustes. */
sealed interface PowerState {
    data object Idle : PowerState

    /** Reiniciado: se espera a que vuelva. [late]: ya tarda más de lo normal. */
    data class Rebooting(val late: Boolean = false) : PowerState

    data object Back : PowerState

    data object Resetting : PowerState

    data class Failed(val message: Txt) : PowerState

    val busy: Boolean get() = this is Rebooting || this == Resetting
}

/** Lo que el router deja hacer; null mientras no se sabe (se ofrece igual: si no deja, lo dirá). */
data class PowerAccess(val reboot: Boolean? = null, val reset: Boolean? = null)

class RouterPowerViewModel(private val repo: RouterRepository) : ViewModel() {

    var state by mutableStateOf<PowerState>(PowerState.Idle)
        private set

    /** Reinicia el router y espera a que vuelva: lo sabe porque lleva encendido menos que antes. */
    @Suppress("TooGenericExceptionCaught") // Cualquier fallo al pedirlo se le cuenta al usuario.
    fun reboot() {
        if (state.busy) return
        state = PowerState.Rebooting()
        viewModelScope.launch {
            val before = uptime()
            try {
                repo.use { it.power.reboot() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = PowerState.Failed(e.userMessage())
                return@launch
            }
            val late = launch {
                delay(LATE)
                state = PowerState.Rebooting(late = true)
            }
            delay(SHUTDOWN)
            val back = withTimeoutOrNull(GIVE_UP) {
                while (uptime()?.let { before == null || it < before } != true) delay(POLL)
            } != null
            late.cancel()
            state = if (back) PowerState.Back else PowerState.Rebooting(late = true)
        }
    }

    /**
     * Borra los ajustes del router. En cuanto empieza, la app sale de él (lo que sabía ya no vale)
     * y la pantalla de conexión cuenta cómo volver a entrar.
     */
    @Suppress("TooGenericExceptionCaught") // Cualquier fallo al pedirlo se le cuenta al usuario.
    fun factoryReset() {
        if (state.busy) return
        state = PowerState.Resetting
        viewModelScope.launch {
            try {
                repo.use { it.power.factoryReset() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = PowerState.Failed(e.userMessage())
                return@launch
            }
            repo.forgetAfterReset(txt(R.string.power_reset_notice))
        }
    }

    /** Cuánto lleva encendido el router, en segundos; null si no contesta. */
    @Suppress("TooGenericExceptionCaught") // Mientras arranca, cualquier fallo es que aún no está.
    private suspend fun uptime(): Long? = try {
        repo.use { it.systemInfo().uptime }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
