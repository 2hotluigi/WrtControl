package dev.angel.wrtcontrol.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.angel.wrtcontrol.addons.isPrivateIpv4
import dev.angel.wrtcontrol.openwrt.parseIpv4
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Añadir otro teléfono: el que ya está conectado enseña un código QR con un enlace
 * (wrtcontrol://connect?address=192.168.1.1&user=root). La cámara del otro teléfono lo abre con la
 * app, que rellena la dirección y el usuario; la contraseña se escribe a mano y nunca va en el código.
 *
 * Un enlace así puede venir de cualquiera (una web, un cartel): solo se acepta si la dirección es
 * una IPv4 de red privada, escrita tal cual. Así no sirve para llevar a nadie a escribir la
 * contraseña de su router en un servidor de internet.
 */

private const val SCHEME = "wrtcontrol"
private const val HOST = "connect"
private const val MAX_USERNAME = 32
private val USERNAME = Regex("^[A-Za-z0-9._-]{1,$MAX_USERNAME}$")

/** Lo que un teléfono le pasa a otro para que encuentre el router. */
data class PairingLink(val address: String, val username: String) {
    /** El enlace que va en el código QR. */
    fun toUri(): String = "$SCHEME://$HOST?address=${encode(address)}&user=${encode(username)}"
}

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

/** El enlace para [address] y [username], o null si con esa dirección no se puede emparejar (ver arriba). */
fun pairingLink(address: String, username: String): PairingLink? =
    PairingLink(address.trim(), username.trim())
        .takeIf { parseIpv4(it.address) != null && isPrivateIpv4(it.address) && USERNAME.matches(it.username) }

/** Lee un enlace de emparejar. null si no lo es, está mal formado o su dirección no es de una red privada. */
fun parsePairingLink(uri: String?): PairingLink? {
    val parsed = try {
        URI(uri.orEmpty())
    } catch (_: URISyntaxException) {
        null
    }
    val ours = SCHEME.equals(parsed?.scheme, ignoreCase = true) && HOST.equals(parsed?.host, ignoreCase = true)
    if (parsed == null || !ours) return null
    val query = parsed.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val key = part.substringBefore('=')
        val value = part.substringAfter('=', "")
        runCatching { key to URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }.toMap()
    return pairingLink(query["address"].orEmpty(), query["user"].orEmpty())
}

/** Los módulos del código QR de [text]: `true` es negro. Vacío si no cabe en un código. */
fun qrModules(text: String): List<BooleanArray> {
    // Sin margen: el borde claro lo pone quien lo dibuja.
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (_: WriterException) {
        return emptyList()
    }
    return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}
