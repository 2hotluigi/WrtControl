package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException

/*
 * Cuál es la red de casa, sin suponer que se llama "lan" (ver ROADMAP, Hito G). Se lee del
 * cortafuegos, que es lo que la define en cualquier OpenWrt: una zona sin NAT, que deja entrar al
 * router (input ACCEPT) y sale a internet (un `forwarding` hacia una zona con NAT). Así queda fuera
 * una red de invitados ajena (input REJECT) y lo que crea la app (zonas "wrtc…": invitados y
 * acceso remoto). Con varias, va primero "lan" si está: en el router de siempre no cambia nada.
 */

/** El nombre de la red de casa de OpenWrt de serie; también, lo que se usa si no se sabe otra cosa. */
internal const val DEFAULT_HOME_NETWORK = "lan"

/** Lo que crea la app en el cortafuegos empieza así (ver GuestWifi.kt y RemoteAccess.kt). */
private const val OWN_ZONE_PREFIX = "wrtc"

/** La zona del acceso remoto (`addons.VPN_ZONE`): sus "aparatos" son teléfonos fuera de casa. */
private const val REMOTE_ZONE = "wrtcvpn"

/**
 * Cómo se reparte la casa en interfaces lógicas de `network`.
 * [home]: la red de casa (o las redes, si hay varias), con la principal primero; nunca vacía.
 * [inside]: todas las redes del lado de dentro (casa, invitados, IoT…), donde puede haber aparatos;
 * null si no se pudo leer el cortafuegos.
 */
data class HomeLayout(val home: List<String>, val inside: Set<String>?) {
    /** La red de casa principal: la de la MAC que identifica al router y donde se crea un Wi-Fi nuevo. */
    val main: String get() = home.first()

    companion object {
        /** Sin poder leer el cortafuegos: lo de OpenWrt de serie. */
        val DEFAULT = HomeLayout(listOf(DEFAULT_HOME_NETWORK), null)
    }
}

/** Lee [HomeLayout] de las secciones de `firewall`. */
internal fun parseHomeLayout(firewall: List<UciSection>): HomeLayout {
    val zones = firewall.filter { it.type == "zone" && it.text("name") != null }
    if (zones.isEmpty()) return HomeLayout.DEFAULT
    val defaultInput = firewall.firstOrNull { it.type == "defaults" }?.text("input")
    val internet = zones.filter { isTrue(it.text("masq")) }.mapNotNull { it.text("name") }.toSet()
    val toInternet = firewall
        .filter { it.type == "forwarding" && !isFalse(it.text("enabled")) && it.text("dest") in internet }
        .mapNotNull { it.text("src") }.toSet()
    val inside = zones.filter { it.text("name") !in internet }
    val home = inside.filter { zone ->
        val name = zone.text("name")!!
        !name.startsWith(OWN_ZONE_PREFIX) &&
            name in toInternet &&
            (zone.text("input") ?: defaultInput).equals("ACCEPT", ignoreCase = true)
    }.flatMap { it.items("network") }.distinct()
    val ordered = home.sortedByDescending { it == DEFAULT_HOME_NETWORK }.ifEmpty { listOf(DEFAULT_HOME_NETWORK) }
    return HomeLayout(
        home = ordered,
        inside = inside.filter { it.text("name") != REMOTE_ZONE }.flatMap { it.items("network") }.toSet() + ordered,
    )
}

/** Lee el cortafuegos y saca [HomeLayout]; si el router no deja leerlo, [HomeLayout.DEFAULT]. */
internal suspend fun UbusCaller.homeLayout(): HomeLayout = try {
    parseHomeLayout(uciSections("firewall"))
} catch (_: UbusException.AccessDenied) {
    HomeLayout.DEFAULT
} catch (_: UbusException.Status) {
    HomeLayout.DEFAULT
} catch (_: UbusException.Protocol) {
    HomeLayout.DEFAULT
}

/** Booleanos de UCI como los lee fw4. */
private fun isTrue(text: String?): Boolean = text in setOf("1", "on", "true", "yes")

private fun isFalse(text: String?): Boolean = text in setOf("0", "off", "false", "no")
