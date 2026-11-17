package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.addons.ipv4Range

/*
 * El Wi-Fi de invitados: una red aparte para quien viene de visita. Tienen internet, pero no ven
 * los aparatos de casa ni pueden entrar al router. Todo lo que crea la app se llama `wrtcguest…`:
 *
 * - network: un puente vacío (`br-wrtcguest`) y la interfaz `wrtcguest`, con dirección fija en una
 *   /24 privada que no choque con ninguna red del router.
 * - dhcp: su reparto de direcciones (2 horas: los invitados van y vienen).
 * - firewall: la zona `wrtcguest`, que rechaza todo lo que va al router salvo DHCP y DNS, y solo
 *   reenvía hacia internet. Una regla rechaza antes lo que vaya a direcciones privadas: si el
 *   router cuelga de otro (doble NAT), la red de delante tampoco se ve.
 * - wireless: una red por radio encendida, unida a `wrtcguest` y con `isolate` (los invitados
 *   tampoco se ven entre sí). Siempre con contraseña (WPA2/WPA3).
 *
 * Apagarlo deja de emitir (`disabled` en sus redes) pero conserva lo demás, con su nombre y su
 * contraseña, para la próxima visita. No se usa una radio apagada: encenderla pondría a emitir
 * también las otras redes que tuviera.
 */

const val GUEST_PREFIX = "wrtcguest"
const val GUEST_INTERFACE = "wrtcguest"
const val GUEST_ZONE = "wrtcguest"
private const val GUEST_BRIDGE = "br-wrtcguest"
private const val GUEST_LABEL = "WrtControl · invitados · "

/** Redes privadas (RFC 1918): lo que un invitado no debe alcanzar aunque esté al otro lado de la WAN. */
private val PRIVATE_NETS = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

/** El Wi-Fi de invitados que hay en el router. [on]: está emitiendo o configurado para emitir. */
data class GuestWifi(val ssid: String, val key: String, val on: Boolean, val clients: List<String>)

/** El de [state], o null si la app aún no lo ha creado. */
fun guestWifi(state: WifiState): GuestWifi? {
    val networks = state.networks.filter { GUEST_INTERFACE in it.networks && it.section.startsWith(GUEST_PREFIX) }
    val first = networks.firstOrNull() ?: return null
    return GuestWifi(first.ssid, first.key.orEmpty(), networks.any { it.enabled }, networks.flatMap { it.clients }.distinct())
}

/** Lo que el usuario quiere: encendido o no, con qué nombre y qué contraseña. */
data class GuestEdit(val on: Boolean, val ssid: String, val key: String)

/** Por qué [edit] no se puede guardar, o null si se puede. */
fun guestProblem(edit: GuestEdit): Txt? = wifiProblem(WifiEdit(edit.ssid, edit.key, WifiSecurity.Wpa2Wpa3, emptySet()))

/** Una /24 privada para los invitados que no choque con las redes del router; devuelve la del router (.1). */
internal fun freeGuestAddress(interfaces: List<NetInterface>): String {
    val taken = interfaces.filter { it.name != GUEST_INTERFACE }.flatMap { it.ipv4 }.map { ipv4Range(it.address, it.mask) }
    val candidates = (99 downTo 90).map { "192.168.$it" } + (99 downTo 90).map { "10.99.$it" }
    val free = candidates.first { c ->
        val range = ipv4Range("$c.0", 24)
        taken.none { it.first <= range.last && range.first <= it.last }
    }
    return "$free.1"
}

/**
 * Todo lo del Wi-Fi de invitados, por fichero de configuración. [radios]: las radios encendidas.
 * [address]: la del router en esa red. [wanZones]: las zonas por las que se sale a internet.
 */
internal fun guestSections(edit: GuestEdit, radios: List<String>, address: String, wanZones: List<String>): Map<String, List<UciSection>> {
    val wan = wanZones.ifEmpty { listOf("wan") }
    val network = listOf(
        uciSection("${GUEST_PREFIX}_dev", "device", "name" to GUEST_BRIDGE, "type" to "bridge", "bridge_empty" to "1"),
        uciSection(
            GUEST_INTERFACE, "interface",
            "proto" to "static", "device" to GUEST_BRIDGE, "ipaddr" to address, "netmask" to "255.255.255.0",
        ),
    )
    val dhcp = listOf(
        uciSection("${GUEST_PREFIX}_pool", "dhcp", "interface" to GUEST_INTERFACE, "start" to "100", "limit" to "150", "leasetime" to "2h"),
    )
    val firewall = listOf(
        uciSection(
            "${GUEST_PREFIX}_zone", "zone",
            "name" to GUEST_ZONE, "network" to listOf(GUEST_INTERFACE), "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT",
        ),
        uciSection(
            "${GUEST_PREFIX}_dhcp", "rule",
            "name" to GUEST_LABEL + "DHCP", "src" to GUEST_ZONE, "proto" to "udp", "dest_port" to "67", "family" to "ipv4",
            "target" to "ACCEPT",
        ),
        uciSection(
            "${GUEST_PREFIX}_dns", "rule",
            "name" to GUEST_LABEL + "DNS", "src" to GUEST_ZONE, "proto" to "tcp udp", "dest_port" to "53", "target" to "ACCEPT",
        ),
    ) + wan.flatMapIndexed { i, zone ->
        listOf(
            uciSection(
                "${GUEST_PREFIX}_priv_$i", "rule",
                "name" to GUEST_LABEL + "sin redes privadas", "src" to GUEST_ZONE, "dest" to zone, "dest_ip" to PRIVATE_NETS,
                "proto" to "all", "family" to "ipv4", "target" to "REJECT",
            ),
            uciSection("${GUEST_PREFIX}_fwd_$i", "forwarding", "src" to GUEST_ZONE, "dest" to zone),
        )
    }
    val wireless = radios.map { radio ->
        uciSection(
            "${GUEST_PREFIX}_$radio", "wifi-iface",
            "device" to radio, "mode" to "ap", "network" to GUEST_INTERFACE, "ssid" to edit.ssid,
            "encryption" to WifiSecurity.Wpa2Wpa3.uci, "key" to edit.key, "isolate" to "1", "disabled" to if (edit.on) "0" else "1",
        )
    }
    return mapOf("network" to network, "dhcp" to dhcp, "firewall" to firewall, "wireless" to wireless)
}

/** Las secciones propias de [existing] que hay que borrar y las de [desired] que hay que crear. */
internal fun guestDiff(existing: List<UciSection>, desired: List<UciSection>): Pair<List<String>, List<UciSection>> {
    val ours = existing.filter { it.name.startsWith(GUEST_PREFIX) }.associateBy { it.name }
    val wanted = desired.associateBy { it.name }
    return ours.values.filter { wanted[it.name] != it }.map { it.name } to wanted.values.filter { ours[it.name] != it }
}

/** Lo que entiende la cámara de un teléfono para unirse a un Wi-Fi con contraseña (WPA). */
fun wifiQrText(ssid: String, key: String): String = "WIFI:T:WPA;S:${qrEscape(ssid)};P:${qrEscape(key)};;"

private fun qrEscape(text: String): String = text.replace(Regex("""([\\;,:"])""")) { "\\" + it.value }
