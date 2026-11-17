package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * El nombre y la dirección fija de un aparato contra un OpenWrt de verdad (como VmIntegrationTest):
 * lo que escribe la app en `dhcp`, lo que dnsmasq hace con ello y que sigue resolviendo nombres.
 * Usa un aparato inventado (una MAC privada que no es de nadie) y lo borra al terminar. dnsmasq se
 * reinicia un par de veces.
 *
 *   set WRTCONTROL_VM=192.168.56.2
 *   gradlew testDebugUnitTest --tests "*DevicesVmTest*" --rerun
 */
class DevicesVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    private val mac = "02:00:00:00:00:77"
    private val section = "host_020000000077"

    /** Una dirección de la red de casa del router que no usa ni él ni el cliente de pruebas (.99). */
    private val address: String get() = vm!!.substringBeforeLast('.') + ".77"

    private fun ssh(command: String): String {
        var out = ""
        repeat(4) {
            val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", command))
                .redirectErrorStream(true).start()
            out = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            if (process.exitValue() != 255) return out.trim()
            Thread.sleep(1000)
        }
        return out.trim()
    }

    /** Lo que dnsmasq tiene configurado para la MAC de pruebas (vacío si nada). */
    private fun dnsmasqHost(): String = ssh("grep -ih '$mac' /var/etc/dnsmasq.conf.* 2>/dev/null || true")

    private fun resolves(): Boolean = ssh("nslookup localhost 127.0.0.1 >/dev/null 2>&1 && echo si || echo no").endsWith("si")

    @Test
    fun `nombre y direccion fija de un aparato en un router de verdad`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra la VM", vm != null)
        assumeTrue("La dirección de pruebas $address ya está en uso", ssh("uci show dhcp | grep -F \"'$address'\" || true").isEmpty())
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val interfaces = api.interfaces()

        try {
            // Nombre con espacios y acento, y dirección fija: dnsmasq reserva la dirección y no ve el nombre.
            api.deviceSettings.save(DeviceEdit(mac, "Tele de la sala", address), interfaces)
            assertEquals("Tele de la sala", ssh("uci -q get dhcp.$section.$LABEL_OPTION"))
            assertEquals(address, ssh("uci -q get dhcp.$section.ip"))
            val reserved = dnsmasqHost()
            assertTrue("dnsmasq reserva la dirección: $reserved", reserved.contains(address))
            assertFalse("y no recibe el nombre: $reserved", reserved.contains("Tele"))
            assertTrue("dnsmasq sigue resolviendo", resolves())

            // La app lo enseña con su nombre aunque no esté conectado.
            val device = api.devices(interfaces).single { it.mac == mac }
            assertEquals("Tele de la sala", device.name)
            assertEquals(address, device.fixedIp)
            assertNotEquals("no puede estar en línea: no existe", Presence.Online, device.presence)

            // Solo el nombre: la sección sigue, pero dnsmasq ya no tiene nada para esa MAC.
            api.deviceSettings.save(DeviceEdit(mac, "Tele de la sala", null), interfaces)
            assertEquals("", ssh("uci -q get dhcp.$section.ip"))
            assertEquals("Tele de la sala", ssh("uci -q get dhcp.$section.$LABEL_OPTION"))
            assertEquals("", dnsmasqHost())
            assertTrue("dnsmasq sigue resolviendo", resolves())

            // Sin nada que guardar, la sección de la app desaparece.
            api.deviceSettings.save(DeviceEdit(mac, null, null), interfaces)
            assertEquals("", ssh("uci -q show dhcp.$section"))
            assertTrue(api.devices(interfaces).none { it.mac == mac })
        } finally {
            ssh("uci -q delete dhcp.$section && uci commit dhcp && /etc/init.d/dnsmasq reload; true")
        }
    }
}
