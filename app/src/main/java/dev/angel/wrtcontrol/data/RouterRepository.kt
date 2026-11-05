package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

sealed interface Connection {
    data object Loading : Connection

    /** Sin router conectado. Se rellenan los últimos datos usados para facilitar la reconexión. */
    data class SignedOut(
        val notice: String? = null,
        val lastAddress: String? = null,
        val lastUsername: String? = null,
    ) : Connection

    data class SignedIn(val address: String, val username: String) : Connection
}

/** Punto único de acceso al router: conexión, credenciales guardadas y API. */
class RouterRepository(
    private val store: RouterStore,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<Connection>(Connection.Loading)
    val state: StateFlow<Connection> = _state.asStateFlow()

    @Volatile
    private var api: OpenWrtApi? = null

    @Volatile
    private var client: UbusClient? = null

    /** Al arrancar la app: si hay un router guardado con contraseña, se entra directamente. */
    suspend fun restore() {
        val saved = store.load()
        if (saved?.password == null) {
            _state.value = Connection.SignedOut(lastAddress = saved?.address, lastUsername = saved?.username)
            return
        }
        // El login real se hace en la primera llamada (UbusClient renueva la sesión solo).
        open(UbusClient(RouterAddress.endpoint(saved.address), http, Credentials(saved.username, saved.password)))
        _state.value = Connection.SignedIn(saved.address, saved.username)
    }

    /** Prueba las credenciales contra el router y, si funcionan, las guarda. */
    suspend fun connect(address: String, username: String, password: String) {
        val endpoint = RouterAddress.endpoint(address)
        val newClient = UbusClient(endpoint, http)
        newClient.login(Credentials(username, password))
        store.save(SavedRouter(address.trim(), username, password))
        open(newClient)
        _state.value = Connection.SignedIn(address.trim(), username)
    }

    suspend fun signOut(notice: String? = null) {
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

    private fun open(newClient: UbusClient) {
        client?.forget()
        client = newClient
        api = OpenWrtApi(newClient)
    }
}
