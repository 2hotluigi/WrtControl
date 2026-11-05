package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.Lookup
import dev.angel.wrtcontrol.openwrt.Resolution
import dev.angel.wrtcontrol.openwrt.UciValue
import dev.angel.wrtcontrol.openwrt.parseNslookup
import dev.angel.wrtcontrol.openwrt.uciSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Protección del control parental (paquete 1): fichero de reglas propias de nftables
 * (cuarentena, DNS cifrado por IP, corte de conexiones, acceso al router), tiempo extra,
 * búsqueda segura por DNS, ajustes guardados, copia de seguridad y lectura de nslookup.
 */
class ProtectionCompilerTest {

    // Miércoles.
    private val now = LocalDateTime.of(2026, 11, 4, 22, 0)
    private val mac1 = "AA:BB:CC:DD:EE:01"
    private val mac2 = "AA:BB:CC:DD:EE:02"
    private val mac3 = "AA:BB:CC:DD:EE:03"
    private val juan = Profile("j1", "Juan", listOf(mac1, mac2))
    private val filtered = juan.copy(filter = ContentFilter(FilterLevel.Adult))
    private val juanMacs = "ether saddr { aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:02 }"

    private fun plan(
        vararg profiles: Profile,
        settings: ParentalSettings = ParentalSettings(),
        env: RouterEnv = RouterEnv(),
        appMacs: Set<String> = emptySet(),
        at: LocalDateTime = now,
    ) = compileRouter(ParentalConfig(profiles.toList(), settings), at, env, appMacs)

    private fun RouterPlan.section(name: String) = (firewall + dhcp).single { it.name == name }

    /** Líneas de una cadena del fichero (la primera es la de tipo y prioridad), o null si no está. */
    private fun NftInclude.chain(name: String): List<String>? {
        val lines = content.lines()
        val start = lines.indexOf("chain $name {").takeIf { it >= 0 } ?: return null
        val end = (start until lines.size).first { lines[it] == "}" }
        return lines.subList(start + 1, end).map { it.trim() }
    }

    /** Reglas con el comentario propio [tag], en orden. */
    private fun NftInclude.rules(tag: String) =
        content.lines().map { it.trim() }.filter { it.endsWith("comment \"$OWN_COMMENT$tag\"") }

    /** Lo que nftables 1.0.2 necesita: ASCII, sin conjuntos vacíos y llaves equilibradas. */
    private fun assertWellFormed(include: NftInclude) {
        val c = include.content
        assertTrue("solo ASCII", c.all { it.code < 128 })
        assertFalse("conjunto vacío", c.contains("{ }") || c.contains("{}"))
        assertEquals("llaves equilibradas", c.count { it == '{' }, c.count { it == '}' })
        assertEquals("counter comment \"${include.marker}\"", include.chain(BLOCK_CHAIN)!![1])
    }

    // ---------- 1. fichero de reglas ----------

    @Test
    fun `sin perfiles ni cuarentena no hay fichero de reglas`() {
        val empty = plan()
        assertNull(empty.include)
        assertTrue(empty.firewall.none { it.name == INCLUDE_SECTION })
        // La cuarentena sí lo necesita, aunque no haya perfiles.
        val quarantine = plan(settings = ParentalSettings(quarantine = true, approved = listOf(mac3)))
        assertNotNull(quarantine.include)
        assertEquals(quarantine.include!!.path, quarantine.section(INCLUDE_SECTION).text("path"))
    }

    @Test
    fun `con perfil - cadena forward con prioridad -1 y la version en la primera regla`() {
        val plan = plan(juan)
        val include = plan.include!!
        assertTrue(include.version, include.version.matches(Regex("[0-9a-f]{12}")))
        assertEquals("/etc/luci-uploads/wrtcontrol-${include.version}.nft", include.path)
        assertEquals("!wrtcontrol: v=${include.version}", include.marker)
        assertTrue(isIncludeFile(include.path))
        assertEquals(include.version, includeVersion(include.path))
        assertEquals(include.path, plan.section(INCLUDE_SECTION).text("path"))

        val forward = include.chain(BLOCK_CHAIN)!!
        assertEquals("type filter hook forward priority -1; policy accept;", forward[0])
        assertEquals("counter comment \"!wrtcontrol: v=${include.version}\"", forward[1])
        assertEquals(2, include.rules("bloqueo").size)
        assertTrue(include.rules("bloqueo").all { it.startsWith("meta mark & 0x08000000 == 0x08000000 ") })
        assertNull("sin perfiles protegidos no hay cadena input", include.chain(INPUT_CHAIN))
        assertWellFormed(include)
    }

    @Test
    fun `todo activado - solo ASCII aunque el nombre lleve acentos`() {
        val p = juan.copy(
            name = "Júan ñandú 😀",
            pause = Pause.Indefinite,
            filter = ContentFilter(FilterLevel.Strict, setOf("tiktok"), listOf("ejemplo.com"), safeSearch = true, youtube = YoutubeMode.Strict),
            protectRouter = true,
        )
        val include = plan(p, settings = ParentalSettings(quarantine = true, approved = listOf(mac3))).include!!
        assertWellFormed(include)
        assertNotNull(include.chain(INPUT_CHAIN))
        assertEquals("type filter hook input priority -1; policy accept;", include.chain(INPUT_CHAIN)!![0])
    }

    @Test
    fun `nunca aparece un conjunto vacio`() {
        val configs = listOf(
            plan(juan),
            plan(juan.copy(macs = emptyList(), filter = ContentFilter(FilterLevel.Adult), protectRouter = true)),
            plan(filtered.copy(protectRouter = true), appMacs = setOf(mac1, mac2)),
            plan(settings = ParentalSettings(quarantine = true), appMacs = setOf(mac3)),
            plan(filtered.copy(protectRouter = true), env = RouterEnv(adminPorts = emptyList())),
            plan(filtered, Profile("a2", "Ana", listOf(mac3), filter = ContentFilter(safeSearch = true), protectRouter = true)),
            plan(filtered.copy(protectRouter = true), settings = ParentalSettings(admins = listOf(mac1, mac2))),
            plan(settings = ParentalSettings(quarantine = true, admins = listOf(mac3))),
            plan(filtered.copy(cutEpoch = 1, cutWindow = CutWindow(2, now.plusSeconds(90)))),
        )
        for (c in configs) assertWellFormed(c.include!!)
    }

    @Test
    fun `la version cambia con el contenido y es estable si no cambia`() {
        val ana = Profile("a2", "Ana", listOf(mac3), filter = ContentFilter(domains = listOf("ejemplo.com")))
        val base = plan(filtered, ana).include!!

        // Mismo contenido: misma versión, a otra hora, en otro orden o con pausas (van en reglas MARK).
        assertEquals(base, plan(filtered, ana).include)
        assertEquals(base, plan(filtered, ana, at = now.plusDays(3)).include)
        assertEquals(base, plan(ana, filtered).include)
        assertEquals(base.version, plan(filtered.copy(pause = Pause.Indefinite, name = "Otro"), ana).include!!.version)

        // Contenido distinto: versión distinta.
        val variants = listOf(
            plan(juan, ana),
            plan(filtered.copy(macs = listOf(mac1)), ana),
            plan(filtered.copy(protectRouter = true), ana),
            plan(filtered.copy(cutEpoch = 2), ana),
            plan(filtered, ana, settings = ParentalSettings(quarantine = true)),
            plan(filtered.copy(protectRouter = true), ana, env = RouterEnv(adminPorts = listOf(22))),
            plan(filtered.copy(cutWindow = CutWindow(2, now.plusSeconds(90))), ana),
            plan(filtered, ana, settings = ParentalSettings(quarantine = true, admins = listOf("AA:BB:CC:DD:EE:20"))),
        ).map { it.include!! }
        for (v in variants) {
            assertNotEquals(base.content, v.content)
            assertNotEquals(base.version, v.version)
        }
        assertEquals("todas distintas", variants.size, variants.map { it.version }.toSet().size)
    }

    // ---------- 2. cuarentena ----------

    @Test
    fun `cuarentena - solo salen los aprobados, los perfiles, los administradores y el telefono`() {
        val env = RouterEnv(homeZones = listOf("lan", "guest"))
        val settings = ParentalSettings(quarantine = true, approved = listOf("AA:BB:CC:DD:EE:10"), admins = listOf("AA:BB:CC:DD:EE:30"))
        // El teléfono que aplica llega con la MAC en minúsculas.
        val include = plan(juan, settings = settings, env = env, appMacs = setOf("aa:bb:cc:dd:ee:20")).include!!

        val match = "meta mark & 0x06000000 == 0x04000000 ct direction original ether saddr != " +
            "{ aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:02, aa:bb:cc:dd:ee:10, aa:bb:cc:dd:ee:20, aa:bb:cc:dd:ee:30 }"
        val rules = include.rules("cuarentena")
        assertEquals(
            listOf(
                "$match meta l4proto tcp counter reject with tcp reset comment \"!wrtcontrol: cuarentena\"",
                "$match counter reject with icmpx type admin-prohibited comment \"!wrtcontrol: cuarentena\"",
            ),
            rules,
        )
        // Lo primero tras la versión: también corta lo ya establecido.
        assertEquals(rules[0], include.chain(BLOCK_CHAIN)!![2])
        assertFalse("ya no se filtra por interfaz", include.content.contains("iifname"))
        assertWellFormed(include)
    }

