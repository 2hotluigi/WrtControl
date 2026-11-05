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
 *
 * 2. Una cadena fija, [BLOCK_CHAIN], con prioridad -1 en el hook forward: rechaza los paquetes
 *    marcados *antes* de la cadena forward de fw4, que acepta primero las conexiones ya
 *    establecidas. Así una pausa corta también un vídeo o un juego que ya estaba en marcha.
 *    La cadena va en /etc/luci-uploads (el ACL de LuCI deja escribir ahí) y se incluye con una
 *    sección `include` de tipo nftables.
 *
 * Todo lo variable va en UCI, así que `uci apply` con reversión cubre cualquier cambio.
 */

/** Bit de marca que usa WrtControl (mwan3 usa 0x3F00 y pbr 0x00FF0000 por defecto). */
const val MARK_BIT = 0x08000000L
private const val MARK_OPTION = "0x08000000/0x08000000"

const val INCLUDE_SECTION = "wrtc_include"
const val INCLUDE_PATH = "/etc/luci-uploads/wrtcontrol.nft"
const val BLOCK_CHAIN = "wrtcontrol_forward"
const val RULE_LABEL_PREFIX = "WrtControl · "

/** Contenido fijo del include. Sintaxis compatible con nftables 1.0.2 (OpenWrt 22.03). Solo ASCII. */
val BLOCK_CHAIN_NFT: String = """
    |# WrtControl: rechaza el trafico reenviado que marcan las reglas "WrtControl" del firewall.
    |# Prioridad -1: va antes de la cadena forward de fw4, asi corta tambien conexiones ya abiertas.
    |# Lo escribe la app WrtControl. Sin esas reglas no hace nada.
    |chain $BLOCK_CHAIN {
    |	type filter hook forward priority -1; policy accept;
    |	meta mark & 0x08000000 == 0x08000000 meta l4proto tcp counter reject with tcp reset comment "!wrtcontrol: bloqueo"
    |	meta mark & 0x08000000 == 0x08000000 counter reject with icmpx type admin-prohibited comment "!wrtcontrol: bloqueo"
    |}
    |
""".trimMargin()

/** Bytes del nombre del perfil dentro del nombre de la regla (el resto ocupa < 60). */
private const val MAX_LABEL_BYTES = 40

/** Lo que debe quedar en el firewall: todas las secciones "wrtc_" y los nombres de las reglas MARK. */
data class FirewallPlan(val sections: List<UciSection>, val ruleLabels: List<String>) {
    val usesBlockChain: Boolean get() = sections.any { it.name == INCLUDE_SECTION }
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

fun compileFirewall(profiles: List<Profile>, now: LocalDateTime): FirewallPlan {
    val current = profiles.map { it.withoutExpiredPause(now) }
    val rules = current.flatMap(::profileRules)
    val sections = profileSections(current) + rules + if (current.isEmpty()) emptyList() else listOf(includeSection())
    return FirewallPlan(sections, rules.map { it.text("name")!! })
}

private fun includeSection() = uciSection(
    INCLUDE_SECTION, "include",
    "type" to "nftables",
    "path" to INCLUDE_PATH,
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
            rules += markRule("wrtc_r_${schedule.id}_${part + 1}", "$label · horario ${index + 1}$suffix", p.macs, window)
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
) = uciSection(
    name, "rule",
    "name" to label,
    "src" to "*",
    "dest" to "*",
    // fw4 usa "tcp udp" si no se indica: con "all" también se marca ICMP, QUIC, etc.
    "proto" to "all",
    "src_mac" to macs,
    "target" to "MARK",
    "set_xmark" to MARK_OPTION,
    "weekdays" to window?.days?.sorted()?.joinToString(" ") { it.uciName },
    "start_time" to window?.start?.format(HH_MM_SS),
    "stop_time" to window?.stop?.format(HH_MM_SS),
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

/** Reglas MARK de otros que tocan el bit de WrtControl: podrían quitar o poner la marca de bloqueo. */
fun markConflicts(sections: List<UciSection>): List<String> = sections
    .filter { !it.name.startsWith(SECTION_PREFIX) && it.type == "rule" && it.text("target").equals("MARK", ignoreCase = true) }
    .filter { s -> listOf("set_mark", "set_xmark").any { key -> s.text(key)?.let(::markMask)?.and(MARK_BIT) == MARK_BIT } }
    .map { it.text("name") ?: it.name }

/** Máscara efectiva de "valor[/máscara]": sin máscara, set_mark cambia los 32 bits. */
private fun markMask(text: String): Long? {
    val parts = text.trim().split('/')
    fun parse(s: String): Long? = s.trim().let { if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
    val value = parse(parts[0]) ?: return null
    val mask = if (parts.size > 1) parse(parts[1]) ?: return null else 0xFFFFFFFFL
    return mask or value
}
