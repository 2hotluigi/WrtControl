package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.UciSection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * UPnP IGD y PCP/NAT-PMP (miniupnpd + luci-app-upnp): los dispositivos de casa abren puertos en
 * el router sin preguntar (consolas, juegos, P2P). Se configura en la sección `config` de
 * /etc/config/upnpd; los puertos abiertos ahora salen de `luci.upnp get_status` y se cierran
 * con `luci.upnp delete_rule` (borra la línea del fichero de concesiones y reinicia miniupnpd).
 */

data class UpnpState(
    val section: String,
    val enabled: Boolean,
    /** Modo seguro: un dispositivo solo puede abrir puertos hacia sí mismo. */
    val secureMode: Boolean,
    /** null: no se sabe (sin permiso para mirar los servicios). */
    val running: Boolean?,
    val mappings: List<UpnpMapping>,
)

data class UpnpMapping(
    /** Lo que pide `delete_rule` para cerrarlo. */
    val token: String,
    val protocol: String,
    val externalPort: Int,
    val address: String,
    val internalPort: Int,
    val description: String,
    val host: String?,
    /** Segundos que le quedan; null si no caduca. */
    val expiresIn: Long?,
)

@Serializable
internal data class UpnpStatus(val rules: List<UpnpRule> = emptyList())

@Serializable
internal data class UpnpRule(
    val num: JsonPrimitive? = null,
    val proto: String = "",
    val extport: Int = 0,
    val intaddr: String = "",
    val intport: Int = 0,
    val descr: String? = null,
    val expires: JsonPrimitive? = null,
    @SerialName("host_hint") val hostHint: String? = null,
)

internal fun parseUpnp(section: UciSection, running: Boolean?, status: UpnpStatus?) = UpnpState(
    section = section.name,
    // Como el init de miniupnpd: sin la opción, está activado.
    enabled = section.text("enabled") != "0",
    secureMode = section.text("secure_mode") != "0",
    running = running,
    mappings = status?.rules.orEmpty().mapNotNull { r ->
        val token = r.num?.contentOrNull ?: return@mapNotNull null
        UpnpMapping(
            token = token,
            protocol = r.proto.uppercase(),
            externalPort = r.extport,
            address = r.intaddr,
            internalPort = r.intport,
            description = r.descr.orEmpty().trim(),
            host = r.hostHint?.takeIf { it.isNotBlank() },
            expiresIn = r.expires?.longOrNull?.takeIf { it > 0 },
        )
    },
)
