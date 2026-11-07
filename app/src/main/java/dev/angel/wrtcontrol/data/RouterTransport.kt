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
import java.util.concurrent.atomic.AtomicInteger
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

    fun stop()
}

/** Por dónde van ahora las peticiones al router. */
enum class Link { Direct, Connecting, Tunnel }

/** No se pudo entrar por el acceso remoto; el mensaje explica por qué. */
class TunnelUnavailableException(message: String) : IOException(message)

/**
 * Lleva las conexiones de OkHttp al router.
 *
 * - Sin acceso remoto en este teléfono, directas, como siempre.
 * - Con acceso remoto, por el túnel también en casa (primero a la IP de la LAN): el saludo de
 *   WireGuard comprueba que al otro lado está nuestro router. Una conexión directa solo comprueba
 *   que algo contesta en esa IP, y en otra red con la misma (192.168.1.1 en un hotel) la sesión y
 *   la contraseña irían a un aparato ajeno, sin cifrar.
 * - Si el túnel no sube (p. ej. tras actualizar el firmware, que borra WireGuard), las peticiones
 *   van directas para no dejar la app sin router en casa, y no se reintenta durante [RETRY_TUNNEL_MS].
 * - La URL no cambia (sigue siendo la dirección de casa): solo cambia a dónde se conecta el socket.
 * - Se quita tras [idleMs] sin usarse y si deja de haber saludos (el router cambió de dirección):
 *   la siguiente petición lo vuelve a levantar, resolviendo otra vez el DNS dinámico.
 */
