package dev.angel.wrtcontrol.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory

/** El túnel nativo (wireguard-go con netstack); las pruebas usan uno falso. */
interface TunnelEngine {
    /** Levanta el túnel hacia [endpoint] ("ip:puerto"), con un puerto local que lleva a [target]. */
    fun start(config: TunnelConfig, endpoint: String, target: String): TunnelHandle
}

interface TunnelHandle {
    /** Puerto de 127.0.0.1 que lleva, por el túnel, a la web del router. */
    val localPort: Int

    /** Hora (segundos) del último saludo con el router; 0 si no ha habido. */
    fun lastHandshake(): Long

    fun setEndpoint(endpoint: String)

    fun stop()
}

/** Por dónde van ahora las peticiones al router. */
enum class Link { Direct, Connecting, Tunnel }

/** No se pudo entrar por el acceso remoto; el mensaje explica por qué. */
class TunnelUnavailableException(message: String) : IOException(message)

/**
 * Lleva las conexiones de OkHttp al router: directas en casa y por el túnel fuera. Cambia solo:
 *
 * - Si una conexión directa no llega a abrirse (el router no está en esta red) y hay acceso remoto
 *   configurado, levanta el túnel y repite la petición, que no llegó a salir.
 * - Con el túnel puesto, las conexiones nuevas van al puerto local del túnel. La URL no cambia
 *   (sigue siendo la dirección de casa): solo cambia a dónde se conecta el socket.
 * - [checkDirect] mira si se vuelve a llegar directamente (al volver a casa) y entonces lo quita;
 *   [stopIfIdle] lo quita si lleva un rato sin usarse, para no gastar batería con los avisos de
 *   WireGuard.
 */
