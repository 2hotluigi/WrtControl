package dev.angel.wrtcontrol.openwrt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Quita del informe del router (ver DeviceReport.kt) lo que dice quién vive en la casa o cómo
 * entrar en ella, y deja lo que explica cómo es el router. Lo mismo recibe siempre el mismo
 * seudónimo dentro de un informe: dos radios con el mismo SSID siguen teniendo el mismo, y una
 * dirección sigue dentro de su red. Así el informe sirve para probar la app contra ese router.
 *
 * - MAC (también sin separadores en `host_<mac>`) → 02:00:00:00:xx:xx.
 * - IPv4 públicas → 203.0.113.x (las privadas, de bucle, máscaras… se quedan: son la estructura).
 * - IPv6: el prefijo de sitio → 2001:db8:N (globales) o fd00:0:N (ULA), y el identificador de
 *   interfaz, si no es uno corto como ::1, → ::a:0:0:N (suele llevar la MAC). La subred se queda.
 *   (Ver ReportAddresses.kt.)
 * - Claves de WireGuard, contraseñas, tokens, el DUID… → secreto-N.
 * - SSID, nombre del router, de los perfiles, aparatos y teléfonos, dominios, URL… → nombre-N.
 *
 * [ReportAnonymizer.leaks] vuelve a mirar el texto final: si algo se escapó, no se comparte.
 */

/** Opciones y claves de JSON con secretos. */
private val SECRET_KEYS = setOf(
    "key", "password", "private_key", "preshared_key", "public_key", "psk", "sae_password", "auth_secret",
    "priv_key_pwd", "token", "secret", "username", "param_enc", "update_url", "update_script", "duid", "clientid",
    "iaid", "ip6hostid",
)

/** Terminaciones de opciones con secretos (`ssh_key`, `admin_password`, `dhcp_default_duid`…). */
private val SECRET_SUFFIXES = listOf("_key", "password", "duid")

/** Opciones y claves de JSON con nombres que pone la gente. */
private val NAME_KEYS = setOf(
    "hostname", "ssid", "mesh_id", "descr", "domain", "lookup_host", "param_opt", "comment",
    LABEL_OPTION, "label", "endpoint_host", "dns_name", "fqdn",
)

/**
 * En UCI, además: `description` la escribe la gente (el nombre de un teléfono en WireGuard). En
 * las respuestas de ubus es otra cosa (la versión del firmware en `system board`).
 */
private val UCI_NAME_KEYS = setOf("description")

/** URL que no dicen nada de nadie (las descargas de OpenWrt). */
private val STOCK_URL = Regex("^https?://([a-z0-9-]+\\.)*openwrt\\.org(/.*)?$")

/** Tipos de sección de UCI cuyo `name` es de una persona o aparato (o lo lleva, como las reglas de un perfil). */
private val NAMED_TYPES = setOf("host", "domain", "hostrecord", "cname", "wrtcontrol_profile", "rule", "redirect", "nat")

/** Tipos de sección con reglas, cuyo nombre de serie (`Allow-Ping`) se queda. */
private val RULE_TYPES = setOf("rule", "redirect", "nat")

/** Tipos de sección de UCI que la gente suele llamar como el aparato ("tele_salon"). */
private val NAMED_SECTION_TYPES = setOf("host", "domain")

/** Nombres de sección que no son de nadie: anónimas, de la app o `host_<mac>` (la MAC ya se cambia). */
private val NEUTRAL_SECTION = Regex("^(cfg[0-9a-f]+|wrtc.*|host_[0-9A-Fa-f]{12})$")

/** Valores de serie que no dicen nada de nadie: se dejan tal cual. */
private val STOCK_VALUES = setOf("OpenWrt", "lan", "OpenWrt.lan", "")

/** Nombres de reglas de serie de OpenWrt. */
private val STOCK_RULE = Regex("^(Allow|Support)-[A-Za-z0-9-]+$")

private val HOST_SECTION = Regex("host_[0-9A-Fa-f]{12}")
private val WG_KEY = Regex("^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=$")
private val WG_KEY_ANYWHERE = Regex("[A-Za-z0-9+/]{43}=")
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

/** Un secreto más corto que esto podría aparecer por casualidad (un "1", un "on"). */
private const val MIN_SECRET = 4

/** Cuánto se enseña de un secreto escapado al contarlo (lo justo para encontrarlo). */
private const val SECRET_HINT = 2

class ReportAnonymizer {
    private val addresses = AddressPseudonyms()
    private val names = LinkedHashMap<String, String>()
    private val secrets = LinkedHashMap<String, String>()

    /** Lo que nunca puede quedar en el texto final: los secretos tal cual. */
    private val forbidden = HashSet<String>()

