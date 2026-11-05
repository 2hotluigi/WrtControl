package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.uciSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DnsFilterTest {

    private val now = LocalDateTime.of(2026, 11, 4, 22, 0)
    private val macs = listOf("AA:BB:CC:DD:EE:01")
    private val juan = Profile("j1", "Juan", macs)

    private fun RouterPlan.section(name: String) = (firewall + dhcp).single { it.name == name }

    @Test
    fun `normaliza lo que escribe el usuario`() {
        assertEquals("tiktok.com", normalizeDomain("https://www.TikTok.com/foryou?lang=es"))
        assertEquals("example.com", normalizeDomain("*.example.com"))
        assertEquals("example.com", normalizeDomain("example.com:8080"))
        assertEquals("sub.example.co.uk", normalizeDomain(" sub.example.co.uk. "))
        assertTrue(normalizeDomain("ejémplo.mx")!!.startsWith("xn--"))
        assertNull("un dominio de primer nivel bloquearía demasiado", normalizeDomain("com"))
        assertNull(normalizeDomain("192.168.1.1"))
        assertNull(normalizeDomain("mal dominio.com"))
        assertNull(normalizeDomain("-guion.com"))
        assertNull(normalizeDomain("a..com"))
        assertNull(normalizeDomain(""))
    }

    @Test
    fun `filtro para adultos - servidor DNS propio, redireccion y corte del DNS cifrado`() {
        val profile = juan.copy(filter = ContentFilter(FilterLevel.Adult, setOf("tiktok"), listOf("ejemplo.com")))
        val plan = compileRouter(listOf(profile), now)

        assertEquals(mapOf("wrtc_dns_j1" to 5301), plan.dnsInstances)
        val dns = plan.section("wrtc_dns_j1")
        assertEquals("dnsmasq", dns.type)
        assertEquals("5301", dns.text("port"))
        assertEquals("1", dns.text("noresolv"))
        assertEquals("0", dns.text("localuse"))
        assertEquals(listOf("1.1.1.3", "1.0.0.3", "/lan/127.0.0.1#53"), dns.items("server"))
        assertEquals("1", dns.text("rebind_protection"))
        assertEquals(listOf("lan"), dns.items("rebind_domain"))
        // TTL corto: al endurecer el filtro, la caché de los dispositivos caduca antes del segundo corte.
        assertEquals("60", dns.text("max_ttl"))
        val blocked = dns.items("address")
        assertTrue(blocked.containsAll(listOf("/ejemplo.com/", "/tiktok.com/", "/tiktokcdn.com/", "/$CANARY_DOMAIN/", "/mask.icloud.com/")))
        assertEquals("sin duplicados", blocked.toSet().size, blocked.size)

        // Nunca sirve DHCP: las secciones dhcp sin "instance" se aplican a todas las instancias.
        val noDhcp = plan.section("wrtc_nodhcp_j1_0")
        assertEquals(listOf("wrtc_dns_j1", "lan", "1"), listOf(noDhcp.text("instance"), noDhcp.text("interface"), noDhcp.text("ignore")))

        val redirect = plan.section("wrtc_d_j1_0")
        assertEquals("redirect", redirect.type)
        assertEquals("WrtControl · Juan · DNS", redirect.text("name"))
        assertEquals(listOf("lan", "53", "5301", "tcp udp", "any", "DNAT"), listOf("src", "src_dport", "dest_port", "proto", "family", "target").map { redirect.text(it) })
        assertEquals(macs, redirect.items("src_mac"))

        val dot = plan.section("wrtc_r_j1_dot")
        assertEquals("853", dot.text("dest_port"))
        assertEquals("tcp udp", dot.text("proto"))
        assertEquals("MARK", dot.text("target"))
        assertTrue(plan.ruleLabels.containsAll(listOf("WrtControl · Juan · DNS", "WrtControl · Juan · DNS cifrado")))

        // El perfil guarda el filtro y su puerto.
        val data = plan.section("wrtc_p_j1")
        assertEquals("adult", data.text("filter"))
        assertEquals(listOf("tiktok"), data.items("block_service"))
        assertEquals(listOf("ejemplo.com"), data.items("block_domain"))
        assertEquals("5301", data.text("dns_port"))
        assertEquals("con puerto y época de corte", profile.copy(dnsPort = 5301, cutEpoch = 1), readProfiles(plan.firewall).single())
    }

    @Test
    fun `sin nivel, solo sitios - sube a la instancia principal`() {
        val plan = compileRouter(listOf(juan.copy(filter = ContentFilter(domains = listOf("ejemplo.com")))), now)
        val dns = plan.section("wrtc_dns_j1")
        assertEquals(listOf("127.0.0.1#53"), dns.items("server"))
        assertEquals("0", dns.text("rebind_protection"))
        assertNull(dns.text("rebind_domain"))
    }

    @Test
    fun `todas las instancias de los perfiles limitan el TTL por debajo de la ventana del corte`() {
        val filters = listOf(
            ContentFilter(FilterLevel.Adult),
            ContentFilter(FilterLevel.Strict),
            ContentFilter(domains = listOf("ejemplo.com")),
            ContentFilter(services = setOf("tiktok")),
            ContentFilter(safeSearch = true),
            ContentFilter(youtube = YoutubeMode.Strict),
        )
        for (f in filters) {
            assertEquals("$f", DNS_MAX_TTL.toString(), compileRouter(listOf(juan.copy(filter = f)), now).section("wrtc_dns_j1").text("max_ttl"))
        }
        assertEquals(60, DNS_MAX_TTL)
        assertTrue(DNS_MAX_TTL < CUT_WINDOW_SECONDS)
    }

    @Test
    fun `estricto usa CleanBrowsing`() {
        val plan = compileRouter(listOf(juan.copy(filter = ContentFilter(FilterLevel.Strict))), now)
        assertEquals(listOf("185.228.168.168", "185.228.169.168", "/lan/127.0.0.1#53"), plan.section("wrtc_dns_j1").items("server"))
    }

    @Test
    fun `sin filtro o sin dispositivos no hay servidor DNS`() {
        val off = compileRouter(listOf(juan.copy(dnsPort = 5301)), now)
        assertTrue(off.dhcp.isEmpty())
        assertTrue(off.dnsInstances.isEmpty())
        assertNull("se libera el puerto", off.profiles.single().dnsPort)

        val noDevices = compileRouter(listOf(juan.copy(macs = emptyList(), filter = ContentFilter(FilterLevel.Adult))), now)
        assertTrue(noDevices.dhcp.isEmpty())
        assertTrue(noDevices.firewall.none { it.type == "redirect" })
    }

    @Test
    fun `puertos estables, sin repetir y del rango`() {
        val f = ContentFilter(FilterLevel.Adult)
        val profiles = assignDnsPorts(
            listOf(
                Profile("a", "A", macs, filter = f, dnsPort = 5305),
                Profile("b", "B", listOf("AA:BB:CC:DD:EE:02"), filter = f), // nuevo
                Profile("c", "C", listOf("AA:BB:CC:DD:EE:03"), filter = f, dnsPort = 5305), // repetido
                Profile("d", "D", listOf("AA:BB:CC:DD:EE:04"), filter = f, dnsPort = 80), // fuera de rango
            ),
        )
        assertEquals(listOf(5305, 5301, 5302, 5303), profiles.map { it.dnsPort })
    }

    @Test
    fun `una redireccion por cada zona de la LAN`() {
        val env = RouterEnv(lanZones = listOf("lan", "guest"), dhcpInterfaces = listOf("lan", "guest"))
        val plan = compileRouter(listOf(juan.copy(filter = ContentFilter(FilterLevel.Adult))), now, env)
        assertEquals("WrtControl · Juan · DNS (guest)", plan.section("wrtc_d_j1_1").text("name"))
        assertEquals("guest", plan.section("wrtc_nodhcp_j1_1").text("interface"))
    }

    @Test
    fun `sin zona LAN no se puede filtrar`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            compileRouter(listOf(juan.copy(filter = ContentFilter(FilterLevel.Adult))), now, RouterEnv(lanZones = emptyList()))
        }
        assertTrue(e.message!!.contains("zona LAN"))
    }

    @Test
    fun `entorno leido de la configuracion real`() {
        val firewall = listOf(
            uciSection("cfg1", "zone", "name" to "lan"),
            uciSection("cfg2", "zone", "name" to "wan", "masq" to "1"),
            uciSection("cfg3", "zone", "name" to "guest"),
        )
        val dhcp = listOf(
            uciSection("cfg4", "dnsmasq", "domain" to "casa"),
            uciSection("lan", "dhcp", "interface" to "lan"),
            uciSection("wan", "dhcp", "interface" to "wan", "ignore" to "1"),
            uciSection("otra", "dhcp", "interface" to "iot", "instance" to "otra_instancia"),
            uciSection("wrtc_nodhcp_j1_0", "dhcp", "interface" to "lan", "instance" to "wrtc_dns_j1"),
        )
        val env = routerEnv(firewall, dhcp)
        assertEquals(listOf("lan", "guest"), env.lanZones)
        assertEquals(listOf("lan", "wan"), env.dhcpInterfaces)
        assertEquals("casa", env.localDomain)
    }

    @Test
    fun `catalogo y dominios de escape validos`() {
        assertEquals(SERVICES.size, SERVICES.map { it.id }.toSet().size)
        for (domain in SERVICES.flatMap { it.domains } + BYPASS_DOMAINS) assertEquals(domain, normalizeDomain(domain))
        assertFalse(SERVICES.any { it.domains.isEmpty() })
    }
}
