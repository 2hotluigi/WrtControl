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
        router = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
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
    private inner class FakeEngine(var answering: Set<String>) : TunnelEngine {
        val endpoints = mutableListOf<String>()
        val targets = mutableListOf<String>()
        var stopped = 0
        var handshakeAt = 1_700_000_000L

        override fun start(config: TunnelConfig, endpoint: String, target: String): TunnelHandle {
            endpoints += endpoint
            targets += target
            return object : TunnelHandle {
                override val localPort = router.address.port
                override fun lastHandshake() = if (endpoint in answering) handshakeAt else 0L
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

    private fun transport(engine: TunnelEngine?, local: Boolean = true, now: () -> Long = System::currentTimeMillis) = RouterTransport(
        engine,
        resolver = { listOf(InetAddress.getByName("203.0.113.7"), InetAddress.getByName("2001:db8::99")) },
        onLocalNetwork = { local },
        handshakeWaitMs = 300,
        lanHandshakeWaitMs = 200,
        now = now,
    )

    private fun client(t: RouterTransport) = OkHttpClient.Builder()
        .connectionPool(t.pool).socketFactory(t.socketFactory).dns(t.dns).addInterceptor(t.interceptor)
        .callTimeout(10, TimeUnit.SECONDS).build()

    private fun get(c: OkHttpClient, port: Int = closedPort) =
        c.newCall(Request.Builder().url("http://127.0.0.1:$port/ubus").build()).execute().use { it.body.string() }

    @Test
    fun `sin acceso remoto va directo como siempre`() {
        val engine = FakeEngine(emptySet())
        val t = transport(engine)
        try {
            get(client(t))
            fail("no hay router")
        } catch (_: java.net.ConnectException) {
        }
        assertEquals("ni se intenta el túnel", emptyList<String>(), engine.endpoints)
        assertEquals(Link.Direct, t.link.value)
    }

    @Test
    fun `con acceso remoto va por el tunel tambien en casa, primero a la IP de la LAN`() {
        val engine = FakeEngine(answering = setOf("127.0.0.1:51820"))
        val t = transport(engine)
        t.config = config

        // La URL no cambia (el Host sigue siendo la dirección de casa); el socket va al túnel.
        assertEquals("por el túnel: 127.0.0.1:$closedPort", get(client(t)))
        assertEquals(Link.Tunnel, t.link.value)
        assertEquals(listOf("127.0.0.1:51820"), engine.endpoints)
        assertEquals(listOf("10.77.0.1:$closedPort"), engine.targets)

        // Las siguientes van por el mismo túnel.
        get(client(t))
        assertEquals(1, engine.endpoints.size)
    }

    @Test
    fun `en datos moviles prueba antes el DNS dinamico, cada direccion con un tunel nuevo`() {
        val engine = FakeEngine(answering = setOf("203.0.113.7:51820"))
        val t = transport(engine, local = false)
        t.config = config

        get(client(t))

        // IPv6 antes que IPv4; la de casa, al final (fuera de una red local no estará ahí).
        assertEquals(listOf("[2001:db8::99]:51820", "203.0.113.7:51820"), engine.endpoints)
        assertEquals("el primero, sin saludo, se paró", 1, engine.stopped)
        assertEquals(
            listOf("[2001:db8::99]:51820", "203.0.113.7:51820", "[2001:db8::1]:51820", "127.0.0.1:51820"),
            t.endpoints(config, "127.0.0.1"),
        )
    }

    @Test
    fun `si uhttpd solo escucha en la LAN el tunel va a esa direccion`() {
        val engine = FakeEngine(answering = setOf("127.0.0.1:51820"))
        val t = transport(engine)
        t.config = config.copy(webAddress = "192.168.1.1")
        get(client(t))
        assertEquals(listOf("192.168.1.1:$closedPort"), engine.targets)
    }

    @Test
    fun `si el tunel no sube va directo y, si tampoco llega, explica lo del tunel`() {
        val engine = FakeEngine(answering = emptySet())
        val t = transport(engine)
        t.config = config
        try {
            get(client(t))
            fail("tenía que fallar")
        } catch (e: TunnelUnavailableException) {
            assertTrue(e.message!!, e.message!!.startsWith("No se pudo llegar al router por el acceso remoto"))
        }
        assertEquals(4, engine.stopped)
        assertEquals(Link.Direct, t.link.value)

        // Con el túnel caído, el router en casa sigue respondiendo por la conexión directa.
        assertEquals("por el túnel: 127.0.0.1:${router.address.port}", get(client(t), router.address.port))
    }

    @Test
    fun `tras un intento fallido no se reintenta el tunel en cada peticion`() {
        var clock = 1_000L
        val engine = FakeEngine(answering = emptySet())
        val t = transport(engine, now = { clock })
        t.config = config
        try { get(client(t)); fail() } catch (_: TunnelUnavailableException) {}
        val tried = engine.endpoints.size
        clock += 10_000
        try { get(client(t)); fail() } catch (_: TunnelUnavailableException) {}
        assertEquals("durante 30 s, directo sin volver a probar", tried, engine.endpoints.size)
        clock += 25_000
        try { get(client(t)); fail() } catch (_: TunnelUnavailableException) {}
        assertEquals(tried * 2, engine.endpoints.size)
    }

    @Test
    fun `sin DNS dinamico ni direcciones ni IP de casa pide configurarlo desde casa`() {
        val t = transport(FakeEngine(emptySet()))
        val bare = config.copy(hostname = null, endpoints = emptyList())
        t.config = bare
        try {
            t.startTunnel(bare, "router.lan", 80)
            fail("tenía que fallar")
        } catch (e: TunnelUnavailableException) {
            assertTrue(e.message!!.contains("DNS dinámico"))
        }
    }

    @Test
    fun `solo directo quita el tunel y no lo levanta`() {
        val engine = FakeEngine(answering = setOf("127.0.0.1:51820"))
        val t = transport(engine)
        t.config = config
        get(client(t))
        assertEquals(Link.Tunnel, t.link.value)

        t.directOnly = true
        assertEquals(Link.Direct, t.link.value)
        assertEquals("por el túnel: 127.0.0.1:${router.address.port}", get(client(t), router.address.port))
        assertEquals("no se levantó otro", 1, engine.endpoints.size)
        t.directOnly = false
    }

    @Test
    fun `un tunel sin saludos desde hace rato se quita y la siguiente peticion lo rehace`() {
        var clock = 1_700_000_000_000L
        val engine = FakeEngine(answering = setOf("127.0.0.1:51820"))
        engine.handshakeAt = clock / 1000
        val t = transport(engine, now = { clock })
        t.config = config
        get(client(t))

        clock += 100_000
        t.checkAlive()
        assertEquals("100 s: aún vale", Link.Tunnel, t.link.value)
        clock += 150_000
        t.checkAlive()
        assertEquals(Link.Direct, t.link.value)
        get(client(t))
        assertEquals(2, engine.endpoints.size)
    }

    @Test
    fun `sin usarlo un rato se quita`() {
        var clock = 0L
        val engine = FakeEngine(answering = setOf("127.0.0.1:51820"))
        val t = RouterTransport(engine, resolver = { emptyList() }, handshakeWaitMs = 300, idleMs = 60_000, now = { clock })
        t.config = config
        get(client(t))
        clock = 30_000
        t.stopIfIdle()
        assertEquals(Link.Tunnel, t.link.value)
        clock = 61_000
        t.stopIfIdle()
        assertEquals(Link.Direct, t.link.value)
    }

    @Test
    fun `cambiar solo donde buscar el router no tira el tunel, cambiar las claves si`() {
        val engine = FakeEngine(answering = setOf("127.0.0.1:51820"))
        val t = transport(engine)
        t.config = config
        get(client(t))

        t.config = config.copy(endpoints = listOf("2001:db8::2"), hostname = "otro.duckdns.org")
        assertEquals(Link.Tunnel, t.link.value)
        t.config = config.copy(privateKey = "otra")
        assertEquals(Link.Direct, t.link.value)
        t.config = null
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
