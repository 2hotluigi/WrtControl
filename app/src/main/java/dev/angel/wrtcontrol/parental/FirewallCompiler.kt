package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.uciSection
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Cómo se bloquea (probado en OpenWrt 22.03 a 25.12 con fw4):
 *
 * 1. Reglas MARK de fw4 por MAC (src '*' → dest '*' cae en la cadena mangle_forward). Marcan cada
 *    paquete reenviado de los dispositivos del perfil mientras dura la pausa o la franja horaria.
 *    fw4 convierte start_time/stop_time en `meta hour "21:00:00"-"23:59:59"` y nftables las pasa a
 *    UTC al cargar (también los rangos que cruzan la medianoche UTC); `meta day` usa la hora local.
 *    El tiempo extra pone `start_date` a las reglas de los horarios: el router vuelve a aplicarlos
 *    solo, a su hora, aunque la app esté cerrada.
 *
 * 2. Reglas propias en un fichero que incluye fw4 (ver NftInclude.kt): rechazan lo marcado antes
 *    de que fw4 acepte las conexiones ya establecidas, y además la cuarentena, el DNS cifrado por
 *    IP, el corte de conexiones al endurecer el filtro y el acceso al router.
 *
 * 3. Filtro de contenido (DNS): cada perfil con filtro tiene su propia instancia de dnsmasq en un
 *    puerto 53xx con su lista de bloqueo, y una redirección de fw4 por MAC le manda todo el DNS
 *    de sus dispositivos, por IPv4 e IPv6 y vaya al servidor que vaya. dnsmasq no admite
 *    políticas por cliente, y el paquete básico no trae nftset; esto funciona sin instalar nada.
 *
 * Todo lo variable va en UCI (`firewall` y `dhcp`) o en el fichero de reglas, cuyo nombre cambia
 * con su contenido; así `uci apply` con reversión cubre cualquier cambio.
 */

/** Bit de marca que usa WrtControl (mwan3 usa 0x3F00 y pbr 0x00FF0000 por defecto). */
const val MARK_BIT = 0x08000000L
private const val MARK_OPTION = "0x08000000/0x08000000"

const val INCLUDE_SECTION = "wrtc_include"
const val BLOCK_CHAIN = "wrtcontrol_forward"
const val RULE_LABEL_PREFIX = "WrtControl · "

/** Bytes del nombre del perfil dentro del nombre de la regla (el resto ocupa < 60). */
private const val MAX_LABEL_BYTES = 40

/**
 * Lo que debe quedar en el router: todas las secciones "wrtc_" de `firewall` y de `dhcp`, los
 * nombres de las reglas que fw4 debe cargar y los servidores DNS de los perfiles con filtro.
 */
data class RouterPlan(
    val firewall: List<UciSection>,
    val ruleLabels: List<String>,
    val dhcp: List<UciSection> = emptyList(),
    /** Instancia de dnsmasq → puerto. */
    val dnsInstances: Map<String, Int> = emptyMap(),
    /** Perfiles ya normalizados (pausas vencidas fuera, puertos DNS asignados). */
    val profiles: List<Profile> = emptyList(),
    val settings: ParentalSettings = ParentalSettings(),
    /** Reglas propias; null si no hacen falta (sin perfiles ni cuarentena). */
    val include: NftInclude? = null,
)

/** Lo que el compilador necesita saber del router además de los perfiles. */
data class RouterEnv(
    /** Zonas de la LAN (las que no hacen NAT hacia fuera): ahí se redirige el DNS de los perfiles. */
    val lanZones: List<String> = listOf("lan"),
    /** Interfaces con secciones `dhcp` que se aplican a todas las instancias de dnsmasq. */
    val dhcpInterfaces: List<String> = listOf("lan"),
    /** Dominio local (`option domain` de dnsmasq), para que sigan resolviendo los nombres de la LAN. */
    val localDomain: String = "lan",
    /** Zonas de la red de casa, donde se aplica la cuarentena (ver [routerEnv]). */
    val homeZones: List<String> = listOf("lan"),
    /** Zonas con NAT (la WAN): lo que entra por ellas nunca se rechaza por la cuarentena. */
    val wanZones: List<String> = listOf("wan"),
    /** Puertos TCP de LuCI y SSH en el router. */
    val adminPorts: List<Int> = DEFAULT_ADMIN_PORTS,
)

/**
 * Lo que hace falta saber del router, leído de su configuración. [admin] son las secciones de
 * uhttpd y dropbear, o null si no se pudieron leer. [devices]: interfaz lógica → dispositivos que
 * tiene ahora (network.interface dump), o null si no se pudo leer.
 */
