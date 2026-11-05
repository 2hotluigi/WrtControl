package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.ubus.Credentials
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class ParentalApiTest {

    private val macs = listOf("AA:BB:CC:DD:EE:01")
    private val juan = Profile("j1", "Juan", macs)

    private fun TestScope.api(fake: FakeOpenWrt): ParentalApi {
        val client = fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler))
        return ParentalApi(client, phoneClockSeconds = { error("debe usar luci.getUnixtime") })
    }

    private fun FakeOpenWrt.base() = apply {
        section("cfg01", "defaults", "input" to "REJECT")
        section("cfg02", "rule", "name" to "Allow-Ping", "target" to "ACCEPT")
        section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "CST6", "zonename" to "America/Mexico_City")
    }

    private suspend inline fun <reified E : Throwable> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("Se esperaba ${E::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun `crear un perfil en pausa - aplica, verifica en el firewall y confirma`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).update { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }

        assertTrue(fake.committed.keys.containsAll(listOf("wrtc_p_j1", "wrtc_r_j1_pausa", INCLUDE_SECTION, "cfg02")))
        assertEquals(BLOCK_CHAIN_NFT, fake.files[INCLUDE_PATH])
        assertNull("el cambio quedó confirmado", fake.pendingApply)
        val calls = fake.calls
        // Sondeo (sin nada preparado) antes de tocar nada, y confirmación al final.
        assertEquals("uci.apply", calls[calls.indexOf("session.login") + 1])
        assertEquals("uci.confirm", calls.last())
        assertFalse(calls.contains("uci.rollback"))
        // Todo en la misma sesión propia.
        val tx = fake.router.calls.first { it.name == "uci.add" }.sid
        assertTrue(fake.router.calls.filter { it.target == "uci" }.all { it.sid == tx })
    }

    @Test
    fun `con otro cambio pendiente no prepara ni confirma nada`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.pendingApply = "luci-session" // p. ej. alguien aplicó en LuCI y aún no confirma
        val before = fake.committed.toMap()

        expect<PendingChangeException> {
            api(fake).update { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }
        }

        // El login devolvió la sesión de LuCI: confirmar habría validado un cambio ajeno.
        assertFalse(fake.calls.contains("uci.confirm"))
        assertFalse(fake.calls.contains("uci.add"))
        assertEquals("luci-session", fake.pendingApply)
        assertEquals(before.keys, fake.committed.keys)
    }

    @Test
    fun `si fw4 no carga las reglas se deshace el cambio`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.fw4Accepts = false
        val before = fake.committed.keys.toList()

        val e = expect<ChangeRevertedException> {
            api(fake).update { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }
        }

        assertTrue(e.message!!.contains("firewall"))
        assertTrue(fake.calls.contains("uci.rollback"))
        assertFalse(fake.calls.contains("uci.confirm"))
        assertEquals(before, fake.committed.keys.toList())
        assertNull(fake.pendingApply)
    }

    @Test
    fun `si el router no llega a deshacerlo no se dice que se deshizo`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.fw4Accepts = false
        fake.snapshotLost = true

        expect<ChangeUncertainException> {
            api(fake).update { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }
        }

        assertTrue(fake.calls.contains("uci.rollback"))
        assertTrue("el cambio se quedó en el router", fake.committed.containsKey("wrtc_r_j1_pausa"))
    }

    @Test
    fun `sin cambios no se aplica nada`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.update { profiles, _ -> profiles + juan }
        val applies = fake.calls.count { it == "uci.apply" }

        api.update { profiles, _ -> profiles }

        assertEquals("solo el sondeo", applies + 1, fake.calls.count { it == "uci.apply" })
    }

    @Test
    fun `la pausa con fin usa la hora del router`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).update { profiles, now -> profiles + juan.copy(pause = Pause.Until(now.plusHours(1))) }

        val until = fake.committed["wrtc_r_j1_pausa"]!!["stop_date"]!!.jsonPrimitive.content
        assertEquals("2026-11-04T23:20:00", until)
    }

    @Test
    fun `borrar el ultimo perfil quita reglas, include y fichero`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.update { profiles, _ ->
            profiles + juan.copy(
                schedules = listOf(Schedule("n1", setOf(DayOfWeek.MONDAY), LocalTime.of(21, 0), LocalTime.of(7, 0))),
            )
        }
        assertTrue(fake.committed.containsKey("wrtc_r_n1_2"))

        api.update { _, _ -> emptyList() }

        assertTrue(fake.committed.keys.none { it.startsWith("wrtc_") })
        assertFalse(fake.files.containsKey(INCLUDE_PATH))
        assertTrue(fake.calls.indexOf("file.remove") > fake.calls.lastIndexOf("uci.confirm"))
    }

    @Test
    fun `cambiar la zona horaria confirma al ver la hora nueva y recarga el firewall`() = runTest {
        val fake = FakeOpenWrt(offsetSeconds = 0).apply {
            section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "GMT0", "zonename" to "UTC")
        }
        val api = api(fake)
        api.update { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }
        val confirmsBefore = fake.calls.count { it == "uci.confirm" }

        api.setTimezone("America/Mexico_City", expectedOffsetSeconds = -21_600)

        val system = fake.committed["cfg03"]!!
        assertEquals("America/Mexico_City", system["zonename"]!!.jsonPrimitive.content)
        assertEquals("CST6", system["timezone"]!!.jsonPrimitive.content)
        assertEquals(-21_600L, fake.offsetSeconds)
        // Dos confirmaciones: la zona y la recarga del firewall.
        assertEquals(confirmsBefore + 2, fake.calls.count { it == "uci.confirm" })
        assertNull(fake.pendingApply)
    }

    @Test
    fun `con reglas viejas de horario de verano manda la hora del telefono`() = runTest {
        // LuCI de 22.03.0 aún traía horario de verano para México, abolido en 2022.
        val fake = FakeOpenWrt(offsetSeconds = 0).apply {
            section("cfg03", "system", "timezone" to "GMT0", "zonename" to "UTC")
            tzStrings = mapOf("America/Mexico_City" to "CST6CDT,M4.1.0,M10.5.0")
        }

        api(fake).setTimezone("America/Mexico_City", expectedOffsetSeconds = -21_600)

        val system = fake.committed["cfg03"]!!
        assertEquals("America/Mexico_City", system["zonename"]!!.jsonPrimitive.content)
        assertEquals("<-06>6", system["timezone"]!!.jsonPrimitive.content)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `la zona se deshace si el router no toma la hora esperada`() = runTest {
        val fake = FakeOpenWrt(offsetSeconds = 0).apply {
            section("cfg03", "system", "timezone" to "GMT0", "zonename" to "UTC")
            tzStrings = mapOf("Europe/Madrid" to "XYZ5") // daría UTC−5 en vez de UTC+1
        }

        expect<ChangeRevertedException> { api(fake).setTimezone("Europe/Madrid", expectedOffsetSeconds = 3_600) }

        assertEquals("UTC", fake.committed["cfg03"]!!["zonename"]!!.jsonPrimitive.content)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `load lee perfiles, hora y desfase del router`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.update { profiles, _ -> profiles + juan }

        val snapshot = api.load() // con la sesión compartida, que inicia sesión en la primera llamada

        assertEquals(listOf(juan), snapshot.profiles)
        assertEquals(LocalDateTime.of(2026, 11, 4, 22, 20), snapshot.routerNow)
        assertEquals(-21_600, snapshot.routerOffsetSeconds)
        assertEquals("America/Mexico_City", snapshot.routerZone)
        assertFalse(snapshot.flowOffloading)
    }
}
