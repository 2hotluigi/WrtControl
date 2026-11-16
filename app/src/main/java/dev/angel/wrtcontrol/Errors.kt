package dev.angel.wrtcontrol

import dev.angel.wrtcontrol.ubus.UbusException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Un error que ya trae su texto para el usuario ([text]: qué pasó y qué hacer). Se muestra tal
 * cual. El resto de errores (de ubus, de red o fallos de la app) se traducen en [describeError].
 */
interface UserFacing {
    val text: Txt
}

/**
 * Error para el usuario sin tipo propio: datos que no valen, o un estado del router que el usuario
 * puede arreglar. Es un IllegalArgumentException para que sirva donde antes había un `require`.
 * El mensaje de la excepción (para el registro) es el nombre del recurso y sus datos.
 */
class UserFacingException(override val text: Txt, cause: Throwable? = null) :
    IllegalArgumentException(text.toString(), cause), UserFacing

/** Como `require`, pero con un texto para el usuario (ver [UserFacingException]). */
inline fun userRequire(value: Boolean, text: () -> Txt) {
    if (!value) throw UserFacingException(text())
}

/**
 * El texto de un error para el usuario: qué pasó, por qué (si se sabe) y qué hacer. Nunca lleva
 * nombres de clases, de objetos de ubus ni códigos de rpcd.
 */
fun describeError(e: Throwable): Txt = when (e) {
    is UserFacing -> e.text
    is UbusException -> describeUbus(e)
    is IOException -> describeNetwork(e)
    else -> txt(R.string.error_unexpected)
}

private fun describeUbus(e: UbusException): Txt = when (e) {
    is UbusException.AuthFailed -> txt(R.string.error_auth_failed)
    is UbusException.SessionExpired -> txt(R.string.error_session_expired)
    is UbusException.SessionLost -> txt(R.string.error_session_lost)
    is UbusException.AccessDenied -> txt(R.string.error_no_permission)
    is UbusException.Status -> describeStatus(e.code)
    is UbusException.Refused -> e.text
    is UbusException.Protocol -> txt(R.string.error_protocol)
    is UbusException.Network -> e.httpCode?.let(::describeHttp) ?: describeNetwork(e.cause ?: e)
}

/** Los estados de ubus (libubus: enum ubus_msg_status), en palabras. */
private fun describeStatus(code: Int): Txt = txt(
    when (code) {
        STATUS_METHOD_NOT_FOUND -> R.string.error_status_missing_function
        STATUS_NOT_FOUND, STATUS_NO_DATA -> R.string.error_status_not_found
        STATUS_PERMISSION_DENIED -> R.string.error_no_permission
        STATUS_TIMEOUT -> R.string.error_status_timeout
        STATUS_CONNECTION_FAILED -> R.string.error_status_internal
        else -> R.string.error_status_rejected
    },
)

private fun describeHttp(code: Int): Txt = txt(
    when (code) {
        HTTP_NOT_FOUND -> R.string.error_http_no_luci
        HTTP_FORBIDDEN, HTTP_UNAUTHORIZED -> R.string.error_no_permission
        else -> R.string.error_http_internal
    },
)

/**
 * Un fallo de red en palabras: el router no responde, rechaza la conexión, no hay camino... Busca
 * en las causas, porque los clientes suelen envolver la excepción original.
 */
fun describeNetwork(e: Throwable): Txt {
    val chain = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
    chain.firstNotNullOfOrNull { it as? UserFacing }?.let { return it.text }
    val text = chain.joinToString(" ") { it.message.orEmpty() }
    return txt(
        when {
            chain.any { it is UnknownHostException } -> R.string.error_net_unknown_host
            chain.any { it is NoRouteToHostException } || "ENETUNREACH" in text || "EHOSTUNREACH" in text ->
                R.string.error_net_no_route
            chain.any { it is ConnectException } || "ECONNREFUSED" in text -> R.string.error_net_refused
            chain.any { it is SSLException } -> R.string.error_net_tls
            chain.any { it is InterruptedIOException } -> R.string.error_net_timeout
            chain.any { it is SocketException } -> R.string.error_net_reset
            else -> R.string.error_net_other
        },
    )
}

private const val MAX_CAUSES = 8

private const val STATUS_METHOD_NOT_FOUND = 3
private const val STATUS_NOT_FOUND = 4
private const val STATUS_NO_DATA = 5
private const val STATUS_PERMISSION_DENIED = 6
private const val STATUS_TIMEOUT = 7
private const val STATUS_CONNECTION_FAILED = 10

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
