package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.ubus.SessionAcl
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {

    // Los permisos de root en un OpenWrt 25.12 con LuCI y los cuatro paquetes opcionales (resumidos).
    private val fullAcl = UbusJson.decodeFromString<SessionAcl>(
        """{
          "access-group": {"luci-base": ["read","write"], "luci-app-firewall": ["read","write"], "luci-app-ddns": ["read","write"],
            "luci-app-sqm": ["read","write"], "luci-app-upnp": ["read","write"], "luci-proto-wireguard": ["read","write"],
            "luci-app-package-manager": ["read","write"], "unauthenticated": ["read"]},
          "ubus": {"uci": ["get","set","commit","apply","confirm"], "luci": ["getFeatures","setPassword"], "hostapd.*": ["del_client"],
            "luci.ddns": ["get_services_status"], "session": ["access","login"]},
          "uci": {"firewall": ["read","write"], "dhcp": ["read","write"], "network": ["read","write"], "ddns": ["read","write"],
            "sqm": ["read","write"], "upnpd": ["read","write"], "system": ["read","write"]},
          "file": {"/etc/luci-uploads/*": ["write"], "/usr/libexec/package-manager-call install *": ["exec"],
            "/usr/libexec/package-manager-call list-installed": ["exec"], "/sbin/ip -[46] neigh show": ["exec"]},
          "cgi-io": {"exec": ["read","write"]}
        }""",
    )

    private val dnsmasq = LuciFeatures(firewall4 = true, wifi = true, ipv6 = true, dnsmasq = JsonObject(emptyMap()))

    private fun caps(
        acl: SessionAcl = fullAcl,
        features: LuciFeatures? = dnsmasq,
        objects: Set<String>? = setOf("uci", "luci", "luci.wireguard", "luci.ddns", "dnsmasq"),
        freeKiB: Long? = 60_000,
        memory: Long = 512L * 1024 * 1024,
        radios: List<Radio> = emptyList(),
    ) = Capabilities("25.12.6", "mediatek/filogic", memory, freeKiB, objects, acl, features, radios)

    /** El mismo ACL sin lo que trae un paquete de LuCI: su grupo y lo que deja escribir. */
    private fun SessionAcl.without(group: String, config: String? = null) =
        copy(groups = groups - group, uci = if (config == null) uci else uci - config)

    @Test
    fun `con todo instalado, todo disponible`() {
        val caps = caps()
        Feature.entries.forEach { assertEquals(it.name, Availability.Ready, caps.availability(it)) }
        assertTrue(caps.canInstallPackages)
    }

    @Test
    fun `lo que falta se instalara si LuCI deja instalar paquetes`() {
        val caps = caps(acl = fullAcl.without("luci-app-ddns", "ddns").without("luci-app-sqm", "sqm"))
        assertEquals(Availability.Installable(listOf("luci-app-ddns")), caps.availability(Feature.DynamicDns))
        assertEquals(Availability.Installable(listOf("luci-app-sqm")), caps.availability(Feature.Sqm))
        assertEquals(Availability.Ready, caps.availability(Feature.Upnp))
    }

    @Test
    fun `WireGuard - vale su grupo de permisos o su objeto de ubus`() {
        val noGroup = fullAcl.without("luci-proto-wireguard")
        assertEquals(Availability.Ready, caps(acl = noGroup).availability(Feature.RemoteAccess))
        assertEquals(Availability.Ready, caps(objects = setOf("uci")).availability(Feature.RemoteAccess))
        assertEquals(
            Availability.Installable(listOf("luci-proto-wireguard")),
            caps(acl = noGroup, objects = setOf("uci")).availability(Feature.RemoteAccess),
        )
        // Si no se pudo preguntar qué objetos hay, decide el grupo.
        assertEquals(Availability.Installable(listOf("luci-proto-wireguard")), caps(acl = noGroup, objects = null).availability(Feature.RemoteAccess))
    }

    @Test
    fun `sin permiso para instalar paquetes, lo que falta no es compatible y dice que instalar`() {
        val acl = fullAcl.without("luci-app-upnp", "upnpd").copy(file = fullAcl.file.filterKeys { "package-manager" !in it })
        val caps = caps(acl = acl)
        assertFalse(caps.canInstallPackages)
        val upnp = caps.availability(Feature.Upnp) as Availability.Unsupported
        assertEquals(
            "Falta luci-app-upnp y este router no deja instalar paquetes desde la app. Instálalo desde LuCI (Sistema → Software).",
            upnp.reason.es,
        )
        assertEquals(Availability.Ready, caps.availability(Feature.DynamicDns))
    }

    @Test
    fun `con opkg tambien se puede instalar`() {
        val acl = fullAcl.copy(file = mapOf("/usr/libexec/opkg-call install *" to setOf("exec"), "/etc/luci-uploads/*" to setOf("write")))
        assertTrue(caps(acl = acl).canInstallPackages)
    }

    @Test
    fun `sin sitio en el router no se ofrece instalar`() {
        val acl = fullAcl.without("luci-app-sqm", "sqm")
        val full = caps(acl = acl, freeKiB = 300).availability(Feature.Sqm) as Availability.Unsupported
        assertEquals("Falta luci-app-sqm y no queda sitio en el router para instalarlo.", full.reason.es)
        // Si no se sabe cuánto queda, se ofrece: el instalador avisará si no cabe.
        assertEquals(Availability.Installable(listOf("luci-app-sqm")), caps(acl = acl, freeKiB = null).availability(Feature.Sqm))
    }

    @Test
    fun `cortafuegos antiguo - ni perfiles ni filtro, y dice que version hace falta`() {
        val caps = caps(features = dnsmasq.copy(firewall4 = false))
        val parental = caps.availability(Feature.Parental) as Availability.Unsupported
        assertEquals("Este router usa el cortafuegos antiguo (iptables). Hace falta OpenWrt 22.03 o posterior.", parental.reason.es)
        assertEquals(parental, caps.availability(Feature.ContentFilter))
        // Lo demás no depende del cortafuegos.
        assertEquals(Availability.Ready, caps.availability(Feature.DynamicDns))
    }

    @Test
    fun `lo que no se pudo preguntar no se da por malo`() {
        val caps = caps(features = null)
        assertEquals(Availability.Ready, caps.availability(Feature.Parental))
        assertEquals(Availability.Ready, caps.availability(Feature.ContentFilter))
    }

    @Test
    fun `sin permiso sobre el cortafuegos o sobre su fichero de reglas, el control parental explica que falta`() {
        val noConfig = caps(acl = fullAcl.copy(uci = fullAcl.uci - "firewall")).availability(Feature.Parental) as Availability.Unsupported
        assertTrue(noConfig.reason.es, "luci-app-firewall" in noConfig.reason.es)
        val noFile = caps(acl = fullAcl.copy(file = fullAcl.file - "/etc/luci-uploads/*")).availability(Feature.Parental)
        assertEquals(noConfig, noFile)
    }

    @Test
    fun `sin dnsmasq hay perfiles pero no filtro de contenido`() {
        val caps = caps(features = dnsmasq.copy(dnsmasq = null))
        assertEquals(Availability.Ready, caps.availability(Feature.Parental))
        val filter = caps.availability(Feature.ContentFilter) as Availability.Unsupported
        assertTrue(filter.reason.es, "dnsmasq" in filter.reason.es)
        assertTrue(caps(acl = fullAcl.copy(uci = fullAcl.uci - "dhcp")).availability(Feature.ContentFilter) is Availability.Unsupported)
    }

    @Test
    fun `poca memoria`() {
        assertTrue(caps(memory = 60L * 1024 * 1024).lowMemory)
        assertFalse(caps(memory = 128L * 1024 * 1024).lowMemory)
        assertFalse("sin dato no se avisa", caps(memory = 0).lowMemory)
    }

    // ---------- lo que cuenta el router ----------

    @Test
    fun `getFeatures - valores nulos y partes que no vienen`() {
        val features = UbusJson.decodeFromString<LuciFeatures>(
            """{"apk":true,"bonding":null,"firewall":true,"firewall4":true,"ipv6":true,"wifi":true,"zram":false,
               "dnsmasq":{"dhcp":true,"nftset":false},"odhcpd":{"dhcpv6":true}}""",
        )
        assertTrue(features.firewall4 && features.wifi && features.hasDnsmasq)
        val bare = UbusJson.decodeFromString<LuciFeatures>("""{"firewall4":null,"wifi":false}""")
        assertFalse(bare.firewall4 || bare.hasDnsmasq)
    }

    @Test
    fun `radios - banda, generacion y redes`() {
        val radios = parseRadios(
            UbusJson.decodeFromString(
                """{
                  "radio1": {"up": true, "disabled": false, "config": {"band": "5g", "htmode": "HE80"},
                    "iwinfo": {"hwmodes": ["n","ac","ax"]}, "interfaces": [{"section": "default_radio1"}]},
                  "radio0": {"up": true, "disabled": true, "config": {"band": "2g"}, "iwinfo": {"hwmodes": ["b","g","n","ax"]}, "interfaces": []}
                }""",
            ),
        )
        assertEquals(listOf("radio0", "radio1"), radios.map { it.name })
        assertEquals(Radio("radio0", WifiBand.Band2, setOf("b", "g", "n", "ax"), enabled = false, networks = 0), radios[0])
        assertEquals(WifiBand.Band5 to 6, radios[1].band to radios[1].generation)
        assertEquals(1, radios[1].networks)
        assertEquals("Wi-Fi: 2,4 GHz + 5 GHz · Wi-Fi 6", wifiSummary(radios).es)
    }

    @Test
    fun `radios - versiones sin band, y lo que no se sabe`() {
        val old = parseRadios(UbusJson.decodeFromString("""{"radio0": {"config": {"hwmode": "11a"}}, "radio1": {"config": {"hwmode": "11g"}}}"""))
        assertEquals(listOf(WifiBand.Band5, WifiBand.Band2), old.map { it.band })
        assertEquals("Wi-Fi: 2,4 GHz + 5 GHz", wifiSummary(old).es)
        val unknown = parseRadios(UbusJson.decodeFromString("""{"radio0": {}}"""))
        assertEquals("Wi-Fi: radio0", wifiSummary(unknown).es)
        assertEquals("Este router no tiene Wi-Fi.", wifiSummary(emptyList()).es)
    }

    @Test
    fun `los titulos y los motivos estan en palabras`() {
        Feature.entries.forEach { assertTrue(it.title.es.isNotBlank()) }
        assertEquals("Entrar desde fuera de casa", Feature.RemoteAccess.title.es)
    }
}
