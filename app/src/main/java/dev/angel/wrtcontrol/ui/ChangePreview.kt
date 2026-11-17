package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.txt

/*
 * La vista previa de un cambio: "esto es lo que va a cambiar", antes de tocar el router. No sale
 * en cada cambio (ponerle nombre a un aparato no necesita permiso dos veces): solo cuando hay algo
 * que conviene saber antes, es decir, cuando la vista previa trae avisos ([ChangePreview.notes]).
 */

/** Algo que cambia de valor: qué es, cómo está y cómo queda. */
data class PreviewLine(val what: Txt, val before: Txt, val after: Txt)

/**
 * Lo que va a hacer un cambio. [title]: qué es. [lines]: lo que cambia de valor. [notes]: lo que
 * conviene saber antes (quién se queda sin internet, qué deja de valer). [reverts]: si el router
 * lo deshace solo cuando no funciona (ver SafeApply.kt).
 */
data class ChangePreview(
    val title: Txt,
    val lines: List<PreviewLine> = emptyList(),
    val notes: List<Txt> = emptyList(),
    val reverts: Boolean = true,
)

/** Un cambio propuesto que espera a que el usuario lo acepte (ver [ChangeRunner.propose]). */
interface Proposals {
    val proposal: ChangePreview?

    /** Hace el cambio propuesto. */
    fun accept()

    /** Lo deja sin hacer. */
    fun dismiss()
}

/** La contraseña del router no se aplica con reversión, y vale para más cosas que la app. */
fun passwordPreview(): ChangePreview = ChangePreview(
    title = txt(R.string.preview_password_title),
    notes = listOf(
        txt(R.string.preview_password_everywhere),
        txt(R.string.preview_password_phones),
        txt(R.string.preview_password_keep),
    ),
    reverts = false,
)

/** Con otra zona horaria cambia la hora del router, y con ella cuándo empiezan y acaban los horarios. */
fun zonePreview(routerZone: String?, newZone: String): ChangePreview = ChangePreview(
    title = txt(R.string.preview_zone_title),
    lines = listOf(
        PreviewLine(
            what = txt(R.string.preview_zone_line),
            before = routerZone?.let { raw(it.replace('_', ' ')) } ?: txt(R.string.preview_zone_unknown),
            after = raw(newZone.replace('_', ' ')),
        ),
    ),
    notes = listOf(txt(R.string.preview_zone_schedules)),
)

/** Enseña el cambio propuesto en [proposals], si lo hay, igual en todas las pantallas. */
@Composable
fun ChangePreviewHost(proposals: Proposals) {
    val preview = proposals.proposal ?: return
    if (!LocalAppLocked.current) ChangePreviewDialog(preview, onAccept = proposals::accept, onDismiss = proposals::dismiss)
}

@Composable
fun ChangePreviewDialog(preview: ChangePreview, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preview_title)) },
        text = { ChangePreviewBody(preview, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag("preview-apply")) { Text(stringResource(R.string.preview_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.preview_cancel)) } },
    )
}

/** Lo que dice la vista previa: qué es, qué cambia de valor, los avisos y si se deshace solo. */
@Composable
fun ChangePreviewBody(preview: ChangePreview, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(preview.title.text(), style = MaterialTheme.typography.titleSmall)
        preview.lines.forEach { line ->
            Column {
                Text(line.what.text(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.preview_change, line.before.text(), line.after.text()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        preview.notes.forEach { Text(it.text(), style = MaterialTheme.typography.bodyMedium) }
        Text(
            stringResource(if (preview.reverts) R.string.preview_reverts else R.string.preview_no_revert),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
