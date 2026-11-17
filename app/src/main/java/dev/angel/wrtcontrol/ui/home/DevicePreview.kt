package dev.angel.wrtcontrol.ui.home

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.PreviewLine

/*
 * Lo que va a cambiar al guardar un aparato o bloquearlo. Solo trae avisos (y por tanto solo se
 * enseña antes de aplicar) cuando cambia algo más que el nombre: la dirección fija o de quién es.
 */

/** Una parte del cambio: la línea de la vista previa y, si hay algo que saber antes, su aviso. */
private data class Part(val line: PreviewLine, val note: Txt?)

private fun orText(value: String?, missing: Int): Txt = value?.let(::raw) ?: txt(missing)

private fun namePart(device: Device, change: DeviceChange): Part? {
    if (change.label == device.label) return null
    val missing = R.string.devices_preview_name_default
    return Part(PreviewLine(txt(R.string.devices_edit_name_label), orText(device.label, missing), orText(change.label, missing)), null)
}

private fun addressPart(device: Device, change: DeviceChange): Part? {
    if (change.fixedIp == device.fixedIp) return null
    val missing = R.string.devices_preview_fixed_none
    val line = PreviewLine(txt(R.string.devices_edit_fixed_title), orText(device.fixedIp, missing), orText(change.fixedIp, missing))
    val note = change.fixedIp?.let { txt(R.string.devices_preview_fixed_on, it) } ?: txt(R.string.devices_preview_fixed_off)
    return Part(line, note)
}

private fun ownerPart(device: Device, change: DeviceChange, profiles: List<Profile>): Part? {
    val before = profiles.firstOrNull { device.mac in it.macs }
    val after = profiles.firstOrNull { it.id == change.owner }
    if (change.owner == null || before?.id == after?.id) return null
    val missing = R.string.devices_edit_person_none
    val line = PreviewLine(txt(R.string.devices_preview_person), orText(before?.name, missing), orText(after?.name, missing))
    val note = when {
        after != null -> txt(R.string.devices_preview_person_on, after.name)
        else -> txt(R.string.devices_preview_person_off, before?.name.orEmpty())
    }
    return Part(line, note)
}

/** Lo que cambia al guardar [change] en [device], que en la app se llama [name]. */
fun devicePreview(name: Txt, device: Device, change: DeviceChange, profiles: List<Profile>): ChangePreview {
    val parts = listOfNotNull(namePart(device, change), addressPart(device, change), ownerPart(device, change, profiles))
    return ChangePreview(txt(R.string.devices_preview_title, name), parts.map { it.line }, parts.mapNotNull { it.note })
}

/** Bloquear un aparato lo deja sin internet en el momento, hasta que alguien se lo devuelva. */
fun blockPreview(name: Txt): ChangePreview = ChangePreview(
    title = txt(R.string.devices_preview_block_title, name),
    lines = listOf(
        PreviewLine(txt(R.string.devices_internet_title), txt(R.string.devices_preview_internet_on), txt(R.string.devices_blocked)),
    ),
    notes = listOf(txt(R.string.devices_preview_block_note)),
)
