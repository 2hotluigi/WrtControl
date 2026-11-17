package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.ROLLBACK_SECONDS
import dev.angel.wrtcontrol.parental.VERIFY_TIMEOUT_MS
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Cuánto se le da al teléfono para volver a entrar cuando el cambio lo saca del Wi-Fi. */
internal const val WIFI_ROLLBACK_SECONDS = 180
internal const val WIFI_VERIFY_TIMEOUT_MS = 165_000L

/** El Wi-Fi de casa (ver Wireless.kt). Las escrituras, con reversión automática. */
class WifiApi(private val ubus: UbusClient, private val writeLock: Mutex = Mutex()) {

    suspend fun state(): WifiState = ubus.readWifi(withClients = true)

    /**
     * Deja "Tu Wi-Fi" como dice [edit]. [phoneOnIt]: el teléfono que hace el cambio habla con el
     * router por ese Wi-Fi, así que se quedará fuera un rato: se le da más tiempo para volver a
     * entrar (con el nombre y la clave nuevos) y confirmarlo; si no lo hace, el router lo deshace.
     */
    suspend fun save(edit: WifiEdit, phoneOnIt: Boolean) = writeLock.withLock {
        wifiProblem(edit)?.let { throw UserFacingException(it) }
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("wireless")
        val changes = wifiPlan(before, edit)
        if (changes.isEmpty()) return@withLock
        for (change in changes) {
            when (change) {
                is WifiChange.Set -> tx.uciSet("wireless", change.section, change.values)
                is WifiChange.DropOption -> tx.uciDeleteOption("wireless", change.section, change.option)
                is WifiChange.Add -> tx.uciAdd("wireless", change.section)
            }
        }
        tx.applySafely(
            failure = txt(R.string.wifi_error_not_applied),
            isReverted = { fresh -> fresh.uciSections("wireless") == before },
            rollbackSeconds = if (phoneOnIt) WIFI_ROLLBACK_SECONDS else ROLLBACK_SECONDS,
            verifyTimeoutMs = if (phoneOnIt) WIFI_VERIFY_TIMEOUT_MS else VERIFY_TIMEOUT_MS,
        ) {
            val devices = tx.wirelessDevices()
            wifiLive(parseWifi(tx.uciSections("wireless"), devices), devices, edit)
        }
    }

    /** Los países que conoce el router (vacío si no tiene Wi-Fi o no deja leerlos). */
    suspend fun countries(): List<WifiCountry> {
        val radio = ubus.uciSections("wireless").firstOrNull { it.type == "wifi-device" } ?: return emptyList()
        return try {
            parseCountries(ubus.callAs<JsonObject>("iwinfo", "countrylist", buildJsonObject { put("device", radio.name) }))
        } catch (_: UbusException.AccessDenied) {
            emptyList()
        } catch (_: UbusException.Status) {
            emptyList()
        }
    }

    /**
     * Pone el país [code] en todas las radios. Las que emiten se reinician con las normas nuevas:
     * si alguna no vuelve (su canal no está permitido en ese país), el router lo deshace. Se espera
     * más si este teléfono va por ese Wi-Fi ([phoneOnIt]) o si una radio tiene que escuchar radares.
     */
    suspend fun setCountry(code: String, phoneOnIt: Boolean) = writeLock.withLock {
        require(isCountryCode(code)) { "País no válido" }
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("wireless")
        val changes = countryPlan(before, code)
        if (changes.isEmpty()) return@withLock
        val up = parseWifi(before, tx.wirelessDevices()).radios.filter { it.up }
        val wasUp = up.map { it.name }.toSet()
        changes.forEach { tx.uciSet("wireless", it.section, it.values) }
        val slow = up.isNotEmpty() && (phoneOnIt || up.any { it.mayWaitForRadar })
        // Justo después de aplicar, las radios siguen "levantadas" un instante antes de reiniciarse.
        var steady = 0
        tx.applySafely(
            failure = txt(R.string.wifi_country_not_applied),
            isReverted = { fresh -> fresh.uciSections("wireless") == before },
            rollbackSeconds = if (slow) WIFI_ROLLBACK_SECONDS else ROLLBACK_SECONDS,
            verifyTimeoutMs = if (slow) WIFI_VERIFY_TIMEOUT_MS else VERIFY_TIMEOUT_MS,
        ) {
            steady = if (radiosBack(wasUp, tx.wirelessDevices())) steady + 1 else 0
            steady >= RADIO_STEADY_CHECKS
        }
    }
}

/** Comprobaciones seguidas (una por segundo) con las radios emitiendo para dar el cambio por bueno. */
internal const val RADIO_STEADY_CHECKS = 3

/** `luci-rpc getWirelessDevices`: radio → su estado. */
internal suspend fun UbusCaller.wirelessDevices(): Map<String, JsonObject> =
    callAs<JsonObject>("luci-rpc", "getWirelessDevices").mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()

/** Lee el Wi-Fi. Con [withClients], también quién está conectado a cada red (si el router deja verlo). */
internal suspend fun UbusCaller.readWifi(withClients: Boolean): WifiState = coroutineScope {
    val sections = async { uciSections("wireless") }
    val devices = async { wirelessDevices() }
    val bare = parseWifi(sections.await(), devices.await())
    if (!withClients) return@coroutineScope bare
    val clients = bare.networks.mapNotNull { it.ifname }.map { ifname -> async { assoclist(ifname)?.let { ifname to it } } }
    parseWifi(sections.await(), devices.await(), clients.awaitAll().filterNotNull().toMap())
}

/** Las MAC conectadas a [ifname], o null si el router no deja verlo. */
private suspend fun UbusCaller.assoclist(ifname: String): List<String>? = try {
    val results = callAs<JsonObject>("iwinfo", "assoclist", buildJsonObject { put("device", ifname) })["results"] as? JsonArray
    results.orEmpty().mapNotNull { ((it as? JsonObject)?.get("mac") as? JsonPrimitive)?.contentOrNull?.uppercase() }
} catch (e: CancellationException) {
    throw e
} catch (_: UbusException) {
    null
}
