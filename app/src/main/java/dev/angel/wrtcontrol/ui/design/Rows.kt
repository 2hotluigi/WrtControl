package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Filas de solo leer (etiqueta y valor), con la línea entre ellas y no tras la última. */
@Composable
fun ValueRows(rows: List<Pair<String, String>>) {
    rows.forEachIndexed { i, (label, value) -> GroupRow(label, value = value, divider = i < rows.lastIndex) }
}

/** Una barra fina que se llena hasta [fraction] (0 a 1): memoria, disco, lo que gasta un aparato. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalPalette.current.accent) {
    val palette = LocalPalette.current
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.raised)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Una fila con cuánto está lleno ([value], p. ej. el porcentaje), [detail] debajo y la barra de [fraction]. */
@Composable
fun MeterRow(
    label: String,
    value: String,
    detail: String,
    fraction: Float,
    divider: Boolean = true,
    color: Color = LocalPalette.current.accent,
) {
    val palette = LocalPalette.current
    Column {
        GroupRow(label, value = value, supporting = detail, divider = false)
        MeterBar(fraction, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), color)
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = palette.separator)
    }
}

/**
 * Una propuesta: qué se consigue ([body]) y el botón para hacerlo ([action]). Sin [action], no se
 * puede; [problem] dice por qué. [enabled]: false mientras otra cosa está en marcha.
 */
@Composable
fun OfferGroup(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    problem: String? = null,
    actionTag: String? = null,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader(title)
        GroupCard {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.label,
                modifier = Modifier.padding(16.dp),
            )
            problem?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.bad,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
            if (action != null) {
                HorizontalDivider(thickness = 0.5.dp, color = palette.separator)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clickable(enabled = enabled, onClick = onAction)
                        .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        action,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) palette.accent else palette.tertiary,
                    )
                }
            }
        }
    }
}
