package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.Lookup
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.UciValue
import dev.angel.wrtcontrol.openwrt.fileList
import dev.angel.wrtcontrol.openwrt.fileRemove
import dev.angel.wrtcontrol.openwrt.fileWrite
import dev.angel.wrtcontrol.openwrt.lookup
import dev.angel.wrtcontrol.openwrt.networkDevices
import dev.angel.wrtcontrol.openwrt.resolve
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
    val settings: ParentalSettings = ParentalSettings(),
    /** Lo cargado en el firewall (null si no se puede leer). */
    val firewall: LoadedFirewall? = null,
    /** Versión del fichero de reglas que pide la configuración (null si no hay o es de una versión antigua). */
    val expectedInclude: String? = null,
    /** Reglas de fw4 que deberían estar cargadas (null si no se pudo calcular). */
    val expectedRules: Set<String>? = null,
) {
    val config: ParentalConfig get() = ParentalConfig(profiles, settings)

    /** ¿El filtro de este perfil está funcionando en el router? null si no se sabe. */
    fun filterRunning(p: Profile): Boolean? =
        if (!p.needsDns) null else dnsRunning?.let { it[dnsInstance(p)] == true }

    /** Intentos de usar DNS cifrado que se bloquearon desde la última recarga del firewall. */
    fun dnsBypassBlocked(p: Profile): Long = firewall?.ownCounters?.get("doh ${p.id}") ?: 0L

    /**
     * ¿Están cargadas las reglas que pide la configuración (el fichero propio y las de fw4)?
     * null si no se sabe. Puede haber de más: una pausa que ya venció sigue cargada hasta el
     * siguiente cambio, sin efecto.
     */
    val rulesLoaded: Boolean?
        get() {
            if (firewall == null || (expectedInclude == null && expectedRules == null)) return null
            if (expectedInclude != null && firewall.includeVersion != expectedInclude) return false
            return expectedRules == null || firewall.wrtcontrolRules.toSet().containsAll(expectedRules)
        }
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
    /** Compartido con los demás cambios de la app (ver OpenWrtApi): dos `uci apply` a la vez se pisarían. */
    private val writeLock: Mutex = Mutex(),
) {
    suspend fun load(): ParentalSnapshot = coroutineScope {
        val firewall = async { ubus.uciSections("firewall") }
        val local = async { ubus.routerLocalTime() }
        val unix = async { ubus.routerUnixTime() }
        val system = async { ubus.systemSection() }
        val dns = async { ubus.serviceInstances("dnsmasq") }
        val loaded = async { runCatchingNonCancel { ubus.loadedFirewall() } }
        val devices = async { runCatchingNonCancel { ubus.networkDevices() } }
        val sections = firewall.await()
        val localSeconds = local.await()
        val config = readConfig(sections)
        val expected = try {
            compileRouter(config, routerDateTime(localSeconds), routerEnv(sections, emptyList(), devices = devices.await())).ruleLabels.toSet()
        } catch (_: IllegalArgumentException) {
            null
        }
        ParentalSnapshot(
            profiles = config.profiles,
            routerNow = routerDateTime(localSeconds),
            routerOffsetSeconds = roundOffset(localSeconds - (unix.await() ?: phoneClockSeconds())),
            routerZone = system.await()?.text("zonename"),
            flowOffloading = sections.firstOrNull { it.type == "defaults" }?.text("flow_offloading") == "1",
            markConflicts = markConflicts(sections),
            dnsRunning = dns.await(),
            settings = config.settings,
            firewall = loaded.await(),
            expectedInclude = includeVersion(sections.firstOrNull { it.name == INCLUDE_SECTION }?.text("path")),
            expectedRules = expected,
        )
    }

    /**
     * Lee la configuración actual del router, le aplica [change] (con la hora local del router)
     * y deja el firewall y el DNS como corresponde. Leer dentro de la transacción evita pisar
     * cambios hechos desde otro teléfono entre medias. [appMacs] son las MAC del teléfono que
     * hace el cambio: queda como administrador (nunca pierde el acceso al router ni, con la
     * cuarentena, internet).
     * [force] vuelve a escribir y cargar las reglas aunque la configuración no cambie (p. ej. si
     * se borró el fichero). Devuelve la configuración que quedó.
     */
    suspend fun update(
        appMacs: Set<String> = emptySet(),
        force: Boolean = false,
        change: (config: ParentalConfig, now: LocalDateTime) -> ParentalConfig,
    ): ParentalConfig = writeLock.withLock {
        val app = appMacs.mapNotNull(::normalizeMac).toSet()
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val state = tx.readState()
        val now = routerDateTime(tx.routerLocalTime())
        // Si la zona horaria cambió desde que se escribieron las horas guardadas, se mueven primero.
        val offset = tx.offsetSeconds()
        val before = readConfig(state.firewall).shiftedTo(offset)
        val changed = change(before, now).let { c ->
            val admins = (c.settings.admins + app).distinct().sorted()
            // Con la cuarentena, un dispositivo que sale de todos los perfiles (al borrar el perfil o
            // quitarlo de él) sigue aprobado: era de confianza. Salvo que el cambio le quite la aprobación.
            val left = before.profiles.flatMap { it.macs }.toSet() - c.profiles.flatMap { it.macs }.toSet()
            val unapproved = before.settings.approved.toSet() - c.settings.approved.toSet()
            // También con la cuarentena apagada si ya se usó (hay aprobados): al reactivarla, siguen aprobados.
            val keepApproved = c.settings.quarantine || c.settings.approved.isNotEmpty()
            val approved = if (keepApproved) (c.settings.approved + (left - unapproved)).distinct().sorted() else c.settings.approved
            c.copy(
                profiles = bumpCutEpochs(before.profiles, c.profiles, now),
                settings = c.settings.copy(approved = approved, admins = admins, timeOffset = offset),
            )
        }
        val plan = compileRouter(changed, now, state.env, app)
        validateConfig(ParentalConfig(plan.profiles, plan.settings))
        applyPlan(tx, state, plan, force)
        ParentalConfig(plan.profiles, plan.settings)
    }

    /** Atajo para cambiar solo los perfiles. */
    suspend fun updateProfiles(
        appMacs: Set<String> = emptySet(),
        change: (profiles: List<Profile>, now: LocalDateTime) -> List<Profile>,
    ): ParentalConfig = update(appMacs) { config, now -> config.copy(profiles = change(config.profiles, now)) }

    /**
     * Pone el router en la zona horaria del teléfono ([zoneId], p. ej. "America/Mexico_City"),
     * cuyo desfase actual es [expectedOffsetSeconds]. Se confirma cuando el reloj del router lo
     * refleja: si las reglas de LuCI para esa zona dieran otra hora, se deja como estaba.
     * Después recarga el firewall, porque nftables pasa las horas de las reglas a UTC al cargarlas.
     */
    suspend fun setTimezone(zoneId: String, expectedOffsetSeconds: Int, appMacs: Set<String> = emptySet()) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        // LuCI no incluye UTC en su lista: lo trata aparte, como zonename "UTC" y timezone "GMT0".
        val zones = tx.routerTimezones() + ("UTC" to "GMT0")
        val wanted = if (zoneId in UTC_ALIASES) "UTC" else zoneId
        val zone = zones.keys.firstOrNull { sameZone(it, wanted) }
            ?: throw IllegalArgumentException("El router no conoce la zona horaria $zoneId")
        val system = tx.systemSection() ?: throw UbusException.Protocol("El router no tiene sección system")
        val oldOffset = tx.offsetSeconds()
        // Si el router trae horario de verano para una zona que ya no lo tiene, manda el teléfono.
        val luciTz = zones.getValue(zone)
        val fixed = runCatching { fixedPosixTz(ZoneId.of(zoneId), Instant.now()) }.getOrNull()
        val tz = if (fixed != null && ',' in luciTz) fixed else luciTz

        tx.uciSet("system", system.name, mapOf("zonename" to zone, "timezone" to tz))
        tx.applySafely(
            failure = "El router no tomó la hora de esa zona (${formatUtcOffset(expectedOffsetSeconds)}), así que se dejó como estaba.",
            isReverted = { fresh -> fresh.systemSection()?.text("zonename") == system.text("zonename") },
            isLive = { tx.offsetSeconds() == expectedOffsetSeconds },
        )

        val state = tx.readState()
        if (state.firewall.none { it.name == INCLUDE_SECTION }) return@withLock
        // Las horas guardadas (fin de la pausa, del tiempo extra y del segundo corte) son hora
        // local: con la zona nueva hay que moverlas para que sigan siendo el mismo instante. Si
        // esto falla, el siguiente cambio las mueve (ver timeOffset).
        val stored = readConfig(state.firewall)
        val config = stored.copy(settings = stored.settings.copy(timeOffset = stored.settings.timeOffset ?: oldOffset))
            .shiftedTo(expectedOffsetSeconds)
        val plan = compileRouter(config, routerDateTime(tx.routerLocalTime()), state.env, appMacs)
        try {
            applyPlan(tx, state, plan, force = true)
        } catch (_: ChangeRevertedException) {
            throw ChangeRevertedException(
                "La zona horaria ya cambió, pero el router no volvió a cargar las reglas de los horarios: " +
                    "pueden ir desfasadas. Vuelve a guardar cualquier perfil para recargarlas.",
            )
        }
    }

    /**
     * Prepara en [tx] lo necesario para pasar de [state] a [plan], lo aplica, lo verifica y
     * limpia los ficheros de reglas que ya no se usan. Con [force], recarga el firewall aunque
     * la configuración no cambie (borrar y volver a crear la sección include basta).
     */
    private suspend fun applyPlan(tx: UbusSession, state: RouterState, plan: RouterPlan, force: Boolean) {
        var changes = state.changesTo(plan)
        val includeSection = plan.firewall.firstOrNull { it.name == INCLUDE_SECTION }
        if (force && includeSection != null && changes.none { (it.change as? UciChange.Add)?.section?.name == INCLUDE_SECTION }) {
            changes = changes + Staged("firewall", UciChange.Delete(INCLUDE_SECTION)) + Staged("firewall", UciChange.Add(includeSection))
        }
        if (changes.isEmpty()) return

        val oldPath = state.includePath
        val newPath = plan.include?.path?.takeIf { it != oldPath }
        // El nombre lleva el hash del contenido: si no cambia, el fichero en uso ya es este y no se
        // reescribe (rpcd trunca y escribe; un corte a mitad dejaría roto el único fichero bueno).
        // Si falta (una actualización del router, una limpieza), se repone.
        val include = plan.include
        if (include != null && (newPath != null || !tx.includeExists(include.path))) writeInclude(tx, include)
        var applying = false
        try {
            tx.stage(changes)
            applying = true
            applyAndVerify(tx, plan, state)
        } catch (e: CancellationException) {
            throw e // a medias: el router revierte solo y el fichero se limpia en el próximo cambio
        } catch (e: Exception) {
            // Si no se llegó a aplicar o el router volvió a la configuración anterior, el fichero
            // nuevo no lo usa nadie. Si no se sabe cómo quedó (ChangeUncertainException) o hay otro
            // cambio pendiente (que podría usar el mismo fichero), se deja: lo limpia el próximo cambio.
            if ((!applying && e !is PendingChangeException) || e is ChangeRevertedException) newPath?.let { removeQuietly(tx, it) }
            throw e
        }
        removeOldIncludes(tx, keep = plan.include?.path, old = oldPath)
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
            isReverted = { fresh -> fresh.readState().ownSections() == before.ownSections() },
        ) {
            val firewallOk = if (firewallBefore == null) {
                // Sin permiso para listar el firewall: solo se puede comprobar que el router sigue respondiendo.
                tx.routerLocalTime()
                ++waited >= 3
            } else {
                val now = tx.loadedFirewall() ?: return@applySafely false
                val reloaded = now.reloadedSince(firewallBefore)
                val matches = now.matches(plan)
                log("firewall: recargado=$reloaded coincide=$matches propias=${now.includeVersion}/${plan.include?.version} reglas=${now.wrtcontrolRules.toSet()} esperadas=${plan.ruleLabels.toSet()}")
                reloaded && matches
            }
            firewallOk && dns.ready()
        }
    }

    private suspend fun UbusCaller.offsetSeconds(): Int {
        val local = routerLocalTime()
        return roundOffset(local - (routerUnixTime() ?: phoneClockSeconds()))
    }

    private suspend fun writeInclude(tx: UbusSession, include: NftInclude) {
        try {
            tx.fileWrite(include.path, include.content, mode = FILE_MODE)
        } catch (e: UbusException.Status) {
            throw if (e.code == 6) {
                UbusException.Protocol("El router no deja escribir en $INCLUDE_DIR (¿tiene LuCI instalado?)")
            } else {
                e
            }
        }
    }

    private suspend fun UbusSession.includeExists(path: String): Boolean {
        val names = runCatchingNonCancel { fileList(INCLUDE_DIR) } ?: return false
        return path.substringAfterLast('/') in names
    }

    /** Ya confirmado el cambio, borra los ficheros de reglas que no usa la configuración. */
    private suspend fun removeOldIncludes(tx: UbusSession, keep: String?, old: String?) {
        val listed = runCatchingNonCancel { tx.fileList(INCLUDE_DIR) }?.map { "$INCLUDE_DIR/$it" }
        val candidates = listed ?: listOfNotNull(old, LEGACY_INCLUDE_PATH)
        candidates.filter { isIncludeFile(it) && it != keep }.distinct().forEach { removeQuietly(tx, it) }
    }

    private suspend fun removeQuietly(tx: UbusSession, path: String) {
        runCatchingNonCancel { tx.fileRemove(path) }
    }

    private companion object {
        /** 0600: solo root (fw4) lee las reglas. */
        const val FILE_MODE = 0x180
    }
}

