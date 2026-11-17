package dev.angel.wrtcontrol.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.parental.ApplyPhase
import dev.angel.wrtcontrol.parental.ApplyProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un cambio en curso en el router: qué se hace y, si se aplica con reversión, por dónde va. */
data class ChangeProgress(val label: Txt, val phase: ApplyPhase? = null)

/**
 * Lleva los cambios de una pantalla, uno a la vez: qué se está haciendo, por dónde va (las fases
 * que da [dev.angel.wrtcontrol.parental.applySafely]) y el error del último, ya en palabras para
 * el usuario. Todas las pantallas que cambian algo en el router lo usan, para que un cambio se vea
 * y se comporte igual en todas.
 */
class ChangeRunner(
    private val scope: CoroutineScope,
    private val describe: (Exception) -> Txt = { it.userMessage() },
    private val log: (String, Exception) -> Unit = { message, e -> Log.w("WrtControl", message, e) },
) {
    /** El cambio en curso, o null si no hay ninguno. */
    var current by mutableStateOf<ChangeProgress?>(null)
        private set

    /** Error del último cambio, hasta que el usuario lo cierre o haga otro. */
    var error by mutableStateOf<Txt?>(null)

    val busy: Txt? get() = current?.label

    /**
     * Hace [block] si no hay otro cambio en curso (si lo hay, no hace nada y devuelve false).
     * [onSuccess] solo si salió bien; [onFinished], siempre, antes de dar el cambio por terminado
     * (para volver a leer lo que haya cambiado). Si se cancela a mitad, el router deshace solo lo
     * que no se llegó a confirmar.
     */
    fun run(label: Txt, onSuccess: () -> Unit = {}, onFinished: () -> Unit = {}, block: suspend () -> Unit): Boolean {
        if (current != null) return false
        current = ChangeProgress(label)
        error = null
        scope.launch {
            try {
                withContext(ApplyProgress { phase -> current = current?.copy(phase = phase) }) { block() }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                log("Falló: $label", e)
                error = describe(e)
            } finally {
                onFinished()
                current = null
            }
        }
        return true
    }
}
