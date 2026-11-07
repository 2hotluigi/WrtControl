package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

sealed interface Connection {
    data object Loading : Connection

    /** Sin router conectado. Se rellenan los últimos datos usados para facilitar la reconexión. */
    data class SignedOut(
        val notice: String? = null,
        val lastAddress: String? = null,
        val lastUsername: String? = null,
    ) : Connection

    /** [emptyPassword]: el router acepta entrar sin contraseña, así que cualquiera en la red puede cambiarlo. */
    data class SignedIn(val address: String, val username: String, val emptyPassword: Boolean = false) : Connection
}

/** Punto único de acceso al router: conexión, credenciales guardadas y API. */
class RouterRepository(
    private val store: RouterStore,
    private val http: OkHttpClient,
    /** Camino hasta el router: directo en casa, por el acceso remoto fuera. */
    val transport: RouterTransport? = null,
) {
    private val _state = MutableStateFlow<Connection>(Connection.Loading)
    val state: StateFlow<Connection> = _state.asStateFlow()

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

    /** Al arrancar la app: si hay un router guardado con contraseña, se entra directamente. */
    suspend fun restore() {
        val saved = store.load()
        if (saved?.password == null) {
            _state.value = Connection.SignedOut(lastAddress = saved?.address, lastUsername = saved?.username)
            return
        }
        // El login real se hace en la primera llamada (UbusClient renueva la sesión solo).
        routerId = saved.routerId
        transport?.config = tunnelFor(saved.address, saved.routerId)
        open(UbusClient(RouterAddress.endpoint(saved.address), http, Credentials(saved.username, saved.password)))
        _state.value = Connection.SignedIn(saved.address, saved.username, saved.password.isEmpty())
    }

    /** Prueba las credenciales contra el router y, si funcionan, las guarda. */
    suspend fun connect(address: String, username: String, password: String) {
        val endpoint = RouterAddress.endpoint(address)
        // Si ya se conocía este router, se entra por su acceso remoto (que comprueba que es él).
        val known = store.load()?.takeIf { it.address == address.trim() }?.routerId
        transport?.config = tunnelFor(address.trim(), known)
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
        if (id != null && id != known) transport?.config = tunnelFor(address.trim(), id)
        open(newClient)
        _state.value = Connection.SignedIn(address.trim(), username, password.isEmpty())
    }

    suspend fun signOut(notice: String? = null) {
        transport?.config = null
        routerId = null
        client?.forget()
        client = null
        api = null
        store.forgetPassword()
        val saved = store.load()
        _state.value = Connection.SignedOut(notice, saved?.address, saved?.username)
    }

    /**
     * Ejecuta [block] con la API del router. Si el router ya no acepta las credenciales
     * guardadas (p. ej. cambiaron la contraseña), cierra la sesión y avisa al usuario.
     */
    suspend fun <T> use(block: suspend (OpenWrtApi) -> T): T {
        val current = api ?: throw UbusException.SessionExpired()
        try {
            return block(current)
        } catch (e: UbusException.AuthFailed) {
            signOut("El router rechazó la contraseña guardada. Vuelve a conectar.")
            throw e
        } catch (e: UbusException.SessionExpired) {
            signOut("La sesión terminó. Vuelve a conectar.")
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
     * Apunta el identificador del router conectado (lo averiguan las pantallas al leer el router).
     * Si cambia, el acceso remoto que se use es el de este router.
     */
    suspend fun rememberRouterId(id: String) {
        val signed = _state.value as? Connection.SignedIn ?: return
        if (id == routerId) return
        routerId = id
        store.saveRouterId(id)
        transport?.config = tunnelFor(signed.address, id)
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
        if (routerId == this.routerId) transport?.config = config
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

    private fun open(newClient: UbusClient) {
        client?.forget()
        client = newClient
        api = OpenWrtApi(newClient)
    }
}
