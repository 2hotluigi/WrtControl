package dev.angel.wrtcontrol.parental

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Reglas propias de WrtControl en nftables, en un fichero que fw4 incluye dentro de su tabla
 * (sección `include`, type nftables, position table-append). Lo que no se puede expresar con
 * reglas de fw4 en UCI va aquí:
 *
 * - Cadena forward con prioridad -1, antes de la de fw4 (que acepta primero lo ya establecido),
 *   así que todo lo que rechaza corta también conexiones abiertas:
 *   1. Cuarentena: lo que entra desde la red de casa (una regla MARK de fw4 por zona marca esos
 *      paquetes con [QUARANTINE_MARK]; fw4 la regenera con los dispositivos que tenga la zona en
 *      cada recarga) y viene de una MAC no aprobada no sale de la red. Solo en la dirección en que
 *      se abrió la conexión: las respuestas que llegan de internet nunca coinciden, aunque una
 *      configuración rara metiera la WAN en una zona "de casa". DHCP y DNS van al propio router
 *      (input), así que el dispositivo se conecta y aparece en la app para aprobarlo.
 *   2. Pausas y horarios: rechaza lo que marcan las reglas MARK de fw4 ([MARK_BIT]).
 *   3. DNS cifrado por IP: los perfiles con filtro no llegan por TCP ni UDP a los resolutores
 *      públicos (DoH, DoT, DNSCrypt en cualquier puerto; el 53 ya va redirigido al router). El
 *      ICMP sí pasa: hay equipos que comprueban la conexión con un ping a 8.8.8.8.
 *   4. Corte de conexiones: cada conexión nueva de un perfil con filtro se marca con su época
 *      (ct mark). Al endurecer el filtro cambia la época y lo que se abrió antes se rechaza. El
 *      dispositivo se reconecta, pero puede hacerlo con la IP que tiene en caché, sin preguntar
 *      al DNS: por eso las conexiones de la ventana siguiente ([CUT_WINDOW_SECONDS]) llevan otra
 *      marca y se cortan otra vez al acabar, cuando la caché ya caducó (las instancias DNS de
 *      los perfiles dan como mucho [DNS_MAX_TTL] s). Solo TCP y QUIC (UDP 443), que es por donde
 *      van las webs y apps: un flujo UDP que reutiliza el mismo puerto (una consola, una llamada)
 *      seguiría en conntrack mientras envíe y quedaría cortado indefinidamente.
 * - Cadena input con prioridad -1: los perfiles protegidos no abren LuCI ni SSH. Los teléfonos
 *   con la app (administradores) nunca se bloquean.
 *
 * El fichero cambia de nombre con su contenido (wrtcontrol-<versión>.nft): así `uci apply` con
 * reversión también restaura las reglas, porque vuelve a apuntar al fichero anterior, que no se
 * borra hasta confirmar. La primera regla lleva la versión en el comentario para comprobar que
 * fw4 cargó justo este fichero.
 *
 * Sintaxis comprobada con el analizador de nftables 1.0.2 (OpenWrt 22.03) y cargada con 1.1.6.
 * Sin sets con nombre: fw4 recarga con `flush table`, que vacía las cadenas pero no los sets, así
 * que una MAC quitada seguiría dentro. Los sets anónimos van en las reglas y se regeneran. Solo
 * ASCII: el nombre de los perfiles no aparece.
 */

const val INCLUDE_DIR = "/etc/luci-uploads"
private const val INCLUDE_PREFIX = "wrtcontrol-"
const val INPUT_CHAIN = "wrtcontrol_input"

/** Comentario de las reglas propias (las de fw4 llevan "!fw4: "). */
const val OWN_COMMENT = "!wrtcontrol: "
private const val VERSION_TAG = "v="

/** Bit de marca de los paquetes que entran desde la red de casa (reglas MARK por zona). */
const val QUARANTINE_MARK = 0x04000000L

/**
 * Bit de marca de lo que entra por la WAN (zonas con NAT). La cuarentena no rechaza nada que lo
 * lleve: si una zona de casa se queda sin dispositivos, fw4 genera su regla sin iifname y marca
 * todo, también lo que llega de internet; así eso sigue pasando (falla abierta, no cerrada).
 */
const val WAN_MARK = 0x02000000L

