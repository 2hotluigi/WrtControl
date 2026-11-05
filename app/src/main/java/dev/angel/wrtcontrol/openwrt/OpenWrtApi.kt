package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Llamadas tipadas a OpenWrt (22.03+). Solo usan objetos ubus que trae LuCI:
 * no hace falta instalar nada en el router para esta parte.
 */
class OpenWrtApi(private val ubus: UbusClient) {

    suspend fun board(): BoardInfo = ubus.callAs("system", "board")

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
