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

    /** El nombre y la dirección fija de cada aparato (escribe en `dhcp` con reversión automática). */
    val deviceSettings = DevicesApi(ubus, writeLock)

    /** El Wi-Fi de casa: nombre, clave, seguridad y bandas (escribe en `wireless` con reversión automática). */
    val wifi = WifiApi(ubus, writeLock)

    /** El Wi-Fi de invitados: una red aparte, aislada de la casa (escribe en network, dhcp, firewall y wireless). */
    val guestWifi = GuestWifiApi(ubus, writeLock)

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

    /** Qué puede hacer este router con la app (ver [Capabilities]). [board] e [info], ya leídos. */
    suspend fun capabilities(board: BoardInfo, info: SystemInfo): Capabilities = coroutineScope {
        val acl = async { ubus.acl() }
        val objects = async { optional { ubus.objects() } }
        val features = async { optional { ubus.callAs<LuciFeatures>("luci", "getFeatures") } }
        val radios = async { optional { ubus.callAs<Map<String, WirelessDeviceJson>>("luci-rpc", "getWirelessDevices") } }
        Capabilities(
            version = board.release.version,
            target = board.release.target,
            memoryBytes = info.memory.total,
            freeStorageKiB = info.root?.avail,
            objects = objects.await(),
            acl = acl.await(),
            features = features.await(),
            radios = parseRadios(radios.await().orEmpty()),
        )
    }

    /**
     * Cómo está el enlace de la salida a internet (ver [WanLink]); null si no es una tarjeta de red
     * con enlace que mirar (una VM, un módem USB) o el router no deja verlo.
     */
    suspend fun wanLink(interfaces: List<NetInterface>): WanLink? = ubus.readWanLink(interfaces)

    /** Lo que el router puede no tener o no dejar ver: null en vez de error. Si no se llega a él, sí falla. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: UbusException.AccessDenied) {
        null
    } catch (_: UbusException.Status) {
        null
    } catch (_: UbusException.Protocol) {
        null
    }

    /** Los equipos de casa. [knownInterfaces], si ya se han leído, ahorra pedirlas otra vez. */
    suspend fun devices(knownInterfaces: List<NetInterface>? = null): List<Device> = coroutineScope {
        val hints = async { ubus.callAs<Map<String, HostHint>>("luci-rpc", "getHostHints") }
        val leases = async { ubus.callAs<DhcpLeases>("luci-rpc", "getDHCPLeases") }
        val ifaces = async { knownInterfaces ?: interfaces() }
        val records = async { optional { parseDeviceRecords(ubus.uciSections("dhcp", "host")) } }
        val neighbors = async { neighbors() }
        buildDeviceList(hints.await(), leases.await(), ifaces.await(), records.await().orEmpty(), neighbors.await())
    }

    /** Cómo dice llamarse por mDNS el aparato que tiene [ip] (ver Mdns.kt), o null si no lo dice. */
    suspend fun mdnsName(ip: String): String? = ubus.mdnsName(ip)

    /**
     * Quién contesta ahora, según la tabla de vecinos (la que enseña la página de estado de LuCI:
     * su ACL deja ejecutar justo estos dos comandos). null si el router no deja verla.
     */
    private suspend fun neighbors(): Map<String, Presence>? = coroutineScope {
        val v4 = async { optional { ubus.exec("/sbin/ip", listOf("-4", "neigh", "show")) } }
        val v6 = async { optional { ubus.exec("/sbin/ip", listOf("-6", "neigh", "show")) } }
        // Si el comando falla, no se sabe quién está: no es que no haya nadie.
        val outputs = listOfNotNull(v4.await(), v6.await()).filter { it.code == 0 }
        if (outputs.isEmpty()) null else parseNeighbors(outputs.joinToString("\n") { it.stdout })
    }
}

@Serializable
internal data class DeviceStatus(val macaddr: String? = null)
