package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.NetInterface
import java.net.URLEncoder

/*
 * Al guardar el DNS dinámico, el teléfono avisa él mismo al proveedor: publica las direcciones del
 * router y borra la familia que no se publica. ddns-scripts solo actualiza la suya, así que sin esto
 * queda el registro que el nombre tuviera antes: DuckDNS pone un A con la IPv4 de quien crea el
 * subdominio, y tras pasar a CGNAT queda la IPv4 de antes. Ese A viejo lleva a los clientes que
 * prefieren IPv4 (WireGuard en Windows, p. ej.) a una dirección que no es la de casa.
 *
 * Cada proveedor lo permite a su manera (según su documentación):
 * - DuckDNS: solo borra los dos a la vez (`clear=true`): se borra y se vuelven a poner los buenos.
 * - deSEC: un valor vacío (`myipv4=`, `myipv6=`) borra ese registro.
 * - dynv6: `ipv4=-` borra el A; el AAAA no se puede borrar por esta API.
 * Con otro proveedor (URL propia) no se sabe cómo, y no se hace nada.
 */

/** GET al proveedor desde el teléfono, por internet (no por el túnel). Devuelve el cuerpo. */
fun interface DdnsHttp {
    suspend fun get(url: String): DdnsHttpResponse
}

data class DdnsHttpResponse(val code: Int, val body: String)

/** Las direcciones que publicará el router: null, esa familia no va (y se borra si se puede). */
data class DdnsRecords(val ipv4: String?, val ipv6: String?)

/** Una petición al proveedor y si su respuesta es buena. */
internal data class DdnsSyncCall(val url: String, val accepted: (DdnsHttpResponse) -> Boolean)

private const val HTTP_OK = 200
private val SERVER_ERROR = 500..599

private fun q(value: String) = URLEncoder.encode(value, "UTF-8")

/** Lo que hay que pedir al proveedor para dejar el nombre con [records]; vacío si no se sabe. */
internal fun ddnsSyncCalls(edit: DdnsEdit, records: DdnsRecords): List<DdnsSyncCall> = when (edit.provider) {
    DdnsProvider.DuckDns -> duckDnsCalls(edit.fullDomain(), edit.token.trim(), records)
    DdnsProvider.Desec -> listOf(desecCall(edit.fullDomain(), edit.token.trim(), records))
    DdnsProvider.Dynv6 -> listOf(dynv6Call(edit.fullDomain(), edit.token.trim(), records))
    DdnsProvider.Custom -> emptyList()
}

/** DuckDNS solo borra los dos registros a la vez: se borran y se ponen los que van. */
private fun duckDnsCalls(domain: String, token: String, records: DdnsRecords): List<DdnsSyncCall> {
    // "OK" o "KO" (nombre o token que no valen), con verbose detrás lo que quedó.
    val ok = { r: DdnsHttpResponse -> r.code == HTTP_OK && r.body.trimStart().startsWith("OK") }
    val base = "https://www.duckdns.org/update?domains=${q(domain.removeSuffix(".duckdns.org"))}&token=${q(token)}"
    // Sin ip=, DuckDNS pondría la IPv4 desde la que llega la petición (la del teléfono).
    val ips = listOfNotNull(records.ipv4?.let { "ip=${q(it)}" }, records.ipv6?.let { "ipv6=${q(it)}" })
    return listOf(DdnsSyncCall("$base&clear=true", ok)) +
        if (ips.isEmpty()) emptyList() else listOf(DdnsSyncCall("$base&${ips.joinToString("&")}", ok))
}

/** deSEC: un valor vacío borra; hay que dar los dos (sin myipv4 pondría la IPv4 del teléfono). */
private fun desecCall(domain: String, token: String, records: DdnsRecords): DdnsSyncCall {
    val url = "https://update.dedyn.io/update?username=${q(domain)}&password=${q(token)}&hostname=${q(domain)}" +
        "&myipv4=${q(records.ipv4.orEmpty())}&myipv6=${q(records.ipv6.orEmpty())}"
    return DdnsSyncCall(url) { r -> r.code == HTTP_OK && Regex("^(good|nochg)").containsMatchIn(r.body.trim()) }
}

/** dynv6: `ipv4=-` borra el A; la ipv6 sin dar se queda como está (no se puede borrar así). */
private fun dynv6Call(domain: String, token: String, records: DdnsRecords): DdnsSyncCall {
    val url = "https://dynv6.com/api/update?hostname=${q(domain)}&token=${q(token)}" +
        "&ipv4=${q(records.ipv4 ?: "-")}" + (records.ipv6?.let { "&ipv6=${q(it)}" } ?: "")
    return DdnsSyncCall(url) { r -> r.code == HTTP_OK && Regex("updated|unchanged").containsMatchIn(r.body) }
}

/** El proveedor dijo que no al nombre o al token: no tiene sentido configurar el router así. */
class DdnsRejectedException(message: String) : IllegalArgumentException(message)

/**
 * Deja el nombre con [records] desde el teléfono. Devuelve un aviso para el usuario (o null) y lanza
 * [DdnsRejectedException] si el proveedor rechaza el nombre o el token. Si no se puede llegar al
 * proveedor, no es grave: el router publicará lo suyo igualmente, solo puede quedar la otra familia.
 */
internal suspend fun syncDdnsRecords(http: DdnsHttp, edit: DdnsEdit, records: DdnsRecords): String? {
    val title = edit.provider.title
    var notice: String? = null
    val calls = ddnsSyncCalls(edit, records).iterator()
    while (notice == null && calls.hasNext()) {
        val call = calls.next()
        val response = try {
            http.get(call.url)
        } catch (e: java.io.IOException) {
            null.also {
                notice = "No se pudo avisar a $title desde el teléfono (${e.message ?: "sin conexión"}). " +
                    "El router publicará su dirección en unos minutos, pero puede quedar publicada una dirección antigua."
            }
        }
        if (response == null || call.accepted(response)) continue
        // 5xx: el proveedor falla. Lo demás (deSEC: 401 badauth; DuckDNS: "KO"): nombre o token que no valen.
        if (response.code !in SERVER_ERROR) {
            throw DdnsRejectedException("$title rechazó el nombre o el token: revisa que el nombre sea tuyo y el token esté bien copiado.")
        }
        notice = "$title no respondió bien (error ${response.code}). El router lo volverá a intentar."
    }
    if (notice == null && edit.provider == DdnsProvider.Dynv6 && records.ipv6 == null) {
        notice = "dynv6 no deja borrar la dirección IPv6 desde aquí: si el nombre tenía una antigua, quítala en dynv6.com."
    }
    return notice
}

/**
 * Las direcciones que publicará el router para [families]: las de la interfaz de cada una, como las
 * lee ddns-scripts ("ip_source network"): la primera IPv4 y la primera IPv6 global.
 */
internal fun ddnsRecords(interfaces: List<NetInterface>, families: Set<IpFamily>): DdnsRecords {
    fun iface(v6: Boolean) = interfaces.firstOrNull { it.name == ddnsInterface(interfaces, v6) }
    val v4 = if (IpFamily.V4 in families) iface(false)?.ipv4?.firstOrNull()?.address else null
    val v6 = if (IpFamily.V6 in families) iface(true)?.routerIpv6?.map { it.address }?.firstOrNull(::isGlobalIpv6) else null
    return DdnsRecords(v4, v6)
}
