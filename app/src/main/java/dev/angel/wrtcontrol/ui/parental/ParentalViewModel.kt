package dev.angel.wrtcontrol.ui.parental

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.ui.Loadable
import dev.angel.wrtcontrol.ui.pollingFlow
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

data class ParentalData(val snapshot: ParentalSnapshot, val devices: List<Device>)

class ParentalViewModel(private val repo: RouterRepository) : ViewModel() {

    private val refresh = Channel<Unit>(Channel.CONFLATED)

    val state: StateFlow<Loadable<ParentalData>> = pollingFlow(15.seconds, refresh) {
        repo.use { api ->
            coroutineScope {
                val snapshot = async { api.parental.load() }
                val devices = async { api.devices() }
                ParentalData(snapshot.await(), devices.await())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())

    /** Qué se está aplicando ahora mismo en el router (null si nada). */
    var busy by mutableStateOf<String?>(null)
        private set

    /** Error del último cambio, hasta que el usuario lo cierre o haga otro. */
    var error by mutableStateOf<String?>(null)

    /**
     * Guarda [profile] tal como quedó en el editor. Si alguno de sus dispositivos estaba en otro
     * perfil, pasa a este: cada dispositivo pertenece a un solo perfil.
     */
    fun save(profile: Profile, onSaved: () -> Unit) = run("Guardando «${profile.name}»…", onSaved) { api ->
        val moved = profile.macs.toSet()
        api.parental.update { profiles, _ ->
            profiles.filter { it.id != profile.id }.map { it.copy(macs = it.macs - moved) } + profile
        }
    }

    fun delete(profile: Profile, onDeleted: () -> Unit) = run("Eliminando «${profile.name}»…", onDeleted) { api ->
        api.parental.update { profiles, _ -> profiles.filter { it.id != profile.id } }
    }

    /** [minutes] null: hasta que se reanude a mano. La hora de fin se calcula con el reloj del router. */
    fun pause(profile: Profile, minutes: Long?) = run("Pausando internet de «${profile.name}»…") { api ->
        api.parental.update { profiles, now ->
            profiles.map {
                if (it.id != profile.id) it
                else it.copy(pause = minutes?.let { m -> Pause.Until(now.plusMinutes(m).withNano(0)) } ?: Pause.Indefinite)
            }
        }
    }

    fun resume(profile: Profile) = run("Reanudando internet de «${profile.name}»…") { api ->
        api.parental.update { profiles, _ -> profiles.map { if (it.id == profile.id) it.copy(pause = null) else it } }
    }

    fun useZone(zone: ZoneId) = run("Cambiando la zona horaria del router…") { api ->
        api.parental.setTimezone(zone.id, zone.rules.getOffset(Instant.now()).totalSeconds)
    }

    private fun run(label: String, onSuccess: () -> Unit = {}, block: suspend (OpenWrtApi) -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                repo.use { block(it) }
                onSuccess()
            } catch (e: CancellationException) {
                throw e // el router revierte solo lo que no se llegó a confirmar
            } catch (e: Exception) {
                Log.w(TAG, "Falló: $label", e)
                error = e.userMessage()
            } finally {
                busy = null
                refresh.trySend(Unit)
            }
        }
    }

    private companion object {
        const val TAG = "WrtControl"
    }
}
