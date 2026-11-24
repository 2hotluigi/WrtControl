package dev.angel.wrtcontrol.ui.home

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.LinkProblem
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.WanLink
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.linkAdvice
import dev.angel.wrtcontrol.openwrt.problems
import dev.angel.wrtcontrol.openwrt.remembered
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause

/** A dónde lleva lo que se toca en Inicio. */
enum class OverviewLink { Devices, Family, Security, Wifi, Guest, Usage, Features }

/**
 * Las páginas interiores, encima de las pestañas: las de Inicio (Wi-Fi, invitados, consumo,
 * funciones) y Ajustes con las suyas. [parent]: a dónde se vuelve (null: a la pestaña).
 */
enum class MorePage(@param:StringRes val title: Int) {
    Settings(R.string.settings_title),
    Wifi(R.string.more_wifi_title),
    Guest(R.string.more_guest_title),
    Usage(R.string.more_usage_title),
    Features(R.string.more_features_title),
    Router(R.string.more_router_title),
    Pair(R.string.more_pair_title),
    ;

    val parent: MorePage? get() = if (this == Router || this == Pair) Settings else null
}

/** Cómo está el Wi-Fi de casa, para Inicio. */
sealed interface HomeWifi {
    /** Emite: con qué nombre y cuántos aparatos hay conectados al router. */
    data class On(val ssid: String, val clients: Int) : HomeWifi

    /** Encendido en los ajustes, pero ahora no emite (lo apagó el horario). */
    data object Paused : HomeWifi

    /** Apagado, y este teléfono está en la red de casa por Wi-Fi: lo dan otros aparatos (repetidores, mesh). */
    data object OtherAccessPoints : HomeWifi

    data object Off : HomeWifi
}

/**
 * El Wi-Fi de casa según [state]. [phoneOnHomeWifi]: el teléfono llega al router por Wi-Fi en la
 * red de casa. Si el router no emite, ese Wi-Fi lo da otro aparato. null si el router no tiene Wi-Fi.
 */
fun homeWifi(state: WifiState, phoneOnHomeWifi: Boolean): HomeWifi? {
    if (state.radios.isEmpty()) return null
    val emitting = state.home.filter { it.ifname != null }
    return when {
        emitting.isNotEmpty() -> HomeWifi.On(emitting.first().ssid, emitting.sumOf { it.clients.size })
        state.homeOn.isNotEmpty() && state.someRadioDown -> HomeWifi.Paused
        phoneOnHomeWifi -> HomeWifi.OtherAccessPoints
        else -> HomeWifi.Off
    }
}

/** Algo que pide atención en Inicio, por orden de urgencia. */
enum class Attention { Cable, NoPassword, RulesInactive, NewDevices }

/**
 * Lo que enseña Inicio: cómo está la red de un vistazo. Lo que aún no se ha leído va a null (los
 * equipos y los perfiles tardan más que las redes) y no se enseña.
 */
data class Overview(
    /** El router tiene salida a internet: una red activa con la ruta por defecto y, si se pudo probar, un ping que llega. */
    val online: Boolean,
    /** A qué velocidad va el cable de internet, en Mbps. */
    val cableMbps: Int? = null,
    /** Lo que le pasa al cable o a la conexión, ya explicado; null si va bien. */
    val linkAdvice: Txt? = null,
    /** Cuánto lleva esa salida sin caerse, en segundos. */
    val onlineSeconds: Long?,
    val devices: Int?,
    val profiles: Int?,
    val paused: Int,
    /** Dispositivos que esperan aprobación (con la cuarentena activa). */
    val newDevices: Int,
    val attention: List<Attention>,
)

fun overview(
    interfaces: List<NetInterface>,
    devices: List<Device>?,
    parental: ParentalSnapshot?,
    emptyPassword: Boolean,
    link: WanLink? = null,
): Overview {
    val problems = link?.problems().orEmpty()
    // Con IPv4 y con IPv6 hay dos salidas: cuenta la que lleva más tiempo en pie.
    val uplinks = interfaces.filter { it.up && it.hasDefaultRoute }
    val pending = if (parental?.settings?.quarantine == true && devices != null) {
        devices.count { it.mac !in parental.config.approvedMacs && !it.remembered }
    } else {
        0
    }
    val paused = parental?.profiles.orEmpty().count { profile ->
        when (val pause = profile.pause) {
            null -> false
            Pause.Indefinite -> true
            is Pause.Until -> pause.end.isAfter(parental!!.routerNow)
        }
    }
    return Overview(
        online = uplinks.isNotEmpty() && link?.carrier != false && link?.internet != false,
        cableMbps = link?.speedMbps,
        linkAdvice = linkAdvice(problems),
        onlineSeconds = uplinks.mapNotNull { it.uptime }.maxOrNull(),
        // Los que solo salen porque tienen nombre guardado no están en la red.
        devices = devices?.count { !it.remembered },
        profiles = parental?.profiles?.size,
        paused = paused,
        newDevices = pending,
        attention = listOfNotNull(
            // Sin cable o sin internet ya lo dice la tarjeta de arriba; aquí, lo que falla a ratos.
            Attention.Cable.takeIf { problems.any { it !is LinkProblem.NoCable && it !is LinkProblem.NoInternet } },
            Attention.NoPassword.takeIf { emptyPassword },
            Attention.RulesInactive.takeIf { parental?.rulesLoaded == false },
            Attention.NewDevices.takeIf { pending > 0 },
        ),
    )
}
