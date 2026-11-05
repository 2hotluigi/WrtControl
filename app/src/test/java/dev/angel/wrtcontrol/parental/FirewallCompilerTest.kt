package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciValue
import dev.angel.wrtcontrol.openwrt.uciSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDateTime
import java.time.LocalTime

class FirewallCompilerTest {

    private val now = LocalDateTime.of(2026, 11, 4, 22, 0)
    private val macs = listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")
    private val juan = Profile("j1", "Juan", macs)

    private fun RouterPlan.rule(name: String) = (firewall + dhcp).single { it.name == name }

    @Test
    fun `sin perfiles no queda nada de WrtControl`() {
        val plan = compileRouter(emptyList(), now)
        assertTrue(plan.firewall.isEmpty())
        assertFalse(plan.usesBlockChain)
    }

    @Test
    fun `perfil sin dispositivos - datos e include, pero ninguna regla`() {
        val plan = compileRouter(listOf(juan.copy(macs = emptyList(), pause = Pause.Indefinite)), now)
        assertTrue(plan.ruleLabels.isEmpty())
        assertTrue(plan.firewall.none { it.type == "rule" })
        assertTrue(plan.usesBlockChain)
    }

    @Test
    fun `pausa indefinida - regla MARK por MAC para todo el trafico reenviado`() {
        val plan = compileRouter(listOf(juan.copy(pause = Pause.Indefinite)), now)
        val rule = plan.rule("wrtc_r_j1_pausa")
        assertEquals(
            uciSection(
                "wrtc_r_j1_pausa", "rule",
                "name" to "WrtControl · Juan · pausa",
                "src" to "*",
                "dest" to "*",
                "proto" to "all",
                "src_mac" to macs,
                "target" to "MARK",
                "set_xmark" to "0x08000000/0x08000000",
            ),
            rule,
        )
        assertEquals(listOf("WrtControl · Juan · pausa"), plan.ruleLabels)
        val include = plan.rule(INCLUDE_SECTION)
        assertEquals(INCLUDE_PATH, include.text("path"))
        assertEquals("nftables", include.text("type"))
        assertEquals("table-append", include.text("position"))
    }

    @Test
    fun `pausa con fin - stop_date en hora local`() {
        val plan = compileRouter(listOf(juan.copy(pause = Pause.Until(now.plusMinutes(90)))), now)
        assertEquals("2026-11-04T23:30:00", plan.rule("wrtc_r_j1_pausa").text("stop_date"))
    }

    @Test
    fun `pausa vencida - desaparece de la regla y de los datos`() {
        val plan = compileRouter(listOf(juan.copy(pause = Pause.Until(now))), now)
        assertTrue(plan.firewall.none { it.type == "rule" })
        val data = plan.rule("wrtc_p_j1")
        assertEquals(null, data.text("pause"))
        assertEquals(null, data.text("pause_until"))
    }

    @Test
    fun `horario nocturno - dos reglas con dias y horas`() {
        val profile = juan.copy(
            schedules = listOf(
                Schedule("tarde1", setOf(SUNDAY), LocalTime.of(15, 0), LocalTime.of(17, 0), enabled = false),
                Schedule("noche1", setOf(SUNDAY, MONDAY, THURSDAY), LocalTime.of(21, 30), LocalTime.of(7, 0)),
            ),
        )
        val plan = compileRouter(listOf(profile), now)

        val night = plan.rule("wrtc_r_noche1_1")
        assertEquals("WrtControl · Juan · horario 2", night.text("name"))
        assertEquals("Monday Thursday Sunday", night.text("weekdays"))
        assertEquals("21:30:00", night.text("start_time"))
        assertEquals("23:59:59", night.text("stop_time"))

        val dawn = plan.rule("wrtc_r_noche1_2")
        assertEquals("WrtControl · Juan · horario 2 (día siguiente)", dawn.text("name"))
        assertEquals("Monday Tuesday Friday", dawn.text("weekdays"))
        assertEquals("00:00:00", dawn.text("start_time"))
        assertEquals("06:59:59", dawn.text("stop_time"))

        // El horario desactivado se guarda, pero no genera reglas.
        assertTrue(plan.firewall.any { it.name == "wrtc_s_tarde1" })
        assertTrue(plan.firewall.none { it.name.startsWith("wrtc_r_tarde1") })
        assertEquals(2, plan.ruleLabels.size)
    }

