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

    /** Modelo y MAC de la LAN del router: el acceso remoto guardado solo vale para ese. */
    @Volatile
    private var routerId: String? = null

    @Volatile
    private var routerIdFrom: OpenWrtApi? = null

    val state: StateFlow<Loadable<AddonsData>> = pollingFlow(15.seconds, refresh) {
        repo.use { api ->
            coroutineScope {
                val state = async { api.addons.state() }
                if (packagesStale || packagesFrom !== api) {
                    packages = api.addons.packages()
                    packagesFrom = api
                    packagesStale = false
                }
                if (routerIdFrom !== api) {
                    routerId = api.routerId()
                    routerIdFrom = api
                }
                val s = state.await()
                var tunnel = repo.loadTunnel()?.takeIf { it.routerId == routerId }
                // Lo que usará fuera de casa: el nombre del DNS dinámico y, por si no lo hay o tarda
                // en actualizarse, las direcciones públicas de ahora.
                if (tunnel != null && tunnel.enabled) {
                    val updated = tunnel.copy(
                        endpoints = s.publicAddresses.ifEmpty { tunnel.endpoints },
                        // Si no se pudo leer el DNS dinámico, se queda el nombre que había.
                        hostname = if (s.ddns != null) s.ownDdns?.domain else tunnel.hostname,
                    )
                    if (updated != tunnel) {
                        tunnel = updated
                        repo.saveTunnel(updated)
                    }
                }
                AddonsData(s, packages, tunnel)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())

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
        val id = routerId ?: api.routerId()
        val old = repo.loadTunnel()?.takeIf { it.routerId == id }
        val config = old ?: TunnelConfig(
            routerId = id,
            peerId = newPeerId(),
            privateKey = X25519.generatePrivateKey(),
            presharedKey = X25519.generatePresharedKey(),
        ).also { repo.saveTunnel(it) }
        val setup = api.addons.enableRemoteAccess(config.peerId, phoneName, X25519.publicKey(config.privateKey), config.presharedKey)
        repo.saveTunnel(
            config.copy(
                enabled = true,
                routerPublicKey = setup.routerPublicKey,
                port = setup.port,
                routerTunnelAddress = setup.routerTunnelAddress,
                phoneAddress = setup.phoneAddress,
                endpoints = setup.publicAddresses.ifEmpty { config.endpoints },
            ),
        )
    }

    /** Quita el acceso remoto de [peer] (este teléfono u otro). */
    fun removeRemotePeer(peer: RemotePeer) = run("Quitando el acceso remoto de ${peer.name}…") { api ->
        api.addons.removeRemotePeer(peer.id)
        if (repo.loadTunnel()?.peerId == peer.id) repo.saveTunnel(null)
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
