package dev.angel.wrtcontrol.parental

import java.net.IDN
import java.util.Locale

/**
 * Servicio que se puede bloquear de un toque. Se bloquean los dominios principales (y todos sus
 * subdominios): es aproximado, porque las apps pueden cambiar de dominios con el tiempo. El
 * perfil guarda el identificador, así que una lista actualizada en otra versión de la app se
 * aplica al volver a guardar.
 */
data class BlockableService(val id: String, val name: String, val domains: List<String>)

val SERVICES: List<BlockableService> = listOf(
    BlockableService(
        "tiktok", "TikTok",
        listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "ttwstatic.com", "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "musical.ly"),
    ),
    BlockableService("instagram", "Instagram", listOf("instagram.com", "cdninstagram.com", "ig.me", "instagr.am")),
    BlockableService("facebook", "Facebook", listOf("facebook.com", "facebook.net", "fb.com", "fb.me", "fbcdn.net", "messenger.com")),
    BlockableService(
        "youtube", "YouTube",
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "googlevideo.com", "youtubei.googleapis.com", "youtube.googleapis.com"),
    ),
    BlockableService("roblox", "Roblox", listOf("roblox.com", "rbxcdn.com", "rbx.com", "robloxlabs.com")),
    BlockableService("fortnite", "Fortnite", listOf("fortnite.com", "epicgames.com", "epicgames.dev")),
    BlockableService("freefire", "Free Fire", listOf("freefiremobile.com", "garena.com", "garenanow.com")),
    BlockableService("discord", "Discord", listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media")),
    BlockableService("twitch", "Twitch", listOf("twitch.tv", "ttvnw.net", "jtvnw.net")),
    BlockableService("x", "X (Twitter)", listOf("x.com", "twitter.com", "twimg.com", "t.co")),
)

/**
 * Siempre bloqueados con el filtro activo, para que no se salte con DNS cifrado del navegador
 * o del sistema: el dominio canario de Firefox (NXDOMAIN apaga su DNS sobre HTTPS), los
 * resolutores DoH más conocidos y el relay privado de iCloud (Apple pide responder NXDOMAIN
 * para desactivarlo en una red). El DNS sobre TLS (puerto 853) se corta en el firewall.
 */
val BYPASS_DOMAINS: List<String> = listOf(
    CANARY_DOMAIN,
    "dns.google", "cloudflare-dns.com", "one.one.one.one", "quad9.net", "doh.opendns.com",
    "dns.adguard-dns.com", "dns.adguard.com", "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.mullvad.net",
    "mask.icloud.com", "mask-h2.icloud.com",
)

/** Dominio que normalmente resuelve: si el DNS de un perfil responde NXDOMAIN, el filtro funciona. */
const val CANARY_DOMAIN = "use-application-dns.net"

/** Resolutores de cada nivel (IPv4: el WAN del router puede no tener IPv6). */
internal fun FilterLevel.upstreams(): List<String> = when (this) {
    FilterLevel.None -> emptyList()
    FilterLevel.Adult -> listOf("1.1.1.3", "1.0.0.3")
    FilterLevel.Strict -> listOf("185.228.168.168", "185.228.169.168")
}

const val MAX_BLOCKED_DOMAINS = 200

/**
 * Normaliza lo que escribe el usuario a un dominio que bloquear con todos sus subdominios:
 * quita esquema, ruta, puerto y "www.", pasa a minúsculas y convierte nombres con acentos (IDN).
 * Exige al menos dos etiquetas para no bloquear un dominio de primer nivel entero.
 */
fun normalizeDomain(text: String): String? {
    var d = text.trim().lowercase(Locale.ROOT)
    d = d.substringAfter("://")
    d = d.substringBefore('/').substringBefore('?').substringBefore('#')
    d = d.substringAfter('@')
    if (d.count { it == ':' } == 1) d = d.substringBefore(':')
    d = d.trimEnd('.').removePrefix("*.").removePrefix("www.")
    d = try {
        IDN.toASCII(d, IDN.USE_STD3_ASCII_RULES)
    } catch (_: IllegalArgumentException) {
        return null
    }.lowercase(Locale.ROOT)
    if (d.length > 253) return null
    val labels = d.split('.')
    if (labels.size < 2) return null
    val label = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    if (!labels.all { label.matches(it) }) return null
    if (labels.last().all { it.isDigit() }) return null // una IP, no un dominio
    return d
}
