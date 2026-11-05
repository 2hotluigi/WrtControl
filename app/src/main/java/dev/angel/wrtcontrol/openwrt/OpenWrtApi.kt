package dev.angel.wrtcontrol.openwrt

import android.util.Log
import dev.angel.wrtcontrol.parental.ParentalApi
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.coroutineScope

/**
 * Llamadas tipadas a OpenWrt (22.03+). Solo usan objetos ubus que trae LuCI:
 * no hace falta instalar nada en el router.
 */
class OpenWrtApi(private val ubus: UbusClient) {

    /** Perfiles, pausas y horarios (escribe en el firewall con reversión automática). */
    val parental = ParentalApi(ubus, log = { Log.d("WrtControl", it) })

    suspend fun board(): BoardInfo = ubus.callAs("system", "board")

    suspend fun setPassword(username: String, password: String) = ubus.setPassword(username, password)

    /**
     * Identifica el aparato (para no mezclar copias de routers distintos en la misma dirección):
     * el modelo y la MAC de la red de casa, que no cambian al restablecerlo.
     */
    suspend fun routerId(): String = coroutineScope {
        val board = async { board() }
        val lanMac = try {
            val lan = interfaces().firstOrNull { it.name == "lan" }
            val device = lan?.device ?: lan?.l3Device
            device?.let { ubus.callAs<DeviceStatus>("network.device", "status", buildJsonObject { put("name", it) }).macaddr }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        board.await().model + "|" + lanMac.orEmpty().uppercase()
    }

    suspend fun systemInfo(): SystemInfo = ubus.callAs("system", "info")

    suspend fun interfaces(): List<NetInterface> =
        ubus.callAs<InterfaceDump>("network.interface", "dump").interfaces.filter { it.name != "loopback" }

    suspend fun devices(): List<Device> = coroutineScope {
        val hints = async { ubus.callAs<Map<String, HostHint>>("luci-rpc", "getHostHints") }
        val leases = async { ubus.callAs<DhcpLeases>("luci-rpc", "getDHCPLeases") }
        val ifaces = async { interfaces() }
        buildDeviceList(hints.await(), leases.await(), ifaces.await())
    }
}

@Serializable
internal data class DeviceStatus(val macaddr: String? = null)
