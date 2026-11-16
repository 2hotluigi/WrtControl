package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.Txt

/**
 * Estado de unos datos que se recargan periódicamente. Si falla una recarga se conservan
 * los últimos datos buenos junto con el error.
 */
data class Loadable<T>(val data: T? = null, val error: Txt? = null) {
    val isLoading get() = data == null && error == null
}