class RouterTransport(
    private val engine: TunnelEngine?,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** ¿Está el teléfono en una red local (Wi-Fi, Ethernet), donde puede estar el router? */
    private val onLocalNetwork: () -> Boolean = { true },
    private val handshakeWaitMs: Long = 5_000,
    private val lanHandshakeWaitMs: Long = 1_500,
    private val idleMs: Long = 90_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Acceso remoto del router conectado (lo pone RouterRepository); null: sin acceso remoto. */
    @Volatile
    var config: TunnelConfig? = null
        set(value) {
            val old = field
            field = value
            // Si solo cambian las direcciones donde buscar el router, el túnel puesto sigue valiendo.
            if (old?.copy(endpoints = emptyList(), hostname = null) != value?.copy(endpoints = emptyList(), hostname = null)) {
                generation.incrementAndGet()
                failedAt = 0L
                dropTunnel()
            }
        }

    /**
     * Solo conexiones directas, sin túnel: para quitar el acceso remoto de este mismo teléfono (al
     * hacerlo, el router corta el túnel por el que habría que comprobarlo).
     */
    @Volatile
    var directOnly = false
        set(value) {
            field = value
            if (value) dropTunnel()
        }

    /** Conexiones reutilizables: al cambiar de camino se tiran, porque van por el otro. */
    val pool = ConnectionPool()

    /** Arranques de uno en uno (son lentos: no se hacen bajo [stateLock]). */
    private val startLock = Any()

    /** Poner o quitar el túnel (rápido). */
    private val stateLock = Any()

    /** Cambia con cada configuración: un arranque que empezó con otra no se publica. */
    private val generation = AtomicInteger()

    @Volatile
    private var tunnel: TunnelHandle? = null

    @Volatile
    private var lastUse = 0L

    /** Cuándo falló el último intento de levantar el túnel (0: no ha fallado). */
    @Volatile
    private var failedAt = 0L

    /** Por qué falló, para explicarlo si la conexión directa tampoco llega. */
    @Volatile
    private var failure: TunnelUnavailableException? = null

    /** Peticiones en marcha: no se quita el túnel debajo de ellas. */
    private val inFlight = AtomicInteger()

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
        inFlight.incrementAndGet()
        try {
            lastUse = now()
            if (wantsTunnel(config)) {
                try {
                    startTunnel(config!!, request.url.host, request.url.port)
                } catch (_: TunnelUnavailableException) {
                    // Sin túnel, directo; si tampoco llega, se explica lo del túnel (ver abajo).
                }
            }
            val viaTunnel = tunnel != null
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                val neverSent = isConnectFailure(e) && e.suppressed.all { it is IOException && isConnectFailure(it) }
                if (viaTunnel && neverSent) {
                    // El túnel se cayó antes de que saliera nada: se rehace y se repite una vez.
                    dropTunnel()
                    if (wantsTunnel(config)) {
                        startTunnel(config!!, request.url.host, request.url.port)
                        return@Interceptor chain.proceed(request)
                    }
                }
                if (!viaTunnel && neverSent) failure?.let { throw it }
                throw e
            }
        } finally {
            inFlight.decrementAndGet()
            lastUse = now()
        }
    }

    private fun wantsTunnel(cfg: TunnelConfig?): Boolean =
        cfg?.enabled == true && engine != null && !directOnly && tunnel == null &&
            (failedAt == 0L || now() - failedAt >= RETRY_TUNNEL_MS)

    /**
     * Levanta el túnel probando las direcciones del router de una en una, cada una con un túnel
     * nuevo (así el primer saludo sale enseguida hacia ella), hasta que haya saludo.
     */
    fun startTunnel(cfg: TunnelConfig, routerHost: String, routerPort: Int): Unit = synchronized(startLock) {
        if (tunnel != null) return
        if (failedAt != 0L && now() - failedAt < RETRY_TUNNEL_MS) throw failure ?: TunnelUnavailableException("Acceso remoto no disponible")
        val gen = generation.get()
        val engine = engine ?: throw TunnelUnavailableException("Esta versión de la app no trae el acceso remoto.")
        val candidates = endpoints(cfg, routerHost)
        if (candidates.isEmpty()) {
            fail(TunnelUnavailableException(
                "No se llega al router y el acceso remoto no sabe dónde buscarlo: configura el DNS dinámico desde casa.",
            ))
        }
        _link.value = Link.Connecting
        val target = "${cfg.webAddress ?: cfg.routerTunnelAddress}:$routerPort"
        val home = routerHost.trim('[', ']')
        for (endpoint in candidates) {
            val handle = try {
                engine.start(cfg, endpoint, target)
            } catch (_: Exception) {
                continue
            }
            val lan = endpoint.startsWith("$home:") || endpoint.startsWith("[$home]:")
            if (waitHandshake(handle, if (lan) lanHandshakeWaitMs else handshakeWaitMs)) {
                synchronized(stateLock) {
                    if (gen != generation.get() || config?.enabled != true || directOnly) {
                        runCatching { handle.stop() }
                        _link.value = Link.Direct
                        throw TunnelUnavailableException("El acceso remoto cambió mientras se conectaba.")
                    }
                    tunnel = handle
                    failedAt = 0L
                    failure = null
                    lastUse = now()
                    pool.evictAll()
                    _link.value = Link.Tunnel
                }
                return
            }
            runCatching { handle.stop() }
        }
        _link.value = Link.Direct
        fail(TunnelUnavailableException(
            "No se pudo llegar al router por el acceso remoto (probado: ${candidates.joinToString()}). " +
                "Puede que la red en la que estás no tenga IPv6 o que el router no esté encendido.",
        ))
    }

    private fun fail(e: TunnelUnavailableException): Nothing {
        failedAt = now()
        failure = e
        throw e
    }

    private fun waitHandshake(handle: TunnelHandle, waitMs: Long): Boolean {
        repeat((waitMs / HANDSHAKE_POLL_MS).toInt().coerceAtLeast(1)) {
            if (handle.lastHandshake() > 0) return true
            Thread.sleep(HANDSHAKE_POLL_MS)
        }
        return handle.lastHandshake() > 0
    }

    /**
     * Direcciones donde buscar el router, en orden: en una red local, primero la de casa (si es
     * una IP: en casa el saludo llega enseguida); después el nombre del DNS dinámico (IPv6 antes) y
     * las últimas direcciones públicas vistas. Fuera de una red local, la de casa va al final.
     */
    internal fun endpoints(cfg: TunnelConfig, routerHost: String?): List<String> {
        val home = routerHost?.trim('[', ']')?.takeIf(::isIpLiteral)
        val remote = buildList {
            cfg.hostname?.let { name ->
                val resolved = try {
                    resolver(name)
                } catch (_: UnknownHostException) {
                    emptyList()
                }
                resolved.sortedBy { it is Inet4Address }.forEach { add(ipText(it)) }
            }
            addAll(cfg.endpoints)
        }
        val hosts = if (home == null) remote else if (onLocalNetwork()) listOf(home) + remote else remote + home
        return hosts.distinct().map { if (':' in it) "[$it]:${cfg.port}" else "$it:${cfg.port}" }
    }

    /** Quita el túnel si lleva [idleMs] sin usarse y no hay nada en marcha por él. */
    fun stopIfIdle() {
        if (tunnel != null && inFlight.get() == 0 && now() - lastUse > idleMs) dropTunnel()
    }

    /**
     * Quita el túnel si hace más de [STALE_HANDSHAKE_S] que no hay saludo (con el aviso cada 25 s,
     * WireGuard vuelve a saludar cada dos minutos mientras el router contesta): el router cambió de
     * dirección o ya no está. La siguiente petición lo vuelve a buscar.
     */
    fun checkAlive() {
        val t = tunnel ?: return
        val last = runCatching { t.lastHandshake() }.getOrDefault(0L)
        if (last > 0 && now() / 1000 - last > STALE_HANDSHAKE_S && inFlight.get() == 0) dropTunnel()
    }

    /** Quita el túnel (las conexiones siguientes lo vuelven a levantar o van directas). */
    fun stop() = dropTunnel()

    private fun dropTunnel() {
        val t = synchronized(stateLock) {
            val t = tunnel ?: return
            tunnel = null
            _link.value = Link.Direct
            t
        }
        runCatching { t.stop() }
        pool.evictAll()
    }

    private inner class RoutedSocket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val t = tunnel
            when {
                t != null -> super.connect(InetSocketAddress(LOOPBACK_V4, t.localPort), timeout)
                // Con acceso remoto y el túnel caído, no se espera mucho a la conexión directa.
                config?.enabled == true -> super.connect(endpoint, if (timeout == 0) DIRECT_TIMEOUT_MS else minOf(timeout, DIRECT_TIMEOUT_MS))
                else -> super.connect(endpoint, timeout)
            }
        }
    }

    companion object {
        /** Lo que se espera a la conexión directa cuando hay acceso remoto. */
        const val DIRECT_TIMEOUT_MS = 2_500

        /** Tras un intento fallido, lo que se espera antes de volver a probar el túnel. */
        const val RETRY_TUNNEL_MS = 30_000L

        /** Sin saludo desde hace esto, el túnel se da por muerto (WireGuard rechaza sesiones de más de 180 s). */
        const val STALE_HANDSHAKE_S = 200L

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
    }
}
