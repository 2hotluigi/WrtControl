package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.data.RouterTransport
import dev.angel.wrtcontrol.data.TunnelConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.InetAddress

/*
 * "¿Se llega desde fuera?": un saludo de WireGuard al router desde los datos móviles del
 * teléfono, sin salir del Wi-Fi de casa, por IPv6 y por IPv4. Es lo que hará la app cuando esté
 * fuera, así que contesta a la pregunta de verdad, y además dice qué falla y dónde.
 */

/** Cómo fue el saludo hacia una dirección. */
enum class ProbeOutcome {
    /** El router contestó. */
    Reached,

    /** El saludo salió y nadie contestó: algo lo descarta por el camino (o el router no está). */
    NoReply,

    /** No salió nada: los datos móviles no tienen esa familia (IPv4 o IPv6). */
    CannotSend,
}

/** Los datos móviles no están disponibles (apagados, sin SIM o sin cobertura). */
class NoMobileDataException(message: String) : IOException(message)

/** El camino hacia internet por los datos móviles, mientras dura una prueba. */
interface OutsideLink {
    /** Resuelve [host] con el DNS de los datos móviles: lo que ve cualquiera desde fuera. */
    fun resolve(host: String): List<InetAddress>

    /** Saludo de WireGuard a [endpoint] ("ip:puerto" o "[ipv6]:puerto") con la clave de este teléfono. */
    fun probe(config: TunnelConfig, endpoint: String): ProbeOutcome
}

/** Abre los datos móviles (sin dejar el Wi-Fi) para una prueba; [NoMobileDataException] si no hay. */
interface OutsideNetwork {
    suspend fun <T> withMobileData(block: suspend (OutsideLink) -> T): T
}

data class FamilyProbe(val family: IpFamily, val address: String, val outcome: ProbeOutcome)

/**
 * Lo que se vio desde fuera. [published]: lo que da el nombre del DNS dinámico desde los datos
 * móviles (null: no hay nombre; vacío: no resuelve).
 */
data class ReachabilityReport(val probes: List<FamilyProbe>, val published: List<String>?) {
    val reached: Set<IpFamily> get() = probes.filter { it.outcome == ProbeOutcome.Reached }.map { it.family }.toSet()
}

internal fun familyOf(address: String) = if (':' in address) IpFamily.V6 else IpFamily.V4

/**
 * Prueba el router desde fuera. Por cada familia, la dirección pública que tiene ahora el router
 * ([publicAddresses]); si no la sabe (p. ej. IPv4 detrás de otro router), la que publique el DNS.
 * Las dos familias van a la vez: cada una puede tardar varios segundos si nadie contesta.
 */
suspend fun checkFromOutside(
    network: OutsideNetwork,
    config: TunnelConfig,
    hostname: String?,
    publicAddresses: List<String>,
): ReachabilityReport = network.withMobileData { link ->
    val published = hostname?.let { name ->
        try {
            link.resolve(name).map(RouterTransport::ipText).distinct()
        } catch (_: IOException) {
            emptyList()
        }
    }
    val targets = listOf(IpFamily.V6, IpFamily.V4).mapNotNull { family ->
        val address = publicAddresses.firstOrNull { familyOf(it) == family }
            ?: published?.firstOrNull { familyOf(it) == family }
        address?.let { family to it }
    }
    val probes = coroutineScope {
        targets.map { (family, address) ->
            async {
                val endpoint = if (family == IpFamily.V6) "[$address]:${config.port}" else "$address:${config.port}"
                FamilyProbe(family, address, link.probe(config, endpoint))
            }
        }.awaitAll()
    }
    ReachabilityReport(probes, published)
}

/**
 * Qué decirle al usuario. [ok]: se llega por alguna familia. [untested]: no se llega, pero porque
 * los datos móviles no pudieron probar ninguna familia que el router tenga, no porque el router no
 * conteste. [publishOnly]: las familias que convendría publicar en el DNS dinámico (las que
 * entran), si no son las que publica ahora.
 */
data class ReachabilityAdvice(
    val ok: Boolean,
    val lines: List<String>,
    val publishOnly: Set<IpFamily>? = null,
    val untested: Boolean = false,
)

/** Lo que se sabe del router para explicar el resultado. */
data class RouterReachContext(
    val port: Int,
    /** IPv4 de la WAN del router si es privada (otro router o CGNAT delante). */
    val privateWanIpv4: String?,
    /** Familias que publica el DNS dinámico de la app; null si no lo hay. */
    val ddnsFamilies: Set<IpFamily>?,
    /** Lo que el router cree tener publicado (sus direcciones públicas de ahora). */
    val publicAddresses: List<String>,
)

fun adviseReachability(report: ReachabilityReport, router: RouterReachContext): ReachabilityAdvice {
    val lines = mutableListOf(
        ipv6Line(report.probes.firstOrNull { it.family == IpFamily.V6 }),
        ipv4Line(report.probes.firstOrNull { it.family == IpFamily.V4 }, router),
    )
    val reached = report.reached
    val (ddnsLines, publishOnly) = ddnsAdvice(report, router)
    lines += ddnsLines
    if (router.ddnsFamilies == null && reached.isNotEmpty()) {
        lines += "Sin DNS dinámico, si tu proveedor le cambia la dirección al router, la app no lo encontrará: configúralo abajo."
    }
    val untested = reached.isEmpty() && report.probes.none { it.outcome == ProbeOutcome.NoReply }
    return ReachabilityAdvice(ok = reached.isNotEmpty(), lines = lines, publishOnly = publishOnly, untested = untested)
}

