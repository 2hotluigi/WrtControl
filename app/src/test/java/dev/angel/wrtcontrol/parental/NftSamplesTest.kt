package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.UciValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Ejemplos de lo que la app escribe en el firewall, uno por función y uno con todo, para
 * comprobarlos con el nftables y el fw4 de cada versión de OpenWrt (tools/nft-check.sh, en la CI).
 * Con `WRTCONTROL_NFT_SAMPLES=<carpeta>`, deja en ella una subcarpeta por ejemplo con el fichero de
 * reglas y `firewall.batch` (las secciones `wrtc_` como órdenes de `uci batch`).
 */
class NftSamplesTest {

    private val now = LocalDateTime.of(2026, 11, 4, 22, 0) // miércoles
    private val mac1 = "AA:BB:CC:DD:EE:01"
    private val mac2 = "AA:BB:CC:DD:EE:02"
    private val mac3 = "AA:BB:CC:DD:EE:03"
    private val phone = "AA:BB:CC:DD:EE:0A"

    private val nightly = Schedule("n1", DayOfWeek.entries.toSet() - DayOfWeek.SATURDAY, LocalTime.of(21, 30), LocalTime.of(7, 0))
    private val school = Schedule("s1", setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0), LocalTime.of(14, 15))
    private val allDay = Schedule("a1", setOf(DayOfWeek.SUNDAY), LocalTime.of(0, 0), LocalTime.of(0, 0))

    private val filter = ContentFilter(
        level = FilterLevel.Strict,
        services = setOf("tiktok"),
        domains = listOf("ejemplo.com"),
        safeSearch = true,
        youtube = YoutubeMode.Strict,
    )

    private val cases: Map<String, Pair<ParentalConfig, RouterEnv>> = mapOf(
        "pausa" to (ParentalConfig(listOf(Profile("p1", "Ana", listOf(mac1), pause = Pause.Indefinite))) to RouterEnv()),
        "pausa-hasta" to (
            ParentalConfig(listOf(Profile("p1", "Ana", listOf(mac1), pause = Pause.Until(now.plusHours(2))))) to RouterEnv()
            ),
        "horarios" to (
            ParentalConfig(
                listOf(Profile("h1", "Luis", listOf(mac1, mac2), schedules = listOf(nightly, school, allDay), extraUntil = now.plusMinutes(30))),
            ) to RouterEnv()
            ),
        "filtro" to (
            ParentalConfig(
                listOf(Profile("f1", "Eva", listOf(mac1), filter = filter, cutEpoch = 2, cutWindow = CutWindow(1, now.plusSeconds(90)))),
            ) to RouterEnv()
            ),
        "cuarentena" to (ParentalConfig(emptyList(), ParentalSettings(quarantine = true, approved = listOf(mac3))) to RouterEnv()),
        "proteger-router" to (
            ParentalConfig(listOf(Profile("r1", "Leo", listOf(mac2), protectRouter = true))) to RouterEnv(adminPorts = listOf(22, 80, 443, 8443))
            ),
        "todo" to (
            ParentalConfig(
                listOf(
                    Profile("f1", "Eva", listOf(mac1), filter = filter, cutEpoch = 3, schedules = listOf(nightly), protectRouter = true),
                    Profile("h1", "Luis", listOf(mac2), schedules = listOf(school), pause = Pause.Until(now.plusHours(1))),
                    Profile("e1", "Ema", emptyList(), filter = ContentFilter(level = FilterLevel.Adult)),
                ),
                ParentalSettings(quarantine = true, approved = listOf(mac3), admins = listOf(phone)),
            ) to RouterEnv()
            ),
    )

    @Test
    fun `ejemplos para comprobar con el nftables de cada OpenWrt`() {
        val out = System.getenv("WRTCONTROL_NFT_SAMPLES")?.let(::File)
        for ((name, case) in cases) {
            val (config, env) = case
            val plan = compileRouter(config, now, env, appMacs = setOf(phone))
            val include = plan.include
            assertTrue("$name: sin fichero de reglas", include != null)
            assertEquals("$name: llaves", include!!.content.count { it == '{' }, include.content.count { it == '}' })
            if (out == null) continue
            val dir = File(out, name).apply { mkdirs() }
            File(dir, include.path.substringAfterLast('/')).writeText(include.content)
            File(dir, "firewall.batch").writeText(uciBatch("firewall", plan.firewall))
        }
    }

    /** Las secciones como órdenes de `uci batch`. */
    private fun uciBatch(config: String, sections: List<UciSection>): String = buildString {
        for (s in sections) {
            appendLine("set $config.${s.name}=${s.type}")
            for ((key, value) in s.options) {
                when (value) {
                    is UciValue.Text -> appendLine("set $config.${s.name}.$key=${quote(value.value)}")
                    is UciValue.Items -> value.values.forEach { appendLine("add_list $config.${s.name}.$key=${quote(it)}") }
                }
            }
        }
    }

    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"
}
