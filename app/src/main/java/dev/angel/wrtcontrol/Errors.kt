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
 * Un error cuyo mensaje ya está escrito para el usuario: qué pasó y qué hacer. Se muestra tal
 * cual. El resto de errores (de ubus, de red o fallos de la app) se traducen en [describeError].
 */
interface UserFacing

/**
 * Error para el usuario sin tipo propio: datos que no valen, o un estado del router que el usuario
 * puede arreglar. Es un IllegalArgumentException para que sirva donde antes había un `require`.
 */
class UserFacingException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause), UserFacing

/** Como `require`, pero con un mensaje para el usuario (ver [UserFacingException]). */
inline fun userRequire(value: Boolean, message: () -> String) {
    if (!value) throw UserFacingException(message())
}

/**
 * El texto de un error para el usuario: qué pasó, por qué (si se sabe) y qué hacer. Nunca lleva
 * nombres de clases, de objetos de ubus ni códigos de rpcd.
 */
fun describeError(e: Throwable): String = when {
    e is UserFacing && !e.message.isNullOrBlank() -> e.message!!
    e is UbusException -> describeUbus(e)
    e is IOException -> describeNetwork(e)
    else -> UNEXPECTED
}

private fun describeUbus(e: UbusException): String = when (e) {
    is UbusException.AuthFailed ->
        "Usuario o contraseña incorrectos. Son los mismos con los que entras en LuCI (normalmente, root)."
    is UbusException.SessionExpired -> "La sesión con el router terminó. Vuelve a entrar."
    is UbusException.SessionLost ->
        "Se perdió la conexión con el router a mitad del cambio, así que no se aplicó. Vuelve a intentarlo."
    is UbusException.AccessDenied -> NO_PERMISSION
    is UbusException.Status -> describeStatus(e.code)
    is UbusException.Refused -> e.message ?: UNEXPECTED
    is UbusException.Protocol ->
        "El router respondió algo que la app no entiende. Comprueba que la dirección es la de un OpenWrt con LuCI; " +
            "si lo es, puede que su versión aún no sea compatible."
    is UbusException.Network -> e.httpCode?.let(::describeHttp) ?: describeNetwork(e.cause ?: e)
}

/** Los estados de ubus (libubus: enum ubus_msg_status), en palabras. */
private fun describeStatus(code: Int): String = when (code) {
    STATUS_METHOD_NOT_FOUND ->
        "A este router le falta una función que la app necesita para esto. Actualiza OpenWrt o instala LuCI completo."
    STATUS_NOT_FOUND, STATUS_NO_DATA ->
        "El router no encontró lo que buscaba la app: puede que alguien lo cambiara desde LuCI. Vuelve a intentarlo."
    STATUS_PERMISSION_DENIED -> NO_PERMISSION
    STATUS_TIMEOUT -> "El router tardó demasiado en responder. Vuelve a intentarlo en un momento."
    STATUS_CONNECTION_FAILED ->
        "El router tiene un problema interno (uno de sus servicios no responde). Reinícialo y vuelve a intentarlo."
    else -> "El router rechazó la petición de la app. Vuelve a intentarlo; si se repite, es un fallo de la app: avísanos."
}

private fun describeHttp(code: Int): String = when (code) {
    HTTP_NOT_FOUND -> "En esa dirección no está la interfaz de LuCI. ¿Es un router OpenWrt con LuCI instalado?"
    HTTP_FORBIDDEN, HTTP_UNAUTHORIZED -> NO_PERMISSION
    else -> "El router dio un error interno. Espera un poco y vuelve a intentarlo; si se repite, reinícialo."
}

/**
 * Un fallo de red en palabras: el router no responde, rechaza la conexión, no hay camino... Busca
 * en las causas, porque los clientes suelen envolver la excepción original.
 */
fun describeNetwork(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
    chain.firstOrNull { it is UserFacing && !it.message.isNullOrBlank() }?.let { return it.message!! }
    val text = chain.joinToString(" ") { it.message.orEmpty() }
    return when {
        chain.any { it is UnknownHostException } ->
            "No se encuentra el router con ese nombre. Escribe su dirección IP (por ejemplo, 192.168.1.1)."
        chain.any { it is NoRouteToHostException } || "ENETUNREACH" in text || "EHOSTUNREACH" in text ->
            "No hay camino hasta el router desde esta red. Conéctate al Wi-Fi de casa o activa el acceso remoto."
        chain.any { it is ConnectException } || "ECONNREFUSED" in text ->
            "El router no acepta conexiones en esa dirección: o no es la del router, o su página web (LuCI) está " +
                "apagada. Revisa la dirección (suele ser 192.168.1.1)."
        chain.any { it is SSLException } ->
            "No se pudo abrir una conexión segura con el router. Prueba con http:// en vez de https://."
        chain.any { it is InterruptedIOException } ->
            "El router no responde. Comprueba que estás en la red de casa (o que el acceso remoto está activo) y que " +
                "la dirección es la del router."
        chain.any { it is SocketException } ->
            "Se cortó la conexión con el router. Vuelve a intentarlo."
        else -> "No se pudo hablar con el router. Comprueba la conexión y vuelve a intentarlo."
    }
}

private const val NO_PERMISSION =
    "El router no da permiso para esto al usuario con el que entraste. Vuelve a entrar con root."

private const val UNEXPECTED = "Algo falló dentro de la app. Vuelve a intentarlo; si se repite, avísanos."

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
