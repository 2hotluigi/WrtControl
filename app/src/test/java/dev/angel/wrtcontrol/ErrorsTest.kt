package dev.angel.wrtcontrol

import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.parental.PendingChangeException
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorsTest {

    private fun network(cause: Throwable) = UbusException.Network("No se pudo contactar con el router (${cause.message})", cause = cause)

    /** Todo lo que puede llegar a la pantalla, con sus mensajes técnicos tal como salen. */
    private val everything: List<Throwable> = listOf(
        UbusException.AuthFailed(),
        UbusException.SessionExpired(),
        UbusException.SessionLost(),
        UbusException.AccessDenied("uci.set"),
        UbusException.Protocol("uci.get: Field 'values' is required for type with serial name 'X'"),
        UbusException.Network("El router respondió HTTP 404", 404),
        UbusException.Network("El router respondió HTTP 502", 502),
        network(ConnectException("failed to connect to /192.168.1.1 (port 80): connect failed: ECONNREFUSED (Connection refused)")),
        network(SocketTimeoutException("failed to connect to /192.168.1.1 (port 80) after 10000ms")),
        network(UnknownHostException("Unable to resolve host \"router.lan\": No address associated with hostname")),
        network(NoRouteToHostException("Host unreachable")),
        network(SSLHandshakeException("Handshake failed")),
        network(IOException("unexpected end of stream on http://192.168.1.1/...")),
        IOException("closed"),
        IllegalStateException("Expected BEGIN_OBJECT but was STRING at path $"),
        IndexOutOfBoundsException("Index 3 out of bounds for length 2"),
        SerializationException("Unexpected JSON token at offset 0"),
        NullPointerException(),
    ) + (1..12).map { UbusException.Status(it, "luci-rpc.getHostHints") }

    @Test
    fun `nunca se ve una clase, un objeto de ubus ni un codigo`() {
        val technical = Regex(
            """Exception|Error\b|[a-z]+[.-][a-zA-Z_]+\.[a-zA-Z_]+|\b(uci|luci|rpcd|ubus)\.[a-z]|código|HTTP|ECONN|""" +
                // Un código suelto (404, 502), no las direcciones de ejemplo como 192.168.1.1.
                """(?<![\d.])\b[0-9]{3}\b(?![.\d])|Unable|failed|Expected|null|\$""",
        )
        for (e in everything) {
            val text = describeError(e).es
            assertFalse("${e.javaClass.simpleName}: $text", technical.containsMatchIn(text))
            assertTrue("${e.javaClass.simpleName}: $text", text.endsWith(".") || text.endsWith("?"))
        }
    }

    @Test
    fun `cada fallo de red dice que hacer`() {
        assertTrue(describeError(everything[7]).es.contains("Revisa la dirección"))
        assertTrue(describeError(everything[8]).es.contains("red de casa"))
        assertTrue(describeError(everything[9]).es.contains("dirección IP"))
        assertTrue(describeError(everything[10]).es.contains("Wi-Fi de casa"))
        assertTrue(describeError(everything[11]).es.contains("http://"))
        assertTrue(describeError(UbusException.Network("HTTP 404", 404)).es.contains("LuCI"))
    }

    @Test
    fun `los errores escritos para el usuario se muestran tal cual`() {
        val reverted = ChangeRevertedException("El cliente perdió internet, así que se deshizo.")
        assertEquals(reverted.message, describeError(reverted).es)
        assertTrue(describeError(PendingChangeException()).es.contains("otro cambio pendiente"))
        assertEquals("Hola.", describeError(UbusException.Refused("Hola.")).es)
        assertEquals("Escribe algo.", describeError(UserFacingException("Escribe algo.")).es)
        // También envueltos en un fallo de red (p. ej. el acceso remoto que no conecta).
        val tunnel = dev.angel.wrtcontrol.data.TunnelUnavailableException("El acceso remoto no conecta.")
        assertEquals("El acceso remoto no conecta.", describeError(network(tunnel)).es)
    }

    @Test
    fun `userRequire es un require con mensaje para el usuario`() {
        userRequire(true) { "no" }
        val e = runCatching { userRequire(false) { "Falta el nombre." } }.exceptionOrNull()
        assertTrue(e is UserFacingException && e is IllegalArgumentException)
        assertEquals("Falta el nombre.", describeError(e!!).es)
    }
}