/**
 * Comprueba los servidores DNS de los perfiles: que estén en marcha, que respondan con su lista de
 * bloqueo (el canario da NXDOMAIN), que su DNS para familias conteste y que la búsqueda segura y
 * YouTube restringido den las direcciones de Google. Si el router no deja mirar algo (ACL), esa
 * parte no se comprueba.
 */
private class DnsCheck(private val tx: UbusSession, private val plan: RouterPlan, private val log: (String) -> Unit) {
    private var upstreamFailures = 0
    private var filteringFailures = 0

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
            for ((name, host) in expectedRecords(profile)) {
                val wanted = SAFE_SEARCH_RECORDS.getValue(host).first()
                val got = tx.resolve(name, port).addresses
                if (wanted !in got) {
                    log("dns: ${dnsInstance(profile)} da $got para $name (se esperaba $wanted)")
                    return false
                }
            }
            if (!filtersUpstream(profile, port)) return false
        }
        return true
    }

    /**
     * Lo que resuelve el DNS para familias (y Bing con la búsqueda segura sin nivel) se comprueba
     * de verdad: si el proveedor de internet intercepta el DNS, las consultas a 1.1.1.3 o a
     * CleanBrowsing llegan a su servidor y no se filtra nada aunque todo "responda". Los dos dan
     * forcesafesearch.google.com para www.google.com y strict.bing.com para www.bing.com.
     */
    private suspend fun filtersUpstream(profile: Profile, port: Int): Boolean {
        val f = profile.filter
        // Si el propio perfil bloquea el dominio de prueba (p. ej. un sitio "google.com"), da
        // NXDOMAIN: se usa el otro o, si no queda ninguno, no se comprueba.
        val blocked = f.domains + f.services.flatMap { id -> SERVICES.firstOrNull { it.id == id }?.domains.orEmpty() }
        fun usable(name: String) = blocked.none { name == it || name.endsWith(".$it") }
        val google = f.level != FilterLevel.None && usable("www.google.com")
        val bing = (f.level != FilterLevel.None || f.safeSearch) && usable("www.bing.com")
        val answer = when {
            google -> tx.resolve("www.google.com", port)
            bing -> tx.resolve("www.bing.com", port)
            else -> return true
        }
        if (answer.status == Lookup.Unavailable) return true
        val ok = if (google) {
            SAFE_SEARCH_RECORDS.getValue(GOOGLE_SAFE_HOST).first() in answer.addresses
        } else {
            BING_STRICT_HOST in answer.cnames
        }
        if (ok) return true
        if (answer.status != Lookup.Resolved) {
            // Sin respuesta no es que lo intercepten: o el router no tiene internet ahora (no es
            // culpa del filtro) o el DNS para familias no contesta.
            if (tx.lookup(UPSTREAM_PROBE) != Lookup.Resolved) return true
            if (++upstreamFailures >= 3) {
                throw VerificationFailed(
                    "El DNS para familias (${(if (f.level != FilterLevel.None) f.level else FilterLevel.Adult).upstreams().first()}) no responde " +
                        "desde el router, así que no se activó el filtro. Revisa si tu proveedor lo bloquea.",
                )
            }
            return false
        }
        log("dns: ${dnsInstance(profile)} no da las respuestas filtradas del DNS para familias")
        if (++filteringFailures >= 3) {
            throw VerificationFailed(
                "El DNS para familias no está filtrando desde tu router: parece que tu proveedor de internet " +
                    "intercepta las consultas DNS. No se activó el filtro.",
            )
        }
        return false
    }

    /** Nombre que preguntar → host de Google cuya dirección debe devolver. */
    private fun expectedRecords(p: Profile): List<Pair<String, String>> = buildList {
        val f = p.filter
        if (f.safeSearch && f.level == FilterLevel.None) add("www.google.com" to GOOGLE_SAFE_HOST)
        if (f.youtube != YoutubeMode.Off && "youtube" !in f.services) {
            add("www.youtube.com" to if (f.youtube == YoutubeMode.Strict) YOUTUBE_STRICT_HOST else YOUTUBE_MODERATE_HOST)
        }
    }

    private companion object {
        /** Dominio estable para comprobar que el DNS para familias contesta. */
        const val UPSTREAM_PROBE = "openwrt.org"
    }
}

