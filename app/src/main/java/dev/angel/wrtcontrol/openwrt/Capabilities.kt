package dev.angel.wrtcontrol.openwrt

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.addons.Addon
import dev.angel.wrtcontrol.joinTxt
import dev.angel.wrtcontrol.parental.INCLUDE_DIR
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.SessionAcl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Qué puede hacer este router con la app, sin probar a hacerlo. Sale de lo que el router cuenta de sí
 * mismo, todo con llamadas pequeñas que ya permite LuCI:
 *
 * - Los permisos de la sesión (el ACL que devuelve el login): qué configuraciones se pueden escribir,
 *   qué comandos se pueden lanzar y qué partes de LuCI hay instaladas (un grupo por paquete).
 * - Los objetos de ubus que existen.
 * - `luci.getFeatures`: si el cortafuegos es fw4, si hay Wi-Fi, si el DNS es dnsmasq.
 * - Las radios (`luci-rpc.getWirelessDevices`), la memoria y el espacio libre (`system.info`).
 *
 * No hace falta el listado de paquetes, que pesa cientos de KB.
 */

/** `luci.getFeatures`: lo que trae el firmware. Solo lo que mira la app. */
@Serializable
data class LuciFeatures(
    /** Cortafuegos con nftables (fw4), el de OpenWrt 22.03 en adelante. */
    val firewall4: Boolean = false,
    val wifi: Boolean = false,
    val ipv6: Boolean = false,
    /** Lo que sabe hacer dnsmasq; si el router no lo usa, no viene. */
    val dnsmasq: JsonElement? = null,
) {
    val hasDnsmasq: Boolean get() = dnsmasq is JsonObject
}

enum class WifiBand(@param:StringRes val label: Int) {
    Band2(R.string.capability_band_2),
    Band5(R.string.capability_band_5),
    Band6(R.string.capability_band_6),
}

/** Una radio Wi-Fi del router. [standards]: `n`, `ac`, `ax`, `be`… */
data class Radio(val name: String, val band: WifiBand?, val standards: Set<String>, val enabled: Boolean, val networks: Int) {
    /** La generación de Wi-Fi más nueva que admite (Wi-Fi 4, 5, 6 o 7); null si no se sabe. */
    val generation: Int?
        get() = WIFI_GENERATIONS.entries.filter { it.key in standards }.maxOfOrNull { it.value }
}

private val WIFI_GENERATIONS = mapOf("n" to 4, "ac" to 5, "ax" to 6, "be" to 7)

@Serializable
internal data class WirelessDeviceJson(
    val up: Boolean = false,
    val disabled: Boolean = false,
    val config: Config = Config(),
    val iwinfo: Iwinfo = Iwinfo(),
    val interfaces: List<JsonElement> = emptyList(),
) {
    @Serializable
    data class Config(val band: String? = null, val hwmode: String? = null)

    @Serializable
    data class Iwinfo(val hwmodes: List<String> = emptyList())
}

internal fun parseRadios(devices: Map<String, WirelessDeviceJson>): List<Radio> = devices.map { (name, d) ->
    val band = when {
        d.config.band == "2g" -> WifiBand.Band2
        d.config.band == "5g" -> WifiBand.Band5
        d.config.band == "6g" -> WifiBand.Band6
        // Antes de 21.02 no había `band`: 11a es 5 GHz y lo demás, 2,4.
        d.config.hwmode?.contains('a') == true -> WifiBand.Band5
        d.config.hwmode != null -> WifiBand.Band2
        else -> null
    }
    Radio(name, band, d.iwinfo.hwmodes.toSet(), enabled = !d.disabled, networks = d.interfaces.size)
}.sortedBy { it.name }

/** Lo que el router cuenta de sí mismo; con esto se decide qué funciones se ofrecen. */
data class Capabilities(
    /** Versión de OpenWrt ("25.12.6", "SNAPSHOT"). */
    val version: String,
    /** Plataforma ("mediatek/filogic"): de ella dependen los paquetes del kernel. */
    val target: String,
    val memoryBytes: Long,
    /** Espacio libre para instalar paquetes, en KiB; null si no se sabe. */
    val freeStorageKiB: Long?,
    /** Objetos de ubus que existen; null si no se pudo preguntar. */
    val objects: Set<String>?,
    val acl: SessionAcl,
    /** null si el router no dejó preguntarlo: no se da por malo lo que no se sabe. */
    val features: LuciFeatures?,
    /** Vacío si no tiene Wi-Fi (o es un router sin radios, como una VM). */
    val radios: List<Radio>,
) {
    /** LuCI deja instalar paquetes desde la app (con apk u opkg). */
    val canInstallPackages: Boolean
        get() = PackageTool.entries.any { acl.canFile("${it.script} install x", "exec") }

    /** Con poca memoria, lo que carga listas grandes (bloqueo de anuncios) no cabe. */
    val lowMemory: Boolean get() = memoryBytes in 1 until LOW_MEMORY_BYTES

    fun has(obj: String): Boolean = objects?.contains(obj) == true
}

