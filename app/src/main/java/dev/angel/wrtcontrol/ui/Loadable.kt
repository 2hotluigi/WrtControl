package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.time.Duration

/**
 * Estado de unos datos que se recargan periódicamente. Si falla una recarga se conservan
 * los últimos datos buenos junto con el error.
 */
data class Loadable<T>(val data: T? = null, val error: String? = null) {
    val isLoading get() = data == null && error == null
}

/**
 * Llama a [load] cada [interval] mientras haya suscriptores. Usado con
 * `stateIn(WhileSubscribed)` se detiene solo cuando la app pasa a segundo plano.
 */
fun <T> pollingFlow(interval: Duration, load: suspend () -> T): Flow<Loadable<T>> = flow {
    var last: T? = null
    while (currentCoroutineContext().isActive) {
        val next = try {
            val data = load()
            last = data
            Loadable(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loadable(last, e.userMessage())
        }
        emit(next)
        delay(interval)
    }
}

fun Throwable.userMessage(): String = when (this) {
    is UbusException -> message ?: "Error del router"
    else -> message ?: javaClass.simpleName
}
