package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.describeError
import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.openwrt.UciValue
import dev.angel.wrtcontrol.openwrt.uciSection
import dev.angel.wrtcontrol.ubus.Credentials
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Routers configurados por una versión anterior (N−1), esta (N) y una más nueva (N+1). */
class SchemaTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    /** Lo que escribía la app antes de `schema`: sin sección de ajustes si eran los de por defecto. */
    private val legacy = listOf(
        uciSection("wrtc_p_j1", PROFILE_TYPE, "name" to "Juan", "mac" to listOf(mac), "pause" to "1"),
        uciSection("wrtc_s_n1", SCHEDULE_TYPE, "profile" to "j1", "days" to "Monday", "start" to "21:00", "stop" to "07:00", "enabled" to "1"),
    )

    @Test
    fun `N-1 - sin version es la 1 y al guardar se escribe`() {
        val config = readConfig(legacy)
        assertEquals(1, config.schema)
        assertFalse(config.newerSchema)
        val written = configSections(config)
        assertEquals("$SCHEMA_VERSION", written.single { it.name == SETTINGS_SECTION }.text("schema"))
        assertEquals(legacy, written.filter { it.name != SETTINGS_SECTION })
        assertEquals(config.profiles, readConfig(written).profiles)
    }

    /** Lo que escribiría una versión más nueva sin subir el esquema: opciones y una sección de más. */
    private val withExtras = listOf(
        uciSection(SETTINGS_SECTION, SETTINGS_TYPE, "schema" to "$SCHEMA_VERSION", "quarantine" to "1", "approved" to listOf(mac), "night_mode" to "1"),
        uciSection("wrtc_p_j1", PROFILE_TYPE, "name" to "Juan", "mac" to listOf(mac), "bedtime_story" to "1", "allow_app" to listOf("duolingo", "khan")),
        uciSection("wrtc_s_n1", SCHEDULE_TYPE, "profile" to "j1", "days" to "Monday", "start" to "21:00", "stop" to "07:00", "enabled" to "1", "label" to "Dormir"),
        uciSection("wrtc_reward_1", "wrtcontrol_reward", "profile" to "j1", "minutes" to "30"),
        // Una regla generada que esta versión no hace: no es un dato, se regenera (o se borra).
        uciSection("wrtc_r_j1_nuevo", "rule", "name" to "wrtc j1 nuevo"),
    )

    @Test
    fun `N - lo que esta version no conoce se conserva al guardar`() {
        val config = readConfig(withExtras)
        assertFalse(config.newerSchema)
        val juan = config.profiles.single()
        assertEquals(mapOf("bedtime_story" to UciValue.Text("1"), "allow_app" to UciValue.Items(listOf("duolingo", "khan"))), juan.extra)
        assertEquals(mapOf("label" to UciValue.Text("Dormir")), juan.schedules.single().extra)
        assertEquals(mapOf("night_mode" to UciValue.Text("1")), config.settings.extra)
        assertEquals(listOf("wrtc_reward_1"), config.preserved.map { it.name })

        // Un cambio de esta versión: renombrar el perfil y pausarlo.
        val changed = config.copy(profiles = listOf(juan.copy(name = "Juanito", pause = Pause.Indefinite)))
        val written = configSections(changed).associateBy { it.name }
        assertEquals("Juanito", written.getValue("wrtc_p_j1").text("name"))
        assertEquals(UciValue.Items(listOf("duolingo", "khan")), written.getValue("wrtc_p_j1").options["allow_app"])
        assertEquals("1", written.getValue("wrtc_p_j1").text("bedtime_story"))
        assertEquals("Dormir", written.getValue("wrtc_s_n1").text("label"))
        assertEquals("1", written.getValue(SETTINGS_SECTION).text("night_mode"))
        assertEquals(withExtras[3], written.getValue("wrtc_reward_1"))
        assertFalse("las reglas generadas no son datos", "wrtc_r_j1_nuevo" in written)
        // Y se lee igual que antes del cambio, salvo lo cambiado.
        assertEquals(changed.profiles, readConfig(written.values.toList()).profiles)
    }

    @Test
    fun `N+1 - con un esquema mas nuevo no se escribe ni se restaura`() {
        val newer = withExtras.map { if (it.name == SETTINGS_SECTION) uciSection(it.name, it.type, "schema" to "${SCHEMA_VERSION + 1}") else it }
        assertTrue(readConfig(newer).newerSchema)
        // Una versión que no se entiende cuenta como más nueva.
        assertTrue(readConfig(listOf(uciSection(SETTINGS_SECTION, SETTINGS_TYPE, "schema" to "dos"))).newerSchema)
        // Una copia de seguridad de una versión más nueva no se restaura.
        val backup = backupJson(readConfig(withExtras)).replace("\"schema\":\"$SCHEMA_VERSION\"", "\"schema\":\"${SCHEMA_VERSION + 1}\"")
        assertNull(parseBackup(backup))
        assertEquals(1, readConfig(withExtras).profiles.size)
    }

    private fun TestScope.api(fake: FakeOpenWrt): ParentalApi {
        fake.clock = { testScheduler.currentTime }
        val client = fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler))
        return ParentalApi(client, phoneClockSeconds = { 0L })
    }

    @Test
    fun `N+1 - el router no se toca y se explica`() = runTest {
        val fake = FakeOpenWrt().apply {
            section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "CST6", "zonename" to "America/Mexico_City")
            section("cfg04", "zone", "name" to "lan")
            section(SETTINGS_SECTION, SETTINGS_TYPE, "schema" to "${SCHEMA_VERSION + 1}", "quarantine" to "1", "approved" to listOf(mac))
            section("wrtc_p_j1", PROFILE_TYPE, "name" to "Juan", "mac" to listOf(mac))
        }
        val before = fake.committed.toMap()
        val api = api(fake)
        assertTrue(api.load().newerSchema)
        for (action in listOf<suspend () -> Unit>(
            { api.updateProfiles { profiles, _ -> profiles.map { it.copy(pause = Pause.Indefinite) } } },
            { api.setTimezone("America/Mexico_City", -21600) },
        )) {
            try {
                action()
                fail("Se esperaba NewerSchemaException")
            } catch (e: NewerSchemaException) {
                assertTrue(describeError(e).es.contains("Actualiza la app"))
            }
        }
        // Solo el sondeo de cambios pendientes (un apply sin nada preparado): nada se prepara ni se confirma.
        assertFalse(fake.calls.any { it == "uci.add" || it == "uci.delete" || it == "uci.set" || it == "uci.confirm" })
        assertEquals(before, fake.committed.toMap())
    }
}
