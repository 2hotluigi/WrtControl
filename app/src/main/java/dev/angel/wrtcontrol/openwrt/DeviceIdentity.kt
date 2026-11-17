package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import java.io.IOException
import java.io.InputStream

/*
 * Quién es cada aparato de la red, para no enseñar "Sin nombre" y una MAC:
 *
 * - El fabricante sale del prefijo de la MAC, con una lista que la app lleva dentro
 *   (assets/oui.txt, de los registros públicos del IEEE; ver tools/update-oui.py). Nunca se
 *   manda una MAC a nadie: dicen qué aparatos hay en una casa.
 * - Una MAC privada (la que inventan Android, iOS y Windows para cada red) no tiene fabricante. Se
 *   reconoce por un bit de su primer byte.
 * - El nombre es el que dice el aparato al pedir dirección. Si no dice ninguno, vale el fabricante
 *   ("Aparato Samsung"), que orienta más que nada.
 */

/**
 * ¿Es una MAC inventada por el aparato en vez de la de fábrica? Las de fábrica las reparte el IEEE;
 * las otras llevan a 1 el bit de "administrada localmente" (el segundo dígito es 2, 6, A o E).
 */
fun isPrivateMac(mac: String): Boolean = mac.getOrNull(1)?.digitToIntOrNull(HEX)?.let { it and LOCAL_BIT != 0 } ?: false

private const val HEX = 16
private const val LOCAL_BIT = 2
private const val PREFIX_DIGITS = 6

/**
 * De qué fabricante es cada prefijo de MAC. [open] da el fichero (una línea por fabricante:
 * `Nombre<TAB>AABBCC AABBCD …`); se lee entero la primera vez que se pregunta. Si no se puede
 * leer, no hay fabricantes: la lista de aparatos se ve igual, sin ellos.
 */
class VendorTable(private val open: () -> InputStream) {
    private val prefixes: Map<String, String> by lazy {
        val table = HashMap<String, String>()
        try {
            open().bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val name = line.substringBefore('\t')
                    // trim: un salto de línea de Windows no se lleva por delante el último prefijo.
                    line.substringAfter('\t', "").trim().split(' ').forEach { if (it.length == PREFIX_DIGITS) table[it] = name }
                }
            }
        } catch (_: IOException) {
            table.clear()
        }
        table
    }

    /** El fabricante de [mac], o null si es privada o no está en la lista. */
    fun vendor(mac: String): String? {
        if (isPrivateMac(mac)) return null
        val prefix = mac.filter { it != ':' && it != '-' }.take(PREFIX_DIGITS).uppercase()
        return if (prefix.length == PREFIX_DIGITS) prefixes[prefix] else null
    }

    /** Lee la lista ya (tarda unas décimas): para hacerlo fuera del hilo de la pantalla. */
    fun load(): VendorTable = also { prefixes.size }
}

/** Qué clase de aparato parece, para ponerle un icono. */
enum class DeviceKind { Phone, Computer, Tv, Network, Smart, Other }

private fun words(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

private val KIND_BY_NAME = listOf(
    DeviceKind.Network to words("deco|router|openwrt|extender|repeater|mesh|\\bap\\b|access.?point|switch"),
    DeviceKind.Tv to words("\\btv\\b|smart.?tv|roku|chromecast|fire.?tv|bravia|shield|appletv|apple-tv|webos|tizen"),
    DeviceKind.Phone to words("iphone|ipad|galaxy|redmi|poco|android|pixel|moto|phone|huawei|honor|oppo|vivo|realme|\\btab\\b"),
    DeviceKind.Computer to words("laptop|desktop|\\bpc\\b|macbook|imac|thinkpad|raspberry|server|nas|linux|windows|ubuntu"),
    DeviceKind.Smart to words("echo|alexa|nest|plug|bulb|lamp|cam|sensor|esp[-_]?\\d*|tasmota|shelly|sonoff|tuya|printer"),
)

/** Fabricantes que casi solo hacen una clase de aparato. Sin nombre, es la mejor pista. */
private val KIND_BY_VENDOR = listOf(
    DeviceKind.Smart to words("espressif|tuya|shelly|sonoff|itead|amazon|ring|wyze|hui zhou gaoshengda"),
    // Antes que los ordenadores: "Motorola Mobility LLC, a Lenovo" es un teléfono.
    DeviceKind.Phone to words("motorola mobility|xiaomi|oneplus|oppo|vivo mobile|realme|hmd global"),
    DeviceKind.Computer to words("raspberry|intel|dell|hewlett|lenovo|asustek|micro-star|gigabyte|synology|qnap"),
    DeviceKind.Network to words("tp-link|ubiquiti|mikrotik|netgear|d-link|zyxel|tenda|mercusys|gl technologies"),
    DeviceKind.Tv to words("roku|tcl|hisense|vizio"),
)

/** El nombre manda sobre el fabricante: un "Galaxy" es un teléfono aunque Samsung también haga teles. */
fun deviceKind(name: String?, vendor: String?): DeviceKind =
    name?.let { n -> KIND_BY_NAME.firstOrNull { it.second.containsMatchIn(n) }?.first }
        ?: vendor?.let { v -> KIND_BY_VENDOR.firstOrNull { it.second.containsMatchIn(v) }?.first }
        ?: DeviceKind.Other

/** Lo que se sabe de un aparato además de sus direcciones. */
data class DeviceIdentity(
    /** Cómo llamarlo: su nombre o, si no dice ninguno, lo que se sepa de él. */
    val title: Txt,
    /** El aparato dijo cómo se llama (si no, [title] es una suposición). */
    val named: Boolean,
    val vendor: String?,
    val privateMac: Boolean,
    val kind: DeviceKind,
)

fun identify(device: Device, vendors: VendorTable?): DeviceIdentity {
    val private = isPrivateMac(device.mac)
    val vendor = vendors?.vendor(device.mac)
    val title = when {
        device.name != null -> raw(device.name)
        vendor != null -> txt(R.string.devices_unnamed_vendor, vendor)
        else -> txt(R.string.devices_unnamed)
    }
    return DeviceIdentity(title, device.name != null, vendor, private, deviceKind(device.name, vendor))
}
