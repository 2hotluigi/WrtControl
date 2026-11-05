package dev.angel.wrtcontrol.ubus

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UbusClientTest {

    private val creds = Credentials("root", "secreto")
    private fun session(id: String) = ok("""{"ubus_rpc_session":"$id","timeout":300,"expires":300}""")

    private suspend inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            fail("Se esperaba ${E::class.simpleName} pero llegó ${e::class.simpleName}: ${e.message}")
        }
        fail("Se esperaba ${E::class.simpleName}, no se lanzó nada")
        throw AssertionError()
    }

    @Serializable
    data class Info(val uptime: Long)

    @Test
    fun `login usa la sesion nula y guarda el token`() = runTest {
        val router = FakeRouter { session("s1") }
        val client = router.client()

        client.login(creds)

        assertEquals("s1", client.session)
        val call = router.calls.single()
        assertEquals(NULL_SESSION, call.sid)
        assertEquals("session.login", call.name)
        assertEquals("root", call.args["username"]!!.jsonPrimitive.content)
        assertEquals("secreto", call.args["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `login con estado 6 es AuthFailed`() = runTest {
        val client = FakeRouter { status(6) }.client()
        assertThrows<UbusException.AuthFailed> { client.login(creds) }
        assertNull(client.session)
    }

    @Test
    fun `callAs decodifica la respuesta`() = runTest {
        val router = FakeRouter { if (it.target == "session") session("s1") else ok("""{"uptime":42,"extra":true}""") }
        val client = router.client(creds)

        val info = client.callAs<Info>("system", "info")

        assertEquals(42, info.uptime)
        // Sin sesión previa, el cliente inicia sesión solo antes de la primera llamada.
        assertEquals(listOf("session.login", "system.info"), router.calls.map { it.name })
        assertEquals("s1", router.calls[1].sid)
    }

    @Test
    fun `sesion caducada con credenciales - renueva y reintenta`() = runTest {
        var loginCount = 0
        val router = FakeRouter { call ->
            when {
                call.name == "session.login" -> session("s${++loginCount}")
                call.sid == "s1" -> accessDenied // s1 caducó: también se rechaza session.access
                else -> ok("""{"uptime":7}""")
            }
        }
        val client = router.client(creds)

        assertEquals(7, client.callAs<Info>("system", "info").uptime) // login s1 → falla → s2
        assertEquals("s2", client.session)
        assertEquals(
            listOf("session.login", "system.info", "session.access", "session.login", "system.info"),
            router.calls.map { it.name },
        )
    }

    @Test
    fun `sesion caducada sin credenciales - SessionExpired`() = runTest {
        val router = FakeRouter { if (it.name == "session.login") session("s1") else accessDenied }
        val client = router.client()
        client.login(creds)
        client.forget()

        assertThrows<UbusException.SessionExpired> { client.call("system", "info") }
    }

    @Test
    fun `permiso denegado con sesion valida - AccessDenied sin re-login`() = runTest {
        val router = FakeRouter { call ->
            when (call.name) {
                "session.login" -> session("s1")
                "session.access" -> ok("""{"access":true}""")
                else -> accessDenied
            }
        }
        val client = router.client(creds)

        val e = assertThrows<UbusException.AccessDenied> { client.call("network.interface.wan", "status") }

        assertEquals("network.interface.wan.status", e.target)
        assertEquals("s1", client.session)
        assertEquals(1, router.calls.count { it.name == "session.login" })
    }

    @Test
    fun `llamadas concurrentes con sesion caducada hacen un solo re-login`() = runTest {
        var loginCount = 0
        val router = FakeRouter { call ->
            when {
                call.name == "session.login" -> session("s${++loginCount}")
                call.sid == "s1" -> accessDenied
                else -> ok("""{"uptime":1}""")
            }
        }
        val client = router.client(creds)
        client.login(creds) // s1, que el router ya considera caducada

        val results = (1..5).map { async { client.callAs<Info>("system", "info") } }.awaitAll()

        assertEquals(5, results.size)
        assertEquals(2, router.calls.count { it.name == "session.login" }) // el inicial + uno de renovación
        assertEquals("s2", client.session)
    }

    @Test
    fun `codigo de estado ubus - Status con texto`() = runTest {
        val client = FakeRouter { if (it.target == "session") session("s1") else status(4) }.client(creds)
        val e = assertThrows<UbusException.Status> { client.call("uci", "get") }
        assertEquals(4, e.code)
        assertTrue(e.message!!.contains("no encontrado"))
    }

    @Serializable
    data class Values(val values: Map<String, String> = emptyMap())

    @Test
    fun `resultado sin datos se decodifica como objeto vacio`() = runTest {
        val client = FakeRouter { if (it.target == "session") session("s1") else ok() }.client(creds)
        assertEquals(Values(), client.callAs<Values>("uci", "get"))
    }

    @Test
    fun `fallos de red y HTTP - Network`() = runTest {
        val down = FakeRouter { Reply.NetworkError }.client(creds)
        assertThrows<UbusException.Network> { down.call("system", "info") }

        val badGateway = FakeRouter { Reply.Http(502) }.client(creds)
        val e = assertThrows<UbusException.Network> { badGateway.call("system", "info") }
        assertEquals(502, e.httpCode)
    }

    @Test
    fun `respuesta que no es JSON - Protocol`() = runTest {
        val client = FakeRouter { Reply.Result("<html>no soy ubus</html>") }.client(creds)
        assertThrows<UbusException.Protocol> { client.call("system", "info") }
    }

    @Test
    fun `respuesta con forma inesperada - Protocol`() = runTest {
        val client = FakeRouter { if (it.target == "session") session("s1") else ok("""{"uptime":"mucho"}""") }.client(creds)
        assertThrows<UbusException.Protocol> { client.callAs<Info>("system", "info") }
    }

    @Test
    fun `las credenciales no aparecen en toString`() {
        assertTrue("secreto" !in creds.toString())
    }
}
