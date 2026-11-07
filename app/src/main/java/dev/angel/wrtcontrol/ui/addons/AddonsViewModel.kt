package dev.angel.wrtcontrol.ui.addons

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.addons.Addon
import dev.angel.wrtcontrol.addons.AddonsState
import dev.angel.wrtcontrol.addons.DdnsEdit
import dev.angel.wrtcontrol.addons.RemotePeer
import dev.angel.wrtcontrol.addons.X25519
import dev.angel.wrtcontrol.data.TunnelConfig
import dev.angel.wrtcontrol.addons.SqmEdit
import dev.angel.wrtcontrol.addons.UpnpMapping
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.openwrt.InstalledPackages
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.openwrt.PackageTool
import dev.angel.wrtcontrol.ui.Loadable
import dev.angel.wrtcontrol.ui.pollingFlow
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * [packages] null: el router no deja listar ni instalar paquetes desde la app. [tunnel]: el acceso
 * remoto de este teléfono a este router, si lo tiene.
 */
data class AddonsData(val state: AddonsState, val packages: InstalledPackages?, val tunnel: TunnelConfig? = null) {
    /** Este teléfono figura como par en el router. */
    val phoneHasAccess: Boolean
        get() = tunnel?.enabled == true && state.remote?.peers?.any { it.id == tunnel.peerId } == true
}

private const val TAG = "WrtControl"

/** Cada cuánto se vuelve a pedir el listado de paquetes aunque no se haya instalado nada. */
private const val PACKAGES_MAX_AGE_MS = 5 * 60_000L

