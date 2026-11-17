package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.UserFacing
import dev.angel.wrtcontrol.openwrt.uciApply
import dev.angel.wrtcontrol.openwrt.uciConfirm
import dev.angel.wrtcontrol.openwrt.uciRollback
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
 *
 * Quien quiera enseñar por dónde va el cambio pone un [ApplyProgress] en el contexto de la
 * corrutina: así lo siguen todas las funciones de la app sin que cada una tenga que pasarlo.
 */

/** Por dónde va un cambio que se aplica con reversión automática. */
enum class ApplyPhase {
    /** Mandando el cambio al router. */
    Applying,

    /** El router ya lo tiene: se comprueba que funciona. Si no se confirma a tiempo, lo deshace solo. */
    Verifying,

    /** Funciona: se confirma para que se quede. */
    Confirming,

    /** No funcionó: se espera a que el router lo deshaga. */
    Reverting,
}

/** Recibe las fases de los cambios que se apliquen dentro de su contexto (ver [applySafely]). */
class ApplyProgress(val report: (ApplyPhase) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApplyProgress>
}

private suspend fun phase(phase: ApplyPhase) {
    currentCoroutineContext()[ApplyProgress]?.report?.invoke(phase)
}

internal const val ROLLBACK_SECONDS = 60
internal const val VERIFY_TIMEOUT_MS = 40_000L
internal const val VERIFY_INTERVAL_MS = 1_000L

/** Lo que se espera, además del plazo, a que rpcd restaure los ficheros y recargue los servicios. */
internal const val REVERT_GRACE_MS = 15_000L

private const val UBUS_NO_DATA = 5
private const val UBUS_PERMISSION_DENIED = 6

/** Otra sesión tiene un `uci apply` pendiente de confirmar. */
class PendingChangeException : Exception(PENDING_CHANGE.toString()), UserFacing {
    override val text: Txt get() = PENDING_CHANGE
}

private val PENDING_CHANGE = txt(R.string.parental_error_pending_change)

/** El router lo configuró una versión más nueva de la app, con un formato que esta no sabe escribir. */
class NewerSchemaException : Exception(NEWER_SCHEMA.toString()), UserFacing {
    override val text: Txt get() = NEWER_SCHEMA
}

private val NEWER_SCHEMA = txt(R.string.parental_error_newer_schema)

/** El cambio se aplicó pero no funcionó como se esperaba, y se deshizo. */
class ChangeRevertedException(override val text: Txt) : Exception(logMessage(text)), UserFacing

/** Lo lanza la comprobación cuando el fallo es definitivo y no vale la pena seguir esperando. */
class VerificationFailed(val text: Txt) : Exception(text.toString())

/** No se pudo comprobar el cambio y tampoco que se deshiciera: hay que mirar cómo quedó el router. */
class ChangeUncertainException : Exception(UNCERTAIN.toString()), UserFacing {
    override val text: Txt get() = UNCERTAIN
}

private val UNCERTAIN = txt(R.string.parental_error_uncertain)

/** Para el registro: el texto tal cual si no se traduce; si no, el recurso y sus datos. */
private fun logMessage(text: Txt): String = (text as? Txt.Raw)?.text ?: text.toString()

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
 *
 * [rollbackSeconds] y [verifyTimeoutMs]: el plazo del router para deshacerlo y lo que se espera a
 * [isLive]. Un cambio que deja al teléfono sin conexión un rato (el Wi-Fi por el que habla) necesita
 * más que el minuto de siempre para volver a entrar y confirmarlo.
 */
internal suspend fun UbusSession.applySafely(
    failure: Txt,
    isReverted: suspend (fresh: UbusSession) -> Boolean,
    /** Lo que haya que hacer nada más aplicar (p. ej. reiniciar una interfaz); si falla, decide [isLive]. */
    afterApply: suspend () -> Unit = {},
    /** Lo que haya que hacer cuando el router ya lo deshizo (p. ej. volver a reiniciar esa interfaz). */
    afterRevert: suspend () -> Unit = {},
    rollbackSeconds: Int = ROLLBACK_SECONDS,
    verifyTimeoutMs: Long = VERIFY_TIMEOUT_MS,
    isLive: suspend () -> Boolean,
) {
    phase(ApplyPhase.Applying)
    try {
        uciApply(rollback = true, timeoutSeconds = rollbackSeconds)
    } catch (e: UbusException.Status) {
        if (e.code == UBUS_PERMISSION_DENIED) throw PendingChangeException()
        throw e
    }
    val applied = TimeSource.Monotonic.markNow()
    runCatchingNonCancel { afterApply() }
    phase(ApplyPhase.Verifying)

    var reason = failure
    val live = withTimeoutOrNull(verifyTimeoutMs) {
        do {
            delay(VERIFY_INTERVAL_MS)
            // Un fallo puntual (p. ej. el router ocupado recargando) no decide nada: se reintenta.
            val ok = try {
                isLive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerificationFailed) {
                reason = e.text
                return@withTimeoutOrNull false
            } catch (_: Exception) {
                false
            }
        } while (!ok)
        true
    } ?: false

    if (!live) {
        phase(ApplyPhase.Reverting)
        runCatchingNonCancel { uciRollback() }
        // Sin permiso para `uci rollback`, el router lo deshace al vencer el plazo: se mira hasta entonces.
        val budget = rollbackSeconds * 1000L + REVERT_GRACE_MS - applied.elapsedNow().inWholeMilliseconds
        val reverted = withTimeoutOrNull(budget.coerceAtLeast(VERIFY_INTERVAL_MS)) {
            while (!checkReverted(isReverted)) delay(VERIFY_INTERVAL_MS * 2)
            true
        } ?: false
        if (reverted) runCatchingNonCancel { afterRevert() }
        throw if (reverted) ChangeRevertedException(reason) else ChangeUncertainException()
    }
    phase(ApplyPhase.Confirming)
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