/** Configuración del router que toca la app, leída en una sesión. */
private data class RouterState(
    val firewall: List<UciSection>,
    val dhcp: List<UciSection>,
    val admin: List<UciSection>? = null,
    val devices: Map<String, List<String>>? = null,
) {
    val env: RouterEnv get() = routerEnv(firewall, dhcp, admin, devices)

    val includePath: String? get() = firewall.firstOrNull { it.name == INCLUDE_SECTION }?.text("path")

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
    val devices = async { runCatchingNonCancel { networkDevices() } }
    // Cada una por su lado: si falta dropbear (o no se puede leer), uhttpd sigue contando.
    val uhttpd = async { runCatchingNonCancel { uciSections("uhttpd") } }
    val dropbear = async { runCatchingNonCancel { uciSections("dropbear") } }
    val web = uhttpd.await()
    val ssh = dropbear.await()
    val admin = when {
        web == null && ssh == null -> null
        else -> (web ?: listOf(DEFAULT_UHTTPD)) + (ssh ?: listOf(DEFAULT_DROPBEAR))
    }
    RouterState(firewall.await(), dhcp.await(), admin, devices.await())
}

private suspend fun UbusSession.stage(changes: List<Staged>) {
    for ((config, change) in changes) when (change) {
        is UciChange.Delete -> uciDelete(config, change.section)
        is UciChange.Add -> uciAdd(config, change.section)
    }
}