/** Bits del ct mark para la época: libres en mwan3 (0x3F00), pbr (0x00FF0000) y los esquemas de DSCP habituales. */
private const val EPOCH_MASK = 0x0000C000L
private const val EPOCH_SHIFT = 14
val CUT_EPOCHS = 1..3

/** Tras endurecer el filtro, durante este tiempo las conexiones nuevas se vuelven a cortar al final. */
const val CUT_WINDOW_SECONDS = 90L

/**
 * La primera vez que un dispositivo pasa por el filtro, su caché viene del DNS principal del
 * router, sin TTL corto (YouTube da 300 s): la ventana dura más para que caduque.
 */
const val CUT_WINDOW_FIRST_SECONDS = 330L

/** TTL máximo que dan las instancias DNS de los perfiles (dnsmasq --max-ttl): menos que la ventana. */
const val DNS_MAX_TTL = 60

/** Resolutores públicos de DNS cifrado. Solo rangos dedicados a DNS, para no bloquear webs normales. */
internal val DOH_IPV4: List<String> = listOf(
    "1.0.0.1", "1.0.0.2", "1.0.0.3", "1.1.1.1", "1.1.1.2", "1.1.1.3", // Cloudflare
    "8.8.4.4", "8.8.8.8", // Google
    "9.9.9.0/24", "149.112.112.0/24", // Quad9
    "45.90.28.0/24", "45.90.30.0/24", // NextDNS
    "76.76.2.0/24", "76.76.10.0/24", // Control D
    "94.140.14.0/24", "94.140.15.0/24", // AdGuard
    "146.112.41.2", // OpenDNS (DoH)
    "185.228.168.0/24", "185.228.169.0/24", // CleanBrowsing
    "194.242.2.0/24", // Mullvad
    "208.67.220.0/24", "208.67.222.0/24", // OpenDNS
)

internal val DOH_IPV6: List<String> = listOf(
    "2001:4860:4860::/48", // Google
    "2606:4700:4700::/48", // Cloudflare
    "2620:fe::/48", // Quad9
    "2a07:a8c0::/32", "2a07:a8c1::/32", // NextDNS
    "2606:1a40::/48", "2606:1a40:1::/48", // Control D
    "2a10:50c0::/32", // AdGuard
    "2a0d:2a00:1::/48", "2a0d:2a00:2::/48", // CleanBrowsing
    "2a07:e340::/32", // Mullvad
    "2620:119:35::/48", "2620:119:53::/48", "2620:119:fc::/48", // OpenDNS
)

/** Puertos de administración por defecto (SSH y LuCI) si no se pueden leer de UCI. */
val DEFAULT_ADMIN_PORTS = listOf(22, 80, 443)

/** Formato de `meta time` en nftables (hora local del router, como hace fw4 con start_date). */
private val NFT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** El fichero de reglas y su versión (hash del contenido). */
data class NftInclude(val content: String, val version: String) {
    val path: String get() = "$INCLUDE_DIR/$INCLUDE_PREFIX$version.nft"
    val marker: String get() = OWN_COMMENT + VERSION_TAG + version
}

/** ¿Es [path] un fichero de reglas de WrtControl (actual o antiguo)? */
fun isIncludeFile(path: String): Boolean =
    path == LEGACY_INCLUDE_PATH || (path.startsWith("$INCLUDE_DIR/$INCLUDE_PREFIX") && path.endsWith(".nft"))

/** Versión que lleva un nombre de fichero de reglas, o null. */
fun includeVersion(path: String?): String? =
    path?.takeIf { it.startsWith("$INCLUDE_DIR/$INCLUDE_PREFIX") && it.endsWith(".nft") }
        ?.removePrefix("$INCLUDE_DIR/$INCLUDE_PREFIX")?.removeSuffix(".nft")

/** Nombre fijo de las versiones anteriores de la app. */
const val LEGACY_INCLUDE_PATH = "/etc/luci-uploads/wrtcontrol.nft"

/**
 * Genera el fichero para [profiles] (ya normalizados) y [settings]. [appMacs] son los
 * dispositivos que ejecutan la app en este cambio (además de [ParentalSettings.admins]).
 */
