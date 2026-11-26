package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lo que gasta cada aparato de casa (nlbwmon, ver Nlbwmon.kt): lo lee y lo instala. */
class DeviceUsageApi internal constructor(
    private val ubus: UbusClient,
    private val writeLock: Mutex = Mutex(),
    private val pollMs: Long = POLL_MS,
) {
    /** Si el router cuenta lo de cada aparato y, si lo hace, lo de este periodo. [interfaces]: para quitar al router. */
    suspend fun recorder(interfaces: List<NetInterface>, freeKiB: Long?): DeviceRecorder {
        // Sin su configuración no está instalado; sin su permiso de LuCI, la app no puede leerlo.
        val section = optional { ubus.uciSections("nlbwmon", "nlbwmon").firstOrNull() }
        val out = section?.let { optional { ubus.exec(NLBW_ACTION, listOf("download", "-g", "mac,ip")) } }
        val monthly = section?.text("database_interval").let { it == null || it == "1" }
        return when {
            section == null || out == null -> DeviceRecorder.Missing(freeKiB)
            !nlbwKeepsData(section) -> DeviceRecorder.NeedsSetup
            else -> {
                // Lo que la app puso y ya no vale se quita al ajustarlo por otra cosa: no cuenta nada de más.
                val relay = ipv6Relay(optional { ubus.uciSections("dhcp", "dhcp") }.orEmpty())
                val missesIpv6 = relayPrefixes(interfaces, relay).any { it !in section.items("local_network") }
                // Recién arrancado, aún no responde: nada contado todavía.
                val devices = if (out.code != 0) {
                    emptyList()
                } else {
                    parseDeviceUsage(UbusJson.decodeFromString(NlbwJson.serializer(), out.stdout), routerAddresses(interfaces))
                }
                DeviceRecorder.Ready(devices, monthly, missesIpv6)
            }
        }
    }

    /** Instala nlbwmon y lo deja guardando en la flash ([setUp]). */
    suspend fun install(interfaces: List<NetInterface>) {
        writeLock.withLock {
            val tool = ubus.installedPackages()?.tool ?: throw UserFacingException(txt(R.string.usage_recorder_cannot_install))
            ubus.installPackages(tool, listOf(NLBW_PACKAGE))
            ubus.awaitAfterInstall("nlbwmon", pollMs)
        }
        setUp(interfaces)
    }

    /**
     * Deja nlbwmon guardando en [NLBW_DIR] cada hora y contando el IPv6 de casa si va en relay
     * ([relayPrefixes]), con reversión: se da por bueno cuando el servicio ha vuelto a arrancar y
     * responde. Al cambiar dónde guarda, lo que llevase contado en la RAM se pierde; al cambiar
     * solo las redes, lo contado se queda (lo guarda al pararse).
     */
    suspend fun setUp(interfaces: List<NetInterface>) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("nlbwmon", "nlbwmon")
        val section = before.firstOrNull() ?: throw UserFacingException(txt(R.string.usage_devices_no_config))
        val wanted = relayPrefixes(interfaces, ipv6Relay(optional { tx.uciSections("dhcp", "dhcp") }.orEmpty()))
        val networks = nlbwNetworks(section, wanted)
        val keepsData = nlbwKeepsData(section)
        val ownChanged = section.items(NLBW_OWN_NETWORKS) != wanted
        if (keepsData && networks == section.items("local_network") && !ownChanged) return@withLock
        if (!keepsData) {
            tx.uciSet("nlbwmon", section.name, mapOf("database_directory" to NLBW_DIR, "commit_interval" to NLBW_COMMIT_INTERVAL))
        }
        if (networks != section.items("local_network")) tx.uciSetList("nlbwmon", section.name, "local_network", networks)
        when {
            !ownChanged -> Unit
            wanted.isEmpty() -> tx.uciDeleteOption("nlbwmon", section.name, NLBW_OWN_NETWORKS)
            else -> tx.uciSetList("nlbwmon", section.name, NLBW_OWN_NETWORKS, wanted)
        }
        tx.applySafely(
            failure = txt(R.string.usage_devices_not_started),
            isReverted = { fresh -> fresh.uciSections("nlbwmon", "nlbwmon") == before },
            // procd lo recarga al aplicar (con el directorio nuevo, que crea su init).
            isLive = { tx.serviceRunning("nlbwmon") != false && tx.exec(NLBW_ACTION, listOf("periods")).code == 0 },
        )
    }
}

/** Lo que puede faltar (paquete sin instalar, sin permiso): null en vez de error. Si no se llega al router, sí falla. */
private suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (_: UbusException.AccessDenied) {
    null
} catch (_: UbusException.Status) {
    null
} catch (_: UbusException.Protocol) {
    null
}