    @Test
    fun `cuarentena - una regla MARK de fw4 por cada zona de casa`() {
        val plan = plan(juan, settings = ParentalSettings(quarantine = true), env = RouterEnv(homeZones = listOf("lan", "guest")))

        fun expected(i: Int, zone: String) = uciSection(
            "wrtc_q_$i", "rule",
            "name" to "WrtControl · cuarentena ($zone)",
            "src" to zone,
            "dest" to "*",
            "proto" to "all",
            "target" to "MARK",
            "set_xmark" to "0x04000000/0x04000000",
        )
        assertEquals(expected(0, "lan"), plan.section("wrtc_q_0"))
        assertEquals(expected(1, "guest"), plan.section("wrtc_q_1"))
        // fw4 debe cargarlas: cuentan para la verificación.
        assertTrue(plan.ruleLabels.containsAll(listOf("WrtControl · cuarentena (lan)", "WrtControl · cuarentena (guest)")))
        // La marca de las reglas UCI es la que miran las reglas propias, y no pisa la del bloqueo.
        assertEquals(0x04000000L, QUARANTINE_MARK)
        assertEquals(0L, QUARANTINE_MARK and MARK_BIT)
        // Lo que lleva también la marca de WAN (entra de internet) nunca se rechaza.
        assertTrue(plan.include!!.rules("cuarentena").all { it.startsWith("meta mark & 0x06000000 == 0x04000000 ") })
        assertEquals(
            uciSection(
                "wrtc_qw_0", "rule",
                "name" to "WrtControl · cuarentena, internet (wan)",
                "src" to "wan",
                "dest" to "*",
                "proto" to "all",
                "target" to "MARK",
                "set_xmark" to "0x02000000/0x02000000",
            ),
            plan.section("wrtc_qw_0"),
        )
        assertEquals(0L, WAN_MARK and (QUARANTINE_MARK or MARK_BIT))

        // Sin cuarentena, ninguna.
        val off = plan(juan, settings = ParentalSettings(approved = listOf(mac3)), env = RouterEnv(homeZones = listOf("lan", "guest")))
        assertTrue(off.firewall.none { it.name.startsWith("wrtc_q_") })
        assertTrue(off.ruleLabels.none { "cuarentena" in it })
    }

    @Test
    fun `cuarentena sin perfiles - basta con los aprobados, un administrador o el telefono`() {
        val onlyPhone = plan(settings = ParentalSettings(quarantine = true), appMacs = setOf(mac3)).include!!
        assertTrue(onlyPhone.rules("cuarentena").all { "ether saddr != { aa:bb:cc:dd:ee:03 }" in it })
        val onlyAdmin = plan(settings = ParentalSettings(quarantine = true, admins = listOf(mac2))).include!!
        assertTrue(onlyAdmin.rules("cuarentena").all { "ether saddr != { aa:bb:cc:dd:ee:02 }" in it })

        val e = assertThrows(IllegalArgumentException::class.java) { plan(settings = ParentalSettings(quarantine = true)) }
        assertTrue(e.message!!, e.message!!.contains("Aprueba al menos un dispositivo"))
    }