internal fun buildInclude(
    profiles: List<Profile>,
    settings: ParentalSettings,
    env: RouterEnv,
    appMacs: Set<String> = emptySet(),
): NftInclude {
    val admins = (appMacs + settings.admins).mapNotNull(::normalizeMac).toSet()
    val forward = mutableListOf<String>()

    if (settings.quarantine) {
        val approved = (settings.approved + profiles.flatMap { it.macs } + admins).distinct().sorted()
        require(env.homeZones.isNotEmpty()) { "No se encontró la zona de la red de casa en el firewall, así que no se puede activar la cuarentena" }
        require(approved.isNotEmpty()) { "Aprueba al menos un dispositivo antes de activar la cuarentena" }
        val marks = QUARANTINE_MARK or WAN_MARK
        val match = "meta mark & ${hex(marks)} == ${hex(QUARANTINE_MARK)} ct direction original ether saddr != ${macSet(approved)}"
        forward += rejectBoth(match, "cuarentena")
    }

    forward += rejectBoth("meta mark & ${hex(MARK_BIT)} == ${hex(MARK_BIT)}", "bloqueo")

    for (p in profiles.filter { it.needsDns }.sortedBy { it.id }) {
        val macs = "ether saddr ${macSet(p.macs)}"
        val tag = "doh ${p.id}"
        for ((family, ips) in listOf("ip" to DOH_IPV4, "ip6" to DOH_IPV6)) {
            forward += "$macs $family daddr ${set(ips)} meta l4proto tcp counter reject with tcp reset comment ${quote(tag)}"
            forward += "$macs $family daddr ${set(ips)} meta l4proto udp counter reject with icmpx type admin-prohibited comment ${quote(tag)}"
        }
    }

    for (p in profiles.filter { it.needsDns && it.cutEpoch in CUT_EPOCHS }.sortedBy { it.id }) {
        forward += cutRules(p)
    }

    val protectedMacs = (profiles.filter { it.protectRouter }.flatMap { it.macs }.mapNotNull(::normalizeMac) - admins).distinct().sorted()
    val input = if (protectedMacs.isEmpty() || env.adminPorts.isEmpty()) {
        emptyList()
    } else {
        listOf(
            "ether saddr ${macSet(protectedMacs)} tcp dport ${set(env.adminPorts.distinct().sorted().map(Int::toString))} " +
                "counter reject with tcp reset comment ${quote("router")}",
        )
    }

    fun render(version: String) = buildString {
        append("# WrtControl: reglas que escribe la app. No editar: se reemplaza en cada cambio.\n")
        append(chain(BLOCK_CHAIN, "forward", listOf("counter comment ${quote(VERSION_TAG + version)}") + forward))
        if (input.isNotEmpty()) append(chain(INPUT_CHAIN, "input", input))
    }

    val version = sha256(render("")).take(12)
    return NftInclude(render(version), version)
}

/** Reglas de corte de un perfil: marcar las conexiones nuevas y rechazar las de otra época. */
private fun cutRules(p: Profile): List<String> {
    val macs = "ether saddr ${macSet(p.macs)}"
    val tag = "corte ${p.id}"
    val keep = hex(EPOCH_MASK.inv() and 0xFFFFFFFFL)
    val epoch = hex(p.cutEpoch.toLong() shl EPOCH_SHIFT)
    val window = p.cutWindow?.takeIf { it.value in CUT_EPOCHS && it.value != p.cutEpoch }
    val rules = mutableListOf<String>()
    fun reject(match: String) {
        // Solo las que abrió el dispositivo: las que vienen de fuera (p. ej. un puerto abierto a
        // una consola) no llevan marca y no deben cortarse en cada paquete.
        rules += "$macs ct direction original $match meta l4proto tcp counter reject with tcp reset comment ${quote(tag)}"
        rules += "$macs ct direction original $match udp dport 443 counter reject with icmpx type admin-prohibited comment ${quote(tag)}"
    }
    val mark = "ct mark & ${hex(EPOCH_MASK)}"
    if (window == null) {
        rules += "$macs ct state new ct mark set ct mark & $keep | $epoch comment ${quote(tag)}"
        reject("$mark != $epoch")
    } else {
        val until = "\"${window.until.format(NFT_TIME)}\""
        val value = hex(window.value.toLong() shl EPOCH_SHIFT)
        rules += "$macs ct state new meta time < $until ct mark set ct mark & $keep | $value comment ${quote(tag)}"
        rules += "$macs ct state new meta time >= $until ct mark set ct mark & $keep | $epoch comment ${quote(tag)}"
        reject("$mark != $epoch $mark != $value") // abiertas antes de endurecer
        reject("$mark == $value meta time >= $until") // abiertas durante la ventana, ya acabada
    }
    return rules
}

