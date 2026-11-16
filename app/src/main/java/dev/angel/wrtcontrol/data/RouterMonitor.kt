package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.addons.AddonsState
import dev.angel.wrtcontrol.addons.publicAddresses
import dev.angel.wrtcontrol.describeError
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.InstalledPackages
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.openwrt.WanLink
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Quien tiene la sesión con el router: [RouterRepository] en la app, uno de pega en las pruebas. */
interface RouterAccess {
    suspend fun <T> use(block: suspend (OpenWrtApi) -> T): T
}

/** Lo que enseña "Estado": el aparato, cómo va de carga y memoria, y sus redes. */
data class StatusData(val board: BoardInfo, val info: SystemInfo, val interfaces: List<NetInterface>)

/** [link] null: la salida a internet no va por una tarjeta de red con enlace que mirar. */
data class WanLinkState(val link: WanLink?)

/** [installed] null: el router no deja listar ni instalar paquetes desde la app. */
data class PackageListing(val installed: InstalledPackages?)

/**
 * Todo lo que la app lee del router para enseñarlo, en un solo sitio. Cada lectura ([Feed]) se
 * comparte: la pide una vez aunque la miren varias pantallas, y las que se apoyan en otra (los
 * equipos necesitan las redes) aprovechan la que ya está leída en vez de repetirla.
 *
 * Aquí solo se lee. Lo que la app apunta a partir de lo leído va aparte, en [followRouter].
 */
class RouterMonitor(
    private val scope: CoroutineScope,
    private val router: RouterAccess,
    private val describe: (Throwable) -> Txt = ::describeError,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val feeds = ArrayList<Feed<*>>()

    /** Modelo, nombre y versión: casi nunca cambian. */
    val board: Feed<BoardInfo> = feed { router.use { it.board() } }

    /** Carga, memoria y tiempo encendido. */
    val system: Feed<SystemInfo> = feed { router.use { it.systemInfo() } }

    /** Las redes del router (WAN, LAN, invitados…) y sus direcciones. */
    val interfaces: Feed<List<NetInterface>> = feed { router.use { it.interfaces() } }

    val status: Feed<StatusData> = feed {
        coroutineScope {
            val about = async { board.fresh(STABLE_MAX_AGE) }
            val info = async { system.fresh() }
            val networks = async { interfaces.fresh(SHARED_MAX_AGE) }
            StatusData(about.await(), info.await(), networks.await())
        }
    }

    /** Los equipos de casa. */
    val devices: Feed<List<Device>> = feed {
        val known = interfaces.fresh(SHARED_MAX_AGE)
        router.use { it.devices(known) }
    }

    /** Cómo está el cable de la salida a internet; `link` null si no hay tarjeta que mirar. */
    val wanLink: Feed<WanLinkState> = feed {
        val known = interfaces.fresh(SHARED_MAX_AGE)
        router.use { WanLinkState(it.wanLink(known)) }
    }

    /** Qué router es (modelo y MAC de la red de casa). Se averigua una vez por sesión. */
    val identity: Feed<String> = feed {
        coroutineScope {
            val about = async { board.fresh(STABLE_MAX_AGE) }
            val networks = async { interfaces.fresh(STABLE_MAX_AGE) }
            val (knownBoard, knownInterfaces) = about.await() to networks.await()
            router.use { it.routerId(knownBoard, knownInterfaces) }
        }
    }

    /** Qué puede hacer este router con la app. Cambia al instalar paquetes. */
    val capabilities: Feed<Capabilities> = feed {
        coroutineScope {
            val about = async { board.fresh(STABLE_MAX_AGE) }
            val info = async { system.fresh(STABLE_MAX_AGE) }
            val (knownBoard, knownInfo) = about.await() to info.await()
            router.use { it.capabilities(knownBoard, knownInfo) }
        }
    }

    /** Perfiles, horarios y protección. */
    val parental: Feed<ParentalSnapshot> = feed { router.use { it.parental.load() } }

    /** Acceso remoto, DNS dinámico, SQM y UPnP. */
    val addons: Feed<AddonsState> = feed { router.use { it.addons.state() } }

    /** Los paquetes instalados. Pesa cientos de KB: se mira de tarde en tarde. */
    val packages: Feed<PackageListing> = feed { router.use { PackageListing(it.addons.packages()) } }

    /** Otra sesión (otro router, o se salió): nada de lo leído vale. */
    fun reset() = feeds.forEach { it.reset() }

    private fun <T : Any> feed(load: suspend () -> T): Feed<T> = Feed(scope, describe, now, load).also { feeds += it }

    companion object {
        /** Una lectura hecha hace menos que esto vale para otra pantalla que la necesite ahora. */
        val SHARED_MAX_AGE = 2.seconds

        /** Para lo que casi nunca cambia: el modelo, el nombre, la tarjeta de la red de casa. */
        val STABLE_MAX_AGE = 1.minutes

        private const val NANOS_PER_MILLI = 1_000_000
    }
}

/**
 * Lo que la app apunta del router según lo va leyendo: cuál es (para usar su acceso remoto y no el
 * de otro que esté en la misma dirección) y por dónde se llega a él desde fuera. Va aparte de las
 * lecturas: reacciona a lo que traen, las haya pedido la pantalla que las haya pedido. Antes de
 * apuntar direcciones se sabe de qué router son.
 */
internal fun CoroutineScope.followRouter(
    monitor: RouterMonitor,
    onIdentified: suspend (routerId: String) -> Unit,
    onAddresses: suspend (endpoints: List<String>, hostname: String?, hostnameKnown: Boolean) -> Unit,
): Job = launch {
    suspend fun identify(): Boolean {
        val id = try {
            monitor.identity.fresh(Duration.INFINITE)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return false // se vuelve a intentar con la siguiente lectura
        }
        onIdentified(id)
        return true
    }
    launch {
        monitor.interfaces.state.mapNotNull { it.data }.collect { interfaces ->
            // Si mientras tanto cambió la sesión, estas redes son de otro router.
            if (identify() && monitor.interfaces.state.value.data === interfaces) onAddresses(publicAddresses(interfaces), null, false)
        }
    }
    launch {
        monitor.addons.state.mapNotNull { it.data }.collect { state ->
            if (identify() && monitor.addons.state.value.data === state) {
                onAddresses(state.publicAddresses, state.ddnsDomain, state.ddns != null)
            }
        }
    }
}