    @Test
    fun `cuarentena sin zona de casa - error claro`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            plan(juan, settings = ParentalSettings(quarantine = true, approved = listOf(mac3)), env = RouterEnv(homeZones = emptyList()))
        }
        assertTrue(e.message!!, e.message!!.contains("zona de la red de casa"))
        // Sin cuarentena no hace falta.
        assertNotNull(plan(filtered, env = RouterEnv(homeZones = emptyList())).include)
    }

    @Test
    fun `sin cuarentena no hay reglas de cuarentena`() {
        val include = plan(filtered.copy(protectRouter = true), settings = ParentalSettings(approved = listOf(mac3))).include!!
        assertTrue(include.rules("cuarentena").isEmpty())
        assertFalse(include.content.contains("0x04000000"))
        assertFalse(include.content.contains("ether saddr !="))
        assertFalse("el aprobado no sale en ninguna regla", include.content.contains("ee:03"))
    }

    // ---------- 3. DNS cifrado por IP ----------

    @Test
    fun `DNS cifrado por IP - solo perfiles con filtro y dispositivos`() {
        val ana = Profile("a2", "Ana", listOf(mac3), filter = ContentFilter(domains = listOf("ejemplo.com")))
        val noFilter = Profile("s3", "Sin filtro", listOf("AA:BB:CC:DD:EE:04"))
        val noDevices = Profile("v4", "Vacío", emptyList(), filter = ContentFilter(FilterLevel.Adult))
        val include = plan(filtered, ana, noFilter, noDevices).include!!

        val j = include.rules("doh j1")
        val v4 = "ip daddr { ${DOH_IPV4.joinToString(", ")} }"
        val v6 = "ip6 daddr { ${DOH_IPV6.joinToString(", ")} }"
        val tag = "comment \"!wrtcontrol: doh j1\""
        // Todos los puertos (DoH, DoT, DNSCrypt...): el 53 ya va redirigido al router.
        assertEquals(
            listOf(
                "$juanMacs $v4 meta l4proto tcp counter reject with tcp reset $tag",
                "$juanMacs $v4 meta l4proto udp counter reject with icmpx type admin-prohibited $tag",
                "$juanMacs $v6 meta l4proto tcp counter reject with tcp reset $tag",
                "$juanMacs $v6 meta l4proto udp counter reject with icmpx type admin-prohibited $tag",
            ),
            j,
        )
        assertTrue("sin puerto", j.none { "dport" in it })
        assertTrue("el ICMP pasa (ping a 8.8.8.8)", j.none { "icmp " in it || "l4proto icmp" in it })

        assertEquals(4, include.rules("doh a2").size)
        assertTrue(include.rules("doh a2").all { it.startsWith("ether saddr { aa:bb:cc:dd:ee:03 } ") })
        assertTrue(include.rules("doh s3").isEmpty())
        assertTrue(include.rules("doh v4").isEmpty())
        assertFalse(include.content.contains("ee:04"))
    }

    @Test
    fun `DNS cifrado por IP - incluye el DoH de OpenDNS`() {
        assertTrue(DOH_IPV4.contains("146.112.41.2"))
        val rules = plan(filtered).include!!.rules("doh j1")
        assertTrue(rules.filter { " ip daddr " in it }.all { "146.112.41.2" in it })
    }

    @Test
    fun `las IP de DNS cifrado no se solapan y son direcciones de red`() {
        // Literales: InetAddress no consulta ningún DNS.
        fun range(cidr: String, bits: Int): Pair<BigInteger, BigInteger> {
            val addr = cidr.substringBefore('/')
            val len = cidr.substringAfter('/', bits.toString()).toInt()
            val bytes = InetAddress.getByName(addr).address
            assertEquals("$cidr: familia", bits / 8, bytes.size)
            val value = BigInteger(1, bytes)
            val size = BigInteger.ONE.shiftLeft(bits - len)
            assertEquals("$cidr: bits de host a cero", BigInteger.ZERO, value.mod(size))
            return value to value + size - BigInteger.ONE
        }
        for ((list, bits) in listOf(DOH_IPV4 to 32, DOH_IPV6 to 128)) {
            assertEquals("sin repetidas", list.size, list.toSet().size)
            val ranges = list.map { it to range(it, bits) }
            for (i in ranges.indices) {
                for (k in i + 1 until ranges.size) {
                    val (a, ra) = ranges[i]
                    val (b, rb) = ranges[k]
                    assertTrue("$a y $b se solapan", ra.second < rb.first || rb.second < ra.first)
                }
            }
            // Las direcciones de búsqueda segura no pueden caer dentro: se cortaría Google por HTTPS.
            for (ip in SAFE_SEARCH_RECORDS.values.flatten()) {
                val bytes = InetAddress.getByName(ip).address
                if (bytes.size != bits / 8) continue
                val v = BigInteger(1, bytes)
                assertTrue("$ip está en la lista de DNS cifrado", ranges.none { (_, r) -> v >= r.first && v <= r.second })
            }
        }
    }

    // ---------- 4. épocas de corte ----------

    @Test
    fun `compileRouter da la epoca 1 a los perfiles con filtro que no tenian`() {
        val noFilter = Profile("s3", "Sin filtro", listOf(mac3))
        val plan = plan(filtered, noFilter)
        assertEquals(1, plan.profiles.single { it.id == "j1" }.cutEpoch)
        assertEquals(0, plan.profiles.single { it.id == "s3" }.cutEpoch)
        assertEquals("1", plan.section("wrtc_p_j1").text("cut_epoch"))
        assertNull(plan.section("wrtc_p_s3").text("cut_epoch"))
        assertTrue(plan.include!!.rules("corte s3").isEmpty())
        // Conserva la que ya tenía.
        assertEquals(3, plan(filtered.copy(cutEpoch = 3)).profiles.single().cutEpoch)
        // Con filtro pero sin dispositivos no hace falta.
        assertEquals(0, plan(filtered.copy(macs = emptyList())).profiles.single().cutEpoch)
    }

    @Test
    fun `reglas de corte con la epoca en los bits 0x0000c000 del ct mark`() {
        for ((epoch, value) in listOf(1 to "0x00004000", 2 to "0x00008000", 3 to "0x0000c000")) {
            val include = plan(filtered.copy(cutEpoch = epoch)).include!!
            val rules = include.rules("corte j1")
            // Primero se marca la conexión nueva y después se rechaza lo de otra época.
            assertEquals("$juanMacs ct state new ct mark set ct mark & 0xffff3fff | $value comment \"!wrtcontrol: corte j1\"", rules[0])
            // Solo TCP y QUIC (UDP 443): un flujo UDP que reutiliza el puerto quedaría cortado para siempre.
            val match = "$juanMacs ct direction original ct mark & 0x0000c000 != $value"
            assertEquals(
                listOf(
                    "$match meta l4proto tcp counter reject with tcp reset comment \"!wrtcontrol: corte j1\"",
                    "$match udp dport 443 counter reject with icmpx type admin-prohibited comment \"!wrtcontrol: corte j1\"",
                ),
                rules.drop(1),
            )
            assertFalse("sin ventana no hay meta time", include.content.contains("meta time"))
            assertWellFormed(include)
        }
    }

    @Test
    fun `reglas de corte con ventana - meta time en hora local y segundo corte de la marca de la ventana`() {
        val until = LocalDateTime.of(2026, 11, 4, 22, 1, 30)
        val include = plan(filtered.copy(cutEpoch = 2, cutWindow = CutWindow(3, until))).include!!
        val tag = "comment \"!wrtcontrol: corte j1\""
        val set = "ct mark set ct mark & 0xffff3fff"
        val time = "\"2026-11-04 22:01:30\""
        val mark = "ct mark & 0x0000c000"
        val tcp = "meta l4proto tcp counter reject with tcp reset $tag"
        val quic = "udp dport 443 counter reject with icmpx type admin-prohibited $tag"
        assertEquals(
            listOf(
                // Hasta el fin de la ventana, las conexiones nuevas llevan la marca de la ventana (3)...
                "$juanMacs ct state new meta time < $time $set | 0x0000c000 $tag",
                // ...y después, la de la época (2).
                "$juanMacs ct state new meta time >= $time $set | 0x00008000 $tag",
                // Lo abierto antes de endurecer (ni época ni ventana), fuera ya.
                "$juanMacs ct direction original $mark != 0x00008000 $mark != 0x0000c000 $tcp",
                "$juanMacs ct direction original $mark != 0x00008000 $mark != 0x0000c000 $quic",
                // Lo abierto durante la ventana, fuera cuando acaba.
                "$juanMacs ct direction original $mark == 0x0000c000 meta time >= $time $tcp",
                "$juanMacs ct direction original $mark == 0x0000c000 meta time >= $time $quic",
            ),
            include.rules("corte j1"),
        )
        assertWellFormed(include)
        // La ventana cambia el fichero (y así se vuelve a cargar).
        assertNotEquals(plan(filtered.copy(cutEpoch = 2)).include!!.version, include.version)
        // Una ventana con la misma marca que la época no tendría sentido: se ignora.
        assertEquals(plan(filtered.copy(cutEpoch = 2)).include, plan(filtered.copy(cutEpoch = 2, cutWindow = CutWindow(2, until))).include)
        // Sin filtro no hay reglas de corte, tenga ventana o no.
        assertTrue(plan(juan.copy(cutEpoch = 2, cutWindow = CutWindow(3, until))).include!!.rules("corte j1").isEmpty())
    }

    private val base = Profile("j1", "Juan", listOf(mac1), filter = ContentFilter(services = setOf("tiktok")), cutEpoch = 1)

    private fun epochAfter(after: Profile, before: Profile? = base) =
        bumpCutEpochs(listOfNotNull(before), listOf(after), now).single().cutEpoch

    @Test
    fun `endurecer abre la ventana del segundo corte con la marca que queda libre`() {
        // Con fracciones de segundo: la ventana se guarda en segundos exactos.
        val at = now.plusNanos(123_456_789)
        for ((current, epoch, window) in listOf(Triple(1, 2, 3), Triple(2, 3, 1), Triple(3, 1, 2))) {
            val before = base.copy(cutEpoch = current)
            val after = before.copy(filter = before.filter.copy(domains = listOf("x.com")))
            val p = bumpCutEpochs(listOf(before), listOf(after), at).single()
            assertEquals("$current", epoch, p.cutEpoch)
            assertEquals("$current: la que no es ni la de antes ni la nueva", CutWindow(window, now.plusSeconds(CUT_WINDOW_SECONDS)), p.cutWindow)
            assertNotEquals(p.cutEpoch, p.cutWindow!!.value)
            assertNotEquals(current, p.cutWindow!!.value)
            assertEquals(0, p.cutWindow!!.until.nano)
            validateProfiles(listOf(p))
        }
        assertEquals(90L, CUT_WINDOW_SECONDS)
        // Perfil nuevo con filtro: época 1 y ventana con la 2.
        // Perfil nuevo con filtro: su caché viene del DNS principal (TTL largos), la ventana es más larga.
        val fresh = bumpCutEpochs(emptyList(), listOf(base.copy(cutEpoch = 0)), now).single()
        assertEquals(1, fresh.cutEpoch)
        assertEquals(CutWindow(2, now.plusSeconds(CUT_WINDOW_FIRST_SECONDS)), fresh.cutWindow)
        // Igual al añadir un dispositivo, que tampoco pasaba por la instancia del perfil.
        val more = bumpCutEpochs(listOf(base), listOf(base.copy(macs = base.macs + "AA:BB:CC:DD:EE:77")), now).single()
        assertEquals(now.plusSeconds(CUT_WINDOW_FIRST_SECONDS), more.cutWindow!!.until)
        assertTrue(CUT_WINDOW_FIRST_SECONDS >= 300)
    }

    @Test
    fun `sin endurecer se conservan la epoca y la ventana del router`() {
        val window = CutWindow(3, now.minusSeconds(10))
        val router = base.copy(cutEpoch = 2, cutWindow = window)
        // Un editor abierto antes del último cambio: época 1 y sin ventana. Solo cambia el nombre.
        val stale = base.copy(name = "Juanito", cutEpoch = 1, cutWindow = null)
        val kept = bumpCutEpochs(listOf(router), listOf(stale), now).single()
        assertEquals(2, kept.cutEpoch)
        assertEquals(window, kept.cutWindow)
        assertEquals("Juanito", kept.name)

        // Una ventana que trae el editor pero el router ya no tiene: manda el router.
        val noWindow = base.copy(cutEpoch = 2)
        assertNull(bumpCutEpochs(listOf(noWindow), listOf(noWindow.copy(cutWindow = CutWindow(1, now))), now).single().cutWindow)

        // Endurecer desde ese editor viejo parte de la época del router (2) y de su ventana (3): la
        // nueva es la libre (1) y la ventana nueva conserva la marca 3.
        val hardened = bumpCutEpochs(listOf(router), listOf(stale.copy(filter = stale.filter.copy(domains = listOf("x.com")))), now).single()
        assertEquals(1, hardened.cutEpoch)
        assertEquals(CutWindow(3, now.plusSeconds(90)), hardened.cutWindow)

        // Un perfil de una versión sin épocas: recibe la primera y ninguna ventana.
        val legacy = base.copy(cutEpoch = 0)
        assertEquals(base.copy(cutEpoch = 1, cutWindow = null), bumpCutEpochs(listOf(legacy), listOf(legacy), now).single())
    }

    @Test
    fun `endurecer dos veces dentro de la ventana corta tambien lo abierto durante la primera`() {
        val first = bumpCutEpochs(listOf(base), listOf(base.copy(filter = base.filter.copy(domains = listOf("a.com")))), now).single()
        assertEquals(CutWindow(3, now.plusSeconds(90)), first.cutWindow)
        // 30 s después (la ventana sigue abierta) se bloquea otro sitio.
        val later = now.plusSeconds(30)
        val second = bumpCutEpochs(listOf(first), listOf(first.copy(filter = first.filter.copy(domains = first.filter.domains + "b.com"))), later).single()

        // Las conexiones abiertas en esos 30 s llevan la marca de la primera ventana y pueden venir de la
        // caché DNS de antes de bloquear: tienen que cortarse, ahora o al acabar la ventana nueva.
        val before = plan(filtered.copy(cutEpoch = first.cutEpoch, cutWindow = first.cutWindow)).include!!
        val opened = before.newMark("corte j1", now.plusSeconds(10))
        assertEquals(first.cutWindow!!.value, opened)
        val after = plan(filtered.copy(cutEpoch = second.cutEpoch, cutWindow = second.cutWindow), at = later).include!!
        assertTrue(
            "la conexión con la marca $opened no se corta nunca (época ${second.cutEpoch}, ventana ${second.cutWindow})",
            after.cuts("corte j1", opened, later.plusHours(1)),
        )
    }

    /** Marca (1 a 3) que ponen las reglas de corte de [tag] a una conexión nueva en [at]. */
    private fun NftInclude.newMark(tag: String, at: LocalDateTime): Int = rules(tag).filter { "ct state new" in it }.mapNotNull { rule ->
        val before = Regex("meta time < \"([^\"]+)\"").find(rule)?.let { LocalDateTime.parse(it.groupValues[1], NFT_TIME) }
        val after = Regex("meta time >= \"([^\"]+)\"").find(rule)?.let { LocalDateTime.parse(it.groupValues[1], NFT_TIME) }
        val applies = (before == null || at.isBefore(before)) && (after == null || !at.isBefore(after))
        if (applies) (Regex("\\| 0x([0-9a-f]{8})").find(rule)!!.groupValues[1].toLong(16) shr 14).toInt() else null
    }.single()

    /** ¿Cortan las reglas de corte de [tag] una conexión TCP con la marca [value] (0 a 3) en [at]? */
    private fun NftInclude.cuts(tag: String, value: Int, at: LocalDateTime): Boolean {
        val mark = value.toLong() shl 14
        return rules(tag).filter { "reject with tcp reset" in it }.any { rule ->
            val marks = Regex("ct mark & 0x0000c000 (==|!=) 0x([0-9a-f]{8})").findAll(rule).toList()
            assertTrue(rule, marks.isNotEmpty())
            val markOk = marks.all { m ->
                val v = m.groupValues[2].toLong(16)
                if (m.groupValues[1] == "==") mark == v else mark != v
            }
            val from = Regex("meta time >= \"([^\"]+)\"").find(rule)?.let { LocalDateTime.parse(it.groupValues[1], NFT_TIME) }
            markOk && (from == null || !at.isBefore(from))
        }
    }

    private val NFT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test
    fun `reglas de corte con ventana - que se corta y cuando`() {
        val until = now.plusSeconds(90)
        val include = plan(filtered.copy(cutEpoch = 2, cutWindow = CutWindow(3, until))).include!!
        assertEquals("durante la ventana, su marca", 3, include.newMark("corte j1", now.plusSeconds(10)))
        assertEquals("al acabar, la de la época", 2, include.newMark("corte j1", until))
        // Lo de antes de endurecer (época 1, o sin marca porque es anterior a las reglas) se corta ya.
        assertTrue(include.cuts("corte j1", 1, now))
        assertTrue(include.cuts("corte j1", 0, now))
        // Lo abierto en la ventana sigue hasta que acaba (la caché DNS del dispositivo aún vale)...
        assertFalse(include.cuts("corte j1", 3, until.minusSeconds(1)))
        // ...y entonces se corta: al reconectar ya pregunta al DNS.
        assertTrue(include.cuts("corte j1", 3, until))
        // Lo abierto después, nunca.
        assertFalse(include.cuts("corte j1", 2, until.plusHours(5)))
    }

    @Test
    fun `endurecer el filtro avanza la epoca`() {
        val f = base.filter
        val cases = mapOf(
            "servicio nuevo" to base.copy(filter = f.copy(services = f.services + "instagram")),
            "sitio nuevo" to base.copy(filter = f.copy(domains = listOf("ejemplo.com"))),
            "nivel para adultos" to base.copy(filter = f.copy(level = FilterLevel.Adult)),
            "nivel estricto" to base.copy(filter = f.copy(level = FilterLevel.Strict)),
            "búsqueda segura" to base.copy(filter = f.copy(safeSearch = true)),
            "YouTube moderado" to base.copy(filter = f.copy(youtube = YoutubeMode.Moderate)),
            "YouTube estricto" to base.copy(filter = f.copy(youtube = YoutubeMode.Strict)),
            "dispositivo nuevo" to base.copy(macs = listOf(mac1, mac2)),
            "dispositivo cambiado" to base.copy(macs = listOf(mac2)),
        )
        for ((what, after) in cases) assertEquals(what, 2, epochAfter(after))

        // Más estricto dentro de cada opción.
        val adult = base.copy(filter = f.copy(level = FilterLevel.Adult))
        assertEquals(2, epochAfter(adult.copy(filter = adult.filter.copy(level = FilterLevel.Strict)), adult))
        val moderate = base.copy(filter = f.copy(youtube = YoutubeMode.Moderate))
        assertEquals(2, epochAfter(moderate.copy(filter = moderate.filter.copy(youtube = YoutubeMode.Strict)), moderate))
    }

    @Test
    fun `la epoca alterna sin pisar nunca la marca de la ventana`() {
        assertEquals(listOf(1, 2, 3, 1, 1), listOf(0, 1, 2, 3, 7).map(::nextEpoch))
        var current = base
        val seen = mutableListOf<Int>()
        for (d in listOf("a.com", "b.com", "c.com", "d.com")) {
            val after = current.copy(filter = current.filter.copy(domains = current.filter.domains + d))
            current = bumpCutEpochs(listOf(current), listOf(after), now).single()
            seen += current.cutEpoch
        }
        // La primera vez: de la 1 a la 2, con la ventana en la 3. Después alterna entre 1 y 2.
        assertEquals(listOf(2, 1, 2, 1), seen)
        assertEquals(3, current.cutWindow!!.value)
    }

    @Test
    fun `filtro nuevo o perfil nuevo empiezan con la epoca siguiente`() {
        val noFilter = base.copy(filter = ContentFilter(), cutEpoch = 0)
        assertEquals(1, epochAfter(base.copy(cutEpoch = 0), noFilter))
        // Una época guardada de cuando tenía filtro: avanza desde ella.
        assertEquals(3, epochAfter(base.copy(cutEpoch = 2), noFilter.copy(cutEpoch = 2)))
        assertEquals(1, epochAfter(base.copy(cutEpoch = 3), noFilter.copy(cutEpoch = 3)))
        // Perfil que no existía.
        assertEquals(1, epochAfter(base.copy(cutEpoch = 0), before = null))
        // Tenía el filtro pero ningún dispositivo.
        assertEquals(2, epochAfter(base, base.copy(macs = emptyList())))
    }

    @Test
    fun `relajar o no cambiar el filtro no mueve la epoca`() {
        val strict = base.copy(
            macs = listOf(mac1, mac2),
            filter = ContentFilter(FilterLevel.Adult, setOf("tiktok", "instagram"), listOf("a.com", "b.com"), safeSearch = true, youtube = YoutubeMode.Strict),
            cutEpoch = 2,
        )
        val f = strict.filter
        val cases = mapOf(
            "igual" to strict,
            "otros cambios" to strict.copy(name = "Juanito", pause = Pause.Indefinite, protectRouter = true, extraUntil = now.plusHours(1), dnsPort = 5301),
            "quitar servicio" to strict.copy(filter = f.copy(services = setOf("tiktok"))),
            "quitar sitio" to strict.copy(filter = f.copy(domains = listOf("a.com"))),
            "sin nivel" to strict.copy(filter = f.copy(level = FilterLevel.None)),
            "sin búsqueda segura" to strict.copy(filter = f.copy(safeSearch = false)),
            "YouTube moderado" to strict.copy(filter = f.copy(youtube = YoutubeMode.Moderate)),
            "YouTube libre" to strict.copy(filter = f.copy(youtube = YoutubeMode.Off)),
            "quitar dispositivo" to strict.copy(macs = listOf(mac1)),
        )
        for ((what, after) in cases) assertEquals(what, 2, epochAfter(after, strict))
        // Sin cambios y sin época asignada: recibe la primera.
        assertEquals(1, epochAfter(base.copy(cutEpoch = 0), base.copy(cutEpoch = 0)))
    }

    @Test
    fun `cambiar de DNS para familias tambien corta, aunque sea de estricto a adultos`() {
        // Son proveedores distintos con listas distintas: el código lo trata como endurecer.
        val strict = base.copy(filter = base.filter.copy(level = FilterLevel.Strict))
        assertEquals(2, epochAfter(strict.copy(filter = strict.filter.copy(level = FilterLevel.Adult)), strict))
    }

    @Test
    fun `perfiles sin filtro no cambian de epoca`() {
        val noFilter = Profile("s3", "Sin filtro", listOf(mac3))
        val after = noFilter.copy(macs = listOf(mac2, mac3), protectRouter = true)
        assertEquals(after, bumpCutEpochs(listOf(noFilter), listOf(after), now).single())
        assertEquals(noFilter, bumpCutEpochs(emptyList(), listOf(noFilter), now).single())
        // Quitar el filtro conserva la época guardada (sin reglas de corte).
        assertEquals(2, epochAfter(base.copy(filter = ContentFilter(), cutEpoch = 2), base.copy(cutEpoch = 2)))
        // Filtro sin dispositivos: tampoco.
        assertEquals(0, epochAfter(base.copy(macs = emptyList(), cutEpoch = 0)))
        // Cada perfil por su lado.
        val ana = Profile("a2", "Ana", listOf(mac3), filter = ContentFilter(FilterLevel.Adult), cutEpoch = 3)
        val result = bumpCutEpochs(listOf(base, ana), listOf(base.copy(filter = base.filter.copy(domains = listOf("x.com"))), ana), now)
        assertEquals(listOf(2, 3), result.map { it.cutEpoch })
    }

    // ---------- 5. acceso al router ----------

    @Test
    fun `proteger el router - cadena input con los puertos y sin el telefono`() {
        val env = RouterEnv(adminPorts = listOf(443, 22, 8443, 22))
        val ana = Profile("a2", "Ana", listOf(mac3), protectRouter = true)
        val other = Profile("o4", "Otro", listOf("AA:BB:CC:DD:EE:04"))
        val include = plan(juan.copy(protectRouter = true), ana, other, env = env, appMacs = setOf(mac2)).include!!

        val input = include.chain(INPUT_CHAIN)!!
        assertEquals("type filter hook input priority -1; policy accept;", input[0])
        assertEquals(
            listOf(
                "ether saddr { aa:bb:cc:dd:ee:01, aa:bb:cc:dd:ee:03 } tcp dport { 22, 443, 8443 } " +
                    "counter reject with tcp reset comment \"!wrtcontrol: router\"",
            ),
            input.drop(1),
        )
        assertWellFormed(include)
    }

    @Test
    fun `proteger el router - sin cadena input si no queda nadie o no hay puertos`() {
        assertNull(plan(juan.copy(protectRouter = true), appMacs = setOf(mac1, mac2)).include!!.chain(INPUT_CHAIN))
        assertNull(plan(juan).include!!.chain(INPUT_CHAIN))
        assertNull(plan(juan.copy(macs = emptyList(), protectRouter = true)).include!!.chain(INPUT_CHAIN))
        assertNull(plan(juan.copy(protectRouter = true), env = RouterEnv(adminPorts = emptyList())).include!!.chain(INPUT_CHAIN))
        // Por defecto, SSH y LuCI.
        assertTrue(plan(juan.copy(protectRouter = true)).include!!.chain(INPUT_CHAIN)!![1].contains("tcp dport { 22, 80, 443 }"))
    }

    @Test
    fun `proteger el router - el telefono se excluye aunque su MAC venga en minusculas`() {
        val include = plan(juan.copy(protectRouter = true), appMacs = setOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02")).include!!
        assertNull(include.chain(INPUT_CHAIN))
    }

    @Test
    fun `proteger el router - los administradores guardados se excluyen aunque aplique otro telefono`() {
        val ana = Profile("a2", "Ana", listOf(mac3), protectRouter = true)
        // mac1 usó la app antes (está en admins); ahora aplica otro teléfono (EE:20).
        val settings = ParentalSettings(admins = listOf(mac1))
        val include = plan(juan.copy(protectRouter = true), ana, settings = settings, appMacs = setOf("AA:BB:CC:DD:EE:20")).include!!
        assertEquals(
            "ether saddr { aa:bb:cc:dd:ee:02, aa:bb:cc:dd:ee:03 } tcp dport { 22, 80, 443 } counter reject with tcp reset comment \"!wrtcontrol: router\"",
            include.chain(INPUT_CHAIN)!![1],
        )
        // Sin ningún teléfono en el cambio, igual.
        assertEquals(include.chain(INPUT_CHAIN), plan(juan.copy(protectRouter = true), ana, settings = settings).include!!.chain(INPUT_CHAIN))
        // Si todos los protegidos son administradores, no hay cadena.
        assertNull(plan(juan.copy(protectRouter = true), settings = ParentalSettings(admins = listOf(mac1, mac2))).include!!.chain(INPUT_CHAIN))
        // Los administradores cambian el fichero: otra versión.
        assertNotEquals(
            plan(juan.copy(protectRouter = true)).include!!.version,
            plan(juan.copy(protectRouter = true), settings = settings).include!!.version,
        )
    }

    @Test
    fun `puertos de administracion de uhttpd y dropbear`() {
        val sections = listOf(
            uciSection(
                "main", "uhttpd",
                "listen_http" to listOf("0.0.0.0:80", "[::]:80"),
                "listen_https" to listOf("0.0.0.0:443", "[::]:8443"),
            ),
            uciSection("cfg01", "dropbear", "Port" to "2222"),
            uciSection("cfg02", "dropbear"), // 22 por defecto
            uciSection("cfg03", "dropbear", "enable" to "0", "Port" to "2200"), // desactivado
            uciSection("cert", "cert", "days" to "730"),
        )
        assertEquals(listOf(22, 80, 443, 2222, 8443), adminPorts(sections))
        // Un `option` con varias direcciones separadas por espacios y valores sin sentido.
        val odd = listOf(uciSection("main", "uhttpd", "listen_http" to "0.0.0.0:8080 [::]:8080", "listen_https" to listOf("0.0.0.0:99999", "basura")))
        assertEquals(listOf(8080), adminPorts(odd))
    }

    @Test
    fun `puertos de administracion por defecto si no hay nada`() {
        assertEquals(listOf(22, 80, 443), adminPorts(emptyList()))
        assertEquals(DEFAULT_ADMIN_PORTS, adminPorts(listOf(uciSection("cfg03", "dropbear", "enable" to "0"))))
        assertEquals(DEFAULT_ADMIN_PORTS, routerEnv(emptyList(), emptyList()).adminPorts)
        assertEquals(DEFAULT_ADMIN_PORTS, routerEnv(emptyList(), emptyList(), admin = null).adminPorts)
        assertEquals(listOf(22), routerEnv(emptyList(), emptyList(), admin = listOf(uciSection("cfg01", "dropbear"))).adminPorts)
    }

    @Test
    fun `zonas de casa - sin NAT y origen de un reenvio habilitado hacia otra zona`() {
        val firewall = listOf(
            uciSection("cfg01", "zone", "name" to "lan", "network" to listOf("lan")),
            uciSection("cfg02", "zone", "name" to "wan", "masq" to "1", "network" to listOf("wan", "wan6")),
            uciSection("cfg03", "zone", "name" to "guest", "network" to "guest"),
            uciSection("cfg04", "zone", "name" to "iot", "masq" to "0"),
            uciSection("cfg05", "zone", "name" to "vpn"),
            // masq como lo lee fw4: cualquier booleano de UCI verdadero.
            uciSection("cfg06", "zone", "name" to "wan2", "masq" to "on"),
            uciSection("cfg07", "zone", "name" to "wan3", "masq" to "true"),
            uciSection("cfg08", "zone", "name" to "wan4", "masq" to "yes"),
            uciSection("cfg09", "zone", "name" to "wan5", "masq" to "enabled"), // fw4 no lo lee como booleano: sin NAT
            uciSection("cfg10", "zone", "name" to "dmz", "masq" to "off"),
            uciSection("cfg11", "zone", "name" to "mgmt"),
            uciSection("cfg12", "zone", "name" to "loop"),
            uciSection("cfg13", "zone", "name" to "aislada"),
            uciSection("cfg14", "rule", "name" to "Allow-Ping", "src" to "aislada", "dest" to "wan"),
            uciSection("f1", "forwarding", "src" to "lan", "dest" to "wan"),
            uciSection("f2", "forwarding", "src" to "guest", "dest" to "wan", "enabled" to "1"),
            uciSection("f3", "forwarding", "src" to "iot", "dest" to "wan"),
            uciSection("f4", "forwarding", "src" to "vpn", "dest" to "lan"), // hacia otra zona, aunque no sea la WAN
            uciSection("f5", "forwarding", "src" to "wan2", "dest" to "lan"), // con NAT: no es de casa
            uciSection("f6", "forwarding", "src" to "wan3", "dest" to "lan"),
            uciSection("f7", "forwarding", "src" to "wan4", "dest" to "lan"),
            uciSection("f8", "forwarding", "src" to "wan5", "dest" to "lan"),
            uciSection("f9", "forwarding", "src" to "dmz", "dest" to "wan"),
            uciSection("f10", "forwarding", "src" to "mgmt", "dest" to "wan", "enabled" to "0"), // desactivado
            uciSection("f11", "forwarding", "src" to "loop", "dest" to "loop"), // a sí misma
        )
        val env = routerEnv(firewall, emptyList())
        assertEquals(listOf("lan", "guest", "iot", "vpn", "wan5", "dmz", "mgmt", "loop", "aislada"), env.lanZones)
        assertEquals(listOf("lan", "guest", "iot", "vpn", "wan5", "dmz"), env.homeZones)
        assertEquals(listOf("wan", "wan2", "wan3", "wan4"), env.wanZones)

        // La cuarentena va en esas zonas, una regla por zona y en ese orden.
        val plan = plan(juan, settings = ParentalSettings(quarantine = true), env = env)
        assertEquals(env.homeZones, plan.firewall.filter { it.name.startsWith("wrtc_q_") }.map { it.text("src") })
        assertEquals(env.homeZones.indices.map { "wrtc_q_$it" }, plan.firewall.filter { it.name.startsWith("wrtc_q_") }.map { it.name })
    }

    @Test
    fun `zonas de casa - una WAN sin NAT no cuenta y sin reenvios no hay ninguna`() {
        val firewall = listOf(
            uciSection("cfg1", "zone", "name" to "lan", "network" to listOf("lan")),
            uciSection("cfg2", "zone", "name" to "wan", "network" to listOf("wan")), // sin masq: router sin NAT
            uciSection("cfg3", "forwarding", "src" to "lan", "dest" to "wan"),
        )
        val env = routerEnv(firewall, emptyList())
        assertEquals("el DNS se sigue redirigiendo en las dos", listOf("lan", "wan"), env.lanZones)
        assertEquals("pero la cuarentena solo va en la red de casa", listOf("lan"), env.homeZones)

        // Sin reenvíos (o desactivados) no se sabe cuál es la red de casa: no se puede activar la cuarentena.
        val noForwarding = routerEnv(firewall.dropLast(1), emptyList())
        assertTrue(noForwarding.homeZones.isEmpty())
        val disabled = routerEnv(firewall.dropLast(1) + uciSection("cfg3", "forwarding", "src" to "lan", "dest" to "wan", "enabled" to "0"), emptyList())
        assertTrue(disabled.homeZones.isEmpty())
        val e = assertThrows(IllegalArgumentException::class.java) {
            plan(juan, settings = ParentalSettings(quarantine = true), env = disabled)
        }
        assertTrue(e.message!!, e.message!!.contains("zona de la red de casa"))
        // El resto sigue funcionando sin ella.
        assertNotNull(plan(filtered, env = disabled).include)
    }

    // ---------- 6. tiempo extra ----------

    private val night = Schedule("noche1", setOf(WEDNESDAY), LocalTime.of(21, 0), LocalTime.of(7, 0))

    @Test
    fun `tiempo extra - start_date en las reglas de horario y no en la pausa`() {
        val extra = now.plusMinutes(90)
        val p = juan.copy(pause = Pause.Until(now.plusHours(3)), schedules = listOf(night), extraUntil = extra)
        val plan = plan(p)
        assertEquals("2026-11-04T23:30:00", plan.section("wrtc_r_noche1_1").text("start_date"))
        assertEquals("2026-11-04T23:30:00", plan.section("wrtc_r_noche1_2").text("start_date"))
        val pause = plan.section("wrtc_r_j1_pausa")
        assertNull(pause.text("start_date"))
        assertEquals("2026-11-05T01:00:00", pause.text("stop_date"))
        assertEquals("2026-11-04T23:30:00", plan.section("wrtc_p_j1").text("extra_until"))
        assertEquals(extra, plan.profiles.single().extraUntil)

        // Con segundos.
        val withSeconds = plan(juan.copy(schedules = listOf(night), extraUntil = now.plusSeconds(30)))
        assertEquals("2026-11-04T22:00:30", withSeconds.section("wrtc_r_noche1_1").text("start_date"))
    }

    @Test
    fun `tiempo extra vencido - se descarta y no se guarda`() {
        for (extra in listOf(now, now.minusMinutes(1), now.minusDays(2))) {
            val plan = plan(juan.copy(schedules = listOf(night), extraUntil = extra))
            assertNull("$extra", plan.section("wrtc_r_noche1_1").text("start_date"))
            assertNull("$extra", plan.section("wrtc_r_noche1_2").text("start_date"))
            assertNull("$extra", plan.section("wrtc_p_j1").text("extra_until"))
            assertNull("$extra", plan.profiles.single().extraUntil)
        }
        // Sin horarios no hay reglas, pero el tiempo extra vigente se guarda.
        assertEquals("2026-11-04T23:00:00", plan(juan.copy(extraUntil = now.plusHours(1))).section("wrtc_p_j1").text("extra_until"))
    }

    // ---------- 7. búsqueda segura y YouTube ----------

    @Test
    fun `busqueda segura sin nivel - registros de Google en la instancia del perfil`() {
        val plan = plan(juan.copy(filter = ContentFilter(safeSearch = true)))

        val host = plan.section("wrtc_hr_j1_g")
        assertEquals("hostrecord", host.type)
        assertEquals("wrtc_dns_j1", host.text("instance"))
        assertEquals(listOf("forcesafesearch.google.com"), host.items("name"))
        assertEquals(listOf("216.239.38.120", "2001:4860:4802:32::78"), host.items("ip"))
        assertEquals("IPv4 e IPv6", listOf(4, 16), host.items("ip").map { InetAddress.getByName(it).address.size })

        assertEquals(187, GOOGLE_SUFFIXES.size)
        assertEquals("sin sufijos repetidos", GOOGLE_SUFFIXES.size, GOOGLE_SUFFIXES.toSet().size)
        val cnames = plan.dhcp.filter { it.type == "cname" }
        assertEquals((cnames.indices).map { "wrtc_cn_j1_g$it" }, cnames.map { it.name })
        for (s in cnames) {
            assertEquals("wrtc_dns_j1", s.text("instance"))
            assertEquals(GOOGLE_SAFE_HOST, s.text("target"))
            assertTrue("un option, no una list: dnsmasq.init lo pega tal cual", s.options["cname"] is UciValue.Text)
            val names = s.text("cname")!!.split(',')
            assertTrue(s.name, names.size in 1..20)
            // dnsmasq.init escribe "cname=<nombres>,<destino>" en una línea.
            val line = "cname=${s.text("cname")},${s.text("target")}"
            assertTrue("${s.name}: ${line.length} bytes", line.toByteArray().size <= 600)
        }
        val names = cnames.flatMap { it.text("cname")!!.split(',') }
        assertEquals(GOOGLE_SUFFIXES.map { "www.google.$it" }, names)
        assertTrue(names.containsAll(listOf("www.google.com", "www.google.com.mx", "www.google.es", "www.google.co.uk")))
        assertTrue(names.all { it.matches(Regex("^[a-z0-9.]+$")) })

        val server = plan.section("wrtc_dns_j1").items("server")
        assertEquals(
            setOf("/bing.com/1.1.1.3", "/bing.com/1.0.0.3", "/duckduckgo.com/1.1.1.3", "/duckduckgo.com/1.0.0.3", "127.0.0.1#53"),
            server.toSet(),
        )
        assertEquals(5, server.size)
        assertEquals("127.0.0.1#53", server.last())
        assertTrue("sin YouTube", plan.dhcp.none { it.name.endsWith("_yt") })
        // Los registros son del DNS (dhcp), no del firewall.
        assertTrue(plan.firewall.none { it.type == "cname" || it.type == "hostrecord" })
    }

    @Test
    fun `busqueda segura con nivel - ya la fuerza el DNS para familias`() {
        for (level in listOf(FilterLevel.Adult, FilterLevel.Strict)) {
            val plan = plan(juan.copy(filter = ContentFilter(level, safeSearch = true)))
            assertTrue("$level", plan.dhcp.none { it.type == "cname" || it.type == "hostrecord" })
            val server = plan.section("wrtc_dns_j1").items("server")
            assertTrue("$level: $server", server.none { it.startsWith("/bing.com/") || it.startsWith("/duckduckgo.com/") })
            assertEquals(level.upstreams() + "/lan/127.0.0.1#53", server)
        }
    }

    @Test
    fun `YouTube restringido - cname a restrict o restrictmoderate con su hostrecord`() {
        val cases = listOf(
            Triple(YoutubeMode.Moderate, "restrictmoderate.youtube.com", listOf("216.239.38.119", "2001:4860:4802:32::77")),
            Triple(YoutubeMode.Strict, "restrict.youtube.com", listOf("216.239.38.120", "2001:4860:4802:32::78")),
        )
        for ((mode, target, ips) in cases) {
            val plan = plan(juan.copy(filter = ContentFilter(youtube = mode)))
            val cname = plan.section("wrtc_cn_j1_yt")
            assertEquals("cname", cname.type)
            assertEquals("wrtc_dns_j1", cname.text("instance"))
            assertEquals(target, cname.text("target"))
            assertEquals(YOUTUBE_NAMES.joinToString(","), cname.text("cname"))
            assertTrue(YOUTUBE_NAMES.containsAll(listOf("www.youtube.com", "m.youtube.com", "youtubei.googleapis.com", "youtube.googleapis.com")))

            val host = plan.section("wrtc_hr_j1_yt")
            assertEquals("hostrecord", host.type)
            assertEquals("wrtc_dns_j1", host.text("instance"))
            assertEquals(listOf(target), host.items("name"))
            assertEquals(ips, host.items("ip"))

            // Sin búsqueda segura no hay nada de Google ni reenvíos.
            assertTrue(plan.dhcp.none { it.name.startsWith("wrtc_cn_j1_g") || it.name == "wrtc_hr_j1_g" })
            assertEquals(listOf("127.0.0.1#53"), plan.section("wrtc_dns_j1").items("server"))
        }
    }

    @Test
    fun `YouTube restringido junto a la busqueda segura y con nivel`() {
        val both = plan(juan.copy(filter = ContentFilter(safeSearch = true, youtube = YoutubeMode.Moderate)))
        val names = both.dhcp.map { it.name }
        assertEquals("nombres de sección únicos", names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf("wrtc_hr_j1_g", "wrtc_cn_j1_g0", "wrtc_hr_j1_yt", "wrtc_cn_j1_yt")))
        // Cloudflare para familias no restringe YouTube: se añade igual.
        val adult = plan(juan.copy(filter = ContentFilter(FilterLevel.Adult, youtube = YoutubeMode.Strict)))
        assertEquals(YOUTUBE_STRICT_HOST, adult.section("wrtc_cn_j1_yt").text("target"))
        assertTrue(adult.dhcp.none { it.name == "wrtc_hr_j1_g" })
    }

    @Test
    fun `YouTube bloqueado del todo - sin registros de modo restringido`() {
        for (mode in listOf(YoutubeMode.Moderate, YoutubeMode.Strict)) {
            val plan = plan(juan.copy(filter = ContentFilter(services = setOf("youtube"), youtube = mode)))
            assertTrue(plan.dhcp.none { it.name.endsWith("_yt") })
            assertTrue(plan.section("wrtc_dns_j1").items("address").contains("/youtube.com/"))
        }
    }

    // ---------- 8. ajustes y campos nuevos guardados en UCI ----------

    @Test
    fun `guardar y volver a leer conserva los campos nuevos y los ajustes`() {
        val config = ParentalConfig(
            profiles = listOf(
                Profile(
                    "ana001", "Ana", listOf(mac3),
                    schedules = listOf(Schedule("s1", setOf(SATURDAY), LocalTime.of(21, 0), LocalTime.of(7, 0))),
                    filter = ContentFilter(youtube = YoutubeMode.Moderate),
                    dnsPort = 5302,
                    cutEpoch = 3,
                ),
                Profile(
                    "k3x9q2", "Juan", listOf(mac1, mac2),
                    pause = Pause.Until(LocalDateTime.of(2026, 11, 5, 19, 30)),
                    filter = ContentFilter(FilterLevel.Adult, setOf("tiktok"), listOf("ejemplo.com"), safeSearch = true, youtube = YoutubeMode.Strict),
                    dnsPort = 5301,
                    extraUntil = LocalDateTime.of(2026, 11, 5, 21, 30, 15),
                    protectRouter = true,
                    cutEpoch = 2,
                    cutWindow = CutWindow(1, LocalDateTime.of(2026, 11, 5, 21, 31, 45)),
                ),
            ),
            settings = ParentalSettings(
                quarantine = true,
                approved = listOf("AA:BB:CC:DD:EE:10", "AA:BB:CC:DD:EE:11"),
                admins = listOf("AA:BB:CC:DD:EE:20", "AA:BB:CC:DD:EE:21"),
            ),
        )
        val sections = configSections(config)
        assertEquals(config, readConfig(sections))

        val juanData = sections.single { it.name == "wrtc_p_k3x9q2" }
        assertEquals("1", juanData.text("safe_search"))
        assertEquals("strict", juanData.text("youtube"))
        assertEquals("2026-11-05T21:30:15", juanData.text("extra_until"))
        assertEquals("1", juanData.text("protect_router"))
        assertEquals("2", juanData.text("cut_epoch"))
        assertEquals("1", juanData.text("cut_window"))
        assertEquals("2026-11-05T21:31:45", juanData.text("cut_until"))
        val anaData = sections.single { it.name == "wrtc_p_ana001" }
        assertEquals("moderate", anaData.text("youtube"))
        for (key in listOf("safe_search", "extra_until", "protect_router", "cut_window", "cut_until")) assertNull(key, anaData.text(key))

        val settings = sections.single { it.name == SETTINGS_SECTION }
        assertEquals(SETTINGS_TYPE, settings.type)
        assertEquals("1", settings.text("quarantine"))
        assertEquals(UciValue.Items(listOf("AA:BB:CC:DD:EE:10", "AA:BB:CC:DD:EE:11")), settings.options["approved"])
        assertEquals(UciValue.Items(listOf("AA:BB:CC:DD:EE:20", "AA:BB:CC:DD:EE:21")), settings.options["admin"])

        // Solo una de las tres cosas.
        val single = listOf(
            ParentalSettings(quarantine = true),
            ParentalSettings(approved = listOf("AA:BB:CC:DD:EE:10")),
            ParentalSettings(admins = listOf("AA:BB:CC:DD:EE:20")),
        )
        for (s in single) assertEquals(s, readConfig(configSections(ParentalConfig(emptyList(), s))).settings)
        val onlyAdmins = configSections(ParentalConfig(emptyList(), ParentalSettings(admins = listOf("AA:BB:CC:DD:EE:20")))).single()
        assertEquals(setOf("admin"), onlyAdmins.options.keys)
    }

    @Test
    fun `los ajustes por defecto no escriben seccion`() {
        val sections = configSections(ParentalConfig(listOf(juan)))
        assertTrue(sections.none { it.name == SETTINGS_SECTION || it.type == SETTINGS_TYPE })
        assertEquals(ParentalSettings(), readSettings(sections))
        // Un perfil sin nada nuevo tampoco escribe sus opciones.
        val profile = sections.single { it.name == "wrtc_p_j1" }
        val keys = listOf("safe_search", "youtube", "extra_until", "protect_router", "cut_epoch", "cut_window", "cut_until")
        for (key in keys) assertNull(key, profile.text(key))
    }

    @Test
    fun `al leer se descartan valores no validos`() {
        val sections = listOf(
            uciSection(
                SETTINGS_SECTION, SETTINGS_TYPE,
                "quarantine" to "1",
                "approved" to listOf("aa:bb:cc:dd:ee:10", "basura", "FF:FF:FF:FF:FF:FF", "AA:BB:CC:DD:EE:10", "aa-bb-cc-dd-ee-0f"),
                "admin" to listOf("aa:bb:cc:dd:ee:21", "01:00:5E:00:00:01", "AA:BB:CC:DD:EE:20", "aa:bb:cc:dd:ee:21"),
            ),
            uciSection(
                "wrtc_p_j1", PROFILE_TYPE,
                "name" to "Juan",
                "safe_search" to "si",
                "youtube" to "total",
                "extra_until" to "mañana",
                "protect_router" to "0",
                "cut_epoch" to "7",
                "cut_window" to "2",
                "cut_until" to "luego",
            ),
            uciSection("wrtc_p_a2", PROFILE_TYPE, "name" to "Ana", "cut_epoch" to "1", "cut_window" to "4", "cut_until" to "2026-11-05T21:31:45"),
            uciSection("wrtc_p_b3", PROFILE_TYPE, "name" to "Beto", "cut_epoch" to "1", "cut_until" to "2026-11-05T21:31:45"),
        )
        val config = readConfig(sections)
        assertEquals(
            ParentalSettings(
                quarantine = true,
                approved = listOf("AA:BB:CC:DD:EE:0F", "AA:BB:CC:DD:EE:10"),
                admins = listOf("AA:BB:CC:DD:EE:20", "AA:BB:CC:DD:EE:21"),
            ),
            config.settings,
        )
        val p = config.profiles.single { it.id == "j1" }
        assertFalse(p.filter.safeSearch)
        assertEquals(YoutubeMode.Off, p.filter.youtube)
        assertNull(p.extraUntil)
        assertFalse(p.protectRouter)
        assertEquals(0, p.cutEpoch)
        assertNull("sin hora no hay ventana", p.cutWindow)
        assertNull("marca fuera de rango", config.profiles.single { it.id == "a2" }.cutWindow)
        assertNull("sin marca no hay ventana", config.profiles.single { it.id == "b3" }.cutWindow)

        // La sección de ajustes solo cuenta con su nombre y su tipo.
        assertEquals(ParentalSettings(), readSettings(listOf(uciSection("cfg123", SETTINGS_TYPE, "quarantine" to "1"))))
        assertEquals(ParentalSettings(), readSettings(listOf(uciSection(SETTINGS_SECTION, PROFILE_TYPE, "quarantine" to "1"))))
        assertEquals(ParentalSettings(), readSettings(listOf(uciSection(SETTINGS_SECTION, SETTINGS_TYPE, "quarantine" to "0"))))
    }

    @Test
    fun `validateConfig rechaza una MAC aprobada o de administrador sin normalizar`() {
        val ok = ParentalConfig(listOf(juan), ParentalSettings(quarantine = true, approved = listOf(mac3), admins = listOf("AA:BB:CC:DD:EE:20")))
        validateConfig(ok)
        for (bad in listOf("aa:bb:cc:dd:ee:03", "AA-BB-CC-DD-EE-03", "basura", "FF:FF:FF:FF:FF:FF")) {
            for (settings in listOf(ParentalSettings(approved = listOf(bad)), ParentalSettings(admins = listOf(bad)))) {
                val e = assertThrows(bad, IllegalArgumentException::class.java) { validateConfig(ok.copy(settings = settings)) }
                assertTrue(e.message!!, e.message!!.contains("MAC no válida"))
            }
        }
        // También valida los perfiles.
        assertThrows(IllegalArgumentException::class.java) {
            validateConfig(ParentalConfig(listOf(juan.copy(cutEpoch = 4))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateConfig(ParentalConfig(listOf(juan.copy(extraUntil = now.withNano(5)))))
        }
    }

    @Test
    fun `validateProfiles - la ventana de corte tiene una marca valida distinta de la epoca y segundos exactos`() {
        val until = now.plusSeconds(90)
        validateProfiles(listOf(filtered.copy(cutEpoch = 2, cutWindow = CutWindow(3, until))))
        validateProfiles(listOf(filtered.copy(cutEpoch = 0, cutWindow = CutWindow(1, until))))
        val bad = mapOf(
            "marca 0" to CutWindow(0, until),
            "marca 4" to CutWindow(4, until),
            "la misma que la época" to CutWindow(2, until),
            "con nanosegundos" to CutWindow(3, until.withNano(1)),
        )
        for ((what, window) in bad) {
            val e = assertThrows(what, IllegalArgumentException::class.java) {
                validateProfiles(listOf(filtered.copy(cutEpoch = 2, cutWindow = window)))
            }
            assertTrue(e.message!!, e.message!!.contains("Ventana de corte"))
        }
    }

    @Test
    fun `aprobados para la cuarentena - los de los ajustes, los de los perfiles y los administradores`() {
        val config = ParentalConfig(
            listOf(juan, Profile("a2", "Ana", emptyList())),
            ParentalSettings(approved = listOf(mac3, mac1), admins = listOf("AA:BB:CC:DD:EE:20", mac2)),
        )
        assertEquals(setOf(mac1, mac2, mac3, "AA:BB:CC:DD:EE:20"), config.approvedMacs)
    }

    // ---------- 9. copia de seguridad ----------

    @Test
    fun `copia de seguridad - ida y vuelta`() {
        val config = ParentalConfig(
            profiles = listOf(
                Profile("ana001", "Ana", emptyList(), pause = Pause.Indefinite),
                Profile(
                    "k3x9q2", "Júan 😀", listOf(mac1, mac2),
                    schedules = listOf(Schedule("s1", setOf(WEDNESDAY), LocalTime.of(21, 0), LocalTime.of(7, 0), enabled = false)),
                    filter = ContentFilter(FilterLevel.Strict, setOf("tiktok", "x"), listOf("ejemplo.com"), safeSearch = true, youtube = YoutubeMode.Moderate),
                    dnsPort = 5301,
                    extraUntil = LocalDateTime.of(2026, 11, 5, 21, 30),
                    protectRouter = true,
                    cutEpoch = 3,
                    cutWindow = CutWindow(1, LocalDateTime.of(2026, 11, 5, 21, 31, 30)),
                ),
            ),
            settings = ParentalSettings(quarantine = true, approved = listOf(mac3), admins = listOf("AA:BB:CC:DD:EE:20")),
        )
        val json = backupJson(config)
        assertEquals(config, parseBackup(json))
        // Solo ajustes también vale (también solo los administradores).
        val onlySettings = ParentalConfig(emptyList(), ParentalSettings(approved = listOf(mac3)))
        assertEquals(onlySettings, parseBackup(backupJson(onlySettings)))
        val onlyAdmins = ParentalConfig(emptyList(), ParentalSettings(admins = listOf("AA:BB:CC:DD:EE:20")))
        assertEquals(onlyAdmins, parseBackup(backupJson(onlyAdmins)))
    }

    @Test
    fun `copia de seguridad vacia o que no se entiende`() {
        for (json in listOf(null, "", "   ", "basura", "[]", "{}", "[1, \"x\", null, []]", "{\"name\":\"wrtc_p_a1\"}", "[{]")) {
            assertNull(json, parseBackup(json))
        }
        assertNull("sin perfiles y con ajustes por defecto", parseBackup(backupJson(ParentalConfig(emptyList()))))
    }

    @Test
    fun `copia de seguridad - ignora secciones ajenas o de tipos no permitidos`() {
        val json = """
            [
              {"name":"wrtc_p_a1","type":"wrtcontrol_profile","values":{"name":"Ana","mac":["AA:BB:CC:DD:EE:03"],"protect_router":"1","raro":{"x":1}}},
              {"name":"cfg0abc","type":"wrtcontrol_profile","values":{"name":"Ajeno"}},
              {"name":"wrtc_p_b2","type":"rule","values":{"name":"Regla"}},
              {"name":"wrtc_r_a1_pausa","type":"rule","values":{"name":"WrtControl · Ana · pausa","target":"MARK"}},
              {"name":"wrtc_settings","type":"zone","values":{"quarantine":"1"}},
              {"name":"wrtc_p_c3","type":"wrtcontrol_profile"},
              {"name":5,"type":"wrtcontrol_profile","values":{"name":"Número"}},
              {"type":"wrtcontrol_profile","values":{"name":"Sin nombre"}},
              "texto",
              {"name":"wrtc_s_s1","type":"wrtcontrol_schedule","values":{"profile":"a1","days":"Monday","start":"21:00","stop":"07:00","enabled":null}}
            ]
        """.trimIndent()
        val config = parseBackup(json)!!
        assertEquals(ParentalSettings(), config.settings)
        val ana = config.profiles.single()
        assertEquals("a1", ana.id)
        assertEquals("Ana", ana.name)
        assertEquals(listOf(mac3), ana.macs)
        assertTrue(ana.protectRouter)
        assertEquals(listOf("s1"), ana.schedules.map { it.id })
    }

    // ---------- 10. nslookup de BusyBox ----------

    @Test
    fun `nslookup - CNAME y varias direcciones, sin la del servidor`() {
        val out = """
            Server:		127.0.0.1
            Address:	127.0.0.1:5301

            Non-authoritative answer:
            www.google.com	canonical name = forcesafesearch.google.com
            Name:	forcesafesearch.google.com
            Address: 216.239.38.120
            Name:	forcesafesearch.google.com
            Address: 216.239.38.121
            Name:	forcesafesearch.google.com
            Address: 216.239.38.120

        """.trimIndent()
        assertEquals(
            Resolution(Lookup.Resolved, listOf("216.239.38.120", "216.239.38.121"), cnames = listOf("forcesafesearch.google.com")),
            parseNslookup(out + "\n"),
        )
    }

    @Test
    fun `nslookup - los CNAME de la respuesta, en cadena y sin repetir`() {
        // Como responde el DNS para familias a www.bing.com con la búsqueda segura forzada.
        val bing = "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:5301\n\nNon-authoritative answer:\n" +
            "www.bing.com\tcanonical name = strict.bing.com\n" +
            "strict.bing.com\tcanonical name = strict-bing-com.a-0001.a-msedge.net\n" +
            "Name:\tstrict-bing-com.a-0001.a-msedge.net\nAddress: 150.171.28.16\n\n" +
            "www.bing.com\tcanonical name = strict.bing.com\n" + // la respuesta AAAA repite la cadena
            "Name:\tstrict-bing-com.a-0001.a-msedge.net\nAddress: 150.171.28.16\n"
        val r = parseNslookup(bing)
        assertEquals(Lookup.Resolved, r.status)
        assertEquals(listOf("strict.bing.com", "strict-bing-com.a-0001.a-msedge.net"), r.cnames)
        assertTrue(BING_STRICT_HOST in r.cnames)
        assertEquals(listOf("150.171.28.16"), r.addresses)
        // Sin CNAME, la lista va vacía; con fin de línea de Windows también se leen.
        assertTrue(parseNslookup("Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:53\n\nName:\topenwrt.org\nAddress: 139.59.209.225\n").cnames.isEmpty())
        assertEquals(listOf("strict.bing.com"), parseNslookup(bing.replace("\n", "\r\n")).cnames.take(1))
        assertTrue(parseNslookup("** server can't find www.bing.com: NXDOMAIN\n").cnames.isEmpty())
    }

    @Test
    fun `nslookup - IPv6 y fin de linea de Windows`() {
        val out = "Server:\t\t127.0.0.1\r\nAddress:\t127.0.0.1#53\r\n\r\nName:\trestrict.youtube.com\r\nAddress: 2001:4860:4802:32::78\r\n"
        assertEquals(Resolution(Lookup.Resolved, listOf("2001:4860:4802:32::78")), parseNslookup(out))
    }

    @Test
    fun `nslookup - NXDOMAIN`() {
        val out = """
            Server:		127.0.0.1
            Address:	127.0.0.1:5301

            ** server can't find use-application-dns.net: NXDOMAIN
        """.trimIndent()
        assertEquals(Resolution(Lookup.NxDomain), parseNslookup(out + "\n"))
        // También si llega por stderr.
        assertEquals(Lookup.NxDomain, parseNslookup("\n** server can't find x.example: NXDOMAIN\n").status)
    }

    @Test
    fun `nslookup - sin respuesta es Failed aunque este la direccion del servidor`() {
        val noAnswer = """
            Server:		127.0.0.1
            Address:	127.0.0.1:5301

            Non-authoritative answer:
            *** Can't find example.com: No answer
        """.trimIndent()
        assertEquals(Resolution(Lookup.Failed), parseNslookup(noAnswer))
        assertEquals(Resolution(Lookup.Failed), parseNslookup("\n;; connection timed out; no servers could be reached\n"))
        assertEquals(Resolution(Lookup.Failed), parseNslookup("Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:53\n\n** server can't find x: SERVFAIL\n"))
        assertEquals(Resolution(Lookup.Failed), parseNslookup(""))
    }

    @Test
    fun `nslookup - nunca devuelve la direccion del servidor como respuesta`() {
        val out = "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:5301\n\nName:\topenwrt.org\nAddress: 139.59.209.225\n"
        val r = parseNslookup(out)
        assertEquals(Lookup.Resolved, r.status)
        assertEquals(listOf("139.59.209.225"), r.addresses)
        assertFalse(r.addresses.any { it.startsWith("127.0.0.1") })
    }
}
