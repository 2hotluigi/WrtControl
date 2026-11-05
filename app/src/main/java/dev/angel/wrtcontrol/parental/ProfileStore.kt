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
 *
 *   config wrtcontrol_schedule 'wrtc_s_8m2c1a'
 *       option profile 'k3x9q2'
 *       option days 'Monday Tuesday Wednesday Thursday Sunday'
 *       option start '21:00'
 *       option stop '07:00'
 *       option enabled '1'
 *
 * Cualquier sección cuyo nombre empiece por "wrtc_" es de la app y se regenera al guardar.
 */

const val SECTION_PREFIX = "wrtc_"
internal const val PROFILE_TYPE = "wrtcontrol_profile"
internal const val SCHEDULE_TYPE = "wrtcontrol_schedule"
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
    return try {
        Pause.Until(LocalDateTime.parse(until, STAMP))
    } catch (_: DateTimeParseException) {
        Pause.Indefinite
    }
}

/** Secciones de datos (perfiles y horarios) que representan a [profiles]. */
internal fun profileSections(profiles: List<Profile>): List<UciSection> = profiles.flatMap { p ->
    val profile = uciSection(
        PROFILE_PREFIX + p.id, PROFILE_TYPE,
        "name" to p.name,
        "mac" to p.macs.ifEmpty { null },
        "pause" to p.pause?.let { "1" },
        "pause_until" to (p.pause as? Pause.Until)?.end?.format(STAMP),
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
    }
}
