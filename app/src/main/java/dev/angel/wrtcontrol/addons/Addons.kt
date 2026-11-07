package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.InstalledPackages
import dev.angel.wrtcontrol.openwrt.InterfaceDump
import dev.angel.wrtcontrol.openwrt.PackageTool
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.exec
import dev.angel.wrtcontrol.openwrt.initAction
import dev.angel.wrtcontrol.openwrt.installPackages
import dev.angel.wrtcontrol.openwrt.installedPackages
import dev.angel.wrtcontrol.openwrt.protoHandlers
import dev.angel.wrtcontrol.openwrt.serviceInstances
import dev.angel.wrtcontrol.openwrt.uciAdd
import dev.angel.wrtcontrol.openwrt.uciDelete
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.uciSection
import dev.angel.wrtcontrol.openwrt.uciSections
import dev.angel.wrtcontrol.openwrt.uciSet
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusSession
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Paquetes opcionales que la app sabe instalar y configurar. [luciPackage] es lo que se instala
 * (arrastra el resto como dependencias) y además trae los permisos de LuCI con los que la app los
 * maneja; [corePackages], lo que hace que funcionen.
 */
enum class Addon(
    val title: String,
    val summary: String,
    val luciPackage: String,
    val corePackages: List<String>,
    /** netifd solo carga protocolos nuevos al arrancar: hay que reiniciar la red después. */
    val restartsNetwork: Boolean = false,
) {
    WireGuard(
        "WireGuard",
        "Túnel cifrado para que la app llegue al router cuando no estás en casa.",
        "luci-proto-wireguard", listOf("wireguard-tools"), restartsNetwork = true,
    ),
    Ddns(
        "DNS dinámico",
        "Un nombre fijo para el router, aunque tu proveedor le cambie la dirección.",
        "luci-app-ddns", listOf("ddns-scripts"),
    ),
    Sqm(
        "SQM (calidad de la conexión)",
        "Evita que la conexión se atasque cuando alguien descarga: videollamadas y juegos siguen fluidos.",
        "luci-app-sqm", listOf("sqm-scripts"),
    ),
    Upnp(
        "UPnP",
        "Deja que consolas y juegos abran puertos solos (NAT abierto). Es cómodo, pero cualquier " +
            "dispositivo de casa puede abrirlos.",
        "luci-app-upnp", listOf("miniupnpd-nftables", "miniupnpd"),
    ),
}

enum class AddonStatus {
    /** No está instalado. */
    Missing,

    /** Funciona, pero falta su parte de LuCI: sin ella la app no tiene permiso para configurarlo. */
    Partial,

    /** Instalado, pero netifd aún no lo cargó: falta reiniciar la red. */
    NeedsRestart,
    Installed,
}