fun routerEnv(
    firewall: List<UciSection>,
    dhcp: List<UciSection>,
    admin: List<UciSection>? = null,
    devices: Map<String, List<String>>? = null,
): RouterEnv {
    // Como fw4: las zonas y los reenvíos desactivados no existen.
    val zones = firewall.filter { it.type == "zone" && !uciFalse(it.text("enabled")) }
    val lanZones = zones.filter { uciBool(it.text("masq")) != true }
    // La cuarentena va en las redes de casa: las que reenvían hacia otra zona (lan → wan). Una WAN
    // sin NAT no reenvía a ninguna (y aunque lo hiciera, las reglas solo miran las conexiones que
    // se abren desde dentro).
    val forwardingSources = firewall
        .filter { it.type == "forwarding" && !uciFalse(it.text("enabled")) && it.text("src") != it.text("dest") }
        .mapNotNull { it.text("src") }.toSet()
    val names = lanZones.mapNotNull { it.text("name") }.distinct()
    // Una zona sin ningún dispositivo ahora (p. ej. el Wi-Fi de invitados apagado) haría que fw4
    // generara la regla MARK sin iifname, marcando todo lo que entra, también por la WAN: con la
    // cuarentena se rechazarían las conexiones que llegan de internet (redirecciones de puertos).
    // Esas zonas se dejan fuera hasta el siguiente cambio.
    fun hasDevices(zone: UciSection): Boolean =
        devices == null || zone.items("device").isNotEmpty() || zone.items("network").any { devices[it].orEmpty().isNotEmpty() }
    val withDevices = lanZones.filter(::hasDevices).mapNotNull { it.text("name") }.toSet()
    return RouterEnv(
        lanZones = names,
        dhcpInterfaces = dhcp
            .filter { it.type == "dhcp" && it.text("instance") == null && !it.name.startsWith(SECTION_PREFIX) }
            .mapNotNull { it.text("interface") }.distinct(),
        localDomain = dhcp.firstOrNull { it.type == "dnsmasq" && !it.name.startsWith(SECTION_PREFIX) }?.text("domain") ?: "lan",
        homeZones = names.filter { it in forwardingSources && it in withDevices },
        wanZones = zones.filter { uciBool(it.text("masq")) == true }.mapNotNull { it.text("name") }.distinct(),
        adminPorts = admin?.let(::adminPorts) ?: DEFAULT_ADMIN_PORTS,
    )
}

/** Booleano de UCI como lo lee fw4 (parse_bool): true, false o null si no es ninguno. */
private fun uciBool(text: String?): Boolean? = when (text) {
    "1", "on", "true", "yes" -> true
    "0", "off", "false", "no" -> false
    else -> null
}

private fun uciFalse(text: String?): Boolean = uciBool(text) == false

/** Puertos de uhttpd (listen_http/listen_https: "0.0.0.0:80", "[::]:443") y de dropbear (Port, 22 por defecto). */
internal fun adminPorts(sections: List<UciSection>): List<Int> {
    val web = sections.filter { it.type == "uhttpd" }
        .flatMap { it.items("listen_http") + it.items("listen_https") }
        .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
    val ssh = sections.filter { it.type == "dropbear" && it.text("enable") != "0" }
        .map { it.text("Port")?.toIntOrNull() ?: 22 }
    val ports = (web + ssh).filter { it in 1..65535 }.distinct().sorted()
    return ports.ifEmpty { DEFAULT_ADMIN_PORTS }
}

/** Franja de una regla: días y horas (inclusivas, en segundos); sin horas = el día entero. */
internal data class RuleWindow(val days: Set<DayOfWeek>, val start: LocalTime?, val stop: LocalTime?)

/**
 * `meta day` se evalúa con la hora local en cada paquete, así que una franja que cruza la
 * medianoche se parte en dos reglas: la noche del día elegido y la madrugada del siguiente.
 */
internal fun Schedule.windows(): List<RuleWindow> = when {
    allDay -> listOf(RuleWindow(days, null, null))
    !overnight -> listOf(RuleWindow(days, start, end.minusSeconds(1)))
    else -> buildList {
        add(RuleWindow(days, start, LocalTime.of(23, 59, 59)))
        if (end != LocalTime.MIDNIGHT) add(RuleWindow(days.map { it.plus(1) }.toSet(), LocalTime.MIDNIGHT, end.minusSeconds(1)))
    }
}

fun compileRouter(profiles: List<Profile>, now: LocalDateTime, env: RouterEnv = RouterEnv()): RouterPlan =
    compileRouter(ParentalConfig(profiles), now, env)

