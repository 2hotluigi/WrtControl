package dev.angel.wrtcontrol.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object RouterAddress {
    /**
     * Convierte lo que escribe el usuario en la URL del endpoint ubus.
     * Acepta `192.168.1.1`, `router.lan:8080`, `https://...`, e IPv6 con o sin corchetes.
     */
    fun endpoint(address: String): HttpUrl {
        var a = address.trim()
        require(a.isNotEmpty()) { "Escribe la dirección del router" }
        if ("://" !in a) {
            a = a.trimEnd('/')
            // Una IPv6 sin corchetes tiene varios ':'; con corchetes ya trae el puerto aparte.
            if (a.count { it == ':' } >= 2 && !a.startsWith("[")) a = "[$a]"
            a = "http://$a"
        }
        val base = a.toHttpUrlOrNull() ?: throw IllegalArgumentException("Dirección no válida: $address")
        return base.newBuilder().encodedPath("/ubus").query(null).fragment(null).build()
    }
}