/** Lo que se ve de los paquetes opcionales; cada parte es null si no está o no se puede leer. */
data class AddonsState(
    /** Espacio libre en la memoria del router, en KiB. */
    val freeKiB: Long?,
    /** Dispositivo de la salida a internet (la interfaz con la ruta por defecto). */
    val internetDevice: String?,
    /** Ese mismo y el que tiene debajo (pppoe-wan sobre eth1, p. ej.): SQM sirve en cualquiera. */
    val internetDevices: Set<String> = setOfNotNull(internetDevice),
    /** IPv4 de esa interfaz. */
    val internetIpv4: String?,
    /** Dispositivos de red, con el de internet primero (para elegir dónde va el SQM). */
    val devices: List<String>,
    val wireguard: List<WireGuardInterface>?,
    val wireguardLoaded: Boolean,
    val sqm: List<SqmQueue>?,
    val upnp: UpnpState?,
    val ddnsConfig: List<UciSection>?,
    /** El acceso remoto de WrtControl, si está configurado. */
    val remote: RemoteServer? = null,
    /** Direcciones con las que se llega al router desde internet (IPv6 global, IPv4 pública). */
    val publicAddresses: List<String> = emptyList(),
    /** Servicios de DNS dinámico (null si no se pueden leer). */
    val ddns: List<DdnsService>? = null,
) {
    /** Los servicios de DNS dinámico de WrtControl (IPv4 y/o IPv6); vacío si no está configurado. */
    val ownDdns: List<DdnsService> get() = ddns.orEmpty().filter { it.own }

    /** El nombre del DNS dinámico de WrtControl. */
    val ddnsDomain: String? get() = ownDdns.firstOrNull()?.domain

    /**
     * La IPv4 de internet del router es privada o de CGNAT: hay otro router delante o el proveedor
     * comparte la dirección. Desde internet no se llega por IPv4, y UPnP no puede abrir puertos.
     */
    val behindNat: Boolean get() = internetIpv4?.let(::isPrivateIpv4) == true

    /** Sin listado de paquetes ([packages] null) solo se sabe si se puede usar. */
    fun status(addon: Addon, packages: InstalledPackages?): AddonStatus {
        val usable = when (addon) {
            Addon.WireGuard -> wireguardLoaded
            Addon.Ddns -> ddnsConfig != null
            Addon.Sqm -> sqm != null
            Addon.Upnp -> upnp != null
        }
        if (packages == null) return if (usable) AddonStatus.Installed else AddonStatus.Missing
        return when {
            !packages.anyOf(addon.corePackages) -> AddonStatus.Missing
            addon == Addon.WireGuard && !wireguardLoaded -> AddonStatus.NeedsRestart
            addon.luciPackage !in packages -> AddonStatus.Partial
            else -> AddonStatus.Installed
        }
    }
}

/** Comprobaciones (una por segundo) tras aplicar SQM antes de darlo por bueno (procd + run.sh). */
internal const val SQM_SETTLE_POLLS = 10

/** Ya hay otra cola de SQM activa en ese dispositivo: sqm-scripts aplicaría las dos. */
class SqmConflictException(device: String) : Exception("Ya hay otra cola de SQM activa en $device. Desactívala antes.")

