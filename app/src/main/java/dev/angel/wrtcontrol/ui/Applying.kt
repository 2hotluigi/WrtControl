package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.parental.ApplyPhase
import dev.angel.wrtcontrol.text

private const val STILL_PROGRESS = 0.4f

/** Los pasos de un cambio que sale bien, en orden. */
private val STEPS = listOf(
    ApplyPhase.Applying to R.string.apply_step_apply,
    ApplyPhase.Verifying to R.string.apply_step_verify,
    ApplyPhase.Confirming to R.string.apply_step_confirm,
)

/**
 * Un cambio en curso en el router, igual en todas las pantallas: qué se hace, por cuál de los tres
 * pasos va (aplicar, comprobar, confirmar) y qué pasa si falla. Mientras se deshace, lo dice. Los
 * cambios que no se aplican con reversión (instalar un paquete) solo enseñan qué se hace.
 */
@Composable
fun ApplyingBanner(progress: ChangeProgress, modifier: Modifier = Modifier) {
    val reverting = progress.phase == ApplyPhase.Reverting
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (reverting) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (reverting) colors.onErrorContainer else colors.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag("applying"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(progress.label.text(), style = MaterialTheme.typography.bodyMedium)
            // En las vistas previas y las capturas, quieta: una animación sin fin no las deja terminar.
            if (LocalInspectionMode.current) {
                LinearProgressIndicator(progress = { STILL_PROGRESS }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val phase = progress.phase
            if (phase != null && !reverting) Steps(phase)
            if (phase != null) Text(stringResource(phase.explanation), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Steps(current: ApplyPhase) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        STEPS.forEachIndexed { index, (phase, label) ->
            val active = phase == current
            Text(
                stringResource(R.string.apply_step, index + 1, stringResource(label)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ApplyPhase.explanation: Int
    get() = when (this) {
        ApplyPhase.Applying -> R.string.apply_phase_applying
        ApplyPhase.Verifying -> R.string.apply_phase_verifying
        ApplyPhase.Confirming -> R.string.apply_phase_confirming
        ApplyPhase.Reverting -> R.string.apply_phase_reverting
    }
