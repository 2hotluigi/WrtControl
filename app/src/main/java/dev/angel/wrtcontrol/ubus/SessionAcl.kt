package dev.angel.wrtcontrol.ubus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lo que rpcd deja hacer a una sesión, tal como lo devuelve el login: por ámbito, a qué y qué
 * funciones. Los nombres pueden llevar comodines de shell (`hostapd.*`, `/sbin/ip -[46] neigh show`).
 */
@Serializable
data class SessionAcl(
    /** Objetos de ubus y los métodos que se pueden llamar. */
    val ubus: Map<String, Set<String>> = emptyMap(),
    /** Configuraciones de UCI: `read` y `write`. */
    val uci: Map<String, Set<String>> = emptyMap(),
    /** Ficheros y comandos: `read`, `write`, `list`, `exec`… */
    val file: Map<String, Set<String>> = emptyMap(),
    /** Los grupos de permisos que tiene: uno por cada parte de LuCI instalada (`luci-app-ddns`…). */
    @SerialName("access-group") val groups: Map<String, Set<String>> = emptyMap(),
) {
    fun canCall(target: String, method: String): Boolean = allows(ubus, target, method)

    fun canWrite(config: String): Boolean = allows(uci, config, "write")

    /** [action]: `read`, `write`, `list` o `exec`; para `exec`, [path] es la línea de comando entera. */
    fun canFile(path: String, action: String): Boolean = allows(file, path, action)

    fun hasGroup(name: String): Boolean = name in groups

    private fun allows(scope: Map<String, Set<String>>, name: String, function: String): Boolean =
        scope.any { (pattern, functions) -> globMatches(pattern, name) && functions.any { globMatches(it, function) } }
}

/** Lo que dentro de un conjunto `[…]` de una expresión regular no es un carácter cualquiera. */
private fun escapeClass(body: String): String = body.replace("\\", "\\\\").replace("[", "\\[").replace("^", "\\^").replace("&", "\\&")

/** Como fnmatch(3) sin escapes, que es lo que usa rpcd: `*`, `?` y `[…]` (con `!` para negar). */
internal fun globMatches(pattern: String, text: String): Boolean {
    if (pattern.none { it == '*' || it == '?' || it == '[' }) return pattern == text
    val regex = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        // Un ']' justo después de '[' o de '[!' es un carácter más del conjunto.
        val close = if (c == '[') pattern.indexOf(']', if (pattern.getOrNull(i + 1) == '!') i + 3 else i + 2) else -1
        when {
            c == '*' -> regex.append(".*")
            c == '?' -> regex.append('.')
            close > 0 -> {
                val body = pattern.substring(i + 1, close)
                val negated = body.startsWith('!')
                regex.append(if (negated) "[^" else "[").append(escapeClass(if (negated) body.drop(1) else body)).append(']')
                i = close
            }
            else -> regex.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL).matches(text)
}