class AddonsApi(
    private val ubus: UbusClient,
    private val writeLock: Mutex = Mutex(),
    /** Lo que se espera a que el router vuelva tras reiniciar la red. */
    private val restartWaitMs: Long = 45_000,
    private val pollMs: Long = 1_000,
) {
    /** Paquetes instalados (es un listado grande: no conviene pedirlo en cada recarga). */
    suspend fun packages(): InstalledPackages? = ubus.installedPackages()

    suspend fun state(): AddonsState = coroutineScope {
        val info = async { optional { ubus.callAs<SystemInfo>("system", "info") } }
        val ifaces = async { optional { ubus.callAs<InterfaceDump>("network.interface", "dump").interfaces } }
        val handlers = async { optional { ubus.protoHandlers() } }
        val network = async { optional { ubus.uciSections("network") } }
        val wgLive = async { optional { ubus.callAs<Map<String, WgInstance>>("luci.wireguard", "getWgInstances") } }
        val sqm = async { optional { ubus.uciSections("sqm", "queue").map(::parseSqmQueue) } }
        val upnpConfig = async { optional { ubus.uciSections("upnpd", "upnpd").firstOrNull() } }
        val upnpRunning = async { optional { ubus.serviceInstances("miniupnpd") } }
        val upnpStatus = async { optional { ubus.callAs<UpnpStatus>("luci.upnp", "get_status") } }
        val ddns = async { optional { ubus.uciSections("ddns") } }
        val ddnsStatus = async { optional { ubus.callAs<Map<String, DdnsStatusJson>>("luci.ddns", "get_services_status") } }

        val interfaces = ifaces.await().orEmpty().filter { it.name != "loopback" }
        val wan = interfaces.firstOrNull { it.up && it.hasDefaultRoute && it.ipv4.isNotEmpty() }
            ?: interfaces.firstOrNull { it.up && it.hasDefaultRoute }
        val internet = wan?.let { it.l3Device ?: it.device }
        val devices = (listOfNotNull(internet) + interfaces.mapNotNull { it.l3Device ?: it.device })
            .filter { it.isNotBlank() && it != "lo" }.distinct()
        val loaded = handlers.await()?.let { "wireguard" in it } ?: false
        AddonsState(
            freeKiB = info.await()?.root?.avail,
            internetDevice = internet,
            internetDevices = listOfNotNull(wan?.l3Device, wan?.device).filter { it.isNotBlank() }.toSet(),
            internetIpv4 = wan?.ipv4?.firstOrNull()?.address,
            devices = devices,
            wireguard = network.await()?.let { parseWireGuard(it, wgLive.await(), interfaces) },
            wireguardLoaded = loaded,
            sqm = sqm.await(),
            upnp = upnpConfig.await()?.let { parseUpnp(it, upnpRunning.await()?.values?.any { r -> r }, upnpStatus.await()) },
            ddnsConfig = ddns.await(),
            remote = network.await()?.let(::parseRemoteServer),
            publicAddresses = publicAddresses(interfaces),
            ddns = ddns.await()?.let { sections ->
                val logs = sections.map { it.name }.filter { it in DDNS_SECTIONS }.associateWith { name ->
                    parseDdnsLog(
                        optional {
                            ubus.callAs<kotlinx.serialization.json.JsonObject>(
                                "luci.ddns", "get_services_log", buildJsonObject { put("service_name", name) },
                            )["result"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        },
                    )
                }
                parseDdns(sections, ddnsStatus.await(), logs)
            },
        )
    }

    /**
     * Crea o cambia el DNS dinámico de WrtControl (la sección `wrtcontrol`). Se da por bueno cuando
     * su actualizador está en marcha; si el proveedor rechaza el token, se ve después en el estado.
     */
    suspend fun saveDdns(edit: DdnsEdit) = writeLock.withLock {
        ddnsProblem(edit)?.let { throw IllegalArgumentException(it) }
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val sections = tx.uciSections("ddns")
        val interfaces = tx.callAs<InterfaceDump>("network.interface", "dump").interfaces
        val wanted = edit.families.ifEmpty { ddnsFamilies(interfaces) }
            .sorted().map { ddnsSection(edit, ddnsInterface(interfaces, it == IpFamily.V6), it) }
        val current = sections.filter { it.name in DDNS_SECTIONS }
        if (current.toSet() == wanted.toSet()) return@withLock
        current.filter { it !in wanted }.forEach { tx.uciDelete("ddns", it.name) }
        wanted.filter { it !in current }.forEach { tx.uciAdd("ddns", it) }
        applyDdns(tx, sections, running = wanted.map { it.name }.toSet())
    }

    /** Quita el DNS dinámico de WrtControl (los servicios de LuCI no se tocan). */
    suspend fun removeDdns() = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val sections = tx.uciSections("ddns")
        val own = sections.filter { it.name in DDNS_SECTIONS }
        if (own.isEmpty()) return@withLock
        own.forEach { tx.uciDelete("ddns", it.name) }
        applyDdns(tx, sections, running = emptySet())
    }

    /**
     * Aplica el DNS dinámico y comprueba que los actualizadores de [running] están en marcha y los
     * demás de la app, parados. Hasta 24.10 ddns no es un servicio de procd: aplicar no lo recarga y
     * el ACL de LuCI no deja ver `service list`, así que se mira el pid en `luci.ddns` y, si no está
     * como debe, se reinicia el servicio (para todos y arranca los activados).
     */
    private suspend fun applyDdns(tx: UbusSession, before: List<UciSection>, running: Set<String>) {
        if (running.isNotEmpty()) optional { tx.initAction("ddns", "enable") }
        // Un servicio borrado ya no sale en luci.ddns, aunque su actualizador siga en marcha: sin
        // `service list` no se ve, así que entonces se reinicia siempre (para los que ya no están).
        val removed = before.map { it.name }.filter { it in DDNS_SECTIONS && it !in running }
        var kicked = false
        tx.applySafely(
            failure = if (running.isNotEmpty()) "El DNS dinámico no arrancó en el router, así que se dejó como estaba." else
                "El router no quitó el DNS dinámico, así que se dejó como estaba.",
            isReverted = { fresh -> fresh.uciSections("ddns") == before },
            isLive = {
                val instances = tx.serviceInstances("ddns")
                val status = if (instances != null) null else
                    optional { tx.callAs<Map<String, DdnsStatusJson>>("luci.ddns", "get_services_status") }
                fun up(name: String) = if (instances != null) instances[name] == true else (status?.get(name)?.pid ?: 0) > 0
                if (instances == null && removed.isNotEmpty() && !kicked) {
                    kicked = true
                    optional { tx.initAction("ddns", "restart") }
                }
                val ok = (instances != null || status != null) &&
                    running.all(::up) && DDNS_SECTIONS.filter { it !in running }.none(::up)
                if (!ok && !kicked) {
                    kicked = true
                    optional { tx.initAction("ddns", "restart") }
                }
                // Sin ninguna forma de mirarlo, basta con haberlo reiniciado.
                ok || (instances == null && status == null)
            },
        )
    }

    /**
     * Da acceso remoto al teléfono [peerId] ([name], con su clave pública y una precompartida):
     * crea el servidor si no existe y añade o actualiza su par. Se aplica con reversión y se da
     * por bueno cuando WireGuard tiene la interfaz con ese par.
     */
    suspend fun enableRemoteAccess(peerId: String, name: String, publicKey: String, presharedKey: String): RemoteSetup =
        writeLock.withLock {
            require(isPeerId(peerId)) { "Identificador de teléfono no válido" }
            require(X25519.isKey(publicKey) && X25519.isKey(presharedKey)) { "Clave de WireGuard no válida" }
            val label = name.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "").trim().take(40).ifEmpty { "Teléfono" }
            val tx = ubus.openSession()
            tx.ensureNoPendingApply()
            val network = tx.uciSections("network")
            val firewall = tx.uciSections("firewall")
            val interfaces = tx.callAs<InterfaceDump>("network.interface", "dump").interfaces
            val current = parseRemoteServer(network)
            val privateKey = remotePrivateKey(network) ?: newRouterKey(tx)
            val port = current?.port ?: freeVpnPort(network)
            val routerAddress = current?.routerAddress ?: (freeVpnSubnet(interfaces).substringBeforeLast('.') + ".1")
            val prefix = current?.prefixLength ?: 24
            val others = current?.peers.orEmpty().filter { it.id != peerId }
            val address = current?.peers?.firstOrNull { it.id == peerId }?.address
                ?: nextPeerAddress(routerAddress, prefix, others.map { it.address }.toSet())
            val peers = others + RemotePeer(peerId, label, publicKey, presharedKey, address)
            val web = applyRemote(tx, network, firewall, interfaces, privateKey, port, routerAddress, prefix, peers)
            RemoteSetup(X25519.publicKey(privateKey), port, web.address, routerAddress, address, publicAddresses(interfaces))
        }

    /** Quita el acceso remoto del teléfono [peerId]; con el último, quita también el servidor. */
    suspend fun removeRemotePeer(peerId: String) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val network = tx.uciSections("network")
        val firewall = tx.uciSections("firewall")
        val current = parseRemoteServer(network) ?: return@withLock
        if (current.peers.none { it.id == peerId }) return@withLock
        val interfaces = tx.callAs<InterfaceDump>("network.interface", "dump").interfaces
        val privateKey = remotePrivateKey(network) ?: newRouterKey(tx)
        val peers = current.peers.filter { it.id != peerId }
        applyRemote(tx, network, firewall, interfaces, privateKey, current.port, current.routerAddress, current.prefixLength, peers)
    }

    /**
     * Aplica el servidor con [peers] (sin ninguno, lo quita entero) y devuelve dónde escucha la web
     * para el túnel.
     */
    private suspend fun applyRemote(
        tx: UbusSession,
        network: List<UciSection>,
        firewall: List<UciSection>,
        interfaces: List<NetInterface>,
        privateKey: String,
        port: Int,
        routerAddress: String,
        prefix: Int,
        peers: List<RemotePeer>,
    ): WebTarget {
        val routerIpv4 = interfaces.flatMap { it.ipv4 }.map { it.address }.toSet()
        val web = if (peers.isEmpty()) WebTarget(null, emptyList()) else webTarget(optional { tx.uciSections("uhttpd") }, routerIpv4)
        val internet = internetZones(firewall, interfaces)
        val (net, fw) = if (peers.isEmpty()) emptyList<UciSection>() to emptyList() else remoteSections(
            privateKey, port, routerAddress, prefix, peers,
            wanZones = internet,
            webPorts = web.ports,
            homeZones = homeZones(firewall, internet),
        )
        val changes = listOf("network" to diffRemote(network, net), "firewall" to diffRemote(firewall, fw))
        if (changes.all { (_, d) -> d.first.isEmpty() && d.second.isEmpty() }) return web
        for ((config, diff) in changes) {
            diff.first.forEach { tx.uciDelete(config, it) }
            diff.second.forEach { tx.uciAdd(config, it) }
        }
        val before = network.filter { it.name.startsWith(VPN_PREFIX) } + firewall.filter { it.name.startsWith(VPN_PREFIX) }
        val keys = peers.map { it.publicKey }.toSet()
        val removed = parseRemoteServer(network)?.peers.orEmpty().map { it.publicKey }.toSet() - keys
        // netifd no vuelve a configurar la interfaz si solo cambian sus pares (secciones aparte): hay
        // que reiniciarla, como el botón "Reiniciar" de LuCI (/sbin/ifup). Si la interfaz es nueva o
        // se quita entera, ya lo hace netifd.
        val restart = parseRemoteServer(network) != null && peers.isNotEmpty()
        suspend fun ifup() {
            tx.exec("/sbin/ifup", listOf(VPN_INTERFACE))
        }
        val uptimeBefore = interfaces.firstOrNull { it.name == VPN_INTERFACE }?.uptime
        tx.applySafely(
            failure = if (peers.isEmpty()) "El router no quitó el acceso remoto, así que se dejó como estaba." else
                "El router no levantó el acceso remoto (WireGuard), así que se dejó como estaba.",
            isReverted = { fresh ->
                fresh.uciSections("network").filter { it.name.startsWith(VPN_PREFIX) } +
                    fresh.uciSections("firewall").filter { it.name.startsWith(VPN_PREFIX) } == before
            },
            // Si la app va por este mismo túnel, la respuesta puede perderse: lo decide isLive.
            afterApply = { if (restart) ifup() },
            // Al deshacerlo, el kernel tampoco recupera los pares de antes sin reiniciar la interfaz.
            afterRevert = { if (restart) ifup() },
            isLive = {
                val live = optional { tx.callAs<Map<String, WgInstance>>("luci.wireguard", "getWgInstances") }
                if (live == null) {
                    // Sin getWgInstances (22.03 sin luci-app-wireguard): que netifd tenga la interfaz
                    // como toca y, si había que reiniciarla, que se haya levantado después del cambio.
                    val iface = tx.callAs<InterfaceDump>("network.interface", "dump").interfaces.firstOrNull { it.name == VPN_INTERFACE }
                    val up = iface?.up == true
                    if (peers.isEmpty()) !up
                    else up && (!restart || uptimeBefore == null || (iface?.uptime ?: Long.MAX_VALUE) < uptimeBefore)
                } else {
                    val wg = live[VPN_INTERFACE]
                    val liveKeys = wg?.peers?.map { it.publicKey }?.toSet()
                    // Puede haber pares añadidos fuera de la app: basta con que estén los de la app y no los quitados.
                    if (peers.isEmpty()) wg == null
                    else wg != null && wg.listenPort == port.toString() && liveKeys!!.containsAll(keys) && liveKeys.none { it in removed }
                }
            },
        )
        return web
    }

    /** Clave privada nueva para el router: la crea el propio router (`wg genkey`) si el ACL lo deja. */
    private suspend fun newRouterKey(tx: UbusSession): String =
        optional { tx.callAs<WgKeyPair>("luci.wireguard", "generateKeyPair").keys.priv }?.takeIf(X25519::isKey)
            ?: X25519.generatePrivateKey()

    /**
     * Instala [addon] con [tool] (actualiza antes las listas) y, si es un protocolo de red,
     * reinicia la red para que netifd lo cargue. Lanza PackageInstallException con el motivo.
     */
    suspend fun install(addon: Addon, tool: PackageTool) = writeLock.withLock {
        ubus.installPackages(tool, listOf(addon.luciPackage))
        if (addon.restartsNetwork) loadProtocol("wireguard")
    }

    /** Termina una instalación que quedó sin cargar (p. ej. se instaló desde la consola). */
    suspend fun finishWireGuard() = writeLock.withLock { loadProtocol("wireguard") }

    private suspend fun loadProtocol(proto: String) {
        if (proto in ubus.protoHandlers()) return
        try {
            ubus.initAction("network", "restart")
        } catch (e: UbusException.Network) {
            // Al reiniciar la red se puede perder la respuesta: se sigue esperando a que vuelva.
        }
        val handlers = withTimeoutOrNull(restartWaitMs) {
            var found: Set<String>? = null
            while (found == null) {
                delay(pollMs)
                found = optionalOrNetwork { ubus.protoHandlers() }
            }
            found
        } ?: throw UbusException.Network("El router no volvió a responder después de reiniciar la red.")
        if (proto !in handlers) {
            throw IllegalStateException("El router instaló el paquete pero no lo cargó. Reinícialo para terminar.")
        }
    }

    /**
     * Crea o cambia una cola de SQM. Se aplica con reversión: si el router deja de responder
     * (p. ej. con una velocidad tan baja que la conexión de la app se corta), lo deshace solo.
     */
    suspend fun saveSqm(edit: SqmEdit) = writeLock.withLock {
        sqmProblem(edit)?.let { throw IllegalArgumentException(it) }
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val sections = tx.uciSections("sqm", "queue")
        val before = sections.map(::parseSqmQueue)
        if (edit.enabled && before.any { it.enabled && it.device == edit.device && it.section != edit.section }) {
            throw SqmConflictException(edit.device)
        }
        val values = mapOf(
            "enabled" to if (edit.enabled) "1" else "0",
            "interface" to edit.device,
            "download" to edit.downloadKbit.toString(),
            "upload" to edit.uploadKbit.toString(),
        )
        if (edit.section == null) {
            val name = newQueueName(edit.device, sections.map { it.name }.toSet())
            tx.uciAdd(
                "sqm",
                uciSection(
                    name, "queue",
                    *values.toList().toTypedArray(),
                    "qdisc" to "cake",
                    "script" to "piece_of_cake.qos",
                    "linklayer" to "none",
                ),
            )
        } else {
            val current = sections.firstOrNull { it.name == edit.section }
                ?: throw IllegalStateException("Esa cola de SQM ya no existe en el router.")
            // Sin cambios, rpcd no deja nada pendiente y `uci apply` respondería "sin datos".
            val changed = values.filter { (k, v) -> current.text(k) != v }
            if (changed.isEmpty()) return@withLock
            tx.uciSet("sqm", edit.section, changed)
        }
        var polls = 0
        var answered = 0
        tx.applySafely(
            failure = "El router dejó de responder con el SQM nuevo, así que se deshizo el cambio.",
            isReverted = { fresh -> fresh.uciSections("sqm", "queue").map(::parseSqmQueue) == before },
            // procd recarga sqm un segundo después del apply y run.sh tarda unos segundos más: se
            // confirma solo si el router sigue respondiendo bien pasado ese rato.
            isLive = {
                val ok = try {
                    tx.callAs<SystemInfo>("system", "info")
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                polls++
                answered = if (ok) answered + 1 else 0
                // Cada comprobación va un segundo después de la anterior: SQM_SETTLE_POLLS ≈ segundos.
                polls >= SQM_SETTLE_POLLS && answered >= 3
            },
        )
        // Como LuCI al activar una cola: que el servicio también arranque con el router.
        if (edit.enabled) optional { ubus.initAction("sqm", "enable") }
    }

    /** Activa o desactiva UPnP ([enabled]) o su modo seguro ([secureMode]); lo que sea null no se toca. */
    suspend fun setUpnp(enabled: Boolean? = null, secureMode: Boolean? = null) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val section = tx.uciSections("upnpd", "upnpd").firstOrNull()
            ?: throw IllegalStateException("UPnP no tiene configuración en el router. Reinstálalo.")
        val values = buildMap {
            enabled?.let { put("enabled", if (it) "1" else "0") }
            secureMode?.let { put("secure_mode", if (it) "1" else "0") }
        }.filter { (k, v) -> section.text(k) != v }
        // Ya estaba así (p. ej. lo cambiaron desde LuCI mientras tanto): no hay nada que aplicar.
        if (values.isEmpty()) return@withLock
        // Si el servicio no arranca con el router (lo desactivaron en LuCI), activar UPnP no bastaría.
        if (enabled == true) optional { tx.initAction("miniupnpd", "enable") }
        tx.uciSet("upnpd", section.name, values)
        // La recarga la hace el disparador que miniupnpd registra en procd al arrancar. Si alguien
        // paró el servicio (LuCI → Sistema → Arranque), no hay disparador: se arranca o para a mano.
        var kicked = false
        tx.applySafely(
            failure = if (enabled == true) "UPnP no arrancó en el router, así que se dejó como estaba." else
                "El router no aplicó el cambio de UPnP, así que se dejó como estaba.",
            isReverted = { fresh -> fresh.uciSections("upnpd", "upnpd").firstOrNull() == section },
            isLive = {
                if (enabled == null) true else {
                    val instances = tx.serviceInstances("miniupnpd")
                    if (instances == null) {
                        // Sin `service list` (hasta 24.10 con el ACL de LuCI): se arranca o para a mano y basta.
                        if (!kicked) {
                            kicked = true
                            optional { tx.initAction("miniupnpd", if (enabled) "restart" else "stop") }
                        }
                        return@applySafely true
                    }
                    val running = instances.values.any { it }
                    if (running != enabled && !kicked) {
                        kicked = true
                        optional { tx.initAction("miniupnpd", if (enabled) "start" else "stop") }
                    }
                    running == enabled
                }
            },
        )
    }

    /** Cierra un puerto abierto por UPnP (miniupnpd se reinicia; el dispositivo puede volver a pedirlo). */
    suspend fun closeUpnpMapping(mapping: UpnpMapping) = writeLock.withLock {
        val reply = ubus.callAs<kotlinx.serialization.json.JsonObject>(
            "luci.upnp", "delete_rule",
            buildJsonObject { put("token", mapping.token) },
        )
        val result = (reply["result"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (result != null && result != "OK") throw UbusException.Protocol("El router no cerró el puerto ($result)")
    }
}

/** Redes privadas (RFC 1918), CGNAT (100.64.0.0/10) y enlace local: no son direcciones de internet. */
fun isPrivateIpv4(address: String): Boolean {
    val p = address.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
        (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254)
}

/** Lo que puede faltar (paquete sin instalar, sin permiso): null en vez de error. */
private suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: UbusException.AccessDenied) {
    null
} catch (_: UbusException.Status) {
    null
}

/** Como [optional], y además null si el router aún no responde (se está reiniciando la red). */
private suspend fun <T> optionalOrNetwork(block: suspend () -> T): T? = try {
    optional(block)
} catch (_: UbusException.Network) {
    null
}