private fun chain(name: String, hook: String, rules: List<String>) = buildString {
    append("chain $name {\n")
    append("\ttype filter hook $hook priority -1; policy accept;\n")
    rules.forEach { append('\t').append(it).append('\n') }
    append("}\n")
}

/** TCP con reset (falla al momento) y el resto con ICMP de "prohibido". */
private fun rejectBoth(match: String, tag: String) = listOf(
    "$match meta l4proto tcp counter reject with tcp reset comment ${quote(tag)}",
    "$match counter reject with icmpx type admin-prohibited comment ${quote(tag)}",
)

private fun set(items: List<String>): String {
    require(items.isNotEmpty()) // `{ }` es un error de sintaxis en nftables 1.0.2
    return items.joinToString(", ", "{ ", " }")
}

private fun macSet(macs: List<String>) = set(macs.map { it.lowercase() }.distinct().sorted())

private fun quote(tag: String): String {
    val text = OWN_COMMENT + tag
    require(text.all { it.code in 0x20..0x7E && it != '"' && it != '\\' }) { "Comentario no válido: $text" }
    return "\"$text\""
}

private fun hex(v: Long) = "0x" + v.toString(16).padStart(8, '0')

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Siguiente época de corte (1 → 2 → 3 → 1). */
internal fun nextEpoch(epoch: Int): Int = if (epoch in CUT_EPOCHS) epoch % CUT_EPOCHS.last + 1 else CUT_EPOCHS.first

/**
 * Cambia la época de los perfiles cuyo filtro se endureció entre [before] y [after] y abre la
 * ventana del segundo corte hasta [now] + [CUT_WINDOW_SECONDS]: así sus conexiones abiertas se
 * cortan y no sigue, por ejemplo, un vídeo de TikTok recién bloqueado. Relajar el filtro no corta
 * nada. Manda la época del router: el perfil puede venir de un editor abierto antes de otro
 * cambio, y volver a una época anterior cortaría todo sin motivo.
 */
internal fun bumpCutEpochs(before: List<Profile>, after: List<Profile>, now: LocalDateTime): List<Profile> {
    val old = before.associateBy { it.id }
    return after.map { p ->
        if (!p.needsDns) return@map p
        val prev = old[p.id]
        val current = prev?.cutEpoch?.takeIf { it in CUT_EPOCHS } ?: p.cutEpoch
        val window = if (prev != null) prev.cutWindow else p.cutWindow
        when {
            p.filterHardenedFrom(prev) -> {
                // Dispositivos que hasta ahora no pasaban por la instancia del perfil (con TTL corto).
                val fresh = prev == null || !prev.needsDns || (p.macs - prev.macs.toSet()).isNotEmpty()
                val seconds = if (fresh) CUT_WINDOW_FIRST_SECONDS else CUT_WINDOW_SECONDS
                // Si ya hubo una ventana (abierta o no), sus conexiones conservan esa marca: la época
                // nueva tiene que ser la otra, o esas conexiones pasarían por actuales y no se
                // cortarían nunca. La ventana nueva reutiliza su marca, así se cortan al acabar esta.
                val oldWindow = window?.value?.takeIf { it in CUT_EPOCHS && it != current }
                val epoch = if (oldWindow != null) CUT_EPOCHS.first { it != current && it != oldWindow } else nextEpoch(current)
                // La marca de la ventana: ni la de antes (la que se corta ya) ni la nueva.
                val value = oldWindow ?: CUT_EPOCHS.first { it != epoch && it != current }
                p.copy(cutEpoch = epoch, cutWindow = CutWindow(value, now.plusSeconds(seconds).withNano(0)))
            }
            current in CUT_EPOCHS -> p.copy(cutEpoch = current, cutWindow = window)
            else -> p.copy(cutEpoch = CUT_EPOCHS.first, cutWindow = null)
        }
    }
}

private fun Profile.filterHardenedFrom(old: Profile?): Boolean {
    if (old == null || !old.needsDns) return true
    if ((macs - old.macs.toSet()).isNotEmpty()) return true
    val f = filter
    val o = old.filter
    return (f.level != o.level && f.level != FilterLevel.None) ||
        (f.services - o.services).isNotEmpty() ||
        (f.domains - o.domains.toSet()).isNotEmpty() ||
        (f.safeSearch && !o.safeSearch) ||
        f.youtube.ordinal > o.youtube.ordinal
}
