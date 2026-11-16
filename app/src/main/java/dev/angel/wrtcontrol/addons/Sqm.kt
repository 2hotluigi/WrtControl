package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.openwrt.UciSection
import dev.angel.wrtcontrol.txt

/*
 * SQM (sqm-scripts + luci-app-sqm): reparte la conexión para que no se forme cola en el módem
 * (bufferbloat). Cada sección `queue` de /etc/config/sqm controla un dispositivo de red:
 * `download`/`upload` en kbit/s (0 = sin limitar ese sentido), `qdisc` (cake) y `script`
 * (piece_of_cake.qos). El paquete trae una de ejemplo, desactivada, en eth1.
 */

data class SqmQueue(
    val section: String,
    val enabled: Boolean,
    val device: String,
    val downloadKbit: Long,
    val uploadKbit: Long,
    val qdisc: String,
    val script: String,
)

internal fun parseSqmQueue(s: UciSection) = SqmQueue(
    section = s.name,
    enabled = s.text("enabled") == "1",
    device = s.text("interface").orEmpty(),
    downloadKbit = s.text("download")?.toLongOrNull() ?: 0,
    uploadKbit = s.text("upload")?.toLongOrNull() ?: 0,
    qdisc = s.text("qdisc") ?: "cake",
    script = s.text("script") ?: "piece_of_cake.qos",
)

/** Por debajo de esto el router apenas tendría conexión (y la app, si entra desde fuera, tampoco). */
const val SQM_MIN_KBIT = 256L

/** Lo que el usuario pide para una cola: [section] null crea una nueva. */
data class SqmEdit(val section: String?, val enabled: Boolean, val device: String, val downloadKbit: Long, val uploadKbit: Long)

/** Motivo por el que [edit] no vale, o null. */
fun sqmProblem(edit: SqmEdit): Txt? = when {
    edit.device.isBlank() -> txt(R.string.addons_sqm_pick_device)
    !Regex("^[A-Za-z0-9._@-]+$").matches(edit.device) -> txt(R.string.addons_sqm_invalid_device)
    edit.downloadKbit < 0 || edit.uploadKbit < 0 -> txt(R.string.addons_sqm_negative_speed)
    edit.enabled && edit.downloadKbit == 0L && edit.uploadKbit == 0L -> txt(R.string.addons_sqm_no_speed)
    edit.downloadKbit in 1 until SQM_MIN_KBIT || edit.uploadKbit in 1 until SQM_MIN_KBIT ->
        txt(R.string.addons_sqm_too_slow, SQM_MIN_KBIT.toString())
    else -> null
}

/** Nombre para una cola nueva: el del dispositivo, como la de ejemplo ("eth1"), sin lo que UCI no admite. */
internal fun newQueueName(device: String, taken: Set<String>): String {
    val base = device.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "wan" }
    if (base !in taken) return base
    return generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in taken }
}
