package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.uciSection
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.random.Random

/*
 * Los perfiles se guardan en /etc/config/firewall, en secciones con tipos propios que fw4 y
 * LuCI ignoran. Así viajan con las reglas en el mismo `uci apply` y se revierten juntos:
 *
 *   config wrtcontrol_profile 'wrtc_p_k3x9q2'
 *       option name 'Juan'
 *       list mac 'AA:BB:CC:DD:EE:FF'
 *       option pause '1'
 *       option pause_until '2026-11-05T19:30:00'   # opcional, hora local del router
 *       option filter 'adult'                      # opcional: adult | strict
 *       list block_service 'tiktok'
 *       list block_domain 'ejemplo.com'
 *       option dns_port '5301'                     # lo asigna la app con el filtro activo
 *       option safe_search '1'                     # opcional
 *       option youtube 'moderate'                  # opcional: moderate | strict
 *       option extra_until '2026-11-05T21:30:00'   # opcional: tiempo extra sobre los horarios
 *       option protect_router '1'                  # opcional: sin acceso a LuCI ni SSH
 *       option cut_epoch '2'                       # la gestiona la app (corte de conexiones)
 *       option cut_window '3'                      # y el segundo corte: marca y hora
 *       option cut_until '2026-11-05T21:31:30'
 *
 *   config wrtcontrol_schedule 'wrtc_s_8m2c1a'
 *       option profile 'k3x9q2'
 *       option days 'Monday Tuesday Wednesday Thursday Sunday'
 *       option start '21:00'
 *       option stop '07:00'
 *       option enabled '1'
 *
 *   config wrtcontrol_settings 'wrtc_settings'
 *       option quarantine '1'                      # dispositivos nuevos sin internet
 *       list approved 'AA:BB:CC:DD:EE:01'          # aprobados fuera de los perfiles
 *       list admin 'AA:BB:CC:DD:EE:02'             # teléfonos desde los que se usó la app
 *       option time_offset '-21600'                # desfase con el que se escribieron las horas
 *
 * Cualquier sección cuyo nombre empiece por "wrtc_" es de la app y se regenera al guardar.
 */

const val SECTION_PREFIX = "wrtc_"
internal const val PROFILE_TYPE = "wrtcontrol_profile"
internal const val SCHEDULE_TYPE = "wrtcontrol_schedule"
internal const val SETTINGS_TYPE = "wrtcontrol_settings"
internal const val SETTINGS_SECTION = "wrtc_settings"
private const val PROFILE_PREFIX = "wrtc_p_"
private const val SCHEDULE_PREFIX = "wrtc_s_"

const val MAX_PROFILE_NAME = 32

private val ID_PATTERN = Regex("^[a-z0-9]{1,16}$")
internal val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
internal val HH_MM_SS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
internal val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

/** "Monday", como lo escriben fw4 y LuCI. */
internal val DayOfWeek.uciName: String get() = name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

private fun parseDay(text: String): DayOfWeek? =
    DayOfWeek.entries.firstOrNull { it.uciName.startsWith(text, ignoreCase = true) && text.length >= 3 }

/** Normaliza una MAC ("aa-bb-cc-dd-ee-ff", "aabbccddeeff"...) a "AA:BB:CC:DD:EE:FF"; null si no es válida como origen. */
fun normalizeMac(text: String): String? {
    val hex = text.trim().replace(":", "").replace("-", "").replace(".", "")
    if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    val bytes = hex.chunked(2).map { it.toInt(16) }
    if (bytes.all { it == 0 }) return null
    if (bytes[0] and 1 == 1) return null // multicast/broadcast: nunca es la MAC de origen de un equipo
    return hex.uppercase(Locale.ROOT).chunked(2).joinToString(":")
}

/** Identificador corto para nombres de sección UCI ([a-z0-9]). */
fun newId(taken: Set<String>, random: Random = Random.Default): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    while (true) {
        val id = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        if (id !in taken) return id
    }
}