/** [appMacs]: dispositivos que ejecutan la app; nunca pierden el acceso al router. */
fun compileRouter(config: ParentalConfig, now: LocalDateTime, env: RouterEnv = RouterEnv(), appMacs: Set<String> = emptySet()): RouterPlan {
    val current = assignDnsPorts(config.profiles.map { it.withoutExpired(now) }).map { p ->
        if (p.needsDns && p.cutEpoch !in CUT_EPOCHS) p.copy(cutEpoch = CUT_EPOCHS.first) else p
    }
    require(current.none { it.needsDns } || env.lanZones.isNotEmpty()) {
        "No se encontró la zona LAN del firewall, así que no se puede aplicar el filtro de contenido"
    }
    val settings = config.settings
    require(!settings.quarantine || env.homeZones.isNotEmpty()) {
        "No se encontró la zona de la red de casa en el firewall, así que no se puede activar la cuarentena"
    }
    val rules = current.flatMap { profileRules(it) + dnsFirewall(it, env) } + quarantineRules(settings, env)
    val include = if (current.isEmpty() && !settings.quarantine) null else buildInclude(current, settings, env, appMacs)
    val data = configSections(ParentalConfig(current, settings))
    return RouterPlan(
        firewall = data + rules + listOfNotNull(include?.let(::includeSection)),
        ruleLabels = rules.map { it.text("name")!! },
        dhcp = current.flatMap { dnsServer(it, env) },
        dnsInstances = current.filter { it.needsDns }.associate { dnsInstance(it) to it.dnsPort!! },
        profiles = current,
        settings = settings,
        include = include,
    )
}

internal fun dnsInstance(p: Profile) = "wrtc_dns_${p.id}"

/**
 * Con la cuarentena, una regla MARK por zona de casa marca lo que entra por ella con
 * [QUARANTINE_MARK], y otra por zona con NAT marca lo que entra de internet con [WAN_MARK]. fw4
 * las regenera con los dispositivos que tenga cada zona en cada recarga. Las reglas propias
 * rechazan lo que lleva la marca de casa sin la de WAN y viene de una MAC no aprobada.
 */
private fun quarantineRules(settings: ParentalSettings, env: RouterEnv): List<UciSection> {
    if (!settings.quarantine) return emptyList()
    val wan = env.wanZones.mapIndexed { i, zone ->
        uciSection(
            "wrtc_qw_$i", "rule",
            "name" to RULE_LABEL_PREFIX + "cuarentena, internet ($zone)",
            "src" to zone,
            "dest" to "*",
            "proto" to "all",
            "target" to "MARK",
            "set_xmark" to "${hexMark(WAN_MARK)}/${hexMark(WAN_MARK)}",
        )
    }
    return wan + env.homeZones.mapIndexed { i, zone ->
        uciSection(
            "wrtc_q_$i", "rule",
            "name" to RULE_LABEL_PREFIX + "cuarentena ($zone)",
            "src" to zone,
            "dest" to "*",
            "proto" to "all",
            "target" to "MARK",
            "set_xmark" to "${hexMark(QUARANTINE_MARK)}/${hexMark(QUARANTINE_MARK)}",
        )
    }
}

private fun hexMark(v: Long) = "0x" + v.toString(16).padStart(8, '0')

/**
 * Servidor DNS propio del perfil: una instancia más de dnsmasq en [Profile.dnsPort] con su lista
 * de bloqueo, que sube las consultas al DNS para familias del nivel elegido o, sin nivel, a la
 * instancia principal del router. Las secciones `dhcp` con `ignore` evitan que sirva DHCP: las
 * secciones `dhcp` sin `instance` se aplican a todas las instancias, y si al arrancar no detectara
 * a tiempo el DHCP de la principal habría dos servidores DHCP en la LAN.
 */
