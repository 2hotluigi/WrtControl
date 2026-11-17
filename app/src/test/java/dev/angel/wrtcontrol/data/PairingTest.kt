package dev.angel.wrtcontrol.data

import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun `el enlace lleva la direccion y el usuario, y se lee igual`() {
        val link = pairingLink(" 192.168.8.1 ", "root")!!
        assertEquals("wrtcontrol://connect?address=192.168.8.1&user=root", link.toUri())
        assertEquals(link, parsePairingLink(link.toUri()))
        // Tal como lo dan algunas cámaras: mayúsculas en el esquema y parámetros de más.
        assertEquals(link, parsePairingLink("WRTCONTROL://connect?user=root&address=192.168.8.1&utm=x"))
    }

    @Test
    fun `nunca lleva la contrasena`() {
        val uri = pairingLink("192.168.1.1", "root")!!.toUri()
        assertTrue(Regex("^wrtcontrol://connect\\?address=[0-9.]+&user=root$").matches(uri))
    }

    @Test
    fun `solo vale una IPv4 de red privada escrita tal cual`() {
        // Un enlace puede venir de cualquiera: no debe servir para llevar la contraseña a internet.
        for (address in listOf("8.8.8.8", "router.ejemplo.com", "http://192.168.1.1", "192.168.1.1:8080", "192.168.1.1/x", "fd00::1", "")) {
            assertNull(address, pairingLink(address, "root"))
            assertNull(address, parsePairingLink("wrtcontrol://connect?address=" + java.net.URLEncoder.encode(address, "UTF-8") + "&user=root"))
        }
        for (address in listOf("192.168.1.1", "10.0.0.1", "172.16.5.1")) assertEquals(address, pairingLink(address, "root")?.address)
    }

    @Test
    fun `lo que no es un enlace de emparejar se ignora`() {
        val others = listOf(
            null, "", "no es un enlace", "https://connect?address=192.168.1.1&user=root",
            "wrtcontrol://otra?address=192.168.1.1&user=root", "wrtcontrol://connect", "wrtcontrol://connect?address=192.168.1.1",
            "wrtcontrol://connect?address=192.168.1.1&user=", "wrtcontrol://connect?address=192.168.1.1&user=ro%20ot",
            "wrtcontrol://connect?address=192.168.1.1&user=" + "a".repeat(40), "wrtcontrol://connect?address=%zz&user=root",
        )
        for (uri in others) assertNull(uri, parsePairingLink(uri))
    }

    @Test
    fun `el codigo QR es cuadrado y cambia con el texto`() {
        val a = qrModules("wrtcontrol://connect?address=192.168.8.1&user=root")
        val b = qrModules("wrtcontrol://connect?address=192.168.8.2&user=root")
        assertTrue(a.size >= 21)
        assertTrue(a.all { it.size == a.size })
        // Las tres esquinas con el patrón de localización empiezan en negro.
        assertTrue(a[0][0] && a[0][a.size - 1] && a[a.size - 1][0])
        assertNotEquals(a.map { it.toList() }, b.map { it.toList() })
    }

    @Test
    fun `lo que se dibuja se lee de vuelta como el mismo enlace`() {
        val link = pairingLink("192.168.8.1", "root")!!
        val read = Decoder().decode(qrModules(link.toUri()).toTypedArray()).text
        assertEquals(link.toUri(), read)
        assertEquals(link, parsePairingLink(read))
    }
}
