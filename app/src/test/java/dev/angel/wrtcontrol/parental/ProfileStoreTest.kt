package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.parseUciSections
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

class ProfileStoreTest {

    @Test
    fun `normalizeMac acepta los formatos habituales`() {
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMac("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMac(" AA-BB-CC-DD-EE-FF "))
        assertEquals("02:00:00:00:00:99", normalizeMac("020000000099"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMac("aabb.ccdd.eeff"))
    }

    @Test
    fun `normalizeMac rechaza lo que no puede ser la MAC de un equipo`() {
        assertNull(normalizeMac("aa:bb:cc:dd:ee"))
        assertNull(normalizeMac("gg:bb:cc:dd:ee:ff"))
        assertNull(normalizeMac("00:00:00:00:00:00"))
        assertNull(normalizeMac("ff:ff:ff:ff:ff:ff")) // broadcast
        assertNull(normalizeMac("01:00:5e:00:00:01")) // multicast
    }

    @Test
    fun `cleanProfileName quita comillas, controles y espacios de sobra`() {
        assertEquals("Juan y Ana", cleanProfileName("  Juan\n y \"Ana\"  "))
        assertEquals("a b", cleanProfileName("a\\b"))
        assertEquals(MAX_PROFILE_NAME, cleanProfileName("x".repeat(80)).length)
    }

    @Test
    fun `newId evita los ya usados`() {
        val first = newId(emptySet(), Random(1))
        // Con la misma semilla saldría el mismo: debe saltarlo.
        val second = newId(setOf(first), Random(1))
        assertTrue(first.matches(Regex("[a-z0-9]{6}")))
        assertTrue(second != first)
    }

    // Respuesta de `uci get firewall` tal como la da rpcd (recortada).
    private val firewallJson = """
        {"values":{
          "cfg01e63d":{".anonymous":true,".type":"defaults",".name":"cfg01e63d",".index":0,"input":"REJECT"},
          "wrtc_p_k3x9q2":{".anonymous":false,".type":"wrtcontrol_profile",".name":"wrtc_p_k3x9q2",".index":12,
             "name":"Juan","mac":["aa:bb:cc:dd:ee:01","AA:BB:CC:DD:EE:02","basura"],"pause":"1","pause_until":"2026-11-05T19:30:00"},
          "wrtc_p_ana001":{".anonymous":false,".type":"wrtcontrol_profile",".name":"wrtc_p_ana001",".index":13,
             "name":"Ana","mac":"AA:BB:CC:DD:EE:03"},
          "wrtc_s_noche1":{".anonymous":false,".type":"wrtcontrol_schedule",".name":"wrtc_s_noche1",".index":14,
             "profile":"k3x9q2","days":"Monday Tuesday Wednesday Thursday Sunday","start":"21:00","stop":"07:00","enabled":"1"},
          "wrtc_s_tarde1":{".anonymous":false,".type":"wrtcontrol_schedule",".name":"wrtc_s_tarde1",".index":15,
             "profile":"k3x9q2","days":["Sat","Sun"],"start":"15:00","stop":"17:30","enabled":"0"},
          "wrtc_s_roto01":{".anonymous":false,".type":"wrtcontrol_schedule",".name":"wrtc_s_roto01",".index":16,
             "profile":"k3x9q2","days":"","start":"25:00","stop":"07:00"},
          "wrtc_p_MAL":{".anonymous":false,".type":"wrtcontrol_profile",".name":"wrtc_p_MAL",".index":17,"name":"id no válido"}
        }}
    """.trimIndent()

    @Test
    fun `lee perfiles y horarios del firewall`() {
        val profiles = readProfiles(parseUciSections(UbusJson.parseToJsonElement(firewallJson).jsonObject))

        assertEquals(listOf("Ana", "Juan"), profiles.map { it.name }) // orden alfabético; el de id inválido se ignora
        val ana = profiles[0]
        assertEquals(listOf("AA:BB:CC:DD:EE:03"), ana.macs) // un `option` también vale
        assertNull(ana.pause)

        val juan = profiles[1]
        assertEquals("k3x9q2", juan.id)
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), juan.macs) // normalizadas, sin la inválida
        assertEquals(Pause.Until(LocalDateTime.of(2026, 11, 5, 19, 30)), juan.pause)
        assertEquals(listOf("tarde1", "noche1"), juan.schedules.map { it.id }) // por hora de inicio; la rota se ignora
        val noche = juan.schedules[1]
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, SUNDAY), noche.days)
        assertEquals(LocalTime.of(21, 0), noche.start)
        assertEquals(LocalTime.of(7, 0), noche.end)
        assertTrue(noche.overnight)
        val tarde = juan.schedules[0]
        assertEquals(setOf(SATURDAY, SUNDAY), tarde.days) // abreviaturas, como acepta fw4
        assertEquals(false, tarde.enabled)
    }

    @Test
    fun `guardar y volver a leer da lo mismo`() {
        val profiles = listOf(
            Profile(
                "k3x9q2", "Juan", listOf("AA:BB:CC:DD:EE:01"), Pause.Indefinite,
                // En el orden en que se leen: por hora de inicio.
                listOf(
                    Schedule("s2", setOf(SATURDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, enabled = false),
                    Schedule("s1", setOf(MONDAY, FRIDAY), LocalTime.of(21, 0), LocalTime.of(7, 0)),
                ),
            ),
            Profile("ana001", "Ana", emptyList(), Pause.Until(LocalDateTime.of(2026, 1, 2, 3, 4, 5))),
        )
        val back = readProfiles(profileSections(profiles))
        assertEquals(profiles.sortedBy { it.name }, back)
    }

    @Test
    fun `validateProfiles detecta un dispositivo en dos perfiles`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            validateProfiles(
                listOf(
                    Profile("a1", "Juan", listOf("AA:BB:CC:DD:EE:01")),
                    Profile("b2", "Ana", listOf("AA:BB:CC:DD:EE:01")),
                ),
            )
        }
        assertTrue(e.message!!.contains("dos perfiles"))
    }

    @Test
    fun `validateProfiles exige dias y MAC normalizadas`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateProfiles(listOf(Profile("a1", "Juan", emptyList(), schedules = listOf(Schedule("s1", emptySet(), LocalTime.NOON, LocalTime.MIDNIGHT)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateProfiles(listOf(Profile("a1", "Juan", listOf("aa:bb:cc:dd:ee:01"))))
        }
    }
}
