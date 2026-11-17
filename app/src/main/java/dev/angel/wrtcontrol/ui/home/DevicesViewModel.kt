package dev.angel.wrtcontrol.ui.home

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
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ChangeRunner
import dev.angel.wrtcontrol.ui.Proposals

/**
 * Lo que el usuario deja puesto en un aparato. [owner]: de qué persona es (el id de su perfil), ""
 * si de nadie, o null para no tocarlo.
 */
data class DeviceChange(val label: String?, val fixedIp: String?, val owner: String? = null)

class DevicesViewModel(private val router: RouterAccess, private val monitor: RouterMonitor) : ViewModel() {

    private val changes = ChangeRunner(viewModelScope)

    /** El cambio que se está guardando ahora mismo en el router (null si nada). */
    val progress: ChangeProgress? get() = changes.current

    /** El cambio que espera a que el usuario vea lo que va a cambiar y lo acepte. */
    val proposals: Proposals get() = changes

    /** Error del último cambio, hasta que el usuario lo cierre o haga otro. */
    var error: Txt?
        get() = changes.error
        set(value) {
            changes.error = value
        }

    /**
     * Guarda [change] en el router: primero el nombre y la dirección fija, y después, si cambió, de
     * quién es. [phoneMacs]: este teléfono, que queda como administrador (ver ParentalApi.update).
     * Si [preview] trae avisos, antes se enseña y espera a que el usuario lo acepte.
     */
    fun save(device: Device, change: DeviceChange, phoneMacs: Set<String>, preview: ChangePreview?, onSaved: () -> Unit) {
        changes.propose(preview) { saveNow(device, change, phoneMacs, onSaved) }
    }

    private fun saveNow(device: Device, change: DeviceChange, phoneMacs: Set<String>, onSaved: () -> Unit) =
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
     * [preview]: lo que se enseña antes, si hay que avisar (ver [save]).
     */
    fun pause(device: Device, minutes: Long?, phoneMacs: Set<String>, preview: ChangePreview?, onDone: () -> Unit) {
        changes.propose(preview) { pauseNow(device, minutes, phoneMacs, onDone) }
    }

    private fun pauseNow(device: Device, minutes: Long?, phoneMacs: Set<String>, onDone: () -> Unit) =
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
        val refresh = {
            monitor.devices.refresh()
            monitor.parental.refresh()
        }
        changes.run(label, onSuccess = onDone, onFinished = refresh) { router.use { block(it) } }
    }
}
