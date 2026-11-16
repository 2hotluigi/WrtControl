package dev.angel.wrtcontrol.openwrt

import android.util.Log
import dev.angel.wrtcontrol.addons.AddonsApi
import dev.angel.wrtcontrol.parental.ParentalApi
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Llamadas tipadas a OpenWrt (22.03+). Solo usan objetos ubus que trae LuCI:
 * no hace falta instalar nada en el router.
 */
class OpenWrtApi(
    private val ubus: UbusClient,
    /** Para hablar con el proveedor del DNS dinámico por internet, no por el router. */
    ddnsHttp: dev.angel.wrtcontrol.addons.DdnsHttp? = null,
) {

    /** Un cambio a la vez: rpcd solo admite un `uci apply` pendiente de confirmar. */
    private val writeLock = Mutex()

    /** Perfiles, pausas y horarios (escribe en el firewall con reversión automática). */
    val parental = ParentalApi(ubus, log = { Log.d("WrtControl", it) }, writeLock = writeLock)

    /** Paquetes opcionales: WireGuard, DNS dinámico, SQM y UPnP. */
    val addons = AddonsApi(ubus, writeLock, ddnsHttp = ddnsHttp)

    suspend fun board(): BoardInfo = ubus.callAs("system", "board")

    suspend fun setPassword(username: String, password: String) = ubus.setPassword(username, password)

    /**
     * Identifica el aparato (para no mezclar copias de routers distintos en la misma dirección):
     * el modelo y la MAC de la red de casa, que no cambian al restablecerlo. [board] e [interfaces],
     * si ya se han leído, ahorran pedirlos otra vez.
     */
    suspend fun routerId(board: BoardInfo? = null, interfaces: List<NetInterface>? = null): String = coroutineScope {
        val known = async { board ?: board() }
        val lanMac = try {
            val lan = (interfaces ?: interfaces()).firstOrNull { it.name == "lan" }
            val device = lan?.device ?: lan?.l3Device
            device?.let { ubus.callAs<DeviceStatus>("network.device", "status", buildJsonObject { put("name", it) }).macaddr }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UbusException.Network) {
            // No se llegó al router: no es que no deje verla. Con una MAC vacía parecería otro router.
            throw e
        } catch (_: Exception) {
            null
        }
        known.await().model + "|" + lanMac.orEmpty().uppercase()
    }

    suspend fun systemInfo(): SystemInfo = ubus.callAs("system", "info")

    suspend fun interfaces(): List<NetInterface> =
        ubus.callAs<InterfaceDump>("network.interface", "dump").interfaces.filter { it.name != "loopback" }

    /** Los equipos de casa. [knownInterfaces], si ya se han leído, ahorra pedirlas otra vez. */
    suspend fun devices(knownInterfaces: List<NetInterface>? = null): List<Device> = coroutineScope {
        val hints = async { ubus.callAs<Map<String, HostHint>>("luci-rpc", "getHostHints") }
        val leases = async { ubus.callAs<DhcpLeases>("luci-rpc", "getDHCPLeases") }
        val ifaces = async { knownInterfaces ?: interfaces() }
        buildDeviceList(hints.await(), leases.await(), ifaces.await())
    }
}

@Serializable
internal data class DeviceStatus(val macaddr: String? = null)
