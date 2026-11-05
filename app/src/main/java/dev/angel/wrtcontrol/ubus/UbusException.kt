package dev.angel.wrtcontrol.ubus

/** Códigos de estado de ubus (libubus: enum ubus_msg_status). */
internal fun ubusStatusText(code: Int): String = when (code) {
    1 -> "comando inválido"
    2 -> "argumento inválido"
    3 -> "método no encontrado"
    4 -> "no encontrado"
    5 -> "sin datos"
    6 -> "permiso denegado"
    7 -> "tiempo agotado"
    8 -> "no soportado"
    9 -> "error desconocido"
    10 -> "fallo de conexión"
    else -> "código $code"
}

sealed class UbusException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Usuario o contraseña incorrectos. */
    class AuthFailed : UbusException("Usuario o contraseña incorrectos")

    /** La sesión caducó y no hay credenciales para renovarla. */
    class SessionExpired : UbusException("La sesión ha caducado")

    /** La sesión es válida, pero el ACL del usuario no permite esta llamada. */
    class AccessDenied(val target: String) : UbusException("Sin permiso para $target")

    /** ubus respondió con un código de error. */
    class Status(val code: Int, val target: String) : UbusException("$target: ${ubusStatusText(code)}")

    /** Error JSON-RPC inesperado o respuesta con un formato que no esperamos. */
    class Protocol(message: String, cause: Throwable? = null) : UbusException(message, cause)

    /** Sin respuesta, timeout o HTTP distinto de 2xx. */
    class Network(message: String, val httpCode: Int? = null, cause: Throwable? = null) : UbusException(message, cause)
}