class RouterTransport(
    private val engine: TunnelEngine?,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** ¿Se abre una conexión directa a host:puerto? (sin pasar por el túnel). */
    private val directProbe: (String, Int) -> Boolean = { host, port -> probe(host, port) },
    private val handshakeWaitMs: Long = 5_000,
    private val idleMs: Long = 90_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Acceso remoto del router conectado (lo pone RouterRepository); null: sin acceso remoto. */
    @Volatile
    var config: TunnelConfig? = null
        set(value) {
            field = value
            if (value?.enabled != true) stop()
        }

    /** Conexiones reutilizables: al cambiar de camino se tiran, porque van por el otro. */
    val pool = ConnectionPool()

    private val lock = Any()

    @Volatile
    private var tunnel: TunnelHandle? = null

    @Volatile
    private var lastUse = 0L

    /** Cuándo falló el último intento de levantar el túnel (0: no ha fallado). */
    @Volatile
    private var failedAt = 0L

    /** Último destino directo pedido (la dirección del router en casa). */
    @Volatile
    private var lastTarget: Pair<String, Int>? = null

    private val _link = MutableStateFlow(Link.Direct)
    val link: StateFlow<Link> = _link.asStateFlow()

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = RoutedSocket()
        override fun createSocket(host: String, port: Int): Socket = RoutedSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int): Socket = RoutedSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
    }

    /** Con el túnel, los nombres (router.lan) no se resuelven: el socket va igualmente al túnel. */
    val dns: Dns = Dns { host -> if (tunnel != null) listOf(LOOPBACK_V4) else Dns.SYSTEM.lookup(host) }

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        lastUse = now()
        if (tunnel == null) lastTarget = request.url.host to request.url.port
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val cfg = config
            if (tunnel != null || cfg?.enabled != true || engine == null || !isConnectFailure(e)) throw e
            // Si el túnel acaba de fallar (p. ej. el router se está reiniciando), no se vuelve a
            // probar en cada petición: cada intento tarda unos segundos.
            if (failedAt != 0L && now() - failedAt < RETRY_TUNNEL_MS) throw e
            // El router no está en esta red: por el túnel. La petición no llegó a salir, se repite.
            try {
                startTunnel(cfg, request.url.host, request.url.port)
            } catch (t: TunnelUnavailableException) {
                failedAt = now()
                throw t
            }
            chain.proceed(request)
        }
    }

    /** Levanta el túnel probando cada dirección conocida del router hasta que haya saludo. */
    fun startTunnel(cfg: TunnelConfig, routerHost: String, routerPort: Int): Unit = synchronized(lock) {
        if (tunnel != null) return
        val engine = engine ?: throw TunnelUnavailableException("Esta versión de la app no trae el acceso remoto.")
        val endpoints = endpoints(cfg, routerHost)
        if (endpoints.isEmpty()) {
            throw TunnelUnavailableException(
                "No se llega al router y el acceso remoto no sabe dónde buscarlo: configura el DNS dinámico desde casa.",
            )
        }
        _link.value = Link.Connecting
        val handle = try {
            engine.start(cfg, endpoints.first(), "${cfg.routerTunnelAddress}:$routerPort")
        } catch (e: Exception) {
            _link.value = Link.Direct
            throw TunnelUnavailableException("No se pudo levantar el acceso remoto: ${e.message}")
        }
        for ((i, endpoint) in endpoints.withIndex()) {
            if (i > 0) runCatching { handle.setEndpoint(endpoint) }
            if (waitHandshake(handle)) {
                tunnel = handle
                failedAt = 0L
                lastUse = now()
                pool.evictAll()
                _link.value = Link.Tunnel
                return
            }
        }
        handle.stop()
        _link.value = Link.Direct
        throw TunnelUnavailableException(
            "No se pudo llegar al router por el acceso remoto (probado: ${endpoints.joinToString()}). " +
                "Puede que la red en la que estás no tenga IPv6 o que el router no esté encendido.",
        )
    }

    private fun waitHandshake(handle: TunnelHandle): Boolean {
        repeat((handshakeWaitMs / HANDSHAKE_POLL_MS).toInt().coerceAtLeast(1)) {
            if (handle.lastHandshake() > 0) return true
            Thread.sleep(HANDSHAKE_POLL_MS)
        }
        return handle.lastHandshake() > 0
    }

    /**
     * Direcciones donde buscar el router, en orden: el nombre del DNS dinámico (IPv6 antes),
     * las últimas direcciones públicas vistas y, por último, la de casa si es una IP.
     */
    internal fun endpoints(cfg: TunnelConfig, routerHost: String?): List<String> {
        val hosts = buildList {
            cfg.hostname?.let { name ->
                val resolved = try {
                    resolver(name)
                } catch (_: UnknownHostException) {
                    emptyList()
                }
                resolved.sortedBy { it is Inet4Address }.forEach { add(ipText(it)) }
            }
            addAll(cfg.endpoints)
            routerHost?.trim('[', ']')?.takeIf(::isIpLiteral)?.let(::add)
        }.distinct()
        return hosts.map { if (':' in it) "[$it]:${cfg.port}" else "$it:${cfg.port}" }
    }

    /** Si ya se llega directamente (de vuelta en casa), quita el túnel. */
    fun checkDirect() {
        if (tunnel == null) return
        val (host, port) = lastTarget ?: return
        if (directProbe(host, port)) stop()
    }

    /** Quita el túnel si lleva [idleMs] sin usarse. */
    fun stopIfIdle() {
        if (tunnel != null && now() - lastUse > idleMs) stop()
    }

    /** Quita el túnel (las conexiones siguientes vuelven a ir directas). */
    fun stop(): Unit = synchronized(lock) {
        val t = tunnel ?: return
        tunnel = null
        runCatching { t.stop() }
        pool.evictAll()
        _link.value = Link.Direct
    }

    private inner class RoutedSocket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val t = tunnel
            when {
                t != null -> super.connect(InetSocketAddress(LOOPBACK_V4, t.localPort), timeout)
                // Con acceso remoto, no se espera mucho a que conteste en casa: si no está, al túnel.
                config?.enabled == true -> super.connect(endpoint, if (timeout == 0) DIRECT_TIMEOUT_MS else minOf(timeout, DIRECT_TIMEOUT_MS))
                else -> super.connect(endpoint, timeout)
            }
        }
    }

    companion object {
        /** Lo que se espera a la conexión directa cuando hay acceso remoto para probar. */
        const val DIRECT_TIMEOUT_MS = 2_500

        /** Tras un intento fallido, lo que se espera antes de volver a probar el túnel. */
        const val RETRY_TUNNEL_MS = 30_000L

        private const val HANDSHAKE_POLL_MS = 100L

        /**
         * 127.0.0.1, donde escucha el túnel. No vale InetAddress.getLoopbackAddress(): en Android
         * es ::1 (en la JVM de escritorio, 127.0.0.1).
         */
        val LOOPBACK_V4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        /** La IP como texto corto (2001:db8::1), igual que la escribe el router: Java la da sin comprimir. */
        internal fun ipText(address: InetAddress): String {
            if (address is Inet4Address) return address.hostAddress!!
            val words = address.address.toList().chunked(2).map { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
            // El tramo de ceros más largo (de al menos dos) se cambia por "::".
            var bestStart = -1
            var bestLen = 1
            var i = 0
            while (i < 8) {
                if (words[i] == 0) {
                    var j = i
                    while (j < 8 && words[j] == 0) j++
                    if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                    i = j
                } else i++
            }
            val hex = words.map { Integer.toHexString(it) }
            if (bestStart < 0) return hex.joinToString(":")
            return hex.subList(0, bestStart).joinToString(":") + "::" + hex.subList(bestStart + bestLen, 8).joinToString(":")
        }

        /** La conexión no llegó a abrirse (el router no está en esta red). */
        fun isConnectFailure(e: IOException): Boolean = when (e) {
            is ConnectException, is NoRouteToHostException, is UnknownHostException -> true
            is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
            is SocketException -> e.message?.let { "unreachable" in it.lowercase() || "ENETUNREACH" in it } == true
            else -> false
        }

        fun isIpLiteral(host: String): Boolean =
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || (':' in host && Regex("^[0-9a-fA-F:.]+$").matches(host))

        private fun probe(host: String, port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), 1_500) }
            true
        } catch (_: IOException) {
            false
        }
    }
}