private fun dnsServer(p: Profile, env: RouterEnv): List<UciSection> {
    if (!p.needsDns) return emptyList()
    val name = dnsInstance(p)
    val familyDns = p.filter.level != FilterLevel.None
    val blocked = (
        p.filter.domains +
            p.filter.services.sorted().flatMap { id -> SERVICES.firstOrNull { it.id == id }?.domains.orEmpty() } +
            BYPASS_DOMAINS
        ).distinct()
    val safeSearch = p.filter.safeSearch && !familyDns // los DNS para familias ya la fuerzan
    val youtube = p.filter.youtube.takeIf { it != YoutubeMode.Off && "youtube" !in p.filter.services }
    val forwards = if (safeSearch) SAFE_SEARCH_FORWARDS.flatMap { d -> FilterLevel.Adult.upstreams().map { "/$d/$it" } } else emptyList()
    val instance = uciSection(
        name, "dnsmasq",
        "port" to p.dnsPort!!.toString(),
        "noresolv" to "1",
        // Sin esto, dnsmasq apuntaría /tmp/resolv.conf del propio router a esta instancia.
        "localuse" to "0",
        "localservice" to "1",
        "domainneeded" to "1",
        "boguspriv" to "1",
        "cachesize" to "1000",
        // Las respuestas caducan pronto en los dispositivos: al endurecer el filtro, el segundo
        // corte llega cuando ya no les queda nada en caché (ver NftInclude.kt).
        "max_ttl" to DNS_MAX_TTL.toString(),
        "server" to if (familyDns) p.filter.level.upstreams() + "/${env.localDomain}/127.0.0.1#53" else forwards + "127.0.0.1#53",
        // Sin nivel, todo pasa por la instancia principal, que ya protege del DNS rebinding;
        // con DNS para familias, se protege aquí y se permiten los nombres de la LAN.
        "rebind_protection" to if (familyDns) "1" else "0",
        "rebind_domain" to if (familyDns) listOf(env.localDomain) else null,
        "address" to blocked.map { "/$it/" },
    )
    val noDhcp = env.dhcpInterfaces.mapIndexed { i, iface ->
        uciSection("wrtc_nodhcp_${p.id}_$i", "dhcp", "instance" to name, "interface" to iface, "ignore" to "1")
    }
    return listOf(instance) + noDhcp + safeSearchRecords(p.id, name, safeSearch, youtube)
}

/**
 * Registros propios de la instancia (secciones `cname` y `hostrecord` con `instance`, que
 * dnsmasq.init aplica solo a esa instancia): www.google.* → forcesafesearch.google.com y los
 * nombres de YouTube → restrict(moderate).youtube.com. Líneas cortas: en 2.86 una línea `cname`
 * de 5 KB no arranca.
 */
private fun safeSearchRecords(id: String, instance: String, safeSearch: Boolean, youtube: YoutubeMode?): List<UciSection> {
    val out = mutableListOf<UciSection>()
    fun host(suffix: String, host: String) {
        out += uciSection("wrtc_hr_${id}_$suffix", "hostrecord", "instance" to instance, "name" to listOf(host), "ip" to SAFE_SEARCH_RECORDS.getValue(host))
    }
    if (safeSearch) {
        host("g", GOOGLE_SAFE_HOST)
        GOOGLE_SUFFIXES.map { "www.google.$it" }.chunked(CNAMES_PER_SECTION).forEachIndexed { i, names ->
            out += uciSection("wrtc_cn_${id}_g$i", "cname", "instance" to instance, "cname" to names.joinToString(","), "target" to GOOGLE_SAFE_HOST)
        }
    }
    if (youtube != null) {
        val target = if (youtube == YoutubeMode.Strict) YOUTUBE_STRICT_HOST else YOUTUBE_MODERATE_HOST
        host("yt", target)
        out += uciSection("wrtc_cn_${id}_yt", "cname", "instance" to instance, "cname" to YOUTUBE_NAMES.joinToString(","), "target" to target)
    }
    return out
}

private const val CNAMES_PER_SECTION = 20

/**
 * Redirige todo el DNS de los dispositivos del perfil (al router o a cualquier servidor, por IPv4
 * e IPv6) a su servidor, y corta el DNS sobre TLS/QUIC (853) para que no se lo salten.
 */
private fun dnsFirewall(p: Profile, env: RouterEnv): List<UciSection> {
    if (!p.needsDns) return emptyList()
    val label = RULE_LABEL_PREFIX + labelName(p.name)
    val redirects = env.lanZones.mapIndexed { i, zone ->
        uciSection(
            "wrtc_d_${p.id}_$i", "redirect",
            "name" to "$label · DNS" + if (env.lanZones.size > 1) " ($zone)" else "",
            "src" to zone,
            "src_dport" to "53",
            "dest_port" to p.dnsPort!!.toString(),
            "proto" to "tcp udp",
            "family" to "any",
            "src_mac" to p.macs,
            "target" to "DNAT",
        )
    }
    val dot = markRule("wrtc_r_${p.id}_dot", "$label · DNS cifrado", p.macs, proto = "tcp udp", destPort = "853")
    return redirects + dot
}

private fun includeSection(include: NftInclude) = uciSection(
    INCLUDE_SECTION, "include",
    "type" to "nftables",
    "path" to include.path,
    "position" to "table-append",
)

