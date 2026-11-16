package dev.angel.wrtcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.angel.wrtcontrol.data.Feed
import dev.angel.wrtcontrol.data.Loadable
import kotlin.time.Duration

/**
 * Lo último que se sabe de esta lectura del router y lo que se vaya leyendo, como mucho cada
 * [every], mientras la pantalla esté a la vista. Con la app en segundo plano no se lee.
 */
@Composable
fun <T : Any> Feed<T>.watchAsState(every: Duration): State<Loadable<T>> {
    val flow = remember(this, every) { watch(every) }
    // Lo que ya se sabía, para pintarlo desde el primer momento; lo demás llega por el flujo.
    val known = remember(this) { state.value }
    return flow.collectAsStateWithLifecycle(initialValue = known)
}
