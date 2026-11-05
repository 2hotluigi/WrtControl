package dev.angel.wrtcontrol.data

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class RouterTransportTest {

    /** El "router al otro lado del túnel": responde con el Host que le llega. */
    private lateinit var router: HttpServer

    /** Un puerto cerrado: la conexión directa se rechaza, como un router que no está en esta red. */
    private var closedPort = 0

    @Before
    fun setUp() {
        router = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        router.createContext("/") { ex ->
            val body = "por el túnel: ${ex.requestHeaders.getFirst("Host")}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        router.start()
        closedPort = ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() = router.stop(0)

    /** Motor falso: solo hay saludo con los endpoints de [answering]; el puerto local es el del "router". */
    private inner class FakeEngine(private val answering: Set<String>) : TunnelEngine {
        val started = mutableListOf<String>()
        val endpoints = mutableListOf<String>()
        var stopped = 0

        override fun start(config: TunnelConfig, endpoint: String, target: String): TunnelHandle {
            started += target
            endpoints += endpoint
            var current = endpoint
            return object : TunnelHandle {
                override val localPort = router.address.port
                override fun lastHandshake() = if (current in answering) 1_700_000_000L else 0L
                override fun setEndpoint(endpoint: String) {
                    current = endpoint
                    endpoints += endpoint
                }
                override fun stop() {
                    stopped++
                }
            }
        }
    }

    private val config = TunnelConfig(
        routerId = "x", peerId = "abcd1234", privateKey = "k", presharedKey = "p", enabled = true,
        routerPublicKey = "r", port = 51820, routerTunnelAddress = "10.77.0.1", phoneAddress = "10.77.0.2",
        endpoints = listOf("2001:db8::1"), hostname = "micasa.duckdns.org",
    )

    private fun transport(engine: TunnelEngine?, direct: Boolean = false) = RouterTransport(
        engine,
        resolver = { listOf(InetAddress.getByName("203.0.113.7"), InetAddress.getByName("2001:db8::99")) },
        directProbe = { _, _ -> direct },
        handshakeWaitMs = 300,
    )

    private fun client(t: RouterTransport) = OkHttpClient.Builder()
        .connectionPool(t.pool).socketFactory(t.socketFactory).dns(t.dns).addInterceptor(t.interceptor)
        .callTimeout(10, TimeUnit.SECONDS).build()

    private fun get(c: OkHttpClient, port: Int = closedPort) =
        c.newCall(Request.Builder().url("http://127.0.0.1:$port/ubus").build()).execute().use { it.body.string() }

    @Test
    fun `sin acceso remoto el fallo de conexion llega tal cual`() {
        val t = transport(FakeEngine(emptySet()))
        try {
            get(client(t))
            fail("no hay router")
        } catch (e: java.net.ConnectException) {
        }
        assertEquals(Link.Direct, t.link.value)
    }

    @Test
    fun `fuera de casa levanta el tunel con la primera direccion que responde y repite la peticion`() {
        val engine = FakeEngine(answering = setOf("203.0.113.7:51820"))
        val t = transport(engine)
        t.config = config

        val body = get(client(t))

        // La URL no cambia (el Host sigue siendo la dirección de casa); el socket va al túnel.
        assertEquals("por el túnel: 127.0.0.1:$closedPort", body)
        assertEquals(Link.Tunnel, t.link.value)
        // El DNS dinámico primero, IPv6 antes que IPv4; después la última IPv6 pública vista.
        assertEquals(listOf("[2001:db8::99]:51820", "203.0.113.7:51820"), engine.endpoints)
        assertEquals(listOf("10.77.0.1:$closedPort"), engine.started)

        // Las siguientes ya van directamente por el túnel, sin volver a levantarlo.
        get(client(t))
        assertEquals(1, engine.started.size)
    }

    @Test
    fun `si no responde ninguna direccion se explica y no queda nada puesto`() {
        val engine = FakeEngine(answering = emptySet())
        val t = transport(engine)
        t.config = config
        try {
            get(client(t))
            fail("tenía que fallar")
        } catch (e: TunnelUnavailableException) {
            assertTrue(e.message!!, e.message!!.startsWith("No se pudo llegar al router por el acceso remoto"))
        }
        assertEquals(1, engine.stopped)
        assertEquals(Link.Direct, t.link.value)
    }

    @Test
    fun `tras un intento fallido no se reintenta el tunel en cada peticion`() {
        var clock = 1_000L
        val engine = FakeEngine(answering = emptySet())
        val t = RouterTransport(
            engine, resolver = { listOf(InetAddress.getByName("2001:db8::99")) }, directProbe = { _, _ -> false },
            handshakeWaitMs = 100, now = { clock },
        )
        t.config = config
        try { get(client(t)); fail() } catch (_: TunnelUnavailableException) {}
        assertEquals(1, engine.started.size)
        // Durante 30 s se da el fallo de la conexión directa sin volver a probar.
        clock += 10_000
        try { get(client(t)); fail() } catch (e: java.net.ConnectException) {}
        assertEquals(1, engine.started.size)
        clock += 25_000
        try { get(client(t)); fail() } catch (_: TunnelUnavailableException) {}
        assertEquals(2, engine.started.size)
    }

    @Test
    fun `sin DNS dinamico ni direcciones pide configurarlo desde casa`() {
        val t = transport(FakeEngine(emptySet()))
        t.config = config.copy(hostname = null, endpoints = emptyList())
        try {
            t.startTunnel(t.config!!, "router.lan", 80)
            fail("tenía que fallar")
        } catch (e: TunnelUnavailableException) {
            assertTrue(e.message!!.contains("DNS dinámico"))
        }
        // La dirección de casa, si es una IP, también se prueba (y, sin más, basta).
        assertEquals(listOf("192.168.1.1:51820"), t.endpoints(config.copy(hostname = null, endpoints = emptyList()), "192.168.1.1"))
    }

    @Test
    fun `al volver a casa se quita el tunel`() {
        var home = false
        val engine = FakeEngine(answering = setOf("[2001:db8::99]:51820"))
        val t = RouterTransport(
            engine,
            resolver = { listOf(InetAddress.getByName("2001:db8::99")) },
            directProbe = { _, _ -> home },
            handshakeWaitMs = 300,
        )
        t.config = config
        get(client(t))
        assertEquals(Link.Tunnel, t.link.value)

        t.checkDirect()
        assertEquals("aún fuera", Link.Tunnel, t.link.value)
        home = true
        t.checkDirect()
        assertEquals(Link.Direct, t.link.value)
        assertEquals(1, engine.stopped)

        // Quitar el acceso remoto también lo quita.
        get(client(t))
        t.config = null
        assertEquals(Link.Direct, t.link.value)
    }

    @Test
    fun `sin usarlo un rato se quita`() {
        var clock = 0L
        val engine = FakeEngine(answering = setOf("[2001:db8::99]:51820"))
        val t = RouterTransport(
            engine, resolver = { listOf(InetAddress.getByName("2001:db8::99")) }, directProbe = { _, _ -> false },
            handshakeWaitMs = 300, idleMs = 60_000, now = { clock },
        )
        t.config = config
        // El reloj falso no avanza solo: el saludo es inmediato.
        get(client(t))
        clock = 30_000
        t.stopIfIdle()
        assertEquals(Link.Tunnel, t.link.value)
        clock = 61_000
        t.stopIfIdle()
        assertEquals(Link.Direct, t.link.value)
    }

    @Test
    fun `las IPv6 resueltas se escriben cortas, como las del router`() {
        assertEquals("2001:db8::99", RouterTransport.ipText(InetAddress.getByName("2001:db8:0:0:0:0:0:99")))
        assertEquals("2a02:8070:1:2:3:4:5:6", RouterTransport.ipText(InetAddress.getByName("2a02:8070:1:2:3:4:5:6")))
        assertEquals("fe80::1:0:0:1", RouterTransport.ipText(InetAddress.getByName("fe80:0:0:0:1:0:0:1")))
        assertEquals("::1", RouterTransport.ipText(InetAddress.getByName("::1")))
        assertEquals("203.0.113.7", RouterTransport.ipText(InetAddress.getByName("203.0.113.7")))
    }

    @Test
    fun `que cuenta como no llegar al router`() {
        assertTrue(RouterTransport.isConnectFailure(java.net.ConnectException("refused")))
        assertTrue(RouterTransport.isConnectFailure(java.net.SocketTimeoutException("connect timed out")))
        assertTrue(RouterTransport.isConnectFailure(java.net.SocketException("Network is unreachable")))
        // Una respuesta que no llega a tiempo no: la petición pudo haber salido (y aplicarse).
        assertTrue(!RouterTransport.isConnectFailure(java.net.SocketTimeoutException("timeout")))
        assertTrue(RouterTransport.isIpLiteral("192.168.1.1"))
        assertTrue(RouterTransport.isIpLiteral("fd00::1"))
        assertTrue(!RouterTransport.isIpLiteral("router.lan"))
    }
}
