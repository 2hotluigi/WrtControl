package dev.angel.wrtcontrol.openwrt

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.ROLLBACK_SECONDS
import dev.angel.wrtcontrol.parental.VERIFY_TIMEOUT_MS
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
        val home = tx.homeLayout().home
        val changes = wifiPlan(before, edit, home)
        if (changes.isEmpty()) return@withLock
        changes.forEach { tx.stage(it) }
        tx.applySafely(
            failure = txt(R.string.wifi_error_not_applied),
            isReverted = { fresh -> fresh.uciSections("wireless") == before },
            rollbackSeconds = if (phoneOnIt) WIFI_ROLLBACK_SECONDS else ROLLBACK_SECONDS,
            verifyTimeoutMs = if (phoneOnIt) WIFI_VERIFY_TIMEOUT_MS else VERIFY_TIMEOUT_MS,
        ) {
            val devices = tx.wirelessDevices()
            wifiLive(parseWifi(tx.uciSections("wireless"), devices, home = home), devices, edit)
        }
    }

    /** Los países que conoce el router (vacío si no tiene Wi-Fi o no deja leerlos). */
    suspend fun countries(): List<WifiCountry> {
        val radio = ubus.wirelessSections().firstOrNull { it.type == "wifi-device" } ?: return emptyList()
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
        val up = parseWifi(before, tx.wirelessDevices()).radios.filter { it.up }
        val slow = phoneOnIt || up.any { it.mayWaitForRadar }
        tx.applyToRadios(before, countryPlan(before, code), up, slow, R.string.wifi_country_not_applied)
    }

    /**
     * Pone [radio] a [width] MHz (ver WirelessWidth.kt). Si emitía, se reinicia; si no vuelve (el
     * canal no admite ese ancho, o a 160 MHz falta el país), el router lo deshace. A 160 MHz se
     * espera más: el router escucha un minuto por si hay radares antes de emitir.
     */
    suspend fun setWidth(radio: String, width: Int, phoneOnIt: Boolean) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("wireless")
        val supported = tx.htmodes(radio).orEmpty()
        val changes = widthPlan(before, radio, width, supported)
        val up = parseWifi(before, tx.wirelessDevices()).radios.filter { it.up }
        val slow = phoneOnIt || width == WIDTH_160 || up.any { it.name == radio && it.mayWaitForRadar }
        tx.applyToRadios(before, changes, up, slow, R.string.wifi_width_not_applied)
    }

    /**
     * Escanea con [radio] (unos segundos; con el Wi-Fi encendido puede ir a tirones mientras) y dice
     * qué canal le conviene. null si no se puede escanear o no hay nada que proponer.
     */
    suspend fun channelAdvice(radio: String): ChannelAdvice? {
        val current = ubus.readWifi(withClients = false).radios.firstOrNull { it.name == radio }
        val seen = ubus.scan(radio)
        val allowed = ubus.allowedChannels(radio)
        return if (current == null || seen == null || allowed == null) null else adviseChannel(current, allowed, seen)
    }

    /** Pone [radio] en el canal [channel]. Si emitía y no vuelve (el país no lo permite), el router lo deshace. */
    suspend fun setChannel(radio: String, channel: Int, phoneOnIt: Boolean) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("wireless")
        val up = parseWifi(before, tx.wirelessDevices()).radios.filter { it.up }
        val radar = up.firstOrNull { it.name == radio }?.copy(channel = channel.toString())?.mayWaitForRadar == true
        tx.applyToRadios(before, channelPlan(before, radio, channel), up, phoneOnIt || radar, R.string.wifi_channel_not_applied)
    }

    /**
     * Pone la potencia de [radio] en [power] (ver WirelessPower.kt). Si emitía, se reinicia; si no
     * vuelve, el router lo deshace.
     */
    suspend fun setPower(radio: String, power: WifiPower, phoneOnIt: Boolean) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("wireless")
        val up = parseWifi(before, tx.wirelessDevices()).radios.filter { it.up }
        val slow = phoneOnIt || up.any { it.name == radio && it.mayWaitForRadar }
        tx.applyToRadios(before, powerPlan(before, radio, power, tx.maxPower(radio)), up, slow, R.string.wifi_power_not_applied)
    }

    private suspend fun UbusSession.stage(change: WifiChange) = when (change) {
        is WifiChange.Set -> uciSet("wireless", change.section, change.values)
        is WifiChange.DropOption -> uciDeleteOption("wireless", change.section, change.option)
        is WifiChange.Add -> uciAdd("wireless", change.section)
    }

    /**
     * Aplica [changes] a las radios, con reversión: las de [up] (las que estaban levantadas) tienen
     * que volver a estarlo varias comprobaciones seguidas, porque justo después de aplicar siguen
     * "levantadas" un instante antes de reiniciarse. [slow]: se les da 3 minutos en vez de 1.
     */
    private suspend fun UbusSession.applyToRadios(
        before: List<UciSection>,
        changes: List<WifiChange>,
        up: List<WifiRadio>,
        slow: Boolean,
        @StringRes failure: Int,
    ) {
        if (changes.isEmpty()) return
        changes.forEach { stage(it) }
        val wasUp = up.map { it.name }.toSet()
        val patient = slow && up.isNotEmpty()
        var steady = 0
        applySafely(
            failure = txt(failure),
            isReverted = { fresh -> fresh.uciSections("wireless") == before },
            rollbackSeconds = if (patient) WIFI_ROLLBACK_SECONDS else ROLLBACK_SECONDS,
            verifyTimeoutMs = if (patient) WIFI_VERIFY_TIMEOUT_MS else VERIFY_TIMEOUT_MS,
        ) {
            steady = if (radiosBack(wasUp, wirelessDevices())) steady + 1 else 0
            steady >= RADIO_STEADY_CHECKS
        }
    }
}

/** Comprobaciones seguidas (una por segundo) con las radios emitiendo para dar el cambio por bueno. */
internal const val RADIO_STEADY_CHECKS = 3
