package dev.angel.wrtcontrol.ui.home

import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause

/** A dónde lleva lo que se toca en Inicio. */
enum class OverviewLink { Devices, Family, Security }

/** Algo que pide atención en Inicio, por orden de urgencia. */
enum class Attention { NoPassword, RulesInactive, NewDevices }

/**
 * Lo que enseña Inicio: cómo está la red de un vistazo. Lo que aún no se ha leído va a null (los
 * equipos y los perfiles tardan más que las redes) y no se enseña.
 */
data class Overview(
    /** El router tiene salida a internet (una red activa con la ruta por defecto). */
    val online: Boolean,
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
): Overview {
    // Con IPv4 y con IPv6 hay dos salidas: cuenta la que lleva más tiempo en pie.
    val uplinks = interfaces.filter { it.up && it.hasDefaultRoute }
    val pending = if (parental?.settings?.quarantine == true && devices != null) {
        devices.count { it.mac !in parental.config.approvedMacs }
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
        online = uplinks.isNotEmpty(),
        onlineSeconds = uplinks.mapNotNull { it.uptime }.maxOrNull(),
        devices = devices?.size,
        profiles = parental?.profiles?.size,
        paused = paused,
        newDevices = pending,
        attention = listOfNotNull(
            Attention.NoPassword.takeIf { emptyPassword },
            Attention.RulesInactive.takeIf { parental?.rulesLoaded == false },
            Attention.NewDevices.takeIf { pending > 0 },
        ),
    )
}
