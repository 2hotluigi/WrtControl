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

/*
 * Búsqueda segura y YouTube restringido forzados por DNS, como documenta Google: los nombres de
 * búsqueda apuntan (CNAME) a sus servidores "forcesafesearch" y "restrict". Las direcciones son
 * las que publica Google desde hace años y la app comprueba al aplicar que el DNS del perfil las
 * devuelve. Van en registros propios de cada instancia de dnsmasq (host-record) con IPv4 e IPv6:
 * con solo IPv4, la consulta AAAA se reenvía y la búsqueda segura se escapa por IPv6.
 */

/** Sufijos de los dominios de búsqueda de Google (www.google.<sufijo>), de google.com/supported_domains. */
internal val GOOGLE_SUFFIXES: List<String> = listOf(
    "com ad ae com.af com.ag al am co.ao com.ar as at com.au az ba com.bd be bf bg com.bh bi bj",
    "com.bn com.bo com.br bs bt co.bw by com.bz ca cd cf cg ch ci co.ck cl cm cn com.co co.cr com.cu",
    "cv com.cy cz de dj dk dm com.do dz com.ec ee com.eg es com.et fi com.fj fm fr ga ge gg com.gh",
    "com.gi gl gm gr com.gt gy com.hk hn hr ht hu co.id ie co.il im co.in iq is it je com.jm jo",
    "co.jp co.ke com.kh ki kg co.kr com.kw kz la com.lb li lk co.ls lt lu lv com.ly co.ma md me mg",
    "mk ml com.mm mn com.mt mu mv mw com.mx com.my co.mz com.na com.ng com.ni ne nl no com.np nr nu",
    "co.nz com.om com.pa com.pe com.pg com.ph com.pk pl pn com.pr ps pt com.py com.qa ro ru rw",
    "com.sa com.sb sc se com.sg sh si sk com.sl sn so sm sr st com.sv td tg co.th com.tj tl tm tn to",
    "com.tr tt com.tw co.tz com.ua co.ug co.uk com.uy co.uz com.vc co.ve co.vi com.vn vu ws rs co.za",
    "co.zm co.zw cat",
).flatMap { it.split(' ') }

internal const val GOOGLE_SAFE_HOST = "forcesafesearch.google.com"
internal const val YOUTUBE_STRICT_HOST = "restrict.youtube.com"
internal const val YOUTUBE_MODERATE_HOST = "restrictmoderate.youtube.com"

/** host-record: nombre → IPv4 e IPv6 (comprobadas con nslookup el 2026-11-05). */
internal val SAFE_SEARCH_RECORDS: Map<String, List<String>> = mapOf(
    GOOGLE_SAFE_HOST to listOf("216.239.38.120", "2001:4860:4802:32::78"),
    YOUTUBE_STRICT_HOST to listOf("216.239.38.120", "2001:4860:4802:32::78"),
    YOUTUBE_MODERATE_HOST to listOf("216.239.38.119", "2001:4860:4802:32::77"),
)

/** Nombres que usan la web y las apps de YouTube. */
internal val YOUTUBE_NAMES = listOf("www.youtube.com", "m.youtube.com", "youtubei.googleapis.com", "youtube.googleapis.com", "www.youtube-nocookie.com")

/**
 * Bing y DuckDuckGo cambian de dirección (CDN), así que no se fijan: sus consultas van al DNS
 * para familias de Cloudflare, que responde con su versión segura (strict.bing.com,
 * safe.duckduckgo.com). Comprobado con nslookup contra 1.1.1.3.
 */
internal val SAFE_SEARCH_FORWARDS = listOf("bing.com", "duckduckgo.com")

/** Lo que da el DNS para familias como CNAME de www.bing.com (búsqueda segura forzada). */
internal const val BING_STRICT_HOST = "strict.bing.com"
