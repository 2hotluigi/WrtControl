package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusJson
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Si el router guarda lo que se gasta por horas y días (vnStat, ver Vnstat.kt) y qué le falta. */
sealed interface Recorder {
    /** No está instalado. [freeKiB]: el espacio libre del router, para saber si cabe (null si no se sabe). */
    data class Missing(val freeKiB: Long?) : Recorder {
        val fits: Boolean get() = freeKiB == null || freeKiB >= VNSTAT_SPACE_KIB
    }

    /** Instalado, pero no mira la salida a internet o lo guarda en la RAM (se perdería al reiniciar). */
    data object NeedsSetup : Recorder

    /** Guarda el consumo de la salida a internet: lo que lleva guardado. */
    data class Ready(val history: UsageHistory) : Recorder
}

/** Lo que tarda como mucho la línea del crontab en ejecutarse (cron mira cada minuto) y vnStat en volver. */
private const val CRON_WAIT_MS = 100_000L
private const val POLL_MS = 3_000L

/** Tras instalar un paquete de LuCI, rpcd se recarga: lo que se espera a que vuelva a contestar. */
private const val RPCD_WAIT_MS = 45_000L

/** Lo que lee la app del consumo y lo que hace para que el router lo guarde. */
class UsageApi internal constructor(
    private val ubus: UbusClient,
    private val writeLock: Mutex = Mutex(),
    private val pollMs: Long = POLL_MS,
) {
    /**
     * Lo que ha pasado por la salida a internet desde que se encendió el router; null si no hay
     * salida que mirar o el router no deja ver sus contadores.
     */
    suspend fun sinceBoot(interfaces: List<NetInterface>, uptimeSeconds: Long): WanUsage? {
        val device = wanDevice(interfaces) ?: return null
        val json = optional { ubus.callAs<Map<String, DeviceStatsJson>>("luci-rpc", "getNetworkDevices")[device] }
        return json?.let { wanUsage(device, it, uptimeSeconds) }
    }

    /** Si vnStat guarda el consumo de la salida a internet y, si lo hace, lo guardado; null si no hay salida. */
    suspend fun recorder(interfaces: List<NetInterface>, freeKiB: Long?): Recorder? {
        val device = wanDevice(interfaces) ?: return null
        // Sin su configuración no está instalado; sin su permiso de LuCI, la app no puede usarlo.
        val config = optional { ubus.uciSections("vnstat", "vnstat") }
        val showconfig = config?.let { optional { ubus.exec(VNSTAT, listOf("--showconfig")) } }
        return when {
            config == null || showconfig == null -> Recorder.Missing(freeKiB)
            config.none { device in it.items("interface") } || !vnstatKeepsData(showconfig.stdout) -> Recorder.NeedsSetup
            else -> Recorder.Ready(history(device))
        }
    }

    /** Lo que vnStat ha guardado de [device]. Sin datos todavía, responde con un error ("Database is empty"): vacío. */
    private suspend fun history(device: String): UsageHistory {
        val hours = vnstatJson(listOf("--json", "h", UsagePeriod.Day.buckets.toString(), "-i", device))
        val days = vnstatJson(listOf("--json", "d", UsagePeriod.Month.buckets.toString(), "-i", device))
        return parseUsageHistory(hours, days, device) ?: UsageHistory(emptyList(), emptyList())
    }

    private suspend fun vnstatJson(args: List<String>): VnstatJson {
        val out = ubus.exec(VNSTAT, args)
        if (out.code != 0) return VnstatJson()
        return UbusJson.decodeFromString(VnstatJson.serializer(), out.stdout)
    }

    /** Instala vnStat y lo deja guardando la salida a internet ([setUp]). */
    suspend fun install(interfaces: List<NetInterface>) {
        writeLock.withLock {
            val tool = ubus.installedPackages()?.tool ?: throw UserFacingException(txt(R.string.usage_recorder_cannot_install))
            ubus.installPackages(tool, listOf(VNSTAT_PACKAGE))
            waitForRpcd()
        }
        setUp(interfaces)
    }

    /**
     * Deja vnStat mirando la salida a internet (sin quitar las tarjetas que ya mirase) y guardando
     * en la flash (ver Vnstat.kt).
     */
    suspend fun setUp(interfaces: List<NetInterface>) = writeLock.withLock {
        val device = wanDevice(interfaces) ?: throw UserFacingException(txt(R.string.usage_recorder_no_wan))
        watch(device)
        if (!vnstatKeepsData(ubus.exec(VNSTAT, listOf("--showconfig")).stdout)) keepData()
    }

    /** Añade [device] a las tarjetas de vnStat, con reversión: se da por bueno cuando vnStat la tiene. */
    private suspend fun watch(device: String) {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val before = tx.uciSections("vnstat", "vnstat")
        val section = before.firstOrNull()
        if (section != null && device in section.items("interface")) return
        if (section == null) {
            tx.uciAdd("vnstat", uciSection("wrtc_vnstat", "vnstat", "interface" to listOf(device)))
        } else {
            tx.uciSetList("vnstat", section.name, "interface", section.items("interface") + device)
        }
        tx.applySafely(
            failure = txt(R.string.usage_recorder_not_watching),
            isReverted = { fresh -> fresh.uciSections("vnstat", "vnstat") == before },
            // Lo añade el init de vnStat al recargarse (lo recarga procd al aplicar).
            isLive = { device in tx.exec(VNSTAT, listOf("--json", "f", "1")).stdout.let(::vnstatDevices) },
        )
    }

    /**
     * Pone la línea del crontab que deja vnStat guardando en la flash, espera a que cron la ejecute
     * y deja cron como estaba (si no estaba en marcha, se para).
     */
    private suspend fun keepData() {
        val before = ubus.fileRead(CRONTAB) ?: throw UserFacingException(txt(R.string.usage_recorder_denied))
        val cronWasRunning = ubus.serviceRunning("cron") == true
        ubus.fileWrite(CRONTAB, withVnstatLine(before), mode = CRONTAB_MODE)
        ubus.initAction("cron", "restart")
        val done = withTimeoutOrNull(CRON_WAIT_MS) {
            while (!vnstatKeepsData(optional { ubus.exec(VNSTAT, listOf("--showconfig")) }?.stdout.orEmpty())) delay(pollMs)
            true
        } ?: false
        // La línea se borra sola al ejecutarse; si no llegó a hacerlo, se quita aquí. Si no había
        // crontab, no se deja uno vacío: con él, cron arrancaría con el router sin nada que hacer.
        val after = ubus.fileRead(CRONTAB).orEmpty()
        val clean = withoutVnstatLine(after)
        when {
            clean.isEmpty() && before.isEmpty() -> optional { ubus.fileRemove(CRONTAB) }
            clean != after -> ubus.fileWrite(CRONTAB, clean, mode = CRONTAB_MODE)
        }
        if (!cronWasRunning && !hasOtherJobs(after)) optional { ubus.initAction("cron", "stop") }
        if (!done) throw UserFacingException(txt(R.string.usage_recorder_not_saved))
    }

    /** Tras instalar, rpcd se recarga y tarda en volver a atender: se espera a que conteste. */
    private suspend fun waitForRpcd() {
        withTimeoutOrNull(RPCD_WAIT_MS) {
            while (true) {
                try {
                    ubus.uciSections("vnstat", "vnstat")
                    return@withTimeoutOrNull
                } catch (_: UbusException.Network) {
                    delay(pollMs)
                }
            }
        } ?: throw UbusException.Network("El router no volvió a responder después de instalar el paquete.")
    }
}

/** rw------- : el crontab es de root. */
private const val CRONTAB_MODE = 0x180

/** Las tarjetas que conoce vnStat, según `vnstat --json f 1`. */
internal fun vnstatDevices(json: String): Set<String> = try {
    UbusJson.decodeFromString(VnstatJson.serializer(), json).interfaces.map { it.name }.toSet()
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
    emptySet()
}

/** Cambia una lista entera de una sección (`uci set` con un array la sustituye). */
private suspend fun UbusCaller.uciSetList(config: String, section: String, option: String, values: List<String>) {
    call("uci", "set", buildJsonObject {
        put("config", config)
        put("section", section)
        putJsonObject("values") { putJsonArray(option) { values.forEach { add(it) } } }
    })
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