    /**
     * Una configuración tal como la da `uci get` (sección → opciones). Los nombres de las
     * secciones de aparatos también se cambian; los que solo dicen qué son (`lan`, `wan`,
     * `radio0`, las de la app…) se quedan.
     */
    fun uci(values: JsonObject): JsonObject = JsonObject(
        values.entries.associate { (section, body) ->
            val options = body as? JsonObject ?: return@associate text(section) to json(body)
            val type = (options[".type"] as? JsonPrimitive)?.content
            val newName = if (type in NAMED_SECTION_TYPES && !NEUTRAL_SECTION.matches(section)) name(section) else text(section)
            newName to JsonObject(options.entries.associate { (option, value) -> option to uciOption(type, newName, option, value) })
        },
    )

    private fun uciOption(type: String?, section: String, option: String, value: JsonElement): JsonElement = when {
        option == ".name" -> JsonPrimitive(section)
        option.startsWith(".") -> value
        option == "name" && type in NAMED_TYPES -> mapStrings(value) { ruleOrName(type, it) }
        option.lowercase() in UCI_NAME_KEYS -> mapStrings(value, ::name)
        else -> keyed(option, value, personalNames = false)
    }

    /**
     * Cualquier respuesta de ubus. [personalNames]: en esta respuesta, `name` es el de una persona o
     * aparato (los pares de WireGuard llevan el nombre del teléfono), no el de una interfaz.
     */
    fun json(element: JsonElement, personalNames: Boolean = false): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (k, v) -> text(k) to keyed(k, v, personalNames) })
        is JsonArray -> JsonArray(element.map { json(it, personalNames) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(text(element.content)) else element
        JsonNull -> JsonNull
    }

    private fun keyed(key: String, value: JsonElement, personalNames: Boolean): JsonElement {
        val k = key.lowercase()
        return when {
            k in SECRET_KEYS || SECRET_SUFFIXES.any(k::endsWith) -> mapStrings(value, ::secret)
            k in NAME_KEYS || (personalNames && k == "name") -> mapStrings(value, ::name)
            else -> json(value, personalNames)
        }
    }

    /** Aplica [f] a cada texto de [value] (un valor o una lista); los números y booleanos, igual. */
    private fun mapStrings(value: JsonElement, f: (String) -> String): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { mapStrings(it, f) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(f(value.content)) else value
        else -> json(value)
    }

    private fun ruleOrName(type: String?, value: String): String =
        if (type in RULE_TYPES && STOCK_RULE.matches(value)) value else name(value)

    private fun secret(value: String): String {
        // Una ruta ("/etc/uhttpd.key") dice dónde está la clave, no cuál es.
        if (value in STOCK_VALUES || value.startsWith("/")) return value
        forbidden += value
        return secrets.getOrPut(value) { "secreto-${secrets.size + 1}" }
    }

    private fun name(value: String): String =
        if (value in STOCK_VALUES) value else names.getOrPut(value) { "nombre-${names.size + 1}" }

    /** Un texto cualquiera: se cambian dentro de él las MAC, las IP, las claves, los correos y las URL. */
    fun text(value: String): String = when {
        WG_KEY.matches(value) -> secret(value)
        "://" in value -> if (STOCK_URL.matches(value)) value else name(value)
        else -> withinText(value)
    }

    private fun withinText(value: String): String {
        var out = EMAIL.replace(value) { name(it.value) }
        out = HOST_SECTION.replace(out) { m ->
            "host_" + addresses.mac(m.value.removePrefix("host_").chunked(2).joinToString(":")).replace(":", "")
        }
        out = MAC.replace(out) { addresses.mac(it.value) }
        out = IPV4.replace(out) { addresses.ipv4(it) }
        return IPV6.replace(out) { addresses.ipv6(it.value) ?: it.value }
    }

    /**
     * Lo que se escapó en [text], el informe ya anonimizado: MAC, IPv4 públicas o IPv6 sin seudónimo,
     * claves de WireGuard o un secreto tal cual. Vacío si no hay nada. Los seudónimos se reconocen
     * por su forma, así que sirve también para revisar un informe hecho por otra instancia.
     */
    fun leaks(text: String): List<String> = macLeaks(text) + ipv4Leaks(text) + ipv6Leaks(text) + keyLeaks(text) +
        forbidden.filter { it.length >= MIN_SECRET && it in text }.map { "secreto ${it.take(SECRET_HINT)}…" }
}

private fun macLeaks(text: String): List<String> =
    MAC.findAll(text).map { it.value.uppercase() }.filter { !it.startsWith(MAC_PSEUDONYM) }.map { "MAC $it" }.toList()

private fun ipv4Leaks(text: String): List<String> =
    IPV4.findAll(text).filter { m -> octets(m)?.let(::isPublic) == true }.map { "IPv4 ${it.value}" }.toList()

private fun ipv6Leaks(text: String): List<String> =
    IPV6.findAll(text).map { it.value }.filter { v -> expandIpv6(v)?.let { !isAnonymousIpv6(it) } == true }.map { "IPv6 $it" }.toList()

private fun keyLeaks(text: String): List<String> =
    WG_KEY_ANYWHERE.findAll(text).filter { WG_KEY.matches(it.value) }.map { "clave ${it.value.take(SECRET_HINT)}…" }.toList()
