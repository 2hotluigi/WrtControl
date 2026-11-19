package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.SessionAcl
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El informe del router no puede llevar nada de la gente de la casa (ver DeviceReport.kt). Los
 * datos de ejemplo están llenos de lo que no debe salir, inventado pero con la forma de verdad:
 * IP públicas y prefijos de proveedor, MAC, SSID y clave del Wi-Fi, claves de WireGuard, usuario de
 * PPPoE, DNS dinámico con su token, nombres de perfiles, aparatos y teléfonos.
 */
class DeviceReportTest {

    private val routerKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val phoneKey = "JSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QEFCQ0Q="
    private val presharedKey = "SktMTU5PUFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGk="

    /** Todo lo que no puede aparecer en el informe. */
    private val personal = listOf(
        // Direcciones públicas del proveedor (IPv4 e IPv6) y la que tiene el teléfono fuera.
        "187.190.33.12", "200.33.146.217", "2806:2f0:9021", "2806:2f0:9021:ab12::5", "2806:2f0:aaaa",
        // ULA del sitio y una dirección de enlace con la MAC dentro.
        "fdb9:d8e3:f4ce", "fe80::9683:c4ff:fe12:3456",
        // MAC, con y sin separadores, en mayúsculas y minúsculas.
        "94:83:C4:12:34:56", "94:83:c4:12:34:56", "A8:FE:9D:0D:0B:72", "a8fe9d0d0b72",
        // Wi-Fi y nombre del router.
        "Familia Pérez", "contraseña-wifi-123", "Casa-de-Perez",
        // PPPoE.
        "cliente123@isp.example", "pppoe-secreta",
        // WireGuard.
        routerKey, phoneKey, presharedKey, "Teléfono de Ana", "perez.example.net",
        // DNS dinámico.
        "perez.duckdns.org", "token-ddns-0123456789",
        // Perfiles y aparatos.
        "Ana", "tele-salon", "tele_salon", "Tele de Ana",
        // El identificador DHCPv6 del router.
        "000404d5ca7df6d44afb96fc708158a5e06a",
    )

    private fun parse(text: String): JsonElement = UbusJson.parseToJsonElement(text)

    private val board = parse("""{"hostname":"Casa-de-Perez","model":"GL.iNet GL-MT6000","board_name":"glinet,gl-mt6000","release":{"version":"25.12.6","description":"OpenWrt 25.12.6"}}""")

    private val interfaces = parse(
        """{"interface":[
            {"interface":"lan","up":true,"proto":"static","device":"br-lan",
             "ipv4-address":[{"address":"192.168.8.1","mask":24}],
             "ipv6-prefix-assignment":[{"address":"fdb9:d8e3:f4ce::","mask":60,"local-address":{"address":"fdb9:d8e3:f4ce::1","mask":60}}]},
            {"interface":"wan","up":true,"proto":"pppoe","l3_device":"pppoe-wan",
             "ipv4-address":[{"address":"187.190.33.12","mask":32}],"dns-server":["200.33.146.217"],
             "route":[{"target":"0.0.0.0","mask":0,"nexthop":"187.190.33.1"}]},
            {"interface":"wan6","up":true,"proto":"dhcpv6","l3_device":"pppoe-wan",
             "ipv6-address":[{"address":"2806:2f0:9021:ab12::5","mask":64}],
             "ipv6-prefix":[{"address":"2806:2f0:9021:ab00::","mask":56}]}
        ]}""",
    )

    private val devices = parse(
        """{"br-lan":{"macaddr":"94:83:C4:12:34:56","up":true},"eth1":{"macaddr":"94:83:c4:12:34:57","up":true,"speed":"1000F"},
            "pppoe-wan":{"up":true,"ipv6":{"link-local":"fe80::9683:c4ff:fe12:3456"}}}""",
    )

    private val wireless = parse(
        """{"radio0":{"up":true,"config":{"band":"2g"},"interfaces":[{"section":"default_radio0","ifname":"phy0-ap0",
              "config":{"ssid":"Familia Pérez","key":"contraseña-wifi-123","network":["lan"]},"iwinfo":{"ssid":"Familia Pérez","bssid":"94:83:C4:12:34:58"}}]},
            "radio1":{"up":true,"config":{"band":"5g"},"interfaces":[{"section":"default_radio1","ifname":"phy1-ap0",
              "config":{"ssid":"Familia Pérez","key":"contraseña-wifi-123","network":["lan"]}}]}}""",
    )

