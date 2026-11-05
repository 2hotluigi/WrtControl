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
import dev.angel.wrtcontrol.parental.ParentalConfig
import dev.angel.wrtcontrol.parental.ParentalSettings
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.backupJson
import dev.angel.wrtcontrol.parental.blockedIntervals
import dev.angel.wrtcontrol.parental.normalizeMac
import dev.angel.wrtcontrol.parental.parseBackup
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

/**
 * [backup]: copia guardada en el teléfono pendiente de restaurar o descartar (el router se quedó
 * sin perfiles: se restableció o los borraron desde LuCI); null si no hay nada que ofrecer.
 * [phoneMacs]: este teléfono según el router.
 */
data class ParentalData(
    val snapshot: ParentalSnapshot,
    val devices: List<Device>,
    val backup: ParentalConfig? = null,
    val phoneMacs: Set<String> = emptySet(),
) {
    /** Dispositivos conectados que esperan aprobación (solo con la cuarentena activa). */
    val pending: List<Device>
        get() = if (!snapshot.settings.quarantine) emptyList() else devices.filter { it.mac !in snapshot.config.approvedMacs }
}

class ParentalViewModel(private val repo: RouterRepository) : ViewModel() {

    private val refresh = Channel<Unit>(Channel.CONFLATED)

    /** IPv4 de este teléfono, para reconocerlo en la lista de dispositivos del router. */
    @Volatile
    var phoneIps: Set<String> = emptySet()

    @Volatile
    private var devices: List<Device> = emptyList()

    /** Identifica el router además de su dirección (modelo y MAC de la LAN), para no mezclar copias. */
    @Volatile
    private var routerKey: String? = null

    /** Sesión con la que se calculó [routerKey]: otra sesión puede ser otro router en la misma dirección. */
    @Volatile
    private var keyApi: OpenWrtApi? = null

    /** Última copia guardada, para no reescribirla en cada recarga. */
    @Volatile
    private var savedBackup: String? = null

