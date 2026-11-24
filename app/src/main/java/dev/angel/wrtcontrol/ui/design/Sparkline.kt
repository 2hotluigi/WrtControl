package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Lo que sube la curva como mucho: deja aire arriba. */
private const val HEADROOM = 0.9f
private const val FILL_ALPHA = 0.4f

/**
 * Una curva pequeña, sin ejes ni números, para dar una idea de cómo va algo (el consumo de las
 * últimas horas). Lo exacto está en su pantalla.
 */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    val color = LocalPalette.current.accent
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().takeIf { it > 0f } ?: 1f
        val step = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v -> Offset(i * step, size.height - size.height * v / max * HEADROOM) }
        val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = FILL_ALPHA), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