    @Test
    fun `todo el dia - solo dias`() {
        val plan = compileRouter(
            listOf(juan.copy(schedules = listOf(Schedule("dom", setOf(SUNDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)))),
            now,
        )
        val rule = plan.rule("wrtc_r_dom_1")
        assertEquals("Sunday", rule.text("weekdays"))
        assertEquals(null, rule.text("start_time"))
        assertEquals(null, rule.text("stop_time"))
    }

    @Test
    fun `el nombre de la regla cabe en un comentario de nftables`() {
        val label = labelName("😀".repeat(30))
        assertTrue(label.toByteArray().size <= 40)
        assertTrue(label.endsWith("…"))
        assertFalse(label.contains('�')) // no parte ningún emoji
        val worst = "!fw4: " + RULE_LABEL_PREFIX + label + " · horario 10 (día siguiente)"
        assertTrue(worst.toByteArray().size < 128)
        assertEquals("Ana", labelName("Ana"))
    }

    @Test
    fun `la cadena fija es ASCII y usa la misma marca que las reglas`() {
        assertTrue(BLOCK_CHAIN_NFT.all { it.code < 128 })
        assertTrue(BLOCK_CHAIN_NFT.contains("chain $BLOCK_CHAIN {"))
        assertTrue(BLOCK_CHAIN_NFT.contains("priority -1"))
        assertTrue(BLOCK_CHAIN_NFT.contains("0x08000000 == 0x08000000"))
        assertEquals(0x08000000L, MARK_BIT)
    }

    @Test
    fun `diff - sin cambios no toca nada`() {
        val plan = compileRouter(listOf(juan.copy(pause = Pause.Indefinite)), now)
        val foreign = uciSection("cfg0abc", "rule", "name" to "Allow-Ping")
        assertTrue(diffSections(plan.firewall + foreign, plan.firewall).isEmpty())
    }

    @Test
    fun `diff - borra lo sobrante y recrea lo cambiado, sin tocar lo ajeno`() {
        val before = compileRouter(listOf(juan.copy(pause = Pause.Indefinite)), now).firewall +
            uciSection("cfg0abc", "rule", "name" to "Allow-Ping")
        val after = compileRouter(listOf(juan.copy(name = "Juanito")), now).firewall

        val changes = diffSections(before, after)

        val deleted = changes.filterIsInstance<UciChange.Delete>().map { it.section }.toSet()
        val added = changes.filterIsInstance<UciChange.Add>().map { it.section.name }.toSet()
        assertEquals(setOf("wrtc_r_j1_pausa", "wrtc_p_j1"), deleted)
        assertEquals(setOf("wrtc_p_j1"), added)
        assertTrue(changes.indexOfFirst { it is UciChange.Add } > changes.indexOfLast { it is UciChange.Delete })
    }

    @Test
    fun `un option y una list con lo mismo cuentan como distintos`() {
        val asList = uciSection("wrtc_x", "rule", "src_mac" to listOf("AA:BB:CC:DD:EE:01"))
        val asText = asList.copy(options = mapOf("src_mac" to UciValue.Text("AA:BB:CC:DD:EE:01")))
        assertEquals(2, diffSections(listOf(asText), listOf(asList)).size)
    }

    @Test
    fun `conflictos de marca con reglas ajenas`() {
        val sections = listOf(
            uciSection("cfg1", "rule", "name" to "QoS", "target" to "MARK", "set_mark" to "0x1"), // 32 bits
            uciSection("cfg2", "rule", "name" to "VPN", "target" to "MARK", "set_xmark" to "0x100/0xff00"),
            uciSection("cfg3", "rule", "name" to "Otra", "target" to "MARK", "set_xmark" to "0x08000000/0x08000000"),
            uciSection("cfg4", "rule", "name" to "Normal", "target" to "ACCEPT"),
            uciSection("wrtc_r_j1_pausa", "rule", "name" to "nuestra", "target" to "MARK", "set_xmark" to "0x08000000/0x08000000"),
        )
        assertEquals(listOf("QoS", "Otra"), markConflicts(sections))
        assertTrue(markConflicts(listOf(uciSection("cfg5", "rule", "target" to "MARK", "set_mark" to "basura"))).isEmpty())
        assertEquals(listOf("cfg6"), markConflicts(listOf(uciSection("cfg6", "rule", "target" to "mark", "set_mark" to "134217728"))))
    }
}
