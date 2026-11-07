package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.uciApply
import dev.angel.wrtcontrol.openwrt.uciConfirm
import dev.angel.wrtcontrol.openwrt.uciRollback
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/*
 * Cómo se aplica un cambio sin arriesgar el acceso al router (rpcd, uci.c):
 *
 * 1. Sesión propia: los cambios pendientes de UCI son por sesión y el ACL de LuCI no permite
 *    `uci revert`, así que si algo falla antes de aplicar basta con abandonar la sesión.
 * 2. Sondeo: `uci apply` con reversión y sin cambios preparados responde "sin datos" (5) si no hay
 *    nada pendiente, o "permiso denegado" (6) si otra sesión (LuCI, otro teléfono) tiene un cambio
 *    sin confirmar. Hay que comprobarlo porque, mientras dure, rpcd devuelve esa misma sesión a
 *    cualquier login del mismo usuario: confirmaríamos un cambio ajeno.
 * 3. `uci apply` con reversión: si no llega `uci confirm` en [ROLLBACK_SECONDS], rpcd restaura
 *    los ficheros y recarga los servicios. Cubre también que la app se cierre o pierda la red.
 * 4. Se comprueba en el router que el cambio funciona (p. ej. que fw4 cargó las reglas) y solo
 *    entonces se confirma. Si no, se deja de confirmar y el router lo deshace al vencer el plazo.
 *    El ACL de LuCI no incluye `uci rollback` (solo `apply` y `confirm`, ver luci-base.json): se
 *    intenta por si el ACL lo permite, pero lo normal es esperar, como hace LuCI.
 */

internal const val ROLLBACK_SECONDS = 60
internal const val VERIFY_TIMEOUT_MS = 40_000L
internal const val VERIFY_INTERVAL_MS = 1_000L

/** Lo que se espera, además del plazo, a que rpcd restaure los ficheros y recargue los servicios. */
internal const val REVERT_GRACE_MS = 15_000L

private const val UBUS_NO_DATA = 5
private const val UBUS_PERMISSION_DENIED = 6

/** Otra sesión tiene un `uci apply` pendiente de confirmar. */
class PendingChangeException : Exception(
    "El router tiene otro cambio pendiente de confirmar (quizá desde LuCI u otro teléfono). " +
        "Espera un minuto y vuelve a intentarlo.",
)

/** El cambio se aplicó pero no funcionó como se esperaba, y se deshizo. */
class ChangeRevertedException(message: String) : Exception(message)

/** Lo lanza la comprobación cuando el fallo es definitivo y no vale la pena seguir esperando. */
class VerificationFailed(message: String) : Exception(message)

/** No se pudo comprobar el cambio y tampoco que se deshiciera: hay que mirar cómo quedó el router. */
class ChangeUncertainException : Exception(
    "No se pudo comprobar que el cambio funcionara y el router no confirmó que lo deshiciera. " +
        "Revisa cómo quedó antes de volver a intentarlo.",
)

/** Falla si hay un `uci apply` pendiente de otra sesión (ver punto 2). No aplica nada. */
internal suspend fun UbusSession.ensureNoPendingApply() {
    try {
        uciApply(rollback = true, timeoutSeconds = ROLLBACK_SECONDS)
    } catch (e: UbusException.Status) {
        when (e.code) {
            UBUS_NO_DATA -> return
            UBUS_PERMISSION_DENIED -> throw PendingChangeException()
            else -> throw e
        }
    }
    // No debería pasar con una sesión recién abierta: aplicó cambios que no preparamos nosotros.
    runCatchingNonCancel { uciRollback() }
    throw PendingChangeException()
}

/**
 * Aplica con reversión automática lo preparado en esta sesión y espera a que [isLive] diga que
 * el router ya funciona con el cambio. Entonces lo confirma; si no llega a tiempo, lo deshace y
 * lanza [ChangeRevertedException] con [failure]... siempre que [isReverted] lo compruebe: rpcd
 * puede quedarse sin la copia que restaura (p. ej. si otra sesión hace un `uci apply` sin
 * reversión mientras tanto) y entonces el cambio se queda. En ese caso, [ChangeUncertainException].
 * [isReverted] recibe una sesión recién abierta (al deshacer, rpcd devuelve los cambios a esta como
 * pendientes, así que no sirve para leer).
 *
 * Si se cancela la corrutina a mitad, no se confirma nada y el router lo revierte solo.
 */
internal suspend fun UbusSession.applySafely(
    failure: String,
    isReverted: suspend (fresh: UbusSession) -> Boolean,
    /** Lo que haya que hacer nada más aplicar (p. ej. reiniciar una interfaz); si falla, decide [isLive]. */
    afterApply: suspend () -> Unit = {},
    /** Lo que haya que hacer cuando el router ya lo deshizo (p. ej. volver a reiniciar esa interfaz). */
    afterRevert: suspend () -> Unit = {},
    isLive: suspend () -> Boolean,
) {
    try {
        uciApply(rollback = true, timeoutSeconds = ROLLBACK_SECONDS)
    } catch (e: UbusException.Status) {
        if (e.code == UBUS_PERMISSION_DENIED) throw PendingChangeException()
        throw e
    }
    val applied = TimeSource.Monotonic.markNow()
    runCatchingNonCancel { afterApply() }

    var reason = failure
    val live = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
        do {
            delay(VERIFY_INTERVAL_MS)
            // Un fallo puntual (p. ej. el router ocupado recargando) no decide nada: se reintenta.
            val ok = try {
                isLive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerificationFailed) {
                reason = e.message ?: failure
                return@withTimeoutOrNull false
            } catch (_: Exception) {
                false
            }
        } while (!ok)
        true
    } ?: false

    if (!live) {
        runCatchingNonCancel { uciRollback() }
        // Sin permiso para `uci rollback`, el router lo deshace al vencer el plazo: se mira hasta entonces.
        val budget = ROLLBACK_SECONDS * 1000L + REVERT_GRACE_MS - applied.elapsedNow().inWholeMilliseconds
        val reverted = withTimeoutOrNull(budget.coerceAtLeast(VERIFY_INTERVAL_MS)) {
            while (!checkReverted(isReverted)) delay(VERIFY_INTERVAL_MS * 2)
            true
        } ?: false
        if (reverted) runCatchingNonCancel { afterRevert() }
        throw if (reverted) ChangeRevertedException(reason) else ChangeUncertainException()
    }
    uciConfirm()
}

/**
 * Mientras el apply sigue pendiente, rpcd da esta misma sesión a cualquier login del usuario: hasta
 * que una sesión nueva tenga otro identificador, el router no lo ha deshecho, aunque la configuración
 * ya parezca la de antes (p. ej. un cambio que solo borraba y volvía a crear una sección igual).
 */
private suspend fun UbusSession.checkReverted(isReverted: suspend (UbusSession) -> Boolean): Boolean = try {
    val fresh = reopen()
    fresh.id != id && isReverted(fresh)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false // el router aún está recargando: se vuelve a mirar
}

private suspend fun runCatchingNonCancel(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Sin permiso (lo normal) o sin respuesta: el router lo deshará solo al vencer el plazo.
    }
}
