package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/*
 * Cuánto internet se gasta. Sin instalar nada, el router solo cuenta los bytes que han pasado por
 * la tarjeta de la salida a internet desde que se encendió: `luci-rpc.getNetworkDevices`, los
 * mismos contadores que enseña LuCI. (`network.device.status` también los da, pero el ACL de LuCI
 * no deja llamarlo hasta 25.12.) Se cuenta en la tarjeta física y no en la de PPPoE (`pppoe-wan`):
 * esa se rehace en cada reconexión y empieza de cero.
 *
 * Por horas y por días ([UsageHistory]) hace falta que el router lo vaya guardando: vnStat, que se
 * instala desde la app.
 */

/** Lo que ha pasado por la salida a internet desde que se encendió el router. */
data class WanUsage(
    /** La tarjeta de red de la salida a internet ("eth1", "wan"). */
    val device: String,
    /** Lo que ha bajado de internet. */
    val receivedBytes: Long,
    /** Lo que ha subido. */
    val sentBytes: Long,
    /** Cuánto lleva encendido el router, en segundos: el periodo que cuentan los bytes. */
    val sinceSeconds: Long,
) {
    val totalBytes: Long get() = receivedBytes + sentBytes

    /** Lo que se gasta en un día, de media; null si lleva encendido menos de un día (la media engañaría). */
    val perDayBytes: Long?
        get() = if (sinceSeconds < DAY_SECONDS) null else (totalBytes.toDouble() * DAY_SECONDS / sinceSeconds).toLong()
}

private const val DAY_SECONDS = 86_400L

/** Lo gastado en una hora o en un día que empieza en [start] (hora del router). */
data class UsageBucket(val start: LocalDateTime, val receivedBytes: Long, val sentBytes: Long) {
    val totalBytes: Long get() = receivedBytes + sentBytes
}

/** Los tres periodos que se enseñan: las últimas 24 horas, 7 días o 30 días. */
enum class UsagePeriod(val buckets: Int) { Day(HOURS_IN_DAY), Week(DAYS_IN_WEEK), Month(DAYS_IN_MONTH) }

private const val HOURS_IN_DAY = 24
private const val DAYS_IN_WEEK = 7
private const val DAYS_IN_MONTH = 30

/** Lo que el router ha ido guardando, de lo más viejo a lo más nuevo: por horas y por días. */
data class UsageHistory(val hours: List<UsageBucket>, val days: List<UsageBucket>) {
    fun buckets(period: UsagePeriod): List<UsageBucket> = when (period) {
        UsagePeriod.Day -> hours
        UsagePeriod.Week, UsagePeriod.Month -> days
    }.takeLast(period.buckets)
}

/** Recibido, enviado y total de un periodo. */
data class UsageTotals(val receivedBytes: Long, val sentBytes: Long) {
    val totalBytes: Long get() = receivedBytes + sentBytes
}

fun List<UsageBucket>.totals(): UsageTotals = UsageTotals(sumOf { it.receivedBytes }, sumOf { it.sentBytes })

/** Una tarjeta en `luci-rpc.getNetworkDevices`; aquí solo importan los contadores. */
@Serializable
internal data class DeviceStatsJson(val stats: Stats? = null) {
    @Serializable
    data class Stats(
        @SerialName("rx_bytes") val rxBytes: Long? = null,
        @SerialName("tx_bytes") val txBytes: Long? = null,
    )
}

internal fun wanUsage(device: String, json: DeviceStatsJson, uptimeSeconds: Long): WanUsage? {
    val received = json.stats?.rxBytes
    val sent = json.stats?.txBytes
    return if (received == null || sent == null) null else WanUsage(device, received, sent, uptimeSeconds)
}

/** Lo que lee la app del consumo. Ahora, solo los contadores desde el arranque. */
class UsageApi internal constructor(private val ubus: UbusCaller) {
    /**
     * Lo que ha pasado por la salida a internet desde que se encendió el router; null si no hay
     * salida que mirar o el router no deja ver sus contadores.
     */
    suspend fun sinceBoot(interfaces: List<NetInterface>, uptimeSeconds: Long): WanUsage? {
        val device = wanDevice(interfaces) ?: return null
        val json = try {
            ubus.callAs<Map<String, DeviceStatsJson>>("luci-rpc", "getNetworkDevices")[device]
        } catch (_: UbusException.AccessDenied) {
            null
        } catch (_: UbusException.Status) {
            null
        } catch (_: UbusException.Protocol) {
            null
        }
        return json?.let { wanUsage(device, it, uptimeSeconds) }
    }
}
