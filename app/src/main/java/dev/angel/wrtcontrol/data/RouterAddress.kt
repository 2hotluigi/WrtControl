package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.userRequire
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object RouterAddress {
    /**
     * Convierte lo que escribe el usuario en la URL del endpoint ubus.
     * Acepta `192.168.1.1`, `router.lan:8080`, `https://...`, e IPv6 con o sin corchetes.
     */
    fun endpoint(address: String): HttpUrl {
        var a = address.trim()
        userRequire(a.isNotEmpty()) { "Escribe la dirección del router (por ejemplo, 192.168.1.1)." }
        if ("://" !in a) {
            a = a.trimEnd('/')
            // Una IPv6 sin corchetes tiene varios ':'; con corchetes ya trae el puerto aparte.
            if (a.count { it == ':' } >= 2 && !a.startsWith("[")) a = "[$a]"
            a = "http://$a"
        }
        val base = a.toHttpUrlOrNull() ?: throw UserFacingException(
            "\"$address\" no es una dirección válida. Escribe la IP del router, por ejemplo 192.168.1.1.",
        )
        return base.newBuilder().encodedPath("/ubus").query(null).fragment(null).build()
    }
}
