package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Lo que el usuario guarda de cada aparato: cómo se llama y, si quiere, que el router le dé siempre
 * la misma dirección. Va en el router, no en el teléfono, para que lo vean todos los teléfonos con
 * la app: en las secciones `host` de /etc/config/dhcp, las mismas que las "concesiones estáticas"
 * de LuCI.
 *
 * - La dirección fija es la opción `ip` de siempre: dnsmasq la reserva para esa MAC.
 * - El nombre va en una opción propia ([LABEL_OPTION]) que dnsmasq no mira: admite espacios y
 *   acentos ("Tele de la sala"), que en `name` (un nombre de DNS) harían que dnsmasq no arrancara.
 *   Si la sección la creó LuCI con su `name`, ese vale de nombre mientras no se ponga otro.
 * - Las secciones que crea la app se llaman `host_<mac>`. No empiezan por "wrtc_": esas son del
 *   control parental, que borra las que no reconoce.
 */

const val LABEL_OPTION = "wrtc_name"
const val MAX_LABEL_LENGTH = 40
private const val OWN_HOST_PREFIX = "host_"

/** Lo guardado de un aparato. [section]: la sección `host` de donde sale. */
data class DeviceRecord(val section: String, val mac: String, val label: String?, val fixedIp: String?)

/** Las secciones `host` con una sola MAC (las que llevan varias son de quien las escribió a mano). */
fun parseDeviceRecords(hosts: List<UciSection>): List<DeviceRecord> = hosts.mapNotNull { s ->
    if (s.type != "host") return@mapNotNull null
    val mac = s.items("mac").singleOrNull()?.let(::normalizeMac) ?: return@mapNotNull null
    val label = (s.text(LABEL_OPTION) ?: s.text("name"))?.let(::cleanLabel)?.ifEmpty { null }
    DeviceRecord(s.name, mac, label, s.text("ip")?.takeIf { parseIpv4(it) != null })
}.distinctBy { it.mac }

/** Un nombre tal como se guarda: sin saltos de línea ni espacios de más, y no más largo de la cuenta. */
fun cleanLabel(text: String): String =
    text.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL_LENGTH).trim()

/** Lo que debe quedar guardado de [mac]: null quita el nombre o la dirección fija. */
data class DeviceEdit(val mac: String, val label: String?, val fixedIp: String?)

/** Por qué [ip] no vale como dirección fija de [mac], o null si vale. */
fun fixedIpProblem(ip: String, mac: String, interfaces: List<NetInterface>, records: List<DeviceRecord>): Txt? {
    val other = records.firstOrNull { it.mac != mac && it.fixedIp == ip }
    return when {
        parseIpv4(ip) == null -> txt(R.string.devices_error_ip_invalid)
        !usableLanAddress(ip, interfaces) -> txt(R.string.devices_error_ip_outside)
        other != null -> txt(R.string.devices_error_ip_taken, other.label ?: other.mac)
        else -> null
    }
}

/** Lo que hay que hacer en `dhcp` para dejar guardado un aparato. */
private sealed interface HostChange {
    data class Add(val section: UciSection) : HostChange

    data class Remove(val section: String) : HostChange

    data class Update(val section: String, val set: Map<String, String>, val drop: List<String>) : HostChange
}

/** La sección nueva [name] para un aparato del que no había nada guardado, o null si no hay nada que guardar. */
private fun newHost(name: String, mac: String, label: String?, ip: String?): HostChange? {
    val section = uciSection(name, "host", "mac" to mac, LABEL_OPTION to label, "ip" to ip)
    return if (section.options.size > 1) HostChange.Add(section) else null
}

/**
 * El cambio para que en [section] (la de un aparato, cuyo nombre guardado es [shown]) queden
 * [label] e [ip], o null si ya está así.
 */
private fun hostUpdate(section: UciSection, shown: String?, label: String?, ip: String?): HostChange? {
    // Lo que se quiere en cada opción y lo que hay. El nombre que puso LuCI (`name`) también vale
    // de nombre, así que con ese ya puesto no se escribe otra vez; y nunca se toca.
    val wanted = mapOf(LABEL_OPTION to label, "ip" to ip)
    val current = mapOf(LABEL_OPTION to shown, "ip" to section.text("ip"))
    val set = wanted.mapNotNull { (key, value) -> value?.takeIf { it != current[key] }?.let { key to it } }.toMap()
    val drop = wanted.filter { (key, value) -> value == null && key in section.options }.keys.toList()
    val left = section.options.keys - drop.toSet() + set.keys - "mac"
    return when {
        set.isEmpty() && drop.isEmpty() -> null
        // Era de la app y ya no guarda nada: fuera, para no dejar reservas vacías en LuCI.
        left.isEmpty() && section.name.startsWith(OWN_HOST_PREFIX) -> HostChange.Remove(section.name)
        else -> HostChange.Update(section.name, set, drop)
    }
}

/** Guarda en el router el nombre y la dirección fija de los aparatos (ver arriba). */
class DevicesApi(private val ubus: UbusClient, private val writeLock: Mutex = Mutex()) {

    /**
     * Deja guardado de un aparato lo que dice [edit]. Con reversión: al tocar `dhcp`, dnsmasq se
     * reinicia, y si no volviera a contestar el router lo deshace solo. [interfaces]: las redes del
     * router, para comprobar que la dirección fija es de casa.
     */
    suspend fun save(edit: DeviceEdit, interfaces: List<NetInterface>) = writeLock.withLock {
        val mac = normalizeMac(edit.mac) ?: throw UserFacingException(txt(R.string.devices_error_mac))
        val label = edit.label?.let(::cleanLabel)?.ifEmpty { null }
        val ip = edit.fixedIp?.trim()?.ifEmpty { null }
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val hosts = tx.uciSections("dhcp", "host")
        val records = parseDeviceRecords(hosts)
        ip?.let { fixedIpProblem(it, mac, interfaces, records) }?.let { throw UserFacingException(it) }

        val record = records.firstOrNull { it.mac == mac }
        val section = hosts.firstOrNull { it.name == record?.section }
        val change = when (section) {
            null -> newHost(newHostSection(mac, hosts), mac, label, ip)
            else -> hostUpdate(section, record?.label, label, ip)
        }
        when (change) {
            null -> return@withLock
            is HostChange.Add -> tx.uciAdd("dhcp", change.section)
            is HostChange.Remove -> tx.uciDelete("dhcp", change.section)
            is HostChange.Update -> {
                if (change.set.isNotEmpty()) tx.uciSet("dhcp", change.section, change.set)
                change.drop.forEach { tx.uciDeleteOption("dhcp", change.section, it) }
            }
        }
        tx.applySafely(
            failure = txt(R.string.devices_error_not_applied),
            isReverted = { fresh -> fresh.uciSections("dhcp", "host") == hosts },
            // dnsmasq se reinicia con el cambio: tiene que seguir en marcha y contestando.
            isLive = { tx.serviceRunning("dnsmasq") != false && tx.lookup("localhost") != Lookup.Failed },
        )
    }

    private fun newHostSection(mac: String, hosts: List<UciSection>): String {
        val base = OWN_HOST_PREFIX + mac.filter { it != ':' }.lowercase()
        val taken = hosts.map { it.name }.toSet()
        return generateSequence(0) { it + 1 }.map { if (it == 0) base else "${base}_$it" }.first { it !in taken }
    }
}
