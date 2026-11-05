package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.Lookup
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.fileRemove
import dev.angel.wrtcontrol.openwrt.fileWrite
import dev.angel.wrtcontrol.openwrt.lookup
import dev.angel.wrtcontrol.openwrt.routerLocalTime
import dev.angel.wrtcontrol.openwrt.routerTimezones
import dev.angel.wrtcontrol.openwrt.routerUnixTime
import dev.angel.wrtcontrol.openwrt.serviceInstances
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
    /** Instancias de dnsmasq en marcha (null si este usuario no puede verlas). */
    val dnsRunning: Map<String, Boolean>? = null,
) {
    /** ¿El filtro de este perfil está funcionando en el router? null si no se sabe. */
    fun filterRunning(p: Profile): Boolean? =
        if (!p.needsDns) null else dnsRunning?.let { it[dnsInstance(p)] == true }
}

/**
 * Control parental sobre el firewall y el DNS del router. Las lecturas usan la sesión compartida;
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
        val dns = async { ubus.serviceInstances("dnsmasq") }
        val sections = firewall.await()
        val localSeconds = local.await()
        ParentalSnapshot(
            profiles = readProfiles(sections),
            routerNow = routerDateTime(localSeconds),
            routerOffsetSeconds = roundOffset(localSeconds - (unix.await() ?: phoneClockSeconds())),
            routerZone = system.await()?.text("zonename"),
            flowOffloading = sections.firstOrNull { it.type == "defaults" }?.text("flow_offloading") == "1",
            markConflicts = markConflicts(sections),
            dnsRunning = dns.await(),
        )
    }

    /**
     * Lee los perfiles actuales del router, les aplica [change] (con la hora local del router)
     * y deja el firewall y el DNS como corresponde. Leer dentro de la transacción evita pisar
     * cambios hechos desde otro teléfono entre medias.
     */
    suspend fun update(change: (profiles: List<Profile>, now: LocalDateTime) -> List<Profile>) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val state = tx.readState()
        val now = routerDateTime(tx.routerLocalTime())
        val updated = change(readProfiles(state.firewall), now)
        val plan = compileRouter(updated, now, state.env)
        validateProfiles(plan.profiles)
        val changes = state.changesTo(plan)
        if (changes.isEmpty()) return@withLock

        if (plan.usesBlockChain) writeBlockChain(tx)
        tx.stage(changes)
        applyAndVerify(tx, plan, state)
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

        val state = tx.readState()
        val include = state.firewall.firstOrNull { it.name == INCLUDE_SECTION } ?: return@withLock
        // Borrar y volver a crear la misma sección basta para que procd recargue fw4.
        tx.stage(listOf(Staged("firewall", UciChange.Delete(include.name)), Staged("firewall", UciChange.Add(include))))
        val plan = compileRouter(readProfiles(state.firewall), routerDateTime(tx.routerLocalTime()), state.env)
        applyAndVerify(tx, plan, state)
    }

    /**
     * Aplica lo preparado y espera a que el router lo cargue tal cual: que fw4 se haya recargado
     * con exactamente las reglas de [plan] y que cada perfil con filtro tenga su servidor DNS en
     * marcha y bloqueando. Si algo no carga, no se confirma y se deshace.
     */
    private suspend fun applyAndVerify(tx: UbusSession, plan: RouterPlan, before: RouterState) {
        val firewallBefore = tx.loadedFirewall()
        log("firewall antes: tabla=${firewallBefore?.tableHandle} max=${firewallBefore?.maxRuleHandle} reglas=${firewallBefore?.wrtcontrolRules}")
        val dns = DnsCheck(tx, plan, log)
        var waited = 0
        tx.applySafely(
            failure = "El router no cargó los cambios (firewall o DNS), así que se restauró la configuración anterior.",
            isReverted = { ubus.openSession().readState().ownSections() == before.ownSections() },
        ) {
            val firewallOk = if (firewallBefore == null) {
                // Sin permiso para listar el firewall: solo se puede comprobar que el router sigue respondiendo.
                tx.routerLocalTime()
                ++waited >= 3
            } else {
                val now = tx.loadedFirewall() ?: return@applySafely false
                val reloaded = now.reloadedSince(firewallBefore)
                val matches = now.matches(plan)
                log("firewall: recargado=$reloaded coincide=$matches reglas=${now.wrtcontrolRules.toSet()} esperadas=${plan.ruleLabels.toSet()}")
                reloaded && matches
            }
            firewallOk && dns.ready()
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

/**
 * Comprueba los servidores DNS de los perfiles: que estén en marcha, que respondan con su lista de
 * bloqueo (el canario da NXDOMAIN) y que su DNS para familias conteste. Si el router no deja
 * mirar algo (ACL), esa parte no se comprueba.
 */
private class DnsCheck(private val tx: UbusSession, private val plan: RouterPlan, private val log: (String) -> Unit) {
    private var upstreamFailures = 0

    suspend fun ready(): Boolean {
        val running = tx.serviceInstances("dnsmasq")
        if (running != null) {
            val ours = running.filterKeys { it.startsWith("wrtc_dns_") }
            if (ours.filterValues { it }.keys != plan.dnsInstances.keys) {
                log("dns: en marcha=${ours.filterValues { it }.keys} esperadas=${plan.dnsInstances.keys}")
                return false
            }
        }
        for (profile in plan.profiles.filter { it.needsDns }) {
            val port = profile.dnsPort!!
            when (tx.lookup(CANARY_DOMAIN, port)) {
                Lookup.Unavailable -> return true // sin permiso para nslookup: basta con que estén en marcha
                Lookup.NxDomain -> {}
                else -> {
                    log("dns: ${dnsInstance(profile)} aún no bloquea el canario")
                    return false
                }
            }
            if (profile.filter.level != FilterLevel.None && tx.lookup(UPSTREAM_PROBE, port) != Lookup.Resolved) {
                // Si el router tampoco resuelve por su DNS normal, no tiene internet ahora: no es culpa del filtro.
                if (tx.lookup(UPSTREAM_PROBE) != Lookup.Resolved) continue
                if (++upstreamFailures >= 3) {
                    throw VerificationFailed(
                        "El DNS para familias (${profile.filter.level.upstreams().first()}) no responde desde el router, " +
                            "así que no se activó el filtro. Prueba otro nivel o revisa si tu proveedor lo bloquea.",
                    )
                }
                return false
            }
        }
        return true
    }

    private companion object {
        /** Dominio estable para comprobar que el DNS para familias contesta. */
        const val UPSTREAM_PROBE = "openwrt.org"
    }
}

/** Configuración del router que toca la app, leída en una sesión. */
private data class RouterState(val firewall: List<UciSection>, val dhcp: List<UciSection>) {
    val env: RouterEnv get() = routerEnv(firewall, dhcp)

    fun changesTo(plan: RouterPlan): List<Staged> =
        diffSections(firewall, plan.firewall).map { Staged("firewall", it) } +
            diffSections(dhcp, plan.dhcp).map { Staged("dhcp", it) }

    /** Secciones de WrtControl por configuración y nombre (para comparar sin importar el orden). */
    fun ownSections(): Map<String, Map<String, UciSection>> = mapOf(
        "firewall" to firewall.filter { it.name.startsWith(SECTION_PREFIX) }.associateBy { it.name },
        "dhcp" to dhcp.filter { it.name.startsWith(SECTION_PREFIX) }.associateBy { it.name },
    )
}

private data class Staged(val config: String, val change: UciChange)

private suspend fun UbusCaller.readState(): RouterState = coroutineScope {
    val firewall = async { uciSections("firewall") }
    val dhcp = async { uciSections("dhcp") }
    RouterState(firewall.await(), dhcp.await())
}

private suspend fun UbusSession.stage(changes: List<Staged>) {
    for ((config, change) in changes) when (change) {
        is UciChange.Delete -> uciDelete(config, change.section)
        is UciChange.Add -> uciAdd(config, change.section)
    }
}

private val UTC_ALIASES = setOf("UTC", "Etc/UTC", "GMT", "Etc/GMT", "Z", "Etc/Universal", "Universal", "Etc/Zulu", "Zulu")
