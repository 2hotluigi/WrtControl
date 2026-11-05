package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.fileRemove
import dev.angel.wrtcontrol.openwrt.fileWrite
import dev.angel.wrtcontrol.openwrt.routerLocalTime
import dev.angel.wrtcontrol.openwrt.routerTimezones
import dev.angel.wrtcontrol.openwrt.routerUnixTime
import dev.angel.wrtcontrol.openwrt.systemSection
import dev.angel.wrtcontrol.openwrt.uciAdd
import dev.angel.wrtcontrol.openwrt.uciDelete
import dev.angel.wrtcontrol.openwrt.uciSections
import dev.angel.wrtcontrol.openwrt.uciSet
import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Lo que muestra la pestaña de control parental. */
data class ParentalSnapshot(
    val profiles: List<Profile>,
    /** Hora local del router: los horarios y las pausas se cuentan con ella. */
    val routerNow: LocalDateTime,
    /** Desfase del router respecto a UTC, en segundos. */
    val routerOffsetSeconds: Int?,
    val routerZone: String?,
    /** Con la descarga de flujos, las conexiones ya abiertas no pasan por el firewall. */
    val flowOffloading: Boolean,
    /** Reglas ajenas que usan la misma marca que WrtControl. */
    val markConflicts: List<String>,
)

/**
 * Control parental sobre el firewall del router. Las lecturas usan la sesión compartida;
 * cada escritura abre la suya y se aplica con reversión automática (ver SafeApply.kt).
 */
