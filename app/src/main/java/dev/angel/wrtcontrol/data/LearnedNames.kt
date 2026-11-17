package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.Presence
import kotlin.time.Duration.Companion.minutes

/**
 * Los nombres que la app ha averiguado preguntando a los aparatos (ver Mdns.kt), mientras dure la
 * sesión. Preguntar tarda (un segundo por aparato que no contesta), así que no se hace al leer la
 * lista: se apunta quién falta ([pending]), se pregunta aparte y la siguiente lectura ya los trae
 * ([named]). A quien no contestó se le vuelve a preguntar pasado un rato, por si estaba dormido.
 *
 * [now]: reloj en milisegundos que no retrocede.
 */
class LearnedNames(private val now: () -> Long) {
    /** Lo que contestó un aparato en una dirección (null: nada) y cuándo se le preguntó. */
    private data class Asked(val ip: String, val name: String?, val at: Long)

    private val asked = HashMap<String, Asked>()

    /** Los aparatos de [devices] a los que toca preguntar: MAC → dirección. Como mucho [MAX_PER_ROUND]. */
    @Synchronized
    fun pending(devices: List<Device>): Map<String, String> {
        val time = now()
        return devices.asSequence()
            .filter { it.name == null && it.presence != Presence.Offline }
            .mapNotNull { d -> d.ipv4.firstOrNull()?.let { d.mac to it } }
            .filter { (mac, ip) -> asked[mac].let { it == null || it.ip != ip || (it.name == null && time - it.at >= RETRY_MS) } }
            .take(MAX_PER_ROUND)
            .toMap()
    }

    /** Apunta lo que contestó [mac] en [ip] ([name] null: nada). Devuelve si es un nombre nuevo. */
    @Synchronized
    fun learned(mac: String, ip: String, name: String?): Boolean {
        val before = asked.put(mac, Asked(ip, name, now()))
        return name != null && name != before?.name
    }

    /** [devices] con el nombre averiguado en los que no tienen otro y siguen en la misma dirección. */
    @Synchronized
    fun named(devices: List<Device>): List<Device> = devices.map { d ->
        val known = asked[d.mac]?.takeIf { d.name == null && it.ip in d.ipv4 }?.name
        if (known != null) d.copy(name = known) else d
    }

    @Synchronized
    fun clear() = asked.clear()

    companion object {
        /** Para no tener al router preguntando a media casa a la vez. */
        const val MAX_PER_ROUND = 8

        private val RETRY_MS = 10.minutes.inWholeMilliseconds
    }
}
