package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.UserFacingException
import dev.angel.wrtcontrol.addons.internetZones
import dev.angel.wrtcontrol.parental.ROLLBACK_SECONDS
import dev.angel.wrtcontrol.parental.VERIFY_TIMEOUT_MS
import dev.angel.wrtcontrol.parental.applySafely
import dev.angel.wrtcontrol.parental.ensureNoPendingApply
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusClient
import dev.angel.wrtcontrol.ubus.UbusSession
import dev.angel.wrtcontrol.ubus.callAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val GUEST_CONFIGS = listOf("network", "dhcp", "firewall", "wireless")

/** Las secciones propias de cada fichero de configuración. */
private fun Map<String, List<UciSection>>.own(): Map<String, List<UciSection>> =
    mapValues { (_, sections) -> sections.filter { it.name.startsWith(GUEST_PREFIX) } }

/** El Wi-Fi de invitados (ver GuestWifi.kt). Se crea, cambia y apaga con reversión automática. */
class GuestWifiApi(private val ubus: UbusClient, private val writeLock: Mutex = Mutex()) {

    /**
     * Deja el Wi-Fi de invitados como dice [edit]. Crear o cambiar sus redes reinicia las radios,
     * así que el Wi-Fi de casa también se corta unos segundos: [phoneOnIt] si el teléfono que lo
     * hace va por él (se le da más tiempo para volver). Apagarlo sin haberlo creado no hace nada.
     */
    suspend fun save(edit: GuestEdit, phoneOnIt: Boolean) = writeLock.withLock {
        if (edit.on) guestProblem(edit)?.let { throw UserFacingException(it) }
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val current = GUEST_CONFIGS.associateWith { tx.uciSections(it) }
        val exists = current.getValue("network").any { it.name == GUEST_INTERFACE }
        val desired = if (edit.on || exists) tx.desired(edit, current) else current.own()
        tx.applyChanges(current, desired, edit.on, phoneOnIt)
    }

    /**
     * Borra el Wi-Fi de invitados del todo: sus redes, su interfaz, su reparto y sus reglas. Como
     * apagarlo, reinicia las radios ([phoneOnIt]: ver [save]). Sin Wi-Fi de invitados no hace nada.
     */
    suspend fun remove(phoneOnIt: Boolean) = writeLock.withLock {
        val tx = ubus.openSession()
        tx.ensureNoPendingApply()
        val current = GUEST_CONFIGS.associateWith { tx.uciSections(it) }
        tx.applyChanges(current, GUEST_CONFIGS.associateWith { emptyList() }, on = false, phoneOnIt)
    }

    /** Deja las secciones propias como [desired] (desde [current]) y lo aplica con reversión. */
    private suspend fun UbusSession.applyChanges(
        current: Map<String, List<UciSection>>,
        desired: Map<String, List<UciSection>>,
        on: Boolean,
        phoneOnIt: Boolean,
    ) {
        val diffs = GUEST_CONFIGS.associateWith { guestDiff(current.getValue(it), desired.getValue(it)) }
        if (diffs.values.all { (deletes, adds) -> deletes.isEmpty() && adds.isEmpty() }) return
        for ((config, diff) in diffs) {
            diff.first.forEach { uciDelete(config, it) }
            diff.second.forEach { uciAdd(config, it) }
        }
        val up = parseWifi(current.getValue("wireless"), wirelessDevices()).radios.filter { it.up }
        applyGuest(on, before = current.own(), up = up, slow = phoneOnIt || up.any { it.mayWaitForRadar })
    }

    /** Cómo tiene que quedar todo lo de invitados, con las radios encendidas y una red que no choque. */
    private suspend fun UbusSession.desired(edit: GuestEdit, current: Map<String, List<UciSection>>): Map<String, List<UciSection>> {
        val radios = current.getValue("wireless").filter { it.type == "wifi-device" && it.text("disabled") != "1" }.map { it.name }
        if (radios.isEmpty()) throw UserFacingException(txt(R.string.guest_error_no_radio))
        val interfaces = callAs<InterfaceDump>("network.interface", "dump").interfaces
        val address = current.getValue("network").firstOrNull { it.name == GUEST_INTERFACE }?.text("ipaddr") ?: freeGuestAddress(interfaces)
        return guestSections(edit, radios, address, internetZones(current.getValue("firewall"), interfaces))
    }

    /** Aplica lo preparado y espera a que quede como se pidió varias comprobaciones seguidas. */
    private suspend fun UbusSession.applyGuest(on: Boolean, before: Map<String, List<UciSection>>, up: List<WifiRadio>, slow: Boolean) {
        val patient = slow && up.isNotEmpty()
        var steady = 0
        applySafely(
            failure = txt(if (on) R.string.guest_error_not_started else R.string.guest_error_not_stopped),
            isReverted = { fresh -> GUEST_CONFIGS.associateWith { fresh.uciSections(it) }.own() == before },
            rollbackSeconds = if (patient) WIFI_ROLLBACK_SECONDS else ROLLBACK_SECONDS,
            verifyTimeoutMs = if (patient) WIFI_VERIFY_TIMEOUT_MS else VERIFY_TIMEOUT_MS,
        ) {
            steady = if (guestLive(on, up.map { it.name }.toSet())) steady + 1 else 0
            steady >= RADIO_STEADY_CHECKS
        }
    }

    /**
     * ¿Está como se pidió? Las radios que emitían han vuelto, cada una emite (o no) la red de
     * invitados y, si está encendido, su interfaz está levantada (sin ella no hay DHCP ni salida).
     */
    private suspend fun UbusSession.guestLive(on: Boolean, wasUp: Set<String>): Boolean {
        val devices = wirelessDevices()
        val state = parseWifi(uciSections("wireless"), devices)
        val emitting = state.networks.filter { it.section.startsWith(GUEST_PREFIX) && it.ifname != null }.map { it.radio }.toSet()
        val radios = radiosBack(wasUp, devices) && wasUp.all { (it in emitting) == on }
        val iface = !on || callAs<InterfaceDump>("network.interface", "dump").interfaces.any { it.name == GUEST_INTERFACE && it.up }
        return radios && iface
    }
}