class ParentalApi(
    private val ubus: UbusClient,
    private val phoneClockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Diagnóstico de la verificación (logcat en la app, stdout en las pruebas). */
    private val log: (String) -> Unit = {},
) {
    private val writeLock = Mutex()

    suspend fun load(): ParentalSnapshot = coroutineScope {
        val firewall = async { ubus.uciSections("firewall") }
        val local = async { ubus.routerLocalTime() }
        val unix = async { ubus.routerUnixTime() }
        val system = async { ubus.systemSection() }
        val sections = firewall.await()
        val localSeconds = local.await()
        ParentalSnapshot(
            profiles = readProfiles(sections),
            routerNow = routerDateTime(localSeconds),
            routerOffsetSeconds = roundOffset(localSeconds - (unix.await() ?: phoneClockSeconds())),
            routerZone = system.await()?.text("zonename"),
            flowOffloading = sections.firstOrNull { it.type == "defaults" }?.text("flow_offloading") == "1",
            markConflicts = markConflicts(sections),
        )
    }

    /**
     * Lee los perfiles actuales del router, les aplica [change] (con la hora local del router)
     * y deja el firewall como corresponde. Leer dentro de la transacción evita pisar cambios
     * hechos desde otro teléfono entre medias.
     */
    suspend fun update(change: (profiles: List<Profile>, now: LocalDateTime) -> List<Profile>) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val existing = tx.uciSections("firewall")
        val now = routerDateTime(tx.routerLocalTime())
        val updated = change(readProfiles(existing), now)
        validateProfiles(updated)
        val plan = compileFirewall(updated, now)
        val changes = diffSections(existing, plan.sections)
        if (changes.isEmpty()) return@withLock

        if (plan.usesBlockChain) writeBlockChain(tx)
        tx.stage(changes)
        applyFirewall(tx, plan, existing)
        if (!plan.usesBlockChain) removeBlockChain(tx)
    }

    /**
     * Pone el router en la zona horaria del teléfono ([zoneId], p. ej. "America/Mexico_City"),
     * cuyo desfase actual es [expectedOffsetSeconds]. Se confirma cuando el reloj del router lo
     * refleja: si las reglas de LuCI para esa zona dieran otra hora, se deja como estaba.
     * Después recarga el firewall, porque nftables pasa las horas de las reglas a UTC al cargarlas.
     */
    suspend fun setTimezone(zoneId: String, expectedOffsetSeconds: Int) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        // LuCI no incluye UTC en su lista: lo trata aparte, como zonename "UTC" y timezone "GMT0".
        val zones = tx.routerTimezones() + ("UTC" to "GMT0")
        val wanted = if (zoneId in UTC_ALIASES) "UTC" else zoneId
        val zone = zones.keys.firstOrNull { sameZone(it, wanted) }
            ?: throw IllegalArgumentException("El router no conoce la zona horaria $zoneId")
        val system = tx.systemSection() ?: throw UbusException.Protocol("El router no tiene sección system")
        // Si el router trae horario de verano para una zona que ya no lo tiene, manda el teléfono.
        val luciTz = zones.getValue(zone)
        val fixed = runCatching { fixedPosixTz(ZoneId.of(zoneId), Instant.now()) }.getOrNull()
        val tz = if (fixed != null && ',' in luciTz) fixed else luciTz

        tx.uciSet("system", system.name, mapOf("zonename" to zone, "timezone" to tz))
        tx.applySafely(
            failure = "El router no tomó la hora de esa zona (${formatUtcOffset(expectedOffsetSeconds)}), así que se dejó como estaba.",
            isReverted = { ubus.openSession().systemSection()?.text("zonename") == system.text("zonename") },
            isLive = { tx.offsetSeconds() == expectedOffsetSeconds },
        )

        val existing = tx.uciSections("firewall")
        val include = existing.firstOrNull { it.name == INCLUDE_SECTION } ?: return@withLock
        // Borrar y volver a crear la misma sección basta para que procd recargue fw4.
        tx.stage(listOf(UciChange.Delete(include.name), UciChange.Add(include)))
        applyFirewall(tx, compileFirewall(readProfiles(existing), routerDateTime(tx.routerLocalTime())), existing)
    }

    /**
     * Aplica los cambios del firewall y espera a que fw4 los cargue tal cual: que se haya
     * recargado y que estén exactamente las reglas de [plan]. Si fw4 rechaza la configuración,
     * la tabla queda como estaba y no se confirma.
     */
    private suspend fun applyFirewall(tx: UbusSession, plan: FirewallPlan, existing: List<UciSection>) {
        val before = tx.loadedFirewall()
        log("firewall antes: tabla=${before?.tableHandle} max=${before?.maxRuleHandle} reglas=${before?.wrtcontrolRules}")
        var waited = 0
        tx.applySafely(
            failure = "El firewall del router no cargó las reglas nuevas, así que se restauró la configuración anterior.",
            isReverted = { ownSections(ubus.openSession().uciSections("firewall")) == ownSections(existing) },
        ) {
            if (before == null) {
                // Sin permiso para listar el firewall: solo se puede comprobar que el router sigue respondiendo.
                tx.routerLocalTime()
                ++waited >= 3
            } else {
                val now = tx.loadedFirewall() ?: return@applySafely false
                val reloaded = now.reloadedSince(before)
                val matches = now.matches(plan)
                log("firewall: recargado=$reloaded coincide=$matches max=${now.maxRuleHandle} reglas=${now.wrtcontrolRules} esperadas=${plan.ruleLabels}")
                reloaded && matches
            }
        }
    }

    private suspend fun UbusCaller.offsetSeconds(): Int {
        val local = routerLocalTime()
        return roundOffset(local - (routerUnixTime() ?: phoneClockSeconds()))
    }

    private suspend fun writeBlockChain(tx: UbusSession) {
        try {
            tx.fileWrite(INCLUDE_PATH, BLOCK_CHAIN_NFT)
        } catch (e: UbusException.Status) {
            throw if (e.code == 6) {
                UbusException.Protocol("El router no deja escribir en /etc/luci-uploads (¿tiene LuCI instalado?)")
            } else {
                e
            }
        }
    }

    /** Ya sin perfiles, la cadena de bloqueo no hace falta. Se borra después de confirmar. */
    private suspend fun removeBlockChain(tx: UbusSession) {
        try {
            tx.fileRemove(INCLUDE_PATH)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin la sección include, el fichero no se usa; no merece un error.
        }
    }
}

private suspend fun UbusSession.stage(changes: List<UciChange>) {
    for (change in changes) when (change) {
        is UciChange.Delete -> uciDelete("firewall", change.section)
        is UciChange.Add -> uciAdd("firewall", change.section)
    }
}

private val UTC_ALIASES = setOf("UTC", "Etc/UTC", "GMT", "Etc/GMT", "Z", "Etc/Universal", "Universal", "Etc/Zulu", "Zulu")

/** Secciones de WrtControl por nombre (para comparar estados sin importar el orden). */
private fun ownSections(sections: List<UciSection>): Map<String, UciSection> =
    sections.filter { it.name.startsWith(SECTION_PREFIX) }.associateBy { it.name }