/** Limpia el nombre que escribe el usuario: sin saltos de línea ni comillas, espacios simples y longitud máxima. */
fun cleanProfileName(text: String): String =
    text.replace(Regex("[\\p{Cntrl}\"\\\\]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_PROFILE_NAME).trim()

private val scheduleOrder = compareBy<Schedule>({ it.start }, { it.days.minOrNull() }, { it.id })

/** Lee perfiles y ajustes de las secciones del firewall. */
fun readConfig(sections: List<UciSection>): ParentalConfig = ParentalConfig(readProfiles(sections), readSettings(sections))

fun readSettings(sections: List<UciSection>): ParentalSettings {
    val s = sections.firstOrNull { it.type == SETTINGS_TYPE && it.name == SETTINGS_SECTION } ?: return ParentalSettings()
    return ParentalSettings(
        quarantine = s.text("quarantine") == "1",
        approved = s.items("approved").mapNotNull(::normalizeMac).distinct().sorted(),
        admins = s.items("admin").mapNotNull(::normalizeMac).distinct().sorted(),
        timeOffset = s.text("time_offset")?.toIntOrNull(),
    )
}

/** Lee los perfiles de las secciones del firewall. Las secciones mal formadas se ignoran. */
fun readProfiles(sections: List<UciSection>): List<Profile> {
    val schedules = sections.asSequence()
        .filter { it.type == SCHEDULE_TYPE && it.name.startsWith(SCHEDULE_PREFIX) }
        .mapNotNull { s -> s.text("profile")?.let { owner -> parseSchedule(s)?.let { owner to it } } }
        .groupBy({ it.first }, { it.second })

    return sections
        .filter { it.type == PROFILE_TYPE && it.name.startsWith(PROFILE_PREFIX) }
        .mapNotNull { s ->
            val id = s.name.removePrefix(PROFILE_PREFIX).takeIf { ID_PATTERN.matches(it) } ?: return@mapNotNull null
            Profile(
                id = id,
                name = cleanProfileName(s.text("name").orEmpty()).ifEmpty { "Perfil" },
                macs = s.items("mac").mapNotNull(::normalizeMac).distinct().sorted(),
                pause = parsePause(s),
                schedules = schedules[id].orEmpty().sortedWith(scheduleOrder),
                filter = ContentFilter(
                    level = FilterLevel.entries.firstOrNull { it.uciValue != null && it.uciValue == s.text("filter") } ?: FilterLevel.None,
                    services = s.items("block_service").toSet(),
                    domains = s.items("block_domain").mapNotNull(::normalizeDomain).distinct().sorted(),
                    safeSearch = s.text("safe_search") == "1",
                    youtube = YoutubeMode.entries.firstOrNull { it.uciValue != null && it.uciValue == s.text("youtube") } ?: YoutubeMode.Off,
                ),
                dnsPort = s.text("dns_port")?.toIntOrNull()?.takeIf { it in DNS_PORTS },
                extraUntil = s.text("extra_until")?.let(::parseStamp),
                protectRouter = s.text("protect_router") == "1",
                cutEpoch = s.text("cut_epoch")?.toIntOrNull()?.takeIf { it in CUT_EPOCHS } ?: 0,
                cutWindow = parseCutWindow(s),
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

private fun parseSchedule(s: UciSection): Schedule? {
    val id = s.name.removePrefix(SCHEDULE_PREFIX).takeIf { ID_PATTERN.matches(it) } ?: return null
    val days = s.items("days").mapNotNull(::parseDay).toSet().ifEmpty { return null }
    val start = s.text("start")?.let(::parseTime) ?: return null
    val end = s.text("stop")?.let(::parseTime) ?: return null
    return Schedule(id, days, start, end, enabled = s.text("enabled") != "0")
}

private fun parseTime(text: String): LocalTime? = try {
    LocalTime.parse(text.trim(), HH_MM)
} catch (_: DateTimeParseException) {
    null
}

private fun parsePause(s: UciSection): Pause? {
    if (s.text("pause") != "1") return null
    val until = s.text("pause_until") ?: return Pause.Indefinite
    return parseStamp(until)?.let { Pause.Until(it) } ?: Pause.Indefinite
}

private fun parseCutWindow(s: UciSection): CutWindow? {
    val value = s.text("cut_window")?.toIntOrNull()?.takeIf { it in CUT_EPOCHS } ?: return null
    val until = s.text("cut_until")?.let(::parseStamp) ?: return null
    return CutWindow(value, until)
}

private fun parseStamp(text: String): LocalDateTime? = try {
    LocalDateTime.parse(text, STAMP)
} catch (_: DateTimeParseException) {
    null
}

/** Secciones de datos de [config]: ajustes (si no son los de por defecto), perfiles y horarios. */
internal fun configSections(config: ParentalConfig): List<UciSection> {
    // El desfase solo hace falta si hay horas guardadas: sin ellas, un router sin nada que
    // guardar no lleva sección de ajustes.
    val times = config.profiles.any { it.pause is Pause.Until || it.extraUntil != null || it.cutWindow != null }
    val s = if (times) config.settings else config.settings.copy(timeOffset = null)
    val settings = if (s == ParentalSettings()) {
        emptyList()
    } else {
        listOf(
            uciSection(
                SETTINGS_SECTION, SETTINGS_TYPE,
                "quarantine" to if (s.quarantine) "1" else null,
                "approved" to s.approved.ifEmpty { null },
                "admin" to s.admins.ifEmpty { null },
                "time_offset" to s.timeOffset?.toString(),
            ),
        )
    }
    return settings + profileSections(config.profiles)
}

/** Secciones de datos (perfiles y horarios) que representan a [profiles]. */
internal fun profileSections(profiles: List<Profile>): List<UciSection> = profiles.flatMap { p ->
    val profile = uciSection(
        PROFILE_PREFIX + p.id, PROFILE_TYPE,
        "name" to p.name,
        "mac" to p.macs.ifEmpty { null },
        "pause" to p.pause?.let { "1" },
        "pause_until" to (p.pause as? Pause.Until)?.end?.format(STAMP),
        "filter" to p.filter.level.uciValue,
        "block_service" to p.filter.services.sorted().ifEmpty { null },
        "block_domain" to p.filter.domains.ifEmpty { null },
        "dns_port" to p.dnsPort?.toString(),
        "safe_search" to if (p.filter.safeSearch) "1" else null,
        "youtube" to p.filter.youtube.uciValue,
        "extra_until" to p.extraUntil?.format(STAMP),
        "protect_router" to if (p.protectRouter) "1" else null,
        "cut_epoch" to p.cutEpoch.takeIf { it in CUT_EPOCHS }?.toString(),
        "cut_window" to p.cutWindow?.value?.toString(),
        "cut_until" to p.cutWindow?.until?.format(STAMP),
    )
    listOf(profile) + p.schedules.map { s ->
        uciSection(
            SCHEDULE_PREFIX + s.id, SCHEDULE_TYPE,
            "profile" to p.id,
            "days" to s.days.sorted().joinToString(" ") { it.uciName },
            "start" to s.start.format(HH_MM),
            "stop" to s.end.format(HH_MM),
            "enabled" to if (s.enabled) "1" else "0",
        )
    }
}

/** Como [validateProfiles], más los ajustes. */
fun validateConfig(config: ParentalConfig) {
    validateProfiles(config.profiles)
    for (mac in config.settings.approved + config.settings.admins) require(normalizeMac(mac) == mac) { "MAC no válida: $mac" }
}

/** Comprueba lo que la interfaz ya debería impedir; lanza [IllegalArgumentException] con el motivo. */
fun validateProfiles(profiles: List<Profile>) {
    require(profiles.map { it.id }.toSet().size == profiles.size) { "Hay dos perfiles con el mismo identificador" }
    val scheduleIds = profiles.flatMap { p -> p.schedules.map { it.id } }
    require(scheduleIds.toSet().size == scheduleIds.size) { "Hay dos horarios con el mismo identificador" }
    val owner = mutableMapOf<String, String>()
    for (p in profiles) {
        require(ID_PATTERN.matches(p.id)) { "Identificador de perfil no válido: ${p.id}" }
        require(p.name.isNotBlank() && p.name == cleanProfileName(p.name)) { "El nombre del perfil no es válido" }
        for (mac in p.macs) {
            require(normalizeMac(mac) == mac) { "MAC no válida: $mac" }
            val other = owner.put(mac, p.name)
            require(other == null) { "El dispositivo $mac está en dos perfiles: $other y ${p.name}" }
        }
        for (s in p.schedules) {
            require(ID_PATTERN.matches(s.id)) { "Identificador de horario no válido: ${s.id}" }
            require(s.days.isNotEmpty()) { "Cada horario necesita al menos un día" }
            require(s.start.second == 0 && s.end.second == 0) { "Los horarios van en minutos exactos" }
        }
        require(p.cutEpoch == 0 || p.cutEpoch in CUT_EPOCHS) { "Época de corte no válida" }
        require(p.cutWindow == null || (p.cutWindow.value in CUT_EPOCHS && p.cutWindow.value != p.cutEpoch && p.cutWindow.until.nano == 0)) {
            "Ventana de corte no válida"
        }
        require(p.extraUntil == null || p.extraUntil.nano == 0) { "El tiempo extra va en segundos exactos" }
        for (d in p.filter.domains) require(normalizeDomain(d) == d) { "Sitio no válido: $d" }
        require(p.filter.domains.size <= MAX_BLOCKED_DOMAINS) { "Como máximo $MAX_BLOCKED_DOMAINS sitios bloqueados por perfil" }
    }
    val ports = profiles.mapNotNull { it.dnsPort }
    require(ports.toSet().size == ports.size) { "Dos perfiles comparten el puerto DNS" }
}

/** Puertos para los servidores DNS de los perfiles (uno por perfil con filtro). */
val DNS_PORTS = 5301..5399

/** ¿Necesita este perfil su propio servidor DNS en el router? */
internal val Profile.needsDns: Boolean get() = macs.isNotEmpty() && filter.active

/**
 * Da puerto a los perfiles que necesitan servidor DNS y se lo quita a los que no. Conserva el
 * que ya tenían para no reiniciar su servidor sin necesidad.
 */
fun assignDnsPorts(profiles: List<Profile>): List<Profile> {
    val kept = mutableSetOf<Int>()
    val first = profiles.map { p ->
        val port = p.dnsPort
        when {
            !p.needsDns -> p.copy(dnsPort = null)
            port != null && port in DNS_PORTS && kept.add(port) -> p
            else -> p.copy(dnsPort = null)
        }
    }
    return first.map { p ->
        if (!p.needsDns || p.dnsPort != null) return@map p
        val free = DNS_PORTS.firstOrNull { it !in kept }
            ?: throw IllegalArgumentException("Hay demasiados perfiles con filtro (máximo ${DNS_PORTS.count()})")
        kept += free
        p.copy(dnsPort = free)
    }
}