private fun profileRules(p: Profile): List<UciSection> {
    // Sin MAC, una regla MARK sin src_mac marcaría el tráfico de toda la red.
    if (p.macs.isEmpty()) return emptyList()
    val label = RULE_LABEL_PREFIX + labelName(p.name)
    val rules = mutableListOf<UciSection>()

    when (val pause = p.pause) {
        Pause.Indefinite -> rules += markRule("wrtc_r_${p.id}_pausa", "$label · pausa", p.macs)
        is Pause.Until -> rules += markRule("wrtc_r_${p.id}_pausa", "$label · pausa", p.macs, stopDate = pause.end)
        null -> {}
    }

    p.schedules.forEachIndexed { index, schedule ->
        if (!schedule.enabled) return@forEachIndexed
        schedule.windows().forEachIndexed { part, window ->
            val suffix = if (part == 0) "" else " (día siguiente)"
            rules += markRule("wrtc_r_${schedule.id}_${part + 1}", "$label · horario ${index + 1}$suffix", p.macs, window, startDate = p.extraUntil)
        }
    }
    return rules
}

private fun markRule(
    name: String,
    label: String,
    macs: List<String>,
    window: RuleWindow? = null,
    stopDate: LocalDateTime? = null,
    startDate: LocalDateTime? = null,
    // fw4 usa "tcp udp" si no se indica: con "all" también se marca ICMP, QUIC, etc.
    proto: String = "all",
    destPort: String? = null,
) = uciSection(
    name, "rule",
    "name" to label,
    "src" to "*",
    "dest" to "*",
    "proto" to proto,
    "src_mac" to macs,
    "dest_port" to destPort,
    "target" to "MARK",
    "set_xmark" to MARK_OPTION,
    "weekdays" to window?.days?.sorted()?.joinToString(" ") { it.uciName },
    "start_time" to window?.start?.format(HH_MM_SS),
    "stop_time" to window?.stop?.format(HH_MM_SS),
    "start_date" to startDate?.format(STAMP),
    "stop_date" to stopDate?.format(STAMP),
)

/**
 * El nombre de la regla acaba como comentario de nftables, que admite como mucho 128 bytes
 * (con el prefijo "!fw4: " de fw4). Se recorta por bytes UTF-8 sin partir caracteres.
 */
internal fun labelName(name: String): String {
    val clean = cleanProfileName(name)
    if (clean.toByteArray(Charsets.UTF_8).size <= MAX_LABEL_BYTES) return clean
    val out = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < clean.length) {
        val cp = clean.codePointAt(i)
        val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > MAX_LABEL_BYTES - 3) break // deja sitio para "…" (3 bytes)
        out.appendCodePoint(cp)
        bytes += size
        i += Character.charCount(cp)
    }
    return out.toString().trimEnd() + "…"
}

// ---------- diferencias con lo que hay en el router ----------

sealed interface UciChange {
    data class Delete(val section: String) : UciChange

    data class Add(val section: UciSection) : UciChange
}

/**
 * Cambios para pasar de [existing] a [desired] tocando solo las secciones "wrtc_". Una sección
 * que cambia se borra y se vuelve a crear: es más simple que borrar opciones sueltas y el orden
 * de estas secciones no importa (las reglas MARK no deciden nada por sí solas).
 */
fun diffSections(existing: List<UciSection>, desired: List<UciSection>): List<UciChange> {
    val ours = existing.filter { it.name.startsWith(SECTION_PREFIX) }.associateBy { it.name }
    val wanted = desired.associateBy { it.name }
    val deletes = ours.values.filter { wanted[it.name] != it }.map { UciChange.Delete(it.name) }
    val adds = wanted.values.filter { ours[it.name] != it }.map { UciChange.Add(it) }
    return deletes + adds
}

// ---------- conflictos ----------

/** Reglas MARK de otros que tocan los bits de WrtControl: podrían quitar o poner la marca de bloqueo o de cuarentena. */
fun markConflicts(sections: List<UciSection>): List<String> = sections
    .filter { !it.name.startsWith(SECTION_PREFIX) && it.type == "rule" && it.text("target").equals("MARK", ignoreCase = true) }
    .filter { s ->
        listOf("set_mark", "set_xmark").any { key -> s.text(key)?.let(::markMask)?.let { it and (MARK_BIT or QUARANTINE_MARK or WAN_MARK) != 0L } == true }
    }
    .map { it.text("name") ?: it.name }

/** Máscara efectiva de "valor[/máscara]": sin máscara, set_mark cambia los 32 bits. */
private fun markMask(text: String): Long? {
    val parts = text.trim().split('/')
    fun parse(s: String): Long? = s.trim().let { if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
    val value = parse(parts[0]) ?: return null
    val mask = if (parts.size > 1) parse(parts[1]) ?: return null else 0xFFFFFFFFL
    return mask or value
}
