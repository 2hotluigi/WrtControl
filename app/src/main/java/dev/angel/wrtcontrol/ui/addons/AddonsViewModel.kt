package dev.angel.wrtcontrol.ui.addons

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.addons.Addon
import dev.angel.wrtcontrol.addons.AddonsState
import dev.angel.wrtcontrol.addons.DdnsEdit
import dev.angel.wrtcontrol.addons.RemotePeer
import dev.angel.wrtcontrol.addons.SqmEdit
import dev.angel.wrtcontrol.addons.UpnpMapping
import dev.angel.wrtcontrol.addons.X25519
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.data.TunnelConfig
import dev.angel.wrtcontrol.openwrt.InstalledPackages
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.openwrt.PackageTool
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
private val PACKAGES_REFRESH = 5.minutes

private val ADDONS_REFRESH = 15.seconds

/** Identificador del teléfono en el router (parte del nombre de su sección): 10 letras y cifras. */
private fun newPeerId(): String {
    val random = java.security.SecureRandom()
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

class AddonsViewModel(private val repo: RouterRepository) : ViewModel() {

    private val monitor = repo.monitor

    val state: StateFlow<Loadable<AddonsData>> =
        combine(monitor.addons.watch(ADDONS_REFRESH), monitor.packages.watch(PACKAGES_REFRESH), repo.tunnel) { addons, packages, tunnel ->
            val state = addons.data
            val listing = packages.data
            val error = addons.error ?: packages.error
            if (state == null || listing == null) Loadable(error = error) else Loadable(AddonsData(state, listing.installed, tunnel), error)
        }
            // Al volver a la pantalla (o a la app), el listado de paquetes se pide de nuevo.
            .onStart { monitor.packages.refresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())

    /** Qué se está haciendo en el router ahora mismo (null si nada). */
    var busy by mutableStateOf<Txt?>(null)
        private set

    /** Error de la última acción, hasta que el usuario lo cierre o haga otra. */
    var error by mutableStateOf<Txt?>(null)

    /** Aviso de una acción que salió bien pero con algo que saber (p. ej. del DNS dinámico). */
    var notice by mutableStateOf<Txt?>(null)

    fun install(addon: Addon, tool: PackageTool) =
        run(txt(R.string.addons_busy_installing, addon.title), packagesChanged = true) { it.addons.install(addon, tool) }

    fun finishWireGuard() = run(txt(R.string.addons_busy_restarting_network)) { it.addons.finishWireGuard() }

    fun saveSqm(edit: SqmEdit, onDone: () -> Unit = {}) =
        run(txt(R.string.addons_busy_sqm), onSuccess = onDone) { it.addons.saveSqm(edit) }

    fun setUpnp(enabled: Boolean) =
        run(txt(if (enabled) R.string.addons_busy_upnp_on else R.string.addons_busy_upnp_off)) { it.addons.setUpnp(enabled = enabled) }

    fun setUpnpSecure(on: Boolean) = run(txt(R.string.addons_busy_upnp_secure)) { it.addons.setUpnp(secureMode = on) }

    /**
     * Da acceso remoto a este teléfono ([phoneName]). La clave se crea aquí y se guarda antes de
     * tocar el router: si la app se cierra a mitad, al repetir se usa la misma.
     */
    fun enableRemoteAccess(phoneName: String) = run(txt(R.string.addons_busy_remote_on)) { api ->
        val id = monitor.identity.fresh(Duration.INFINITE).also { repo.rememberRouterId(it) }
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
    fun removeRemotePeer(peer: RemotePeer) = run(txt(R.string.addons_busy_remote_off, peer.name)) { api ->
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
                    throw UserFacingException(txt(R.string.addons_remove_remote_from_home))
                }
            }
            api.addons.removeRemotePeer(peer.id)
            if (mine) repo.saveTunnel(tunnel!!.routerId, null)
        } finally {
            if (mine && transport != null) transport.directOnly = false
        }
    }

    fun saveDdns(edit: DdnsEdit, onDone: () -> Unit = {}) =
        run(txt(R.string.addons_busy_ddns_on), onSuccess = onDone) { notice = it.addons.saveDdns(edit) }

    fun removeDdns() = run(txt(R.string.addons_busy_ddns_off)) { it.addons.removeDdns() }

    fun closeMapping(mapping: UpnpMapping) =
        run(txt(R.string.addons_busy_closing_port, mapping.externalPort)) { it.addons.closeUpnpMapping(mapping) }

    private fun run(
        label: Txt,
        packagesChanged: Boolean = false,
        onSuccess: () -> Unit = {},
        block: suspend (OpenWrtApi) -> Unit,
    ) {
        if (busy != null) return
        busy = label
        error = null
        notice = null
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
                if (packagesChanged) {
                    monitor.packages.refresh()
                    monitor.capabilities.refresh()
                }
                busy = null
                monitor.addons.refresh()
            }
        }
    }
}