/** Identificador del teléfono en el router (parte del nombre de su sección): 10 letras y cifras. */
private fun newPeerId(): String {
    val random = java.security.SecureRandom()
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

class AddonsViewModel(private val repo: RouterRepository) : ViewModel() {

    private val refresh = Channel<Unit>(Channel.CONFLATED)

    /** El listado de paquetes pesa cientos de KB: se pide al entrar y después de instalar algo. */
    @Volatile
    private var packages: InstalledPackages? = null

    /** Sesión con la que se pidió [packages]; si cambia (otro router), se vuelve a pedir. */
    @Volatile
    private var packagesFrom: OpenWrtApi? = null

    @Volatile
    private var packagesStale = true

    /** Cuándo se pidió [packages]: se vuelve a pedir pasado un rato (pudo cambiar desde LuCI). */
    @Volatile
    private var packagesAt = 0L

    /** Modelo y MAC de la LAN del router: el acceso remoto guardado solo vale para ese. */
    @Volatile
    private var routerId: String? = null

    @Volatile
    private var routerIdFrom: OpenWrtApi? = null

    val state: StateFlow<Loadable<AddonsData>> = pollingFlow(15.seconds, refresh) {
        repo.use { api ->
            coroutineScope {
                val state = async { api.addons.state() }
                val now = android.os.SystemClock.elapsedRealtime()
                if (packagesStale || packagesFrom !== api || now - packagesAt > PACKAGES_MAX_AGE_MS) {
                    packages = api.addons.packages()
                    packagesFrom = api
                    packagesAt = now
                    packagesStale = false
                }
                if (routerIdFrom !== api) {
                    routerId = api.routerId().also { repo.rememberRouterId(it) }
                    routerIdFrom = api
                }
                val s = state.await()
                // Lo que usará fuera de casa: el nombre del DNS dinámico (si se pudo leer) y las
                // direcciones públicas de ahora.
                repo.refreshTunnelAddresses(s.publicAddresses, s.ddnsDomain, hostnameKnown = s.ddns != null)
                AddonsData(s, packages, repo.loadTunnel())
            }
        }
    }
        // Al volver a la pantalla (o a la app), el listado de paquetes se pide de nuevo.
        .onStart { packagesStale = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())

    /** Qué se está haciendo en el router ahora mismo (null si nada). */
    var busy by mutableStateOf<String?>(null)
        private set

    /** Error de la última acción, hasta que el usuario lo cierre o haga otra. */
    var error by mutableStateOf<String?>(null)

    fun install(addon: Addon, tool: PackageTool) =
        run("Instalando ${addon.title}… Puede tardar hasta un minuto.", packagesChanged = true) { it.addons.install(addon, tool) }

    fun finishWireGuard() = run("Reiniciando la red del router…") { it.addons.finishWireGuard() }

    fun saveSqm(edit: SqmEdit, onDone: () -> Unit = {}) =
        run("Aplicando el SQM…", onSuccess = onDone) { it.addons.saveSqm(edit) }

    fun setUpnp(enabled: Boolean) =
        run(if (enabled) "Activando UPnP…" else "Desactivando UPnP…") { it.addons.setUpnp(enabled = enabled) }

    fun setUpnpSecure(on: Boolean) = run("Cambiando el modo seguro de UPnP…") { it.addons.setUpnp(secureMode = on) }

    /**
     * Da acceso remoto a este teléfono ([phoneName]). La clave se crea aquí y se guarda antes de
     * tocar el router: si la app se cierra a mitad, al repetir se usa la misma.
     */
    fun enableRemoteAccess(phoneName: String) = run("Configurando el acceso remoto…") { api ->
        val id = routerId ?: api.routerId().also { repo.rememberRouterId(it) }
        val old = repo.loadTunnel()?.takeIf { it.routerId == id }
        val config = old ?: TunnelConfig(
            routerId = id,
            peerId = newPeerId(),
            privateKey = X25519.generatePrivateKey(),
            presharedKey = X25519.generatePresharedKey(),
        ).also { repo.saveTunnel(id, it) }
        val setup = api.addons.enableRemoteAccess(config.peerId, phoneName, X25519.publicKey(config.privateKey), config.presharedKey)
        repo.saveTunnel(
            id,
            config.copy(
                enabled = true,
                routerPublicKey = setup.routerPublicKey,
                port = setup.port,
                routerTunnelAddress = setup.routerTunnelAddress,
                webAddress = setup.webAddress,
                phoneAddress = setup.phoneAddress,
                endpoints = setup.publicAddresses.ifEmpty { config.endpoints },
            ),
        )
    }

    /**
     * Quita el acceso remoto de [peer]. Si es este teléfono, se hace por la conexión directa: al
     * quitarlo, el router corta el túnel por el que habría que comprobar el cambio. Por eso solo se
     * puede desde casa.
     */
    fun removeRemotePeer(peer: RemotePeer) = run("Quitando el acceso remoto de ${peer.name}…") { api ->
        val tunnel = repo.loadTunnel()
        val mine = tunnel?.peerId == peer.id
        val transport = repo.transport
        if (mine && transport != null) transport.directOnly = true
        try {
            if (mine) {
                try {
                    api.systemInfo()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    throw IllegalStateException(
                        "Ahora la app entra al router por el acceso remoto de este teléfono: quitarlo cortaría la conexión. " +
                            "Quítalo desde casa.",
                    )
                }
            }
            api.addons.removeRemotePeer(peer.id)
            if (mine) repo.saveTunnel(tunnel!!.routerId, null)
        } finally {
            if (mine && transport != null) transport.directOnly = false
        }
    }

    fun saveDdns(edit: DdnsEdit, onDone: () -> Unit = {}) =
        run("Configurando el DNS dinámico…", onSuccess = onDone) { it.addons.saveDdns(edit) }

    fun removeDdns() = run("Quitando el DNS dinámico…") { it.addons.removeDdns() }

    fun closeMapping(mapping: UpnpMapping) =
        run("Cerrando el puerto ${mapping.externalPort}…") { it.addons.closeUpnpMapping(mapping) }

    private fun run(
        label: String,
        packagesChanged: Boolean = false,
        onSuccess: () -> Unit = {},
        block: suspend (OpenWrtApi) -> Unit,
    ) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                repo.use { block(it) }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falló: $label", e)
                error = e.userMessage()
            } finally {
                // Aunque falle, la instalación pudo quedar a medias: se vuelve a mirar qué hay.
                if (packagesChanged) packagesStale = true
                busy = null
                refresh.trySend(Unit)
            }
        }
    }
}
