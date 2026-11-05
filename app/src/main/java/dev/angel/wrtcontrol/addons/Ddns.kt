package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.openwrt.uciSection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DNS dinámico (ddns-scripts + luci-app-ddns): el router avisa a un proveedor cada vez que cambia
 * su dirección, para que un nombre fijo (micasa.duckdns.org) apunte siempre a él. Lo usa el acceso
 * remoto para encontrar el router fuera de casa.
 *
 * La app crea su propio servicio, la sección `wrtcontrol` de /etc/config/ddns, con IPv6 si el
 * router tiene una dirección IPv6 pública (con CGNAT, la IPv4 no sirve para entrar). Los servicios
 * creados desde LuCI no se tocan; solo se muestran si están activados.
 */

const val DDNS_SECTION = "wrtcontrol"

/** Certificados para HTTPS: los de ca-bundle (22.03) y ca-certificates (25.12) acaban aquí. */
private const val CA_CERTS = "/etc/ssl/certs/ca-certificates.crt"

/**
 * Proveedores gratuitos con IPv6 que trae ddns-scripts. [suffix]: lo que se añade al nombre si el
 * usuario solo escribe la primera parte; [usernameIsDomain]: deSEC usa el dominio como usuario.
 */
enum class DdnsProvider(
    val service: String?,
    val title: String,
    val help: String,
    val suffix: String? = null,
    val usernameIsDomain: Boolean = false,
) {
    DuckDns(
        "duckdns.org", "DuckDNS",
        "Gratis. En duckdns.org entras con tu cuenta de Google o GitHub, eliges un nombre y copias el token.",
        suffix = ".duckdns.org",
    ),
    Dynv6(
        "dynv6.com", "dynv6",
        "Gratis y con buen soporte de IPv6. En dynv6.com creas una zona (p. ej. micasa.dynv6.net) y copias su token.",
    ),
    Desec(
        "desec.io", "deSEC",
        "Gratis y sin publicidad. En desec.io registras un nombre dedyn.io y creas un token.",
        suffix = ".dedyn.io", usernameIsDomain = true,
    ),

    /** Otro proveedor: la URL de actualización de ddns-scripts, con [DOMAIN], [PASSWORD] e [IP]. */
    Custom(null, "Otro", "La URL de actualización de tu proveedor, con [DOMAIN], [USERNAME], [PASSWORD] e [IP] donde vayan."),
    ;

    companion object {
        fun of(service: String?) = entries.firstOrNull { it.service != null && it.service == service }
    }
}

/** Lo que el usuario pide. [ipv6]: actualizar la dirección IPv6 (si no, la IPv4). */
data class DdnsEdit(
    val provider: DdnsProvider,
    val domain: String,
    val token: String,
    val ipv6: Boolean,
    val updateUrl: String? = null,
    val username: String? = null,
)

/** Estado del servicio en el router (`luci.ddns get_services_status`). */
@Serializable
internal data class DdnsStatusJson(
    val ip: String? = null,
    @SerialName("last_update") val lastUpdate: String? = null,
    @SerialName("next_update") val nextUpdate: String? = null,
    val pid: Int? = null,
)

/** Un servicio de DNS dinámico del router. */
data class DdnsService(
    val section: String,
    val enabled: Boolean,
    val provider: DdnsProvider?,
    val serviceName: String?,
    val domain: String,
    val ipv6: Boolean,
    /** Dirección que tiene registrada ahora el nombre (o la última que envió el router). */
    val registeredIp: String?,
    /** Fecha de la última actualización, como la escribe el router. */
    val lastUpdate: String?,
    /** El actualizador está en marcha. */
    val running: Boolean,
    /** Qué falló desde la última actualización buena, explicado; null si nada. */
    val lastError: String? = null,
    /** Última dirección que el router envió al proveedor con éxito. */
    val lastSentIp: String? = null,
) {
    val own: Boolean get() = section == DDNS_SECTION
}

internal fun parseDdns(sections: List<UciSection>, status: Map<String, DdnsStatusJson>?, logs: Map<String, DdnsLog> = emptyMap()): List<DdnsService> =
    sections.filter { it.type == "service" }.map { s ->
        val st = status?.get(s.name)
        val log = logs[s.name]
        DdnsService(
            section = s.name,
            enabled = s.text("enabled") == "1",
            provider = DdnsProvider.of(s.text("service_name")) ?: if (s.text("update_url") != null) DdnsProvider.Custom else null,
            serviceName = s.text("service_name"),
            domain = s.text("domain") ?: s.text("lookup_host") ?: s.name,
            ipv6 = s.text("use_ipv6") == "1",
            registeredIp = st?.ip?.replace("<br/>", " ")?.trim()?.takeIf { it.isNotEmpty() },
            lastUpdate = st?.lastUpdate,
            running = (st?.pid ?: 0) > 0,
            lastError = log?.lastError,
            lastSentIp = log?.lastSentIp,
        )
    }

