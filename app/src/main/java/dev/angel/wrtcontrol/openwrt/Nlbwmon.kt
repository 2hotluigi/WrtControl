package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * nlbwmon (luci-app-nlbwmon): cuenta lo que gasta cada aparato de casa, por periodos (de serie,
 * meses que empiezan el día 1). Mira las conexiones del cortafuegos (conntrack), no la tarjeta:
 * suma lo que pasa entre la red de casa y fuera. Lo que la app necesita de él, comprobado en 24.10
 * y 25.12:
 *
 * - Su ACL deja `nlbwmon-action download -g mac,ip`: una fila por aparato y dirección, con lo
 *   recibido (`rx_bytes`, lo que baja el aparato) y lo enviado. El propio router sale como un
 *   aparato más (con la IP de su WAN) y lo que no se sabe de quién es, con la MAC 00:00:...: los
 *   dos se quitan.
 * - Cuenta en RAM y lo guarda en `database_directory` cada `commit_interval` (24 h de serie) y al
 *   apagarse. De serie es /var/lib/nlbwmon, también RAM: al reiniciar se pierde todo. A diferencia
 *   de vnStat, se cambia por UCI (su ACL deja escribir la config `nlbwmon`) y el servicio se recarga
 *   solo. Al cambiarlo empieza de cero: se hace al instalar.
 * - Tarda hasta `refresh_interval` (30 s) en contar una conexión abierta.
 */

internal const val NLBW_ACTION = "/usr/libexec/nlbwmon-action"

/** Lo que se instala: trae nlbwmon y el permiso de LuCI para usarlo desde la app. */
const val NLBW_PACKAGE = "luci-app-nlbwmon"

/** Donde la app deja la base de nlbwmon: en la flash, para que no se pierda al reiniciar. */
const val NLBW_DIR = "/etc/wrtc_nlbwmon"

/** Cada cuánto guarda en la flash: una vez por hora, como vnStat (de serie, una vez al día). */
internal const val NLBW_COMMIT_INTERVAL = "1h"

/** Lo que ocupa con lo que necesita (unos 250 KB), con margen. */
const val NLBW_SPACE_KIB = 1024L

/** Lo que ha gastado un aparato en el periodo en curso. */
data class DeviceUsage(val mac: String, val receivedBytes: Long, val sentBytes: Long) {
    val totalBytes: Long get() = receivedBytes + sentBytes
}

/** Si el router cuenta lo que gasta cada aparato (nlbwmon) y qué le falta. */
sealed interface DeviceRecorder {
    /** No está instalado. [freeKiB]: el espacio libre del router (null si no se sabe). */
    data class Missing(val freeKiB: Long?) : DeviceRecorder {
        val fits: Boolean get() = freeKiB == null || freeKiB >= NLBW_SPACE_KIB
    }

    /** Instalado, pero lo guarda en la RAM: se perdería al reiniciar. */
    data object NeedsSetup : DeviceRecorder

    /**
     * Cuenta y guarda: lo de cada aparato este periodo, de más a menos. [monthly]: el periodo es el
     * mes desde el día 1 (lo de serie); si no, el que se puso en LuCI.
     */
    data class Ready(val devices: List<DeviceUsage>, val monthly: Boolean = true) : DeviceRecorder
}

/** Lo que da `nlbwmon-action download`: el nombre de cada columna y las filas. */
@Serializable
internal data class NlbwJson(val columns: List<String> = emptyList(), val data: List<List<JsonPrimitive>> = emptyList())

private const val NO_MAC = "00:00:00:00:00:00"

/**
 * Lo de cada aparato, de más a menos, sumando sus direcciones. Fuera el propio router (las filas
 * con una de sus direcciones, [routerAddresses]) y lo que no tiene MAC.
 */
internal fun parseDeviceUsage(json: NlbwJson, routerAddresses: Set<String>): List<DeviceUsage> {
    val mac = json.columns.indexOf("mac")
    val ip = json.columns.indexOf("ip")
    val rx = json.columns.indexOf("rx_bytes")
    val tx = json.columns.indexOf("tx_bytes")
    if (mac < 0 || rx < 0 || tx < 0) return emptyList()
    return json.data
        .filter { row -> row.size == json.columns.size }
        .filter { row -> ip < 0 || row[ip].contentOrNull !in routerAddresses }
        .mapNotNull { row ->
            val address = row[mac].contentOrNull?.uppercase()?.takeIf { it.isNotBlank() && it != NO_MAC } ?: return@mapNotNull null
            DeviceUsage(address, row[rx].longOrNull ?: 0, row[tx].longOrNull ?: 0)
        }
        .groupBy { it.mac }
        .map { (address, rows) -> DeviceUsage(address, rows.sumOf { it.receivedBytes }, rows.sumOf { it.sentBytes }) }
        .filter { it.totalBytes > 0 }
        .sortedByDescending { it.totalBytes }
}

/** ¿Guarda nlbwmon donde lo deja la app, cada hora? Según su sección de /etc/config/nlbwmon. */
internal fun nlbwKeepsData(section: UciSection): Boolean =
    section.text("database_directory") == NLBW_DIR && section.text("commit_interval") == NLBW_COMMIT_INTERVAL

/** Las direcciones del propio router, para no contarlo como un aparato de casa. */
internal fun routerAddresses(interfaces: List<NetInterface>): Set<String> =
    interfaces.flatMap { it.ipv4 + it.routerIpv6 }.map { it.address }.toSet()