private fun ipv6Line(p: FamilyProbe?): String = when (p?.outcome) {
    ProbeOutcome.Reached -> "IPv6 (${p.address}): se llega."
    ProbeOutcome.NoReply -> "IPv6 (${p.address}): el router no contestó. Casi siempre es el módem del proveedor, que " +
        "descarta lo que entra por IPv6: en su página, baja el nivel del cortafuegos IPv6 o pon el router en su DMZ."
    ProbeOutcome.CannotSend -> "IPv6: tus datos móviles no tienen IPv6, así que no se ha podido probar. Desde redes con " +
        "IPv6 puede que sí se llegue."
    null -> "IPv6: el router no tiene una IPv6 pública. Si tu proveedor da IPv6, revisa la WAN6 del router."
}

private fun ipv4Line(p: FamilyProbe?, router: RouterReachContext): String {
    val behind = router.privateWanIpv4
    return when (p?.outcome) {
        ProbeOutcome.Reached -> "IPv4 (${p.address}): se llega."
        ProbeOutcome.NoReply -> if (behind != null) {
            "IPv4 (${p.address}): no entra. El router está detrás de otro aparato (su WAN tiene $behind): " +
                "en ese aparato, abre el puerto UDP ${router.port} hacia el router o ponlo en su DMZ."
        } else {
            "IPv4 (${p.address}): el router no contestó. Puede que algo delante descarte lo que entra."
        }
        ProbeOutcome.CannotSend -> "IPv4: tus datos móviles no tienen IPv4, así que no se ha podido probar."
        null -> if (behind != null) {
            "IPv4: el router está detrás de otro aparato o de un CGNAT (su WAN tiene $behind), así que por IPv4 no se llega."
        } else {
            "IPv4: el router no tiene una IPv4 pública."
        }
    }
}

/** Lo que da el nombre desde fuera comparado con lo que entra, y qué familias convendría publicar. */
private fun ddnsAdvice(report: ReachabilityReport, router: RouterReachContext): Pair<List<String>, Set<IpFamily>?> {
    val families = router.ddnsFamilies
    val published = report.published
    if (families == null || published.isNullOrEmpty()) {
        // Android no da las direcciones de una familia que la red no tiene: sin IPv6 en los datos
        // móviles, un nombre que solo publica IPv6 parece vacío.
        val untestable = report.probes.filter { it.outcome == ProbeOutcome.CannotSend }.map { it.family }.toSet()
        val empty = published?.isEmpty() == true && families != null && !untestable.containsAll(families)
        return listOfNotNull(EMPTY_NAME.takeIf { empty }) to null
    }
    val lines = mutableListOf<String>()
    val routerFamilies = router.publicAddresses.map(::familyOf).toSet()
    val stale = published.filter { it !in router.publicAddresses && familyOf(it) in routerFamilies }
    if (stale.isNotEmpty()) {
        lines += "El DNS dinámico aún da ${stale.joinToString()}, que ya no es del router (se actualiza cada pocos minutos)."
    }
    val dead = published.map(::familyOf).toSet()
        .filter { f -> report.probes.any { it.family == f && it.outcome == ProbeOutcome.NoReply } }
    val keep = families intersect report.reached
    val publishOnly = keep.takeIf { dead.isNotEmpty() && it.isNotEmpty() && it != families }
    if (publishOnly != null) {
        lines += "El DNS dinámico publica " + dead.joinToString(" y ") { familyName(it) } + ", que no entra: algunos " +
            "programas la prueban primero y no conectan (WireGuard en Windows, p. ej.). Mejor publicar solo " +
            publishOnly.sorted().joinToString(" y ") { familyName(it) } + "."
    }
    return lines to publishOnly
}

private const val EMPTY_NAME =
    "Desde fuera, el nombre del DNS dinámico aún no da ninguna dirección: el proveedor puede tardar unos minutos."

private fun familyName(f: IpFamily) = if (f == IpFamily.V6) "IPv6" else "IPv4"

/** El DNS dinámico de la app con el mismo nombre y token, publicando solo [families]; null si no lo hay. */
fun ddnsEditWith(state: AddonsState, families: Set<IpFamily>): DdnsEdit? {
    val own = state.ownDdns.firstOrNull()
    val section = state.ddnsConfig.orEmpty().firstOrNull { it.name in DDNS_SECTIONS }
    val provider = own?.provider
    if (section == null || provider == null) return null
    val custom = provider == DdnsProvider.Custom
    return DdnsEdit(
        provider = provider,
        domain = own.domain,
        token = section.text("password").orEmpty(),
        families = families,
        updateUrl = section.text("update_url").takeIf { custom },
        username = section.text("username")?.takeIf { custom && it != own.domain },
    )
}