/** Nombre completo: "micasa" → "micasa.duckdns.org" si el proveedor lo pide. */
fun DdnsEdit.fullDomain(): String {
    val d = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/', '.')
    val suffix = provider.suffix ?: return d
    return if ('.' in d) d else d + suffix
}

/** Motivo por el que [edit] no vale, o null. */
fun ddnsProblem(edit: DdnsEdit): String? {
    val domain = edit.fullDomain()
    return when {
        !Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(domain) -> "Escribe un nombre válido (p. ej. micasa${edit.provider.suffix ?: ".dynv6.net"})."
        edit.token.isBlank() -> "Falta el token (la clave que te da el proveedor)."
        edit.token.any { it.isWhitespace() || it == '\'' } -> "El token no puede llevar espacios ni comillas."
        edit.provider == DdnsProvider.Custom && edit.updateUrl.isNullOrBlank() -> "Falta la URL de actualización."
        edit.provider == DdnsProvider.Custom && !edit.updateUrl!!.trim().let { it.startsWith("http://") || it.startsWith("https://") } ->
            "La URL tiene que empezar por http:// o https://."
        edit.provider == DdnsProvider.Custom && "[IP]" !in edit.updateUrl!! -> "La URL tiene que llevar [IP], donde va la dirección."
        else -> null
    }
}

/**
 * La interfaz cuya dirección se publica: la primera con una IPv6 global (la de internet antes) o,
 * para IPv4, la de la ruta por defecto. ddns-scripts la lee de netifd ("ip_source network").
 */
internal fun ddnsInterface(interfaces: List<NetInterface>, ipv6: Boolean): String {
    val ordered = interfaces.filter { it.up && it.hasDefaultRoute } + interfaces.filter { it.up && !it.hasDefaultRoute }
    return if (ipv6) {
        ordered.firstOrNull { i -> i.ipv6.any { isGlobalIpv6(it.address) } }?.name
            ?: interfaces.firstOrNull { it.name == "wan6" }?.name ?: "wan6"
    } else {
        ordered.firstOrNull { it.hasDefaultRoute && it.ipv4.isNotEmpty() }?.name ?: "wan"
    }
}

internal fun ddnsSection(edit: DdnsEdit, iface: String): UciSection {
    val domain = edit.fullDomain()
    val url = edit.updateUrl?.trim()
    return uciSection(
        DDNS_SECTION, "service",
        "enabled" to "1",
        "service_name" to edit.provider.service,
        "update_url" to url.takeIf { edit.provider == DdnsProvider.Custom },
        "lookup_host" to domain,
        "domain" to domain,
        "username" to (edit.username?.trim()?.takeIf { it.isNotEmpty() } ?: domain),
        "password" to edit.token.trim(),
        "use_ipv6" to if (edit.ipv6) "1" else "0",
        "ip_source" to "network",
        "ip_network" to iface,
        "interface" to iface,
        "use_https" to if (edit.provider == DdnsProvider.Custom && url?.startsWith("http://") == true) "0" else "1",
        "cacert" to CA_CERTS,
        "check_interval" to "5",
        "check_unit" to "minutes",
    )
}

/** Lo que interesa del registro de un servicio (/var/log/ddns/<sección>.log). */
data class DdnsLog(val lastError: String?, val lastSentIp: String?)

/**
 * Registro de ddns-scripts: "<uptime> <nivel> : <mensaje>", con nivel ERROR (3), WARN (4), note,
 * info. Cuenta como fallo lo que hay después de la última actualización buena: los ERROR y el WARN
 * de "Updating IP at DDNS provider failed". El WARN "NO valid IP found" de la comprobación inicial
 * (el nombre aún no resuelve) no es un fallo.
 */
internal fun parseDdnsLog(log: String?): DdnsLog {
    val lines = log?.lines().orEmpty()
    val ok = Regex("(?:Forced )?[Uu]pdate successful - IP:? '([^']+)'")
    val lastOk = lines.indexOfLast { ok.containsMatchIn(it) || "No update needed" in it }
    val sent = lines.mapNotNull { ok.find(it)?.groupValues?.get(1) }.lastOrNull()
    val failure = lines.drop(lastOk + 1).lastOrNull { line ->
        Regex("^\\s*\\d+\\s+ERROR\\s*:").containsMatchIn(line) ||
            (Regex("^\\s*\\d+\\s+WARN\\s*:").containsMatchIn(line) && "DDNS provider failed" in line)
    }?.substringAfter(" : ")?.trim()
    return DdnsLog(failure?.let(::explainDdnsError), sent)
}

private fun explainDdnsError(message: String): String = when {
    "not accepted by DDNS Provider" in message || "No update send" in message ->
        "El proveedor rechazó la actualización: revisa el nombre y el token."
    "private or invalid IP" in message ->
        "El router no tiene una dirección pública que publicar (¿CGNAT sin IPv6?)."
    "No valid certificate" in message ->
        "Faltan los certificados para HTTPS en el router (paquete ca-bundle)."
    "Error:" in message || "error:" in message || "Transfer failed" in message || "provider failed" in message ->
        "El router no pudo conectar con el proveedor: $message"
    else -> message
}
