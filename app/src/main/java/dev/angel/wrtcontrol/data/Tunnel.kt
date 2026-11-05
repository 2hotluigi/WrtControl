package dev.angel.wrtcontrol.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lo que el teléfono guarda para entrar al router por el acceso remoto: su clave privada (no sale
 * nunca del teléfono), la precompartida y lo que respondió el router al darlo de alta. Va cifrado
 * con el Keystore, como la contraseña.
 *
 * [routerId] es el router para el que se creó (modelo y MAC de la LAN): si en la misma dirección
 * aparece otro, no se usa. [enabled] es false mientras el router aún no lo ha aceptado.
 */
@Serializable
data class TunnelConfig(
    val routerId: String,
    val peerId: String,
    val privateKey: String,
    val presharedKey: String,
    val enabled: Boolean = false,
    val routerPublicKey: String = "",
    val port: Int = 0,
    val routerTunnelAddress: String = "",
    val phoneAddress: String = "",
    /** Direcciones públicas del router la última vez que se vieron (IPv6 global, IPv4 pública). */
    val endpoints: List<String> = emptyList(),
    /** Nombre del DNS dinámico, si hay. */
    val hostname: String? = null,
)

private val TunnelJson = Json { ignoreUnknownKeys = true }

internal fun TunnelConfig.toJson(): String = TunnelJson.encodeToString(TunnelConfig.serializer(), this)

internal fun parseTunnel(json: String?): TunnelConfig? =
    json?.let { runCatching { TunnelJson.decodeFromString(TunnelConfig.serializer(), it) }.getOrNull() }
