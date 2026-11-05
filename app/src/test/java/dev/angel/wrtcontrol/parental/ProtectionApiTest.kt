package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.status
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

/**
 * Protección (paquete 1) contra el router simulado: fichero de reglas versionado y su limpieza,
 * reaplicar a la fuerza, cuarentena por zona, administradores, estado de las reglas cargadas,
 * búsqueda segura y comprobación del DNS para familias, épocas y ventana de
 * corte, protección del router y cambio de zona horaria.
 */
class ProtectionApiTest {

    private val mac1 = "AA:BB:CC:DD:EE:01"
    private val mac2 = "AA:BB:CC:DD:EE:02"
    private val tv = "AA:BB:CC:DD:EE:50"
    private val phone = "AA:BB:CC:DD:EE:99"
    private val juan = Profile("j1", "Juan", listOf(mac1))

    private fun TestScope.api(fake: FakeOpenWrt): ParentalApi {
        fake.clock = { testScheduler.currentTime }
        val client = fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler))
        return ParentalApi(client, phoneClockSeconds = { error("debe usar luci.getUnixtime") })
    }

    /** Como en ParentalApiTest, pero la zona lan nombra su red (hace falta para la cuarentena). */
    private fun FakeOpenWrt.base() = apply {
        section("cfg01", "defaults", "input" to "REJECT")
        section("cfg02", "rule", "name" to "Allow-Ping", "target" to "ACCEPT")
        section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "CST6", "zonename" to "America/Mexico_City")
        section("cfg04", "zone", "name" to "lan", "network" to listOf("lan"))
        section("cfg05", "zone", "name" to "wan", "masq" to "1", "network" to listOf("wan"))
        section("cfg06", "dnsmasq", "domain" to "lan")
        section("cfg07", "forwarding", "src" to "lan", "dest" to "wan")
        section("lan", "dhcp", "interface" to "lan")
    }

    private val FakeOpenWrt.includePath: String? get() = option(INCLUDE_SECTION, "path")

    /** Contenido del fichero de reglas al que apunta la sección include. */
    private val FakeOpenWrt.rules: String get() = files.getValue(includePath!!)

    private val FakeOpenWrt.includeFiles: List<String> get() = files.keys.filter(::isIncludeFile).sorted()

    private fun FakeOpenWrt.count(name: String) = calls.count { it == name }

    /** Rutas de las llamadas `file.<method>` desde la llamada número [from]. */
    private fun FakeOpenWrt.paths(method: String, from: Int = 0): List<String> =
        router.calls.drop(from).filter { it.name == method }.map { it.args["path"]!!.jsonPrimitive.content }

    /** Parámetros de cada nslookup que hizo la app. */
    private fun FakeOpenWrt.lookups(): List<List<String>> = router.calls
        .filter { it.name == "file.exec" && it.args["command"]?.jsonPrimitive?.content == "/usr/bin/nslookup" }
        .map { c -> c.args["params"]!!.jsonArray.map { it.jsonPrimitive.content } }

    private fun FakeOpenWrt.touchesInclude(from: Int, method: String, key: String) =
        router.calls.drop(from).any { it.name == method && it.args[key]?.jsonPrimitive?.content == INCLUDE_SECTION }

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

    // ---------- fichero de reglas versionado ----------

    @Test
    fun `el fichero de reglas lleva su version en el nombre y tras confirmar se borran los viejos`() = runTest {
        val fake = FakeOpenWrt().base()
        // Instalación de una versión anterior de la app: fichero de nombre fijo.
        fake.section(INCLUDE_SECTION, "include", "type" to "nftables", "path" to LEGACY_INCLUDE_PATH, "position" to "table-append")
        fake.files[LEGACY_INCLUDE_PATH] = "# reglas de la versión anterior\n"
        val orphan = "$INCLUDE_DIR/wrtcontrol-0123456789ab.nft" // de un cambio que no llegó a confirmarse
        fake.files[orphan] = "# huérfano\n"
        val others = listOf("$INCLUDE_DIR/cbid.firewall.cfg99.file", "$INCLUDE_DIR/wrtcontrol-notas.txt", "/etc/wrtcontrol-0123456789ab.nft")
        others.forEach { fake.files[it] = "ajeno" }

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }

        val path = fake.includePath!!
        val version = includeVersion(path)!!
        assertTrue(version, Regex("[0-9a-f]{12}").matches(version))
        assertEquals("$INCLUDE_DIR/wrtcontrol-$version.nft", path)
        // La primera regla lleva la versión: así se comprueba que fw4 cargó justo este fichero.
        assertTrue(fake.rules.contains("\tcounter comment \"${OWN_COMMENT}v=$version\"\n"))
        val write = fake.router.calls.single { it.name == "file.write" }
        assertEquals("solo root lo lee (0600)", 384, write.args["mode"]!!.jsonPrimitive.content.toInt())

        assertEquals(listOf(path), fake.includeFiles)
        others.forEach { assertTrue("no se toca $it", fake.files.containsKey(it)) }
        assertEquals(setOf(LEGACY_INCLUDE_PATH, orphan), fake.paths("file.remove").toSet())
        val confirm = fake.calls.lastIndexOf("uci.confirm")
        val removes = fake.calls.withIndex().filter { it.value == "file.remove" }.map { it.index }
        assertTrue("se borran después de confirmar", confirm >= 0 && removes.all { it > confirm })
        assertNull(fake.pendingApply)
    }

    @Test
    fun `cambiar las reglas propias crea otra version y borra la anterior tras confirmar`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val v1 = fake.includePath!!

        // Una pausa solo cambia reglas de UCI: el mismo fichero.
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(pause = Pause.Indefinite) } }
        assertEquals(v1, fake.includePath)
        assertEquals(listOf(v1), fake.includeFiles)

        // Proteger el router añade la cadena input: otra versión.
        val start = fake.router.calls.size
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }

        val v2 = fake.includePath!!
        assertNotEquals(v1, v2)
        assertTrue(fake.rules.contains("chain $INPUT_CHAIN {"))
        assertEquals(listOf(v2), fake.includeFiles)
        val calls = fake.router.calls.drop(start)
        val confirm = calls.indexOfLast { it.name == "uci.confirm" }
        val removed = calls.indexOfFirst { it.name == "file.remove" && it.args["path"]!!.jsonPrimitive.content == v1 }
        assertTrue("el anterior se borra después de confirmar", confirm >= 0 && removed > confirm)
        assertEquals(true, api.load().rulesLoaded)
    }

    @Test
    fun `sin permiso para listar el directorio se borran el fichero anterior y el de nombre fijo`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.fileListDenied = true
        fake.files[LEGACY_INCLUDE_PATH] = "# reglas de la versión anterior\n"
        val orphan = "$INCLUDE_DIR/wrtcontrol-0123456789ab.nft"
        fake.files[orphan] = "# huérfano\n"
        val api = api(fake)

        api.updateProfiles { profiles, _ -> profiles + juan }
        val v1 = fake.includePath!!
        assertFalse(fake.files.containsKey(LEGACY_INCLUDE_PATH))
        assertTrue("sin listar no se sabe de él", fake.files.containsKey(orphan))

        api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }

        assertFalse("el que usaba la configuración anterior", fake.files.containsKey(v1))
        assertTrue(fake.files.containsKey(fake.includePath!!))
        assertNull(fake.pendingApply)
    }

    @Test
    fun `si fw4 no carga se borra el fichero nuevo y la seccion sigue apuntando al anterior`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val old = fake.includePath!!
        fake.fw4Accepts = false
        val start = fake.router.calls.size

        expect<ChangeRevertedException> {
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }
        }

        val new = fake.paths("file.write", start).single()
        assertNotEquals(old, new)
        assertEquals("se borra el que no llegó a usarse", listOf(new), fake.paths("file.remove", start))
        assertEquals(old, fake.includePath)
        assertEquals(listOf(old), fake.includeFiles)
        assertTrue(fake.calls.contains("uci.rollback"))
        assertNull(fake.option("wrtc_p_j1", "protect_router"))
        assertNull(fake.pendingApply)
    }

    @Test
    fun `si fw4 no carga el primer perfil no queda ningun fichero de reglas`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.fw4Accepts = false

        expect<ChangeRevertedException> { api(fake).updateProfiles { profiles, _ -> profiles + juan } }

        assertEquals(1, fake.paths("file.write").size)
        assertTrue(fake.includeFiles.isEmpty())
        assertNull(fake.includePath)
    }

    @Test
    fun `si no se puede comprobar que se deshizo no se borra ningun fichero`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val old = fake.includePath!!
        fake.fw4Accepts = false
        fake.snapshotLost = true
        val start = fake.router.calls.size

        expect<ChangeUncertainException> {
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }
        }

        val new = fake.paths("file.write", start).single()
        assertTrue(fake.paths("file.remove", start).isEmpty())
        assertTrue(fake.files.containsKey(old))
        assertTrue(fake.files.containsKey(new))
        assertEquals("el cambio se quedó en el router y su fichero sigue ahí", new, fake.includePath)
    }

    // ---------- reaplicar ----------

    @Test
    fun `force vuelve a escribir y cargar las reglas aunque la configuracion no cambie`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }
        val path = fake.includePath!!
        fake.files.remove(path) // p. ej. alguien limpió /etc/luci-uploads
        val start = fake.router.calls.size

        api.update(force = true) { config, _ -> config }

        val calls = fake.router.calls.drop(start).map { it.name }
        assertEquals("sondeo y aplicar", 2, calls.count { it == "uci.apply" })
        assertEquals(1, calls.count { it == "uci.confirm" })
        // Borrar y volver a crear la sección include basta para que fw4 recargue.
        assertTrue(fake.touchesInclude(start, "uci.delete", "section"))
        assertTrue(fake.touchesInclude(start, "uci.add", "name"))
        assertEquals("mismo contenido, misma versión", path, fake.includePath)
        assertEquals(listOf(path), fake.paths("file.write", start))
        assertEquals(listOf(path), fake.includeFiles)
        assertNull(fake.pendingApply)
        assertEquals(true, api.load().rulesLoaded)
    }

    @Test
    fun `sin force y sin cambios no se aplica ni se escribe nada`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val start = fake.router.calls.size

        val result = api.update { config, _ -> config }

        val calls = fake.router.calls.drop(start).map { it.name }
        assertEquals("solo el sondeo", 1, calls.count { it == "uci.apply" })
        for (name in listOf("uci.add", "uci.delete", "uci.confirm", "file.write", "file.remove")) assertFalse(name, name in calls)
        assertEquals(listOf(juan), result.profiles)
    }

    @Test
    fun `el fichero de reglas solo se escribe si cambia su ruta o, al forzar, si falta`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val path = fake.includePath!!
        assertEquals(1, fake.count("file.write"))

        // Cambios que solo tocan UCI (pausa, horario, nombre): el fichero en uso es ya el bueno y no se trunca.
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(pause = Pause.Indefinite) } }
        api.updateProfiles { profiles, now -> profiles.map { it.copy(pause = Pause.Until(now.plusHours(1)), name = "Juanito") } }
        assertEquals(path, fake.includePath)
        assertEquals(1, fake.count("file.write"))

        // Forzar con el fichero en su sitio: se recarga el firewall sin reescribirlo.
        val start = fake.router.calls.size
        api.update(force = true) { config, _ -> config }
        assertTrue(fake.touchesInclude(start, "uci.add", "name"))
        assertEquals(1, fake.count("file.write"))
        assertTrue("se miró si estaba", fake.router.calls.drop(start).any { it.name == "file.list" })

        // Forzar sin el fichero: se repone, con el mismo nombre.
        fake.files.remove(path)
        api.update(force = true) { config, _ -> config }
        assertEquals(2, fake.count("file.write"))
        assertEquals(listOf(path), fake.includeFiles)

        // Otro contenido, otra ruta: se escribe el nuevo.
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }
        assertEquals(3, fake.count("file.write"))
        assertNotEquals(path, fake.includePath)
        assertEquals(true, api.load().rulesLoaded)
    }

    @Test
    fun `al forzar sin permiso para listar el directorio se repone el fichero por si acaso`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        fake.fileListDenied = true

        api.update(force = true) { config, _ -> config }

        assertEquals(2, fake.count("file.write"))
        assertEquals(listOf(fake.includePath!!), fake.includeFiles)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `si otra sesion se adelanta al aplicar no se borra el fichero nuevo`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val old = fake.includePath!!
        val start = fake.router.calls.size
        // Mientras se preparan los cambios, alguien aplica desde LuCI y queda pendiente de confirmar.
        fake.intercept = { call ->
            if (call.name == "uci.add" && fake.pendingApply == null) fake.pendingApply = "luci-session"
            null
        }

        expect<PendingChangeException> {
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }
        }

        val new = fake.paths("file.write", start).single()
        assertNotEquals(old, new)
        assertTrue("el otro cambio podría usar ese mismo fichero", fake.files.containsKey(new))
        assertTrue(fake.paths("file.remove", start).isEmpty())
        assertEquals(old, fake.includePath)
        assertFalse(fake.calls.drop(start).contains("uci.confirm"))
        assertEquals("luci-session", fake.pendingApply)
    }

    @Test
    fun `si falla al preparar los cambios se borra el fichero nuevo`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val old = fake.includePath!!
        val start = fake.router.calls.size
        fake.intercept = { call -> if (call.name == "uci.add") status(4) else null }

        expect<UbusException.Status> {
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(protectRouter = true) } }
        }

        val new = fake.paths("file.write", start).single()
        assertNotEquals(old, new)
        assertEquals("no llegó a aplicarse: nadie lo usa", listOf(new), fake.paths("file.remove", start))
        assertEquals(listOf(old), fake.includeFiles)
        assertEquals(old, fake.includePath)
        assertEquals("solo el sondeo", 1, fake.router.calls.drop(start).count { it.name == "uci.apply" })
        assertNull(fake.pendingApply)
    }

    @Test
    fun `si falla al preparar un cambio que no cambia el fichero no se borra el que esta en uso`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val path = fake.includePath!!
        fake.files.remove(path)
        val start = fake.router.calls.size
        fake.intercept = { call -> if (call.name == "uci.add") status(4) else null }

        expect<UbusException.Status> { api.update(force = true) { config, _ -> config } }

        assertEquals("se repuso", listOf(path), fake.paths("file.write", start))
        assertTrue("y es el que usa la configuración", fake.paths("file.remove", start).isEmpty())
        assertEquals(listOf(path), fake.includeFiles)
    }

    @Test
    fun `force sin reglas propias no aplica nada`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).update(force = true) { config, _ -> config }

        assertEquals("solo el sondeo", 1, fake.count("uci.apply"))
        assertFalse(fake.calls.contains("uci.confirm"))
        assertFalse(fake.calls.contains("file.write"))
    }

    // ---------- cuarentena ----------

    @Test
    fun `cuarentena - el telefono que la activa queda de administrador, se guarda en wrtc_settings y desactivarla conserva las listas`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)

        val on = api.update(appMacs = setOf(phone)) { config, _ ->
            config.copy(settings = config.settings.copy(quarantine = true, approved = listOf(tv)))
        }

        assertEquals(ParentalSettings(quarantine = true, approved = listOf(tv), admins = listOf(phone)), on.settings.copy(timeOffset = null))
        assertEquals(setOf(tv, phone), on.approvedMacs)
        assertEquals("wrtcontrol_settings", fake.committed[SETTINGS_SECTION]!![".type"]!!.jsonPrimitive.content)
        assertEquals("1", fake.option(SETTINGS_SECTION, "quarantine"))
        assertEquals("el teléfono no se mezcla con los aprobados", listOf(tv), fake.list(SETTINGS_SECTION, "approved"))
        assertEquals(listOf(phone), fake.list(SETTINGS_SECTION, "admin"))
        assertTrue(
            fake.rules,
            fake.rules.contains(
                "meta mark & 0x06000000 == 0x04000000 ct direction original ether saddr != { aa:bb:cc:dd:ee:50, aa:bb:cc:dd:ee:99 } " +
                    "meta l4proto tcp counter reject with tcp reset comment \"${OWN_COMMENT}cuarentena\"",
            ),
        )
        // La regla MARK de la zona lan, cargada por fw4.
        assertEquals("WrtControl · cuarentena (lan)", fake.option("wrtc_q_0", "name"))
        assertEquals("lan", fake.option("wrtc_q_0", "src"))
        assertEquals("0x04000000/0x04000000", fake.option("wrtc_q_0", "set_xmark"))
        assertNull(fake.pendingApply)
        val loaded = api.load()
        assertEquals("se guarda sin el desfase: no hay horas guardadas", on.settings.copy(timeOffset = null), loaded.settings)
        assertTrue(loaded.firewall!!.wrtcontrolRules.contains("WrtControl · cuarentena (lan)"))
        assertEquals(true, loaded.rulesLoaded)

        val off = api.update(appMacs = setOf(phone)) { config, _ -> config.copy(settings = config.settings.copy(quarantine = false)) }

        assertEquals(ParentalSettings(quarantine = false, approved = listOf(tv), admins = listOf(phone)), off.settings.copy(timeOffset = null))
        assertNull(fake.option(SETTINGS_SECTION, "quarantine"))
        assertEquals(listOf(tv), fake.list(SETTINGS_SECTION, "approved"))
        assertEquals(listOf(phone), fake.list(SETTINGS_SECTION, "admin"))
        assertEquals(off.settings.copy(timeOffset = null), api.load().settings)
        assertFalse(fake.committed.containsKey("wrtc_q_0"))
        // Sin perfiles ni cuarentena no hacen falta reglas propias.
        assertNull(fake.includePath)
        assertTrue(fake.includeFiles.isEmpty())
    }

    @Test
    fun `cuarentena - dejan salir los perfiles y el telefono que hace cualquier cambio`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }

        val on = api.update { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }

        assertTrue("los dispositivos de los perfiles ya cuentan como aprobados", on.settings.approved.isEmpty())
        assertEquals(setOf(mac1), on.approvedMacs)
        assertTrue(fake.rules.contains("ether saddr != { aa:bb:cc:dd:ee:01 } "))

        val next = api.updateProfiles(appMacs = setOf(phone)) { profiles, _ -> profiles.map { it.copy(pause = Pause.Indefinite) } }

        assertTrue(next.settings.approved.isEmpty())
        assertEquals(listOf(phone), next.settings.admins)
        assertEquals(listOf(phone), fake.list(SETTINGS_SECTION, "admin"))
        assertTrue(fake.rules.contains("ether saddr != { aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:99 } "))
        assertNull(fake.pendingApply)
    }

    @Test
    fun `cuarentena sin nada aprobado o sin zona de casa no se aplica`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)

        val nothing = expect<IllegalArgumentException> {
            api.update { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }
        }
        assertTrue(nothing.message, nothing.message!!.contains("Aprueba al menos un dispositivo"))

        // Sin reenvío de lan a wan no se sabe cuál es la red de casa.
        fake.section("cfg07", "forwarding", "src" to "lan", "dest" to "wan", "enabled" to "0")
        val noHome = expect<IllegalArgumentException> {
            api.update(appMacs = setOf(phone)) { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }
        }
        assertTrue(noHome.message, noHome.message!!.contains("zona de la red de casa"))

        for (name in listOf("uci.add", "uci.confirm", "file.write")) assertFalse(name, fake.calls.contains(name))
        assertNull(fake.committed[SETTINGS_SECTION])
    }

    @Test
    fun `cuarentena - una regla MARK por cada zona de casa, tambien la de invitados`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.section("cfg08", "zone", "name" to "guest", "network" to listOf("guest"))
        fake.section("cfg09", "forwarding", "src" to "guest", "dest" to "wan")
        fake.section("cfg10", "zone", "name" to "wan6", "masq" to "on") // otra salida con NAT: no es de casa
        fake.interfaces = mapOf("lan" to "br-lan", "wan" to "eth1", "guest" to "phy0-ap1")
        val api = api(fake)

        api.update(appMacs = setOf(phone)) { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }

        assertEquals(listOf("lan", "guest"), listOf("wrtc_q_0", "wrtc_q_1").map { fake.option(it, "src") })
        // Y lo que entra por las zonas con NAT lleva la marca de WAN.
        assertEquals(listOf("wan", "wan6"), listOf("wrtc_qw_0", "wrtc_qw_1").map { fake.option(it, "src") })
        assertEquals("0x02000000/0x02000000", fake.option("wrtc_qw_0", "set_xmark"))
        assertFalse(fake.committed.containsKey("wrtc_q_2"))
        assertEquals("WrtControl · cuarentena (guest)", fake.option("wrtc_q_1", "name"))
        assertEquals("*", fake.option("wrtc_q_1", "dest"))
        assertEquals("all", fake.option("wrtc_q_1", "proto"))
        assertEquals("MARK", fake.option("wrtc_q_1", "target"))
        assertEquals(true, api.load().rulesLoaded)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `cuarentena - los dispositivos que salen de todos los perfiles siguen aprobados`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        val mac3 = "AA:BB:CC:DD:EE:03"
        api.updateProfiles { profiles, _ -> profiles + juan.copy(macs = listOf(mac1, mac2)) + Profile("a2", "Ana", listOf(mac3)) }
        api.update { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }

        // Se quita un dispositivo de su perfil: era de confianza, sigue con internet.
        val removed = api.updateProfiles { profiles, _ -> profiles.map { if (it.id == "j1") it.copy(macs = listOf(mac1)) else it } }
        assertEquals(listOf(mac2), removed.settings.approved)
        // Se borra un perfil entero.
        val deleted = api.updateProfiles { profiles, _ -> profiles.filter { it.id != "a2" } }
        assertEquals(listOf(mac2, mac3), deleted.settings.approved)
        assertEquals(listOf(mac2, mac3), fake.list(SETTINGS_SECTION, "approved"))
        assertTrue(fake.rules.contains("ether saddr != { aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:02, aa:bb:cc:dd:ee:03 } "))
        // Pasar de un perfil a otro no lo aprueba aparte.
        val moved = api.updateProfiles { profiles, _ -> profiles.map { it.copy(macs = emptyList()) } + Profile("b3", "Beto", listOf(mac1)) }
        assertEquals(listOf(mac2, mac3), moved.settings.approved)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `cuarentena - salvo que el mismo cambio les quite la aprobacion`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        // La TV estaba aprobada y además en un perfil.
        api.update { config, _ ->
            config.copy(profiles = listOf(juan.copy(macs = listOf(mac1, tv))), settings = ParentalSettings(quarantine = true, approved = listOf(tv)))
        }
        assertEquals(listOf(tv), fake.list(SETTINGS_SECTION, "approved"))

        val result = api.update { config, _ ->
            config.copy(
                profiles = config.profiles.map { it.copy(macs = listOf(mac1)) },
                settings = config.settings.copy(approved = emptyList()),
            )
        }

        assertTrue(result.settings.approved.isEmpty())
        assertTrue(fake.list(SETTINGS_SECTION, "approved").isEmpty())
        assertFalse("la TV queda en cuarentena", fake.rules.contains("ee:50"))
    }

    @Test
    fun `sin cuarentena los dispositivos que salen de los perfiles no se aprueban`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan.copy(macs = listOf(mac1, mac2)) }

        val removed = api.updateProfiles { profiles, _ -> profiles.map { it.copy(macs = listOf(mac1)) } }
        val deleted = api.updateProfiles { _, _ -> emptyList() }

        assertTrue(removed.settings.approved.isEmpty())
        assertTrue(deleted.settings.approved.isEmpty())
        assertNull(fake.committed[SETTINGS_SECTION])
    }

    // ---------- administradores ----------

    @Test
    fun `administradores - el telefono queda guardado y sigue fuera de la proteccion aunque aplique otro`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        val phoneA = "AA:BB:CC:DD:EE:98"
        // El teléfono A (Android da la MAC en minúsculas) crea un perfil protegido.
        api.updateProfiles(appMacs = setOf("aa:bb:cc:dd:ee:98")) { profiles, _ -> profiles + juan.copy(protectRouter = true) }
        assertEquals(listOf(phoneA), fake.list(SETTINGS_SECTION, "admin"))
        assertTrue(fake.list(SETTINGS_SECTION, "approved").isEmpty())

        // Desde el teléfono B se mete por error el teléfono A en el perfil protegido.
        val result = api.updateProfiles(appMacs = setOf(phone)) { profiles, _ -> profiles.map { it.copy(macs = listOf(mac1, phoneA)) } }

        assertEquals(listOf(phoneA, phone), result.settings.admins)
        assertEquals(listOf(phoneA, phone), fake.list(SETTINGS_SECTION, "admin"))
        val rule = fake.rules.lines().single { "\"${OWN_COMMENT}router\"" in it }.trim()
        assertTrue("A no pierde el acceso al router: $rule", rule.startsWith("ether saddr { aa:bb:cc:dd:ee:01 } tcp dport "))

        // Reaplicado sin ningún teléfono (p. ej. sin saber la MAC del que aplica), sigue igual.
        api.update(force = true) { config, _ -> config }
        assertEquals(rule, fake.rules.lines().single { "\"${OWN_COMMENT}router\"" in it }.trim())
        assertEquals(listOf(phoneA, phone), api.load().settings.admins)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `administradores - con la cuarentena tienen internet aunque la active otro telefono`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        val phoneA = "AA:BB:CC:DD:EE:98"
        api.updateProfiles(appMacs = setOf(phoneA)) { profiles, _ -> profiles + juan }

        val on = api.update(appMacs = setOf(phone)) { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }

        assertTrue("A no está en ningún perfil ni aprobado", on.settings.approved.isEmpty())
        assertEquals(setOf(mac1, phoneA, phone), on.approvedMacs)
        assertTrue(fake.rules, fake.rules.contains("ether saddr != { aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:98, aa:bb:cc:dd:ee:99 } "))
        assertNull(fake.pendingApply)
    }

    // ---------- estado de las reglas cargadas ----------

    @Test
    fun `load - version esperada, reglas cargadas e intentos de DNS cifrado bloqueados`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.ownPackets = mapOf("doh j1" to 3L, "bloqueo" to 5L)
        val api = api(fake)
        val ana = Profile("a2", "Ana", listOf(mac2))
        api.updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com"))) + ana }

        val s = api.load()

        assertEquals(includeVersion(fake.includePath), s.expectedInclude)
        assertEquals(s.expectedInclude, s.firewall!!.includeVersion)
        assertEquals(true, s.rulesLoaded)
        // Cuatro reglas (IPv4 e IPv6, TCP y UDP) con 3 paquetes cada una.
        assertEquals(12L, s.dnsBypassBlocked(s.profiles.single { it.id == "j1" }))
        assertEquals("sin filtro no tiene reglas de DNS cifrado", 0L, s.dnsBypassBlocked(s.profiles.single { it.id == "a2" }))
        assertEquals("las demás etiquetas se cuentan aparte", 10L, s.firewall!!.ownCounters["bloqueo"])
    }

    @Test
    fun `load - las reglas no estan cargadas si fw4 no tiene el fichero o tiene otro`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.ownPackets = mapOf("doh j1" to 3L)
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com"))) }
        val path = fake.includePath!!

        // Alguien borra el fichero y el firewall se recarga sin las reglas propias.
        fake.files.remove(path)
        fake.reloadFirewall()
        val missing = api.load()
        assertEquals(includeVersion(path), missing.expectedInclude)
        assertNull(missing.firewall!!.includeVersion)
        assertEquals(false, missing.rulesLoaded)
        assertEquals(0L, missing.dnsBypassBlocked(missing.profiles.single()))

        // Reaplicar lo arregla.
        api.update(force = true) { config, _ -> config }
        assertEquals(true, api.load().rulesLoaded)

        // La configuración pide otra versión que fw4 no llegó a cargar.
        fake.fw4Accepts = false
        fake.section(INCLUDE_SECTION, "include", "type" to "nftables", "path" to "$INCLUDE_DIR/wrtcontrol-aaaaaaaaaaaa.nft", "position" to "table-append")
        val other = api.load()
        assertEquals("aaaaaaaaaaaa", other.expectedInclude)
        assertEquals(includeVersion(path), other.firewall!!.includeVersion)
        assertEquals(false, other.rulesLoaded)
    }

    @Test
    fun `load - sin reglas propias o con el fichero de nombre fijo solo se miran las reglas de fw4`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)

        val empty = api.load()
        assertNull(empty.expectedInclude)
        assertEquals("sin perfiles no se espera ninguna regla", emptySet<String>(), empty.expectedRules)
        assertEquals("y no falta nada", true, empty.rulesLoaded)

        // Instalación de una versión anterior: el fichero no lleva versión, así que no se puede comparar.
        fake.section(INCLUDE_SECTION, "include", "type" to "nftables", "path" to LEGACY_INCLUDE_PATH)
        fake.files[LEGACY_INCLUDE_PATH] = "# reglas de la versión anterior\n"
        fake.section("wrtc_p_j1", PROFILE_TYPE, "name" to "Juan", "mac" to listOf(mac1), "pause" to "1")
        fake.section("wrtc_r_j1_pausa", "rule", "name" to "WrtControl · Juan · pausa", "src" to "*", "dest" to "*", "target" to "MARK")
        val legacy = api.load()
        assertNull(legacy.expectedInclude)
        assertEquals(setOf("WrtControl · Juan · pausa"), legacy.expectedRules)
        assertEquals(true, legacy.rulesLoaded)
        // Si fw4 no tuviera la regla de la pausa, sí se sabría que falta.
        fake.fw4Accepts = false
        fake.section("wrtc_s_n1", SCHEDULE_TYPE, "profile" to "j1", "days" to "Monday", "start" to "21:00", "stop" to "22:00")
        assertEquals(false, api.load().rulesLoaded)
    }

    @Test
    fun `load - faltan reglas de fw4 que pide la configuracion y sobran las de una pausa ya vencida`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, now -> profiles + juan.copy(pause = Pause.Until(now.plusHours(1))) }

        val ok = api.load()
        assertEquals(setOf("WrtControl · Juan · pausa"), ok.expectedRules)
        assertEquals(true, ok.rulesLoaded)

        // La pausa vence: su regla sigue cargada hasta el próximo cambio, sin efecto. No es un fallo.
        fake.localTime += 2 * 3600
        val expired = api.load()
        assertEquals(emptySet<String>(), expired.expectedRules)
        assertTrue(expired.firewall!!.wrtcontrolRules.contains("WrtControl · Juan · pausa"))
        assertEquals(true, expired.rulesLoaded)

        // Alguien añade un horario (p. ej. desde otra versión de la app) y fw4 no llega a cargarlo.
        fake.fw4Accepts = false
        fake.section("wrtc_s_n1", SCHEDULE_TYPE, "profile" to "j1", "days" to "Monday", "start" to "21:00", "stop" to "22:00", "enabled" to "1")
        val missing = api.load()
        assertEquals(setOf("WrtControl · Juan · horario 1"), missing.expectedRules)
        assertEquals("el fichero propio sí es el que toca", missing.expectedInclude, missing.firewall!!.includeVersion)
        assertEquals(false, missing.rulesLoaded)

        // Reaplicar lo arregla.
        fake.fw4Accepts = true
        api.update(force = true) { config, _ -> config }
        assertEquals(true, api.load().rulesLoaded)
    }

    // ---------- búsqueda segura y YouTube ----------

    @Test
    fun `busqueda segura sin nivel - registros propios comprobados antes de confirmar`() = runTest {
        val fake = FakeOpenWrt().base()

        val result = api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(safeSearch = true)) }

        assertNull(fake.pendingApply)
        assertFalse(fake.calls.contains("uci.rollback"))
        assertEquals("uci.confirm", fake.calls.last { it != "file.list" && it != "file.remove" })
        assertTrue(result.profiles.single().filter.safeSearch)
        assertEquals("1", fake.option("wrtc_p_j1", "safe_search"))
        // host-record con IPv4 e IPv6, solo para la instancia del perfil.
        assertEquals("hostrecord", fake.committed["wrtc_hr_j1_g"]!![".type"]!!.jsonPrimitive.content)
        assertEquals("dhcp", fake.committed["wrtc_hr_j1_g"]!![".config"]!!.jsonPrimitive.content)
        assertEquals("wrtc_dns_j1", fake.option("wrtc_hr_j1_g", "instance"))
        assertEquals(listOf(GOOGLE_SAFE_HOST), fake.list("wrtc_hr_j1_g", "name"))
        assertEquals(SAFE_SEARCH_RECORDS.getValue(GOOGLE_SAFE_HOST), fake.list("wrtc_hr_j1_g", "ip"))
        // Todos los www.google.<sufijo> apuntan a forcesafesearch, en líneas cortas.
        val cnames = fake.committed.keys.filter { it.startsWith("wrtc_cn_j1_g") }
        assertTrue(cnames.isNotEmpty())
        for (c in cnames) {
            assertEquals(GOOGLE_SAFE_HOST, fake.option(c, "target"))
            assertEquals("wrtc_dns_j1", fake.option(c, "instance"))
            assertTrue(c, fake.option(c, "cname")!!.split(',').size <= 20)
        }
        val names = cnames.flatMap { fake.option(it, "cname")!!.split(',') }
        assertEquals(GOOGLE_SUFFIXES.map { "www.google.$it" }.toSet(), names.toSet())
        // Bing y DuckDuckGo van al DNS para familias de Cloudflare; lo demás, a la instancia principal.
        assertEquals(
            SAFE_SEARCH_FORWARDS.flatMap { listOf("/$it/1.1.1.3", "/$it/1.0.0.3") } + "127.0.0.1#53",
            fake.list("wrtc_dns_j1", "server"),
        )
        // Se preguntó www.google.com al DNS del perfil antes de confirmar.
        assertTrue(fake.lookups().any { "www.google.com" in it && "-port=5301" in it })
        assertFalse("sin YouTube restringido", fake.committed.containsKey("wrtc_cn_j1_yt"))
    }

    @Test
    fun `busqueda segura - si dnsmasq no aplica el cname no se confirma y se deshace`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.ignoredRecords = setOf("cname") // www.google.com da la dirección normal

        val e = expect<ChangeRevertedException> {
            api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(safeSearch = true)) }
        }

        assertTrue(e.message!!.contains("DNS"))
        assertTrue(fake.calls.contains("uci.rollback"))
        assertFalse(fake.calls.contains("uci.confirm"))
        assertTrue(fake.committed.keys.none { it.startsWith(SECTION_PREFIX) })
        assertTrue(fake.includeFiles.isEmpty())
        assertNull(fake.pendingApply)
    }

    @Test
    fun `busqueda segura - sin el host-record no se confirma y se deshace`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.ignoredRecords = setOf("hostrecord") // el cname no tiene a dónde apuntar

        expect<ChangeRevertedException> {
            api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(safeSearch = true)) }
        }

        assertFalse(fake.calls.contains("uci.confirm"))
        assertTrue(fake.committed.keys.none { it.startsWith(SECTION_PREFIX) })
        assertNull(fake.pendingApply)
    }

    @Test
    fun `YouTube restringido - comprueba restrictmoderate y si el modo nuevo no carga se queda el anterior`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)

        api.updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(youtube = YoutubeMode.Moderate)) }

        assertEquals("moderate", fake.option("wrtc_p_j1", "youtube"))
        assertEquals(YOUTUBE_MODERATE_HOST, fake.option("wrtc_cn_j1_yt", "target"))
        assertEquals(YOUTUBE_NAMES.joinToString(","), fake.option("wrtc_cn_j1_yt", "cname"))
        assertEquals(SAFE_SEARCH_RECORDS.getValue(YOUTUBE_MODERATE_HOST), fake.list("wrtc_hr_j1_yt", "ip"))
        assertTrue(fake.lookups().any { "www.youtube.com" in it && "-port=5301" in it })
        assertFalse("sin búsqueda segura no hay registros de Google", fake.committed.containsKey("wrtc_hr_j1_g"))
        assertNull(fake.pendingApply)

        // Pasar a estricto: si dnsmasq no aplica el cname, se queda en moderado.
        fake.ignoredRecords = setOf("cname")
        expect<ChangeRevertedException> {
            api.updateProfiles { profiles, _ -> profiles.map { it.copy(filter = it.filter.copy(youtube = YoutubeMode.Strict)) } }
        }
        assertEquals("moderate", fake.option("wrtc_p_j1", "youtube"))
        assertEquals(YOUTUBE_MODERATE_HOST, fake.option("wrtc_cn_j1_yt", "target"))
        assertEquals("1", fake.option("wrtc_p_j1", "cut_epoch"))
    }

    @Test
    fun `con nivel de familia la busqueda segura la da su DNS - sin registros de Google, pero se comprueba que la fuerza`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.ignoredRecords = setOf("cname", "hostrecord") // no hacen falta

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(FilterLevel.Adult, safeSearch = true)) }

        assertNull(fake.pendingApply)
        assertTrue(fake.committed.keys.none { it.startsWith("wrtc_hr_") || it.startsWith("wrtc_cn_") })
        assertEquals(listOf("1.1.1.3", "1.0.0.3", "/lan/127.0.0.1#53"), fake.list("wrtc_dns_j1", "server"))
        // www.google.com se pregunta al DNS del perfil: lo responde el DNS para familias.
        assertTrue(fake.lookups().any { "www.google.com" in it && "-port=5301" in it })
    }

    // ---------- el DNS para familias filtra de verdad ----------

    @Test
    fun `nivel de familia - si el proveedor intercepta el DNS no se activa y se explica`() = runTest {
        for (level in listOf(FilterLevel.Adult, FilterLevel.Strict)) {
            val fake = FakeOpenWrt().base()
            fake.dnsIntercepted = true // 1.1.1.3 "responde", pero sin filtrar: www.google.com da la dirección normal

            val e = expect<ChangeRevertedException> {
                api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(level)) }
            }

            assertTrue("$level: ${e.message}", e.message!!.contains("intercepta"))
            assertTrue(fake.calls.contains("uci.rollback"))
            assertFalse(fake.calls.contains("uci.confirm"))
            assertTrue(fake.committed.keys.none { it.startsWith(SECTION_PREFIX) })
            assertTrue(fake.includeFiles.isEmpty())
            assertNull(fake.pendingApply)
            // Se deja de esperar tras 3 intentos, no al agotar el plazo.
            val googleLookups = fake.lookups().count { "www.google.com" in it && "-port=5301" in it }
            assertEquals("$level", 3, googleLookups)
        }
    }

    @Test
    fun `nivel de familia - comprueba que www google com da forcesafesearch por el puerto del perfil`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(FilterLevel.Strict)) }

        assertNull(fake.pendingApply)
        assertTrue(fake.lookups().any { "www.google.com" in it && "-port=5301" in it })
        assertTrue("Bing solo con la búsqueda segura sin nivel", fake.lookups().none { "www.bing.com" in it })
    }

    @Test
    fun `busqueda segura sin nivel - si el proveedor intercepta el DNS no se activa y se explica`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.dnsIntercepted = true // bing.com va a 1.1.1.3, pero llega sin el CNAME strict.bing.com

        val e = expect<ChangeRevertedException> {
            api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(safeSearch = true)) }
        }

        assertTrue(e.message, e.message!!.contains("intercepta"))
        assertTrue(fake.lookups().any { "www.bing.com" in it && "-port=5301" in it })
        assertTrue(fake.committed.keys.none { it.startsWith(SECTION_PREFIX) })
        assertNull(fake.pendingApply)
    }

    @Test
    fun `busqueda segura sin nivel - comprueba el CNAME strict bing com`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(safeSearch = true)) }

        assertNull(fake.pendingApply)
        assertTrue(fake.lookups().any { "www.bing.com" in it && "-port=5301" in it })
    }

    @Test
    fun `sin nivel ni busqueda segura no hace falta el DNS para familias aunque intercepten`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.dnsIntercepted = true

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com"), youtube = YoutubeMode.Moderate)) }

        assertNull(fake.pendingApply)
        assertFalse(fake.calls.contains("uci.rollback"))
        assertTrue(fake.lookups().none { "www.google.com" in it || "www.bing.com" in it })
    }

    @Test
    fun `busqueda segura sin nivel - si el DNS para familias no contesta no se dice que lo interceptan`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.familyDnsDown = true // 1.1.1.3 bloqueado: las consultas de bing.com no tienen respuesta

        val e = expect<ChangeRevertedException> {
            api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(safeSearch = true)) }
        }

        assertFalse(e.message, e.message!!.contains("intercepta"))
        assertTrue(e.message, e.message!!.contains("no responde"))
    }

    @Test
    fun `comprobar el DNS para familias no impide bloquear google com o bing com`() = runTest {
        val cases = listOf(
            ContentFilter(FilterLevel.Adult, domains = listOf("google.com")),
            ContentFilter(safeSearch = true, domains = listOf("bing.com")),
        )
        for (filter in cases) {
            val fake = FakeOpenWrt().base()

            api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = filter) }

            assertFalse("$filter", fake.calls.contains("uci.rollback"))
            assertEquals("$filter", filter.domains, fake.list("wrtc_p_j1", "block_domain"))
            assertNull(fake.pendingApply)
        }
    }

    // ---------- épocas de corte ----------

    @Test
    fun `epocas - suben al endurecer el filtro y no al guardar sin endurecerlo`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        fun epoch() = fake.option("wrtc_p_j1", "cut_epoch")
        suspend fun edit(change: (Profile) -> Profile) = api.updateProfiles { profiles, _ -> profiles.map(change) }

        api.updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com"))) }
        assertEquals("1", epoch())
        assertTrue(fake.rules.contains("ether saddr { aa:bb:cc:dd:ee:01 } ct direction original ct mark & 0x0000c000 != 0x00004000 "))
        val first = fake.includePath

        edit { it.copy(filter = it.filter.copy(services = setOf("tiktok"))) }
        // La época nueva no puede ser la marca de la ventana (2): es la 3, y la ventana sigue con la 2.
        assertEquals("nuevo servicio bloqueado", "3", epoch())
        assertTrue(fake.rules.contains("ct mark set ct mark & 0xffff3fff | 0x00008000 "))
        assertTrue(fake.rules.contains("ct mark & 0x0000c000 != 0x00008000 "))
        assertNotEquals("otra versión de las reglas: se cortan las conexiones abiertas", first, fake.includePath)
        val second = fake.includePath

        edit { it.copy(name = "Juanito", pause = Pause.Indefinite) }
        assertEquals("sin cambios del filtro", "3", epoch())
        assertEquals(second, fake.includePath)

        edit { it.copy(filter = it.filter.copy(services = emptySet())) }
        assertEquals("relajar no corta", "3", epoch())
        assertEquals(second, fake.includePath)

        edit { it.copy(filter = it.filter.copy(domains = it.filter.domains + "otro.com")) }
        assertEquals("alterna entre las dos marcas que no son la de la ventana", "1", epoch())
        edit { it.copy(macs = it.macs + mac2) }
        assertEquals("un dispositivo nuevo también corta", "3", epoch())
        assertEquals("la ventana conserva su marca", "2", fake.option("wrtc_p_j1", "cut_window"))
        assertNull(fake.pendingApply)
    }

    @Test
    fun `epocas - al endurecer se guarda la ventana del segundo corte y va en las reglas con la hora del router`() = runTest {
        val fake = FakeOpenWrt().base() // 22:20:00 en el router
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com"))) }
        assertEquals("1", fake.option("wrtc_p_j1", "cut_epoch"))
        assertEquals("perfil nuevo: ventana con la 2", "2", fake.option("wrtc_p_j1", "cut_window"))
        assertEquals("perfil nuevo: ventana larga", "2026-11-04T22:25:30", fake.option("wrtc_p_j1", "cut_until"))

        fake.localTime += 600 // 22:30:00
        val result = api.updateProfiles { profiles, _ -> profiles.map { it.copy(filter = it.filter.copy(services = setOf("tiktok"))) } }

        val p = result.profiles.single()
        // La época nueva es la marca libre (3): la de la ventana anterior (2) la conserva la nueva
        // ventana, así lo abierto en la primera también se corta al acabar esta.
        assertEquals(3, p.cutEpoch)
        assertEquals(CutWindow(2, LocalDateTime.of(2026, 11, 4, 22, 31, 30)), p.cutWindow)
        assertEquals("3", fake.option("wrtc_p_j1", "cut_epoch"))
        assertEquals("2", fake.option("wrtc_p_j1", "cut_window"))
        assertEquals("2026-11-04T22:31:30", fake.option("wrtc_p_j1", "cut_until"))
        assertTrue(fake.rules, fake.rules.contains("ct state new meta time < \"2026-11-04 22:31:30\" ct mark set ct mark & 0xffff3fff | 0x00008000 "))
        assertTrue(fake.rules.contains("ct mark & 0x0000c000 == 0x00008000 meta time >= \"2026-11-04 22:31:30\" "))

        // Guardar sin endurecer conserva la ventana guardada (y el fichero).
        val path = fake.includePath
        fake.localTime += 600
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(name = "Juanito", cutWindow = null) } }
        assertEquals("2", fake.option("wrtc_p_j1", "cut_window"))
        assertEquals("2026-11-04T22:31:30", fake.option("wrtc_p_j1", "cut_until"))
        assertEquals(path, fake.includePath)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `epocas - las instancias DNS de los perfiles dan TTL cortos`() = runTest {
        val fake = FakeOpenWrt().base()

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(FilterLevel.Adult)) }

        assertEquals("60", fake.option("wrtc_dns_j1", "max_ttl"))
    }

    @Test
    fun `epocas - un perfil sin filtro no tiene epoca`() = runTest {
        val fake = FakeOpenWrt().base()

        val result = api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(pause = Pause.Indefinite) }

        assertEquals(0, result.profiles.single().cutEpoch)
        assertNull(fake.option("wrtc_p_j1", "cut_epoch"))
        assertFalse(fake.rules.contains("corte"))
    }

    @Test
    fun `epocas - guardar un perfil con una epoca vieja sin endurecer el filtro no la cambia`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com"))) }
        // Se abre el editor con el perfil en la época 1...
        val editing = api.load().profiles.single()
        assertEquals(1, editing.cutEpoch)
        // ...y mientras tanto otro teléfono bloquea TikTok, lo que la cambia a 3 (la 2 es de la ventana).
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(filter = it.filter.copy(services = setOf("tiktok"))) } }
        assertEquals("3", fake.option("wrtc_p_j1", "cut_epoch"))
        val rules = fake.includePath

        // Se guarda el editor con el mismo filtro que ya hay y otro nombre.
        api.updateProfiles { profiles, _ -> profiles.map { editing.copy(name = "Juanito", filter = it.filter) } }

        assertEquals("solo cambió el nombre: no hay nada que cortar", "3", fake.option("wrtc_p_j1", "cut_epoch"))
        assertEquals(rules, fake.includePath)
    }

    // ---------- protección del router ----------

    @Test
    fun `proteccion del router - el telefono que aplica el cambio nunca queda fuera`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)

        api.updateProfiles(appMacs = setOf(mac1)) { profiles, _ -> profiles + juan.copy(macs = listOf(mac1, mac2), protectRouter = true) }

        assertEquals("1", fake.option("wrtc_p_j1", "protect_router"))
        val rules = fake.rules
        assertTrue(rules.contains("chain $INPUT_CHAIN {\n\ttype filter hook input priority -1; policy accept;\n"))
        val rule = rules.lines().single { "\"${OWN_COMMENT}router\"" in it }
        assertEquals(
            "\tether saddr { aa:bb:cc:dd:ee:02 } tcp dport { 22, 80, 443 } counter reject with tcp reset comment \"${OWN_COMMENT}router\"",
            rule,
        )
        assertNull(fake.pendingApply)

        // Si todos sus dispositivos ejecutan la app, la cadena sobra.
        api.updateProfiles(appMacs = setOf(mac1, mac2)) { profiles, _ -> profiles }

        assertFalse(fake.rules.contains(INPUT_CHAIN))
        assertEquals("sigue guardado en el perfil", "1", fake.option("wrtc_p_j1", "protect_router"))
        assertEquals(true, api.load().rulesLoaded)
    }

    @Test
    fun `proteccion del router - corta los puertos de LuCI y SSH que tiene configurados`() = runTest {
        val fake = FakeOpenWrt().base()
        fake.section("main", "uhttpd", "listen_http" to listOf("0.0.0.0:8080", "[::]:8080"), "listen_https" to listOf("0.0.0.0:8443", "[::]:8443"))
        fake.section("cfg0b", "dropbear", "Port" to "2222")
        fake.section("cfg0c", "dropbear", "Port" to "2223", "enable" to "0")

        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(protectRouter = true) }

        val rule = fake.rules.lines().single { "\"${OWN_COMMENT}router\"" in it }
        assertTrue(rule, rule.contains("ether saddr { aa:bb:cc:dd:ee:01 } tcp dport { 2222, 8080, 8443 } "))
        assertNull(fake.pendingApply)
    }

    /** `uci get` de una configuración que no existe en el router (paquete no instalado o sin permiso). */
    private fun FakeOpenWrt.withoutConfig(vararg configs: String) = apply {
        intercept = { call -> if (call.name == "uci.get" && call.args["config"]?.jsonPrimitive?.content in configs) status(4) else null }
    }

    private suspend fun TestScope.protectedPorts(fake: FakeOpenWrt): String {
        api(fake).updateProfiles { profiles, _ -> profiles + juan.copy(protectRouter = true) }
        assertNull(fake.pendingApply)
        val rule = fake.rules.lines().single { "\"${OWN_COMMENT}router\"" in it }
        return Regex("tcp dport \\{ ([0-9, ]+) \\}").find(rule)!!.groupValues[1]
    }

    @Test
    fun `proteccion del router - uhttpd y dropbear se leen por separado y al que falte se le suponen sus puertos`() = runTest {
        // Sin dropbear (p. ej. con OpenSSH): los puertos de LuCI configurados y el 22.
        val noSsh = FakeOpenWrt().base().withoutConfig("dropbear")
        noSsh.section("main", "uhttpd", "listen_http" to listOf("0.0.0.0:8080"), "listen_https" to listOf("0.0.0.0:8443"))
        assertEquals("22, 8080, 8443", protectedPorts(noSsh))

        // Sin uhttpd (p. ej. LuCI con nginx): 80 y 443, y el puerto de dropbear configurado.
        val noWeb = FakeOpenWrt().base().withoutConfig("uhttpd")
        noWeb.section("cfg0b", "dropbear", "Port" to "2222")
        assertEquals("80, 443, 2222", protectedPorts(noWeb))

        // Sin ninguno de los dos: los de siempre.
        assertEquals("22, 80, 443", protectedPorts(FakeOpenWrt().base().withoutConfig("uhttpd", "dropbear")))
    }

    // ---------- zona horaria ----------

    @Test
    fun `cambiar la zona con perfiles vuelve a crear el include, recarga el firewall y confirma`() = runTest {
        val fake = FakeOpenWrt(offsetSeconds = 0).base().apply {
            section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "GMT0", "zonename" to "UTC")
        }
        val api = api(fake)
        api.updateProfiles(appMacs = setOf(mac1)) { profiles, _ ->
            profiles + juan.copy(macs = listOf(mac1, mac2), protectRouter = true, pause = Pause.Indefinite)
        }
        val path = fake.includePath!!
        val start = fake.router.calls.size

        api.setTimezone("America/Mexico_City", expectedOffsetSeconds = -21_600, appMacs = setOf(mac1))

        val calls = fake.router.calls.drop(start)
        val confirms = calls.withIndex().filter { it.value.name == "uci.confirm" }.map { it.index }
        assertEquals("la zona y la recarga del firewall", 2, confirms.size)
        val reload = calls.subList(confirms[0], confirms[1]).map { it.name to (it.args["section"] ?: it.args["name"])?.jsonPrimitive?.content }
        assertTrue(reload.contains("uci.delete" to INCLUDE_SECTION))
        assertTrue(reload.contains("uci.add" to INCLUDE_SECTION))
        assertTrue(reload.any { it.first == "uci.apply" })
        assertEquals("mismas reglas: misma versión", path, fake.includePath)
        assertEquals(listOf(path), fake.includeFiles)
        assertFalse("el teléfono sigue fuera de la protección", fake.rules.contains("aa:bb:cc:dd:ee:01"))
        assertEquals("CST6", fake.option("cfg03", "timezone"))
        assertEquals(-21_600L, fake.offsetSeconds)
        assertNull(fake.pendingApply)
        assertEquals(true, api.load().rulesLoaded)
    }

    @Test
    fun `cambiar la zona mueve el fin de la pausa, del tiempo extra y del segundo corte para que sigan siendo el mismo instante`() = runTest {
        val fake = FakeOpenWrt(offsetSeconds = 0).base().apply {
            section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "GMT0", "zonename" to "UTC")
        }
        val api = api(fake)
        val night = Schedule("n1", setOf(java.time.DayOfWeek.WEDNESDAY), java.time.LocalTime.of(21, 0), java.time.LocalTime.of(7, 0))
        // 22:20 UTC: pausa hasta las 23:20, tiempo extra hasta las 00:20 y ventana de corte hasta las 22:25:30.
        api.updateProfiles { profiles, now ->
            profiles + juan.copy(
                pause = Pause.Until(now.plusHours(1)),
                schedules = listOf(night),
                extraUntil = now.plusHours(2),
                filter = ContentFilter(domains = listOf("ejemplo.com")),
            )
        }
        assertEquals("2026-11-04T23:20:00", fake.option("wrtc_p_j1", "pause_until"))
        assertEquals("perfil nuevo: ventana larga", "2026-11-04T22:25:30", fake.option("wrtc_p_j1", "cut_until"))
        val epoch = fake.option("wrtc_p_j1", "cut_epoch")
        val window = fake.option("wrtc_p_j1", "cut_window")

        // UTC → UTC−6: las mismas horas son 6 horas antes en la hora local nueva.
        api.setTimezone("America/Mexico_City", expectedOffsetSeconds = -21_600)

        assertEquals(LocalDateTime.of(2026, 11, 4, 16, 20), api.load().routerNow)
        assertEquals("2026-11-04T17:20:00", fake.option("wrtc_p_j1", "pause_until"))
        assertEquals("2026-11-04T18:20:00", fake.option("wrtc_p_j1", "extra_until"))
        assertEquals("2026-11-04T16:25:30", fake.option("wrtc_p_j1", "cut_until"))
        // Y en las reglas que carga el router.
        assertEquals("2026-11-04T17:20:00", fake.option("wrtc_r_j1_pausa", "stop_date"))
        assertEquals("2026-11-04T18:20:00", fake.option("wrtc_r_n1_1", "start_date"))
        assertTrue(fake.rules, fake.rules.contains("meta time < \"2026-11-04 16:25:30\""))
        assertFalse(fake.rules.contains("22:25:30"))
        // La época y la marca de la ventana no cambian: no hay nada que cortar.
        assertEquals(epoch, fake.option("wrtc_p_j1", "cut_epoch"))
        assertEquals(window, fake.option("wrtc_p_j1", "cut_window"))
        assertEquals("sigue en pausa", Access.Paused(LocalDateTime.of(2026, 11, 4, 17, 20)), api.load().profiles.single().access(LocalDateTime.of(2026, 11, 4, 16, 20)))
        assertEquals(listOf(fake.includePath!!), fake.includeFiles)
        assertNull(fake.pendingApply)
        assertEquals(true, api.load().rulesLoaded)
    }

    @Test
    fun `cambiar a una zona con mas desfase mueve las horas hacia adelante`() = runTest {
        val fake = FakeOpenWrt().base() // UTC−6, 22:20
        fake.tzStrings = fake.tzStrings + ("Europe/Madrid" to "CET-1")
        val api = api(fake)
        api.updateProfiles { profiles, now -> profiles + juan.copy(pause = Pause.Until(now.plusMinutes(30))) }

        api.setTimezone("Europe/Madrid", expectedOffsetSeconds = 3_600)

        // 22:50 en UTC−6 son las 05:50 del día siguiente en UTC+1.
        assertEquals("2026-11-05T05:50:00", fake.option("wrtc_p_j1", "pause_until"))
        assertEquals("2026-11-05T05:50:00", fake.option("wrtc_r_j1_pausa", "stop_date"))
        assertNull(fake.pendingApply)
    }

    @Test
    fun `cambiar la zona sin reglas propias no toca el firewall`() = runTest {
        val fake = FakeOpenWrt(offsetSeconds = 0).base().apply {
            section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "GMT0", "zonename" to "UTC")
        }

        api(fake).setTimezone("America/Mexico_City", expectedOffsetSeconds = -21_600)

        assertEquals(1, fake.count("uci.confirm"))
        assertFalse(fake.calls.contains("uci.add"))
        assertFalse(fake.calls.contains("file.write"))
        assertEquals("America/Mexico_City", fake.option("cfg03", "zonename"))
    }

    // ---------- tercera revisión ----------

    @Test
    fun `si la zona cambio desde LuCI, el siguiente cambio mueve las horas guardadas`() = runTest {
        val fake = FakeOpenWrt().base() // UTC-6, 22:20 en el router
        val api = api(fake)
        api.updateProfiles { profiles, now -> profiles + juan.copy(pause = Pause.Until(now.plusHours(1))) }
        assertEquals("2026-11-04T23:20:00", fake.option("wrtc_p_j1", "pause_until"))
        assertEquals("con horas guardadas se guarda el desfase", "-21600", fake.option(SETTINGS_SECTION, "time_offset"))

        // Alguien pone el router en UTC desde LuCI: la hora local salta 6 horas adelante.
        fake.section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "GMT0", "zonename" to "UTC")
        fake.localTime += 21_600
        fake.offsetSeconds = 0
        api.updateProfiles { profiles, _ -> profiles.map { it.copy(name = "Juanito") } }

        assertEquals("el mismo instante en la hora nueva", "2026-11-05T05:20:00", fake.option("wrtc_p_j1", "pause_until"))
        assertEquals("2026-11-05T05:20:00", fake.option("wrtc_r_j1_pausa", "stop_date"))
        assertEquals("0", fake.option(SETTINGS_SECTION, "time_offset"))
        assertNull(fake.pendingApply)
    }

    @Test
    fun `si falta el fichero de reglas en uso, un cambio normal lo repone`() = runTest {
        val fake = FakeOpenWrt().base()
        val api = api(fake)
        api.updateProfiles { profiles, _ -> profiles + juan }
        val path = fake.includePath!!
        fake.files.remove(path) // p. ej. una actualización del router que no conserva /etc/luci-uploads

        api.updateProfiles { profiles, _ -> profiles.map { it.copy(pause = Pause.Indefinite) } }

        assertEquals("pausar no cambia el fichero", path, fake.includePath)
        assertTrue("pero se repone", fake.files.containsKey(path))
        assertEquals(true, api.load().rulesLoaded)
        assertNull(fake.pendingApply)
    }

    @Test
    fun `cuarentena - una zona de casa sin dispositivos ahora se deja fuera`() = runTest {
        val fake = FakeOpenWrt().base()
        // Wi-Fi de invitados apagado: la zona existe, pero su red no tiene dispositivo. fw4
        // generaría su regla MARK sin iifname y marcaría todo lo que entra.
        fake.section("cfg08", "zone", "name" to "guest", "network" to listOf("guest"))
        fake.section("cfg09", "forwarding", "src" to "guest", "dest" to "wan")
        val api = api(fake)

        api.update(appMacs = setOf(phone)) { config, _ -> config.copy(settings = config.settings.copy(quarantine = true)) }

        assertEquals("lan", fake.option("wrtc_q_0", "src"))
        assertFalse(fake.committed.containsKey("wrtc_q_1"))
        assertEquals(true, api.load().rulesLoaded)
        assertNull(fake.pendingApply)
    }
}
