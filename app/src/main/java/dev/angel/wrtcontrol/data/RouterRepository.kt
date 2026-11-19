package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.describeError
import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import kotlin.time.Duration

sealed interface Connection {
    data object Loading : Connection

    /** Sin router conectado. Se rellenan los últimos datos usados para facilitar la reconexión. */
    data class SignedOut(
        val notice: Txt? = null,
        val lastAddress: String? = null,
        val lastUsername: String? = null,
    ) : Connection

    /**
     * [emptyPassword]: el router acepta entrar sin contraseña, así que cualquiera en la red puede cambiarlo.
     * [needsSetup]: es la primera vez que este teléfono entra en este router: toca la puesta en marcha.
     */
    data class SignedIn(
        val address: String,
        val username: String,
        val emptyPassword: Boolean = false,
        val needsSetup: Boolean = false,
    ) : Connection
}

/** Punto único de acceso al router: conexión, credenciales guardadas, API y lo que se lee de él. */
class RouterRepository(
    /** Vive lo que la app: en él se lee del router y se apunta lo que se va sabiendo. */
    scope: CoroutineScope,
    private val store: RouterStore,
    private val http: OkHttpClient,
    /** Camino hasta el router: directo en casa, por el acceso remoto fuera. */
    val transport: RouterTransport? = null,
    /** Para avisar al proveedor del DNS dinámico por internet (no por el túnel). */
    private val ddnsHttp: dev.angel.wrtcontrol.addons.DdnsHttp? = null,
    /** Los datos móviles, para probar el acceso remoto desde fuera sin salir de casa. */
    val outside: dev.angel.wrtcontrol.addons.OutsideNetwork? = null,
    /** Cómo se le cuenta al usuario un fallo al leer del router. */
    describe: (Throwable) -> Txt = ::describeError,
    /** Reloj en milisegundos que no retrocede. */
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : RouterAccess {
    private val _state = MutableStateFlow<Connection>(Connection.Loading)
    val state: StateFlow<Connection> = _state.asStateFlow()

    /** Lo que se lee del router para enseñarlo, compartido entre las pantallas. */
    val monitor = RouterMonitor(scope, this, describe, clock)

    private val _tunnel = MutableStateFlow<TunnelConfig?>(null)

    /** Acceso remoto de este teléfono al router conectado, si lo tiene y ya se sabe qué router es. */
    val tunnel: StateFlow<TunnelConfig?> = _tunnel.asStateFlow()

    private val _pairing = MutableStateFlow<PairingLink?>(null)

    /** El router que otro teléfono le pasó a este con su código QR, mientras no se haya entrado en él. */
    val pairing: StateFlow<PairingLink?> = _pairing.asStateFlow()

    @Volatile
    private var api: OpenWrtApi? = null

    @Volatile
    private var client: UbusClient? = null

    /**
     * Identificador del router conectado (modelo y MAC de la LAN), cuando se conoce. El acceso remoto
     * guardado solo se usa con el router para el que se creó.
     */
    @Volatile
    var routerId: String? = null
        private set

    init {
        // Aparte de las lecturas: qué router es y por dónde se llega a él desde fuera.
        scope.followRouter(
            monitor,
            onIdentified = ::rememberRouterId,
            onAddresses = ::refreshTunnelAddresses,
            onInUse = { features -> features.forEach { markVerified(it) } },
        )
    }

    /** Al arrancar la app: si hay un router guardado con contraseña, se entra directamente. */
    suspend fun restore() {
        val saved = store.load()
        if (saved?.password == null) {
            _state.value = Connection.SignedOut(lastAddress = saved?.address, lastUsername = saved?.username)
            return
        }
        // El login real se hace en la primera llamada (UbusClient renueva la sesión solo).
        routerId = saved.routerId
        // Ya hay un router guardado: un código escaneado no lo sustituye.
        _pairing.value = null
        useTunnel(tunnelFor(saved.address, saved.routerId))
        open(UbusClient(RouterAddress.endpoint(saved.address), http, Credentials(saved.username, saved.password)))
        // Quien ya venía usando la app con este router no necesita que se lo presenten.
        store.setSetupDone(saved.address)
        _state.value = Connection.SignedIn(saved.address, saved.username, saved.password.isEmpty())
    }

    /**
     * Otro teléfono le pasó a este la dirección de su router (ver Pairing.kt). Solo sirve para
     * rellenar la pantalla de conexión: si ya se está dentro de un router, no cambia nada.
     */
    fun offerPairing(link: PairingLink) {
        if (_state.value !is Connection.SignedIn) _pairing.value = link
    }

    /** Prueba las credenciales contra el router y, si funcionan, las guarda. */
    suspend fun connect(address: String, username: String, password: String) {
        val endpoint = RouterAddress.endpoint(address)
        // Si ya se conocía este router, se entra por su acceso remoto (que comprueba que es él).
        val known = store.load()?.takeIf { it.address == address.trim() }?.routerId
        useTunnel(tunnelFor(address.trim(), known))
        val newClient = UbusClient(endpoint, http)
        newClient.login(Credentials(username, password))
        val id = try {
            OpenWrtApi(newClient).routerId()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        store.save(SavedRouter(address.trim(), username, password, id ?: known))
        routerId = id ?: known
        if (id != null && id != known) useTunnel(tunnelFor(address.trim(), id))
        open(newClient)
        val needsSetup = !store.setupDone(address.trim())
        _state.value = Connection.SignedIn(address.trim(), username, password.isEmpty(), needsSetup)
        // Después de entrar: si se quitara antes, la pantalla de conexión empezaría de nuevo un instante.
        _pairing.value = null
    }

    /** La puesta en marcha de este router ya está hecha (o el usuario la saltó): no se vuelve a ofrecer. */
    suspend fun finishSetup() {
        val signed = _state.value as? Connection.SignedIn ?: return
        store.setSetupDone(signed.address)
        _state.value = signed.copy(needsSetup = false)
    }

    suspend fun signOut(notice: Txt? = null) {
        useTunnel(null)
        routerId = null
        client?.forget()
        client = null
        api = null
        monitor.reset()
        store.forgetPassword()
        val saved = store.load()
        _state.value = Connection.SignedOut(notice, saved?.address, saved?.username)
    }

    /**
     * Ejecuta [block] con la API del router. Si el router ya no acepta las credenciales
     * guardadas (p. ej. cambiaron la contraseña), cierra la sesión y avisa al usuario.
     */
    override suspend fun <T> use(block: suspend (OpenWrtApi) -> T): T {
        val current = api ?: throw UbusException.SessionExpired()
        try {
            return block(current)
        } catch (e: UbusException) {
            // Solo si la sesión que falló sigue siendo la de ahora: una lectura que venía de la
            // anterior (se salió, o se entró en otro router) no cierra la nueva.
            val notice = when (e) {
                is UbusException.AuthFailed -> R.string.router_saved_password_rejected
                is UbusException.SessionExpired -> R.string.router_session_ended
                else -> null
            }
            if (notice != null && api === current) signOut(txt(notice))
            throw e
        }
    }

    /**
     * Pone [newPassword] al usuario conectado (luci.setPassword, como LuCI) y la guarda. La
     * sesión sigue abierta; los inicios de sesión siguientes usan la nueva.
     */
    suspend fun changePassword(newPassword: String) {
        val signed = _state.value as? Connection.SignedIn ?: throw UbusException.SessionExpired()
        val current = client ?: throw UbusException.SessionExpired()
        use { it.setPassword(signed.username, newPassword) }
        // En cuanto el router la acepta, se usa y se guarda: si lo siguiente falla, la app ya tiene la buena.
        current.updateCredentials(Credentials(signed.username, newPassword))
        store.save(SavedRouter(signed.address, signed.username, newPassword))
        _state.value = signed.copy(emptyPassword = newPassword.isEmpty())
        try {
            current.login(Credentials(signed.username, newPassword))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // La sesión actual sigue valiendo; el siguiente inicio de sesión ya usa la nueva.
        }
    }

    /** Copia de los perfiles de este router; [router] distingue routers con la misma dirección (el modelo). */
    suspend fun loadBackup(router: String): String? =
        (_state.value as? Connection.SignedIn)?.let { store.loadBackup(it.address + "|" + router) }

    suspend fun saveBackup(router: String, json: String?) {
        (_state.value as? Connection.SignedIn)?.let { store.saveBackup(it.address + "|" + router, json) }
    }

    /** ¿Hay una copia pendiente de restaurar o descartar? Mientras tanto no se sustituye. */
    suspend fun backupPending(router: String): Boolean =
        (_state.value as? Connection.SignedIn)?.let { store.backupPending(it.address + "|" + router) } ?: false

    suspend fun setBackupPending(router: String, pending: Boolean) {
        (_state.value as? Connection.SignedIn)?.let { store.setBackupPending(it.address + "|" + router, pending) }
    }

    /**
     * Apunta el identificador del router conectado (se averigua con la primera lectura de la
     * sesión). Si cambia, el acceso remoto que se use es el de este router.
     */
    suspend fun rememberRouterId(id: String) {
        val signed = _state.value as? Connection.SignedIn ?: return
        if (id == routerId) return
        routerId = id
        store.saveRouterId(id)
        useTunnel(tunnelFor(signed.address, id))
    }

    /**
     * Las funciones que se han visto funcionando en el router [router] (el de `monitor.identity`)
     * desde este teléfono: "funciona aquí" en Estado (ver [markVerified]).
     */
    fun verifiedFeatures(router: String): Flow<Set<Feature>> {
        val signed = _state.value as? Connection.SignedIn ?: return flowOf(emptySet())
        return store.verified(signed.address + "|" + router).map { names -> Feature.entries.filter { it.name in names }.toSet() }
    }

    /**
     * Apunta que [feature] funciona en el router conectado. Se llama cuando un cambio que la activa
     * terminó bien (el router lo aplicó, la app comprobó que funcionaba y lo confirmó) y cuando una
     * lectura la ve en marcha (ver addons/InUse.kt y parental/Verified.kt). Si no se sabe qué
     * router es, no se apunta (no es parte del cambio: nunca falla).
     */
    suspend fun markVerified(feature: Feature) {
        val signed = _state.value as? Connection.SignedIn ?: return
        try {
            store.addVerified(signed.address + "|" + monitor.identity.fresh(Duration.INFINITE), feature.name)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // Sin saber qué router es, mejor no apuntar nada que apuntarlo en otro.
        }
    }

    /** Acceso remoto de este teléfono al router conectado (ver [TunnelConfig]), si se sabe cuál es. */
    suspend fun loadTunnel(): TunnelConfig? {
        val signed = _state.value as? Connection.SignedIn ?: return null
        return tunnelFor(signed.address, routerId)
    }

    /** Guarda (o, con null, borra) el acceso remoto del router [routerId] conectado. */
    suspend fun saveTunnel(routerId: String, config: TunnelConfig?) {
        val signed = _state.value as? Connection.SignedIn ?: return
        require(config == null || config.routerId == routerId)
        store.saveTunnel(signed.address, routerId, config?.toJson())
        if (routerId == this.routerId) useTunnel(config)
    }

    /**
     * Mantiene al día dónde buscar el router fuera de casa: [endpoints], sus direcciones públicas de
     * ahora (vacío: no se cambian), y [hostname], el nombre del DNS dinámico (si [hostnameKnown]).
     */
    suspend fun refreshTunnelAddresses(endpoints: List<String>, hostname: String? = null, hostnameKnown: Boolean = false) {
        val id = routerId ?: return
        val tunnel = loadTunnel()?.takeIf { it.enabled } ?: return
        val updated = tunnel.copy(
            endpoints = endpoints.ifEmpty { tunnel.endpoints },
            hostname = if (hostnameKnown) hostname else tunnel.hostname,
        )
        if (updated != tunnel) saveTunnel(id, updated)
    }

    private suspend fun tunnelFor(address: String, id: String?): TunnelConfig? =
        id?.let { parseTunnel(store.loadTunnel(address, it))?.takeIf { t -> t.routerId == id } }

    private fun useTunnel(config: TunnelConfig?) {
        transport?.config = config
        _tunnel.value = config
    }

    private fun open(newClient: UbusClient) {
        client?.forget()
        client = newClient
        api = OpenWrtApi(newClient, ddnsHttp)
        // Lo leído con la sesión anterior puede ser de otro router.
        monitor.reset()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}