/** Lo que no es esencial (listar, borrar ficheros viejos): si falla, null. */
private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Lo que se supone si no se puede leer uhttpd o dropbear: los puertos de siempre. */
private val DEFAULT_UHTTPD = UciSection("main", "uhttpd", mapOf("listen_http" to UciValue.Items(listOf("0.0.0.0:80")), "listen_https" to UciValue.Items(listOf("0.0.0.0:443"))))
private val DEFAULT_DROPBEAR = UciSection("main", "dropbear", emptyMap())

/** La configuración con las horas guardadas pasadas del desfase con que se escribieron a [offset]. */
private fun ParentalConfig.shiftedTo(offset: Int): ParentalConfig {
    val from = settings.timeOffset ?: return copy(settings = settings.copy(timeOffset = offset))
    val shift = (offset - from).toLong()
    return copy(profiles = profiles.map { it.shiftedBy(shift) }, settings = settings.copy(timeOffset = offset))
}

/** El perfil con las horas guardadas movidas [seconds]. */
private fun Profile.shiftedBy(seconds: Long): Profile = if (seconds == 0L) this else copy(
    pause = (pause as? Pause.Until)?.let { Pause.Until(it.end.plusSeconds(seconds)) } ?: pause,
    extraUntil = extraUntil?.plusSeconds(seconds),
    cutWindow = cutWindow?.let { it.copy(until = it.until.plusSeconds(seconds)) },
)

private val UTC_ALIASES = setOf("UTC", "Etc/UTC", "GMT", "Etc/GMT", "Z", "Etc/Universal", "Universal", "Etc/Zulu", "Zulu")