    val state: StateFlow<Loadable<ParentalData>> = pollingFlow(15.seconds, refresh) {
        repo.use { api ->
            coroutineScope {
                val snapshot = async { api.parental.load() }
                val list = async { api.devices() }
                if (keyApi !== api) {
                    routerKey = null
                    savedBackup = null
                }
                val key = routerKey ?: api.routerId().also { routerKey = it; keyApi = api }
                val s = snapshot.await()
                devices = list.await()
                val empty = s.profiles.isEmpty() && s.settings.copy(admins = emptyList()) == ParentalSettings()
                // Router vacío con copia guardada: se ofrece restaurarla y, hasta que el usuario
                // decida, no se sustituye (aunque mientras tanto cree otro perfil).
                var pending = repo.backupPending(key)
                val stored = parseBackup(repo.loadBackup(key))
                if (empty && stored != null && !pending) {
                    repo.setBackupPending(key, true)
                    pending = true
                }
                // Si los perfiles de la copia ya vuelven a estar (los restauró otro teléfono o se
                // rehicieron), ya no hay nada que ofrecer.
                if (pending && !empty && (stored == null || s.profiles.map { it.id }.containsAll(stored.profiles.map { it.id }))) {
                    repo.setBackupPending(key, false)
                    pending = false
                }
                if (!pending) keepBackup(s.config)
                ParentalData(s, devices, backup = if (pending) stored else null, phoneMacs = phoneMacs())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())

    /** Qué se está aplicando ahora mismo en el router (null si nada). */
    var busy by mutableStateOf<String?>(null)
        private set

    /** Error del último cambio, hasta que el usuario lo cierre o haga otro. */
    var error by mutableStateOf<String?>(null)

    /** MAC de este teléfono según el router (por su IP en la lista de dispositivos). */
    private fun phoneMacs(): Set<String> = devices.filter { d -> d.ipv4.any { it in phoneIps } }.map { it.mac }.toSet()

    /**
     * Guarda [profile] tal como quedó en el editor. Si alguno de sus dispositivos estaba en otro
     * perfil, pasa a este: cada dispositivo pertenece a un solo perfil. Del editor solo se toma lo
     * que edita: la pausa y el tiempo extra pueden haber cambiado mientras estaba abierto.
     */
    fun save(profile: Profile, onSaved: () -> Unit) = run("Guardando «${profile.name}»…", onSaved) { api ->
        val moved = profile.macs.toSet()
        api.parental.updateProfiles(phoneMacs()) { profiles, _ ->
            val current = profiles.firstOrNull { it.id == profile.id }
            val merged = current?.copy(
                name = profile.name,
                macs = profile.macs,
                schedules = profile.schedules,
                filter = profile.filter,
                protectRouter = profile.protectRouter,
            ) ?: profile
            profiles.filter { it.id != profile.id }.map { it.copy(macs = it.macs - moved) } + merged
        }
    }

    fun delete(profile: Profile, onDeleted: () -> Unit) = run("Eliminando «${profile.name}»…", onDeleted) { api ->
        api.parental.updateProfiles(phoneMacs()) { profiles, _ -> profiles.filter { it.id != profile.id } }
    }

    /** [minutes] null: hasta que se reanude a mano. La hora de fin se calcula con el reloj del router. */
    fun pause(profile: Profile, minutes: Long?) = run("Pausando internet de «${profile.name}»…") { api ->
        api.parental.updateProfiles(phoneMacs()) { profiles, now ->
            profiles.map {
                if (it.id != profile.id) it
                else it.copy(pause = minutes?.let { m -> Pause.Until(now.plusMinutes(m).withNano(0)) } ?: Pause.Indefinite)
            }
        }
    }

    fun resume(profile: Profile) = run("Reanudando internet de «${profile.name}»…") { api ->
        api.parental.updateProfiles(phoneMacs()) { profiles, _ -> profiles.map { if (it.id == profile.id) it.copy(pause = null) else it } }
    }

    /**
     * Tiempo extra sobre los horarios: [option] dice cuántos minutos o hasta el final del horario
     * en curso, y se calcula con la hora del router al aplicar. null lo quita.
     */
    fun extraTime(profile: Profile, option: ExtraOption?) {
        val label = if (option == null) "Quitando el tiempo extra de «${profile.name}»…" else "Dando tiempo extra a «${profile.name}»…"
        run(label) { api ->
            api.parental.updateProfiles(phoneMacs()) { profiles, now ->
                profiles.map { p ->
                    if (p.id != profile.id) return@map p
                    val until = when {
                        option == null -> null
                        option.minutes != null -> now.plusMinutes(option.minutes)
                        else -> p.blockedIntervals(now.toLocalDate()).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }?.end
                            ?: return@map p // el horario ya terminó: no hay nada que dar
                    }
                    p.copy(extraUntil = until?.withNano(0))
                }
            }
        }
    }

    /**
     * Activa o desactiva la cuarentena. La primera vez se aprueba todo lo que el router conoce
     * ahora, para no dejar sin internet a la TV o la impresora. Al volver a activarla, no: lo que
     * se conectó mientras estuvo apagada (o esperaba aprobación) tiene que aprobarse.
     */
    fun setQuarantine(on: Boolean) {
        val known = devices.map { it.mac }
        run(if (on) "Activando la cuarentena de dispositivos nuevos…" else "Desactivando la cuarentena…") { api ->
            api.parental.update(phoneMacs()) { config, _ ->
                val first = config.settings.approved.isEmpty()
                val approved = if (on && first) (config.settings.approved + known).distinct().sorted() else config.settings.approved
                config.copy(settings = config.settings.copy(quarantine = on, approved = approved))
            }
        }
    }

    fun approve(device: Device) = approveMac(device.mac, device.name ?: device.mac)

    /** Aprueba una MAC escrita a mano (un equipo que no aparece en la lista). */
    fun approveMac(mac: String, label: String = mac) {
        val clean = normalizeMac(mac) ?: return
        run("Aprobando «$label»…") { api ->
            api.parental.update(phoneMacs()) { config, _ ->
                config.copy(settings = config.settings.copy(approved = (config.settings.approved + clean).distinct().sorted()))
            }
        }
    }

    /** Marca [mac] como teléfono con la app (p. ej. el de otro padre que está en un perfil protegido). */
    fun addAdmin(mac: String) {
        val clean = normalizeMac(mac) ?: return
        run("Añadiendo el teléfono…") { api ->
            api.parental.update(phoneMacs()) { config, _ ->
                config.copy(settings = config.settings.copy(admins = (config.settings.admins + clean).distinct().sorted()))
            }
        }
    }

    /** Quita la aprobación de [mac] (o deja de tratarlo como teléfono con la app): con la cuarentena, se queda sin internet. */
    fun unapprove(mac: String) = run("Quitando la aprobación…") { api ->
        api.parental.update(phoneMacs()) { config, _ ->
            config.copy(settings = config.settings.copy(approved = config.settings.approved - mac, admins = config.settings.admins - mac))
        }
    }

    /** Pasa [device] a [profile] (y deja de estar en cuarentena). */
    fun assign(device: Device, profile: Profile) = run("Pasando «${device.name ?: device.mac}» a «${profile.name}»…") { api ->
        api.parental.updateProfiles(phoneMacs()) { profiles, _ ->
            profiles.map {
                when (it.id) {
                    profile.id -> it.copy(macs = (it.macs + device.mac).distinct().sorted())
                    else -> it.copy(macs = it.macs - device.mac)
                }
            }
        }
    }

    /** Sustituye lo que haya en el router por la copia. */
    fun restore(backup: ParentalConfig) = run("Restaurando los perfiles…") { api ->
        val result = api.parental.update(phoneMacs()) { _, _ -> backup }
        routerKey?.let { repo.setBackupPending(it, false) }
        result
    }

    fun discardBackup() {
        viewModelScope.launch {
            routerKey?.let {
                repo.saveBackup(it, null)
                repo.setBackupPending(it, false)
            }
            savedBackup = null
            refresh.trySend(Unit)
        }
    }

    /** Vuelve a escribir y cargar las reglas aunque la configuración no haya cambiado. */
    fun reapply() = run("Volviendo a aplicar las reglas…") { api ->
        api.parental.update(phoneMacs(), force = true) { config, _ -> config }
    }

    fun useZone(zone: ZoneId) = run("Cambiando la zona horaria del router…") { api ->
        api.parental.setTimezone(zone.id, zone.rules.getOffset(Instant.now()).totalSeconds, phoneMacs())
        null
    }

    fun changePassword(password: String, onDone: () -> Unit) {
        if (busy != null) return
        busy = "Cambiando la contraseña del router…"
        error = null
        viewModelScope.launch {
            try {
                repo.changePassword(password)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falló el cambio de contraseña", e)
                error = e.userMessage()
            } finally {
                busy = null
            }
        }
    }

    private suspend fun keepBackup(config: ParentalConfig) {
        val key = routerKey ?: return
        val json = backupJson(config)
        if (json != savedBackup) {
            repo.saveBackup(key, json)
            savedBackup = json
        }
    }

    /** [block] devuelve la configuración que quedó en el router (para la copia), o null. */
    private fun run(label: String, onSuccess: () -> Unit = {}, block: suspend (OpenWrtApi) -> ParentalConfig?) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                val result = repo.use { block(it) }
                val key = routerKey
                // Con una copia pendiente de restaurar, no se toca hasta que el usuario decida.
                if (result != null && key != null && !repo.backupPending(key)) {
                    // Si la app deja el router sin nada, tampoco hay copia que ofrecer.
                    if (result.profiles.isEmpty() && result.settings.copy(admins = emptyList()) == ParentalSettings()) {
                        repo.saveBackup(key, null)
                        savedBackup = null
                    } else {
                        keepBackup(result)
                    }
                }
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
