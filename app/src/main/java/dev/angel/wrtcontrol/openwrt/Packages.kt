package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Paquetes, con lo mismo que usa la página "Software" de LuCI: un script que el ACL deja ejecutar
 * con unos argumentos concretos, llamado con cgi-exec (ver UbusClient.cgiExec).
 *
 * - 24.10 en adelante (luci-app-package-manager): /usr/libexec/package-manager-call, que usa apk
 *   (25.12) u opkg (24.10). El listado sale en JSON con apk 25.12, como `apk list` en las
 *   versiones de desarrollo de 24.10 con apk y como el fichero de estado de opkg con opkg.
 * - 22.03 y 23.05 (luci-app-opkg): /usr/libexec/opkg-call. Su ACL solo deja `update *`, así que
 *   lleva el argumento que pone LuCI (--force-removal-of-dependent-packages, que opkg ignora al
 *   actualizar las listas).
 *
 * Ambos scripts devuelven `{ code, stdout, stderr }` al instalar.
 */

enum class PackageTool(val script: String) {
    PackageManager("/usr/libexec/package-manager-call"),
    Opkg("/usr/libexec/opkg-call"),
}

/** Paquetes instalados (nombre → versión) y con qué se gestionan; [tool] null: el ACL no deja instalar. */
data class InstalledPackages(val tool: PackageTool?, val versions: Map<String, String>) {
    operator fun contains(name: String) = name in versions

    fun anyOf(names: List<String>) = names.any { it in versions }
}

/** El gestor de paquetes del router respondió con un error; [message] ya va explicado para el usuario. */
class PackageInstallException(message: String, val output: String = "") : Exception(message)

@Serializable
private data class ToolReply(val code: Int = -1, val stdout: String = "", val stderr: String = "")

/** Tiempo que puede tardar una instalación en un router lento antes de que uhttpd la corte. */
private const val INSTALL_TIMEOUT_SECONDS = 75L

/**
 * Lista los paquetes instalados probando los dos scripts. Si ninguno existe o el ACL no deja
 * usarlos, devuelve null: la app no podrá instalar nada (y avisa de por qué).
 */
suspend fun UbusClient.installedPackages(): InstalledPackages? {
    for (tool in PackageTool.entries) {
        val out = try {
            cgiExec(tool.script, listOf("list-installed"))
        } catch (e: UbusException.AccessDenied) {
            continue
        } catch (e: UbusException.Status) {
            if (e.code == 4) continue // no existe el script
            throw e
        }
        return InstalledPackages(tool, parseInstalled(out))
    }
    return null
}

/**
 * Actualiza las listas e instala [packages] (con sus dependencias). Lanza
 * [PackageInstallException] con el motivo si el gestor de paquetes falla.
 */
suspend fun UbusClient.installPackages(tool: PackageTool, packages: List<String>) {
    require(packages.isNotEmpty() && packages.all { PACKAGE_NAME.matches(it) }) { "Nombre de paquete no válido" }
    run(tool, updateArgs(tool), "actualizar la lista de paquetes")
    run(tool, listOf("install") + packages, "instalar ${packages.joinToString()}")
}

private fun updateArgs(tool: PackageTool): List<String> = when (tool) {
    PackageTool.PackageManager -> listOf("update")
    PackageTool.Opkg -> listOf("update", "--force-removal-of-dependent-packages")
}

private suspend fun UbusClient.run(tool: PackageTool, args: List<String>, what: String) {
    val out = try {
        cgiExec(tool.script, args, INSTALL_TIMEOUT_SECONDS)
    } catch (e: UbusException.AccessDenied) {
        throw PackageInstallException("El router no deja $what desde la app (permisos de LuCI).")
    }
    val reply = try {
        UbusJson.decodeFromString(ToolReply.serializer(), out)
    } catch (e: Exception) {
        throw PackageInstallException("Respuesta inesperada del gestor de paquetes al $what.", out)
    }
    if (reply.code != 0) {
        val output = (reply.stderr + "\n" + reply.stdout).trim()
        throw PackageInstallException(explainFailure(reply.code, output, what), output)
    }
}

private val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9.+_-]*$")

/** Explica en palabras normales los fallos típicos de opkg y apk. */
internal fun explainFailure(code: Int, output: String, what: String): String {
    val o = output.lowercase()
    return when {
        code == 255 && "lock" in o ->
            "Otro programa está instalando paquetes en el router (quizá LuCI). Espera un poco y vuelve a intentarlo."
        "no space left" in o || "not enough space" in o || "insufficient space" in o ->
            "El router no tiene espacio libre suficiente para $what."
        // apk: "unable to select packages: kernel-…: breaks: kmod-…"; opkg: "Cannot satisfy … kernel (= …)".
        "kernel" in o && KERNEL_MISMATCH.any { it in o } ->
            "El paquete no es compatible con el kernel de este firmware (pasa con versiones de desarrollo o " +
                "firmware de fabricante). Actualiza el router a una versión estable de OpenWrt."
        DOWNLOAD_ERRORS.any { it in o } ->
            "El router no pudo descargar los paquetes: comprueba que tiene internet."
        "unknown package" in o || "no such package" in o || "unable to select packages" in o ->
            "El paquete no está disponible para este router."
        else -> "El gestor de paquetes del router no pudo $what (código $code)."
    }
}

private val KERNEL_MISMATCH = listOf("breaks:", "cannot satisfy", "unsatisfiable", "cannot find dependency", "incompatible")

/** Lo que escriben opkg (uclient-fetch) y apk cuando no llegan al servidor de paquetes. */
private val DOWNLOAD_ERRORS = listOf(
    "failed to download", "wget returned", "network unreachable", "network is unreachable", "network error",
    "temporary error", "could not resolve", "dns lookup", "failed to send request", "connection refused",
    "connection timed out", "unable to fetch", "failed to establish connection", "ssl verify error",
)

/** El listado de instalados en cualquiera de sus tres formatos: nombre → versión. */
internal fun parseInstalled(out: String): Map<String, String> {
    val text = out.trimStart()
    return when {
        text.startsWith("[") -> parseApkJson(text)
        text.startsWith("Package:") || "\nPackage:" in text -> parseOpkgStatus(text)
        else -> parseApkList(text)
    }
}

/** `apk query --format json`: `[{ "name": ..., "version": ... }, ...]`. */
private fun parseApkJson(text: String): Map<String, String> {
    val array = try {
        UbusJson.parseToJsonElement(text) as? JsonArray
    } catch (_: Exception) {
        null
    } ?: return emptyMap()
    return array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        name to ((o["version"] as? JsonPrimitive)?.contentOrNull ?: "")
    }.toMap()
}

/** /usr/lib/opkg/status: bloques "Package:", "Version:", "Status: install user installed". */
private fun parseOpkgStatus(text: String): Map<String, String> =
    text.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
        val fields = block.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0 || line.first().isWhitespace()) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap()
        val name = fields["Package"] ?: return@mapNotNull null
        val status = fields["Status"].orEmpty().split(' ')
        if (status.lastOrNull() != "installed") return@mapNotNull null
        name to fields["Version"].orEmpty()
    }.toMap()

/**
 * `apk list -I --full`: "nombre-versión arch {origen} (licencia) [installed]". La versión empieza
 * en el primer "-" seguido de una cifra (los nombres con cifras, como "chacha20", no llevan guion
 * antes).
 */
private fun parseApkList(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val full = line.trim().substringBefore(' ').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val i = Regex("-\\d").find(full)?.range?.first
        if (i == null) full to "" else full.substring(0, i) to full.substring(i + 1)
    }.toMap()
