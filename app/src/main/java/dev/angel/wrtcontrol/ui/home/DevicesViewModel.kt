package dev.angel.wrtcontrol.ui.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.data.RouterAccess
import dev.angel.wrtcontrol.data.RouterMonitor
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.DeviceEdit
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Lo que el usuario deja puesto en un aparato. [owner]: de qué persona es (el id de su perfil), ""
 * si de nadie, o null para no tocarlo.
 */
data class DeviceChange(val label: String?, val fixedIp: String?, val owner: String? = null)

class DevicesViewModel(private val router: RouterAccess, private val monitor: RouterMonitor) : ViewModel() {

    /** Qué se está guardando ahora mismo en el router (null si nada). */
    var busy by mutableStateOf<Txt?>(null)
        private set

    /** Error del último cambio, hasta que el usuario lo cierre o haga otro. */
    var error by mutableStateOf<Txt?>(null)

    /**
     * Guarda [change] en el router: primero el nombre y la dirección fija, y después, si cambió, de
     * quién es. [phoneMacs]: este teléfono, que queda como administrador (ver ParentalApi.update).
     */
    fun save(device: Device, change: DeviceChange, phoneMacs: Set<String>, onSaved: () -> Unit) =
        run(txt(R.string.devices_busy_saving, change.label ?: device.name ?: device.mac), onSaved) { api ->
            if (change.label != device.label || change.fixedIp != device.fixedIp) {
                val interfaces = monitor.interfaces.fresh(RouterMonitor.SHARED_MAX_AGE)
                api.deviceSettings.save(DeviceEdit(device.mac, change.label, change.fixedIp), interfaces)
            }
            val owner = change.owner
            if (owner != null) {
                api.parental.updateProfiles(phoneMacs) { profiles, _ ->
                    profiles.map {
                        val macs = if (it.id == owner) it.macs + device.mac else it.macs - device.mac
                        it.copy(macs = macs.distinct().sorted())
                    }
                }
            }
        }

    /**
     * Deja a [device] sin internet [minutes] minutos (contados con el reloj del router), o hasta
     * que se le devuelva si es null ("bloqueado"). Se suma a lo que diga el perfil de su persona.
     */
    fun pause(device: Device, minutes: Long?, phoneMacs: Set<String>, onDone: () -> Unit) =
        run(txt(R.string.devices_busy_pausing, device.name ?: device.mac), onDone) { api ->
            api.parental.update(phoneMacs) { config, now ->
                val pause = minutes?.let { Pause.Until(now.plusMinutes(it).withNano(0)) } ?: Pause.Indefinite
                config.copy(settings = config.settings.copy(devicePauses = config.settings.devicePauses + (device.mac to pause)))
            }
        }

    fun resume(device: Device, phoneMacs: Set<String>, onDone: () -> Unit) =
        run(txt(R.string.devices_busy_resuming, device.name ?: device.mac), onDone) { api ->
            api.parental.update(phoneMacs) { config, _ ->
                config.copy(settings = config.settings.copy(devicePauses = config.settings.devicePauses - device.mac))
            }
        }

    private fun run(label: Txt, onDone: () -> Unit, block: suspend (OpenWrtApi) -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                router.use { block(it) }
                onDone()
            } catch (e: CancellationException) {
                throw e // el router revierte solo lo que no se llegó a confirmar
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w("WrtControl", "No se guardó el aparato", e)
                error = e.userMessage()
            } finally {
                busy = null
                monitor.devices.refresh()
                monitor.parental.refresh()
            }
        }
    }
}