    private val wg = parse(
        """{"wrtcvpn":{"public_key":"$routerKey","listen_port":51820,"peers":{"$phoneKey":{"name":"Teléfono de Ana",
            "endpoint":"[2806:2f0:aaaa:1::9]:41641","allowed_ips":["10.77.0.2/32"],"latest_handshake":1795000000}}}}""",
    )

    private fun uci(vararg sections: String) = parse("""{"values":{${sections.joinToString(",")}}}""")

    private val raw = RawReport(
        objects = Result.success(setOf("system", "network.interface", "luci-rpc", "uci")),
        acl = Result.success(SessionAcl(uci = mapOf("network" to setOf("read", "write")))),
        calls = listOf(
            ReportCall("system", "board") to Result.success(board),
            ReportCall("network.interface", "dump") to Result.success(interfaces),
            ReportCall("network.device", "status") to Result.success(devices),
            ReportCall("luci-rpc", "getWirelessDevices") to Result.success(wireless),
            ReportCall("luci.wireguard", "getWgInstances", personalNames = true) to Result.success(wg),
            ReportCall("luci.upnp", "get_status") to Result.failure(UbusException.AccessDenied("luci.upnp")),
        ),
        uci = mapOf(
            "network" to Result.success(
                uci(
                    """"wan":{".type":"interface",".name":"wan","proto":"pppoe","username":"cliente123@isp.example","password":"pppoe-secreta","macaddr":"94:83:C4:12:34:57"}""",
                    """"wrtcvpn":{".type":"interface",".name":"wrtcvpn","proto":"wireguard","private_key":"$presharedKey","addresses":["10.77.0.1/24"]}""",
                    """"wrtcvpn_p_1":{".type":"wireguard_wrtcvpn",".name":"wrtcvpn_p_1","description":"WrtControl: Teléfono de Ana","public_key":"$phoneKey","preshared_key":"$presharedKey","endpoint_host":"perez.example.net"}""",
                    """"lan":{".type":"interface",".name":"lan","proto":"static","ipaddr":"192.168.8.1","ip6assign":"60"}""",
                    """"globals":{".type":"globals",".name":"globals","dhcp_default_duid":"000404d5ca7df6d44afb96fc708158a5e06a","ula_prefix":"fdb9:d8e3:f4ce::/48"}""",
                ),
            ),
            "wireless" to Result.success(
                uci(""""default_radio0":{".type":"wifi-iface",".name":"default_radio0","device":"radio0","network":["lan"],"ssid":"Familia Pérez","encryption":"sae-mixed","key":"contraseña-wifi-123"}"""),
            ),
            "dhcp" to Result.success(
                uci(
                    """"tele_salon":{".type":"host",".name":"tele_salon","name":"tele-salon","mac":["A8:FE:9D:0D:0B:72"],"ip":"192.168.8.50"}""",
                    """"host_a8fe9d0d0b72":{".type":"host",".name":"host_a8fe9d0d0b72","mac":["A8:FE:9D:0D:0B:72"],"wrtc_name":"Tele de Ana"}""",
                    """"cfg01":{".type":"dnsmasq",".name":"cfg01","domain":"lan","local":"/lan/"}""",
                ),
            ),
            "firewall" to Result.success(
                uci(
                    """"cfg02":{".type":"zone",".name":"cfg02","name":"lan","network":["lan"],"input":"ACCEPT"}""",
                    """"cfg03":{".type":"rule",".name":"cfg03","name":"Allow-Ping","src":"wan","proto":"icmp"}""",
                    """"wrtc_r_1":{".type":"redirect",".name":"wrtc_r_1","name":"WrtControl · Ana · DNS","src_mac":["A8:FE:9D:0D:0B:72"]}""",
                    """"wrtc_p_1":{".type":"wrtcontrol_profile",".name":"wrtc_p_1","name":"Ana","mac":["A8:FE:9D:0D:0B:72"]}""",
                ),
            ),
            "system" to Result.success(uci(""""cfg01":{".type":"system",".name":"cfg01","hostname":"Casa-de-Perez","zonename":"America/Mexico_City"}""")),
            "ddns" to Result.success(
                uci(
                    """"wrtcontrol":{".type":"service",".name":"wrtcontrol","service_name":"duckdns.org","domain":"perez.duckdns.org","lookup_host":"perez.duckdns.org","password":"token-ddns-0123456789","update_url":"https://www.duckdns.org/update?domains=perez&token=token-ddns-0123456789"}""",
                ),
            ),
        ),
    )