private const val LOW_MEMORY_BYTES = 100L * 1024 * 1024

/** Menos que esto libre y no cabe ni el paquete más pequeño con sus dependencias. */
internal const val MIN_FREE_KIB = 1024L

/** Lo que la app sabe hacer en un router. [addon]: el paquete que necesita, si no viene de serie. */
enum class Feature(val title: Txt, val addon: Addon? = null) {
    Parental(txt(R.string.capability_parental)),
    ContentFilter(txt(R.string.capability_content_filter)),
    RemoteAccess(txt(R.string.capability_remote_access), Addon.WireGuard),
    DynamicDns(txt(R.string.capability_ddns), Addon.Ddns),
    Sqm(txt(R.string.capability_sqm), Addon.Sqm),
    Upnp(txt(R.string.capability_upnp), Addon.Upnp),
}

sealed interface Availability {
    /** Se puede usar ya. */
    data object Ready : Availability

    /** Falta instalar [packages]: la app lo hace cuando se activa la función. */
    data class Installable(val packages: List<String>) : Availability

    /** Este router no puede; [reason] dice por qué y, si lo hay, qué hacer. */
    data class Unsupported(val reason: Txt) : Availability
}

fun Capabilities.availability(feature: Feature): Availability = when (feature) {
    Feature.Parental -> parental()
    Feature.ContentFilter -> when (val base = parental()) {
        Availability.Ready -> if (features?.hasDnsmasq == false || !acl.canWrite("dhcp")) {
            Availability.Unsupported(txt(R.string.capability_reason_no_dnsmasq))
        } else {
            Availability.Ready
        }
        else -> base
    }
    else -> addon(checkNotNull(feature.addon))
}

/** Perfiles, pausas, horarios y cuarentena: reglas de fw4 en un fichero que carga el cortafuegos. */
private fun Capabilities.parental(): Availability = when {
    features?.firewall4 == false -> Availability.Unsupported(txt(R.string.capability_reason_old_firewall))
    !acl.canWrite("firewall") || !acl.canFile("$INCLUDE_DIR/wrtcontrol.nft", "write") ->
        Availability.Unsupported(txt(R.string.capability_reason_no_firewall_access))
    else -> Availability.Ready
}

private fun Capabilities.addon(addon: Addon): Availability {
    val free = freeStorageKiB
    return when {
        configurable(addon) -> Availability.Ready
        !canInstallPackages -> Availability.Unsupported(txt(R.string.capability_reason_no_installer, addon.luciPackage))
        free != null && free < MIN_FREE_KIB -> Availability.Unsupported(txt(R.string.capability_reason_no_space, addon.luciPackage))
        else -> Availability.Installable(listOf(addon.luciPackage))
    }
}

/**
 * ¿Está instalada la parte de LuCI de [addon]? Es la que da permiso a la app para configurarlo, y se
 * nota en el ACL: su grupo, o la configuración que deja escribir.
 */
private fun Capabilities.configurable(addon: Addon): Boolean = acl.hasGroup(addon.luciPackage) || when (addon) {
    // La configuración de WireGuard va en `network`, que se puede escribir siempre: se mira su objeto.
    // Que exista no basta: rpcd lo conserva cargado después de desinstalar el paquete, pero el
    // permiso para llamarlo se va con él.
    Addon.WireGuard -> has("luci.wireguard") && acl.canCall("luci.wireguard", "getWgInstances")
    Addon.Ddns -> acl.canWrite("ddns")
    Addon.Sqm -> acl.canWrite("sqm")
    Addon.Upnp -> acl.canWrite("upnpd")
}

/**
 * Lo que se dice de una función que este router puede usar pero que la app aún no ha puesto en
 * marcha aquí: si la app está probada con su versión de OpenWrt (ver [openWrtSupport]).
 */
fun notVerifiedDetail(version: String): Txt = when (openWrtSupport(version)) {
    OpenWrtSupport.Tested -> txt(R.string.capability_not_verified_tested, version)
    else -> txt(R.string.capability_not_verified_untested, version)
}

/** "Wi-Fi: 2,4 GHz + 5 GHz · Wi-Fi 6", o que no tiene. */
fun wifiSummary(radios: List<Radio>): Txt {
    if (radios.isEmpty()) return txt(R.string.capability_wifi_none)
    val bands = radios.mapNotNull { it.band }.distinct().sorted().map { txt(it.label) }
    val generation = radios.mapNotNull { it.generation }.maxOrNull()
    val parts = listOfNotNull(
        bands.takeIf { it.isNotEmpty() }?.joinTxt(" + "),
        generation?.let { txt(R.string.capability_wifi_generation, it) },
    )
    // Sin banda ni estándar conocidos, al menos cuáles son.
    return txt(R.string.capability_wifi, if (parts.isEmpty()) raw(radios.joinToString { it.name }) else parts.joinTxt(" · "))
}