    private val report: String by lazy { buildReport(raw, "0.3.0") }

    @Test
    fun `no sale nada personal`() {
        val found = personal.filter { report.contains(it, ignoreCase = true) }
        assertTrue("en el informe: $found", found.isEmpty())
        assertTrue(ReportAnonymizer().leaks(report).isEmpty())
    }

    @Test
    fun `lo que dice como es el router se queda`() {
        listOf(
            "GL.iNet GL-MT6000", "OpenWrt 25.12.6", "\"pppoe\"", "192.168.8.1", "10.77.0.2/32", "Allow-Ping", "radio0", "sae-mixed",
            "America/Mexico_City", "duckdns.org", "\"local\": \"/lan/\"", "access denied", "\"format\": 1", "0.3.0",
        ).forEach { assertTrue("falta $it", report.contains(it)) }
    }

    @Test
    fun `lo mismo lleva el mismo seudonimo`() {
        val json = UbusJson.parseToJsonElement(report).jsonObject
        val calls = json["calls"]!!.jsonArray.associate { it.jsonObject["call"]!!.jsonPrimitive.content to it.jsonObject["result"]!! }
        val radios = calls.getValue("luci-rpc.getWirelessDevices").jsonObject
        fun ssid(radio: String) = radios[radio]!!.jsonObject["interfaces"]!!.jsonArray[0].jsonObject["config"]!!.jsonObject["ssid"]!!.jsonPrimitive.content
        // Las dos radios siguen teniendo el mismo nombre de red.
        assertEquals(ssid("radio0"), ssid("radio1"))
        assertNotEquals("Familia Pérez", ssid("radio0"))

        // La dirección del router sigue dentro de su prefijo delegado (la misma subred, otro prefijo).
        val wan6 = calls.getValue("network.interface.dump").jsonObject["interface"]!!.jsonArray[2].jsonObject
        val address = wan6["ipv6-address"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content
        val prefix = wan6["ipv6-prefix"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content
        assertTrue("$address y $prefix", address.startsWith("2001:db8:") && address.substringBeforeLast(":ab12") == prefix.substringBeforeLast(":ab00"))

        // La MAC del aparato es la misma en dhcp y en la regla de su perfil.
        val uci = json["uci"]!!.jsonObject
        val hostMac = uci["dhcp"]!!.jsonObject.values.first().jsonObject["mac"]!!.jsonArray[0].jsonPrimitive.content
        val ruleMac = uci["firewall"]!!.jsonObject["wrtc_r_1"]!!.jsonObject["src_mac"]!!.jsonArray[0].jsonPrimitive.content
        assertEquals(hostMac, ruleMac)
        assertTrue(hostMac.startsWith("02:00:00:00:"))
    }

    @Test
    fun `la revision final encuentra lo que se haya escapado`() {
        val a = ReportAnonymizer()
        assertFalse(a.leaks("mac 94:83:C4:12:34:56").isEmpty())
        assertFalse(a.leaks("ip 187.190.33.12").isEmpty())
        assertFalse(a.leaks("ipv6 2806:2f0:9021:ab12::5").isEmpty())
        assertFalse(a.leaks("clave $routerKey").isEmpty())
        // Lo que no es de nadie no salta.
        assertTrue(a.leaks("192.168.8.1 255.255.255.0 ::1 fe80::1 ff02::1 2001:db8:1::1 12:30:45").isEmpty())
    }

    @Test
    fun `las direcciones IPv6 se leen bien`() {
        assertEquals(listOf(0x2806, 0x2F0, 0x9021, 0xAB12, 0, 0, 0, 5), expandIpv6("2806:2f0:9021:ab12::5"))
        assertEquals(List(8) { 0 }, expandIpv6("::"))
        assertEquals(null, expandIpv6("12:30:45"))
        assertEquals(null, expandIpv6("02:00:00:00:00:01"))
    }

    @Test
    fun `el formato esta en el informe`() {
        val json = UbusJson.parseToJsonElement(report) as JsonObject
        assertEquals(REPORT_FORMAT, json["format"]!!.jsonPrimitive.content.toInt())
    }
}
