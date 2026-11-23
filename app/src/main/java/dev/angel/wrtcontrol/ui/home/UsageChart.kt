package dev.angel.wrtcontrol.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.UsageBucket
import dev.angel.wrtcontrol.openwrt.UsagePeriod
import dev.angel.wrtcontrol.openwrt.totals
import dev.angel.wrtcontrol.ui.LocalChartColors
import dev.angel.wrtcontrol.ui.formatBytes
import dev.angel.wrtcontrol.ui.formatScale
import dev.angel.wrtcontrol.ui.niceCeilBytes
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.format.TextStyle as DayStyle

/*
 * La gráfica del consumo: una barra por hora o por día, con lo recibido abajo y lo enviado encima.
 * Al tocar una barra se ve lo de esa hora o ese día; los totales del periodo van debajo, en texto.
 */

private val CHART_HEIGHT = 180.dp
private val BAR_MAX_WIDTH = 24.dp
private val BAR_RADIUS = 4.dp

/** El hueco del color del fondo entre lo recibido y lo enviado de una barra. */
private val SEGMENT_GAP = 2.dp

/** Una barra con algo, aunque sea poco, se ve. */
private val MIN_SEGMENT = 1.dp
private val LABEL_GAP = 4.dp
private const val BAR_FRACTION = 0.6f

/** Las líneas de la escala: cero, la mitad y el tope. */
private val GRID_FRACTIONS = listOf(0f, 0.5f, 1f)

/** Cada cuántas barras lleva fecha u hora el eje. */
private const val HOUR_LABEL_EVERY = 6
private const val MONTH_LABEL_EVERY = 7

@Composable
fun UsageChart(buckets: List<UsageBucket>, period: UsagePeriod, modifier: Modifier = Modifier) {
    var selected by remember(buckets, period) { mutableStateOf<Int?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val colors = LocalChartColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val axis = axisLabels(buckets, period, locale)
    val totals = buckets.totals()
    val description = stringResource(
        R.string.usage_chart_description,
        stringResource(period.label),
        formatBytes(totals.receivedBytes),
        formatBytes(totals.sentBytes),
    )
    val pick = selected?.let { buckets.getOrNull(it) }
    val top = niceCeilBytes(buckets.maxOfOrNull { it.totalBytes } ?: 0)
    // Las marcas de la escala van a la izquierda, en su margen: así nunca las tapa una barra.
    val scale = remember(top, labelStyle) {
        GRID_FRACTIONS.associateWith { measurer.measure(formatScale((top * it).toLong()), labelStyle) }
    }
    val gutter = scale.values.maxOf { it.size.width } + with(LocalDensity.current) { LABEL_GAP.toPx() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (pick != null) {
                val name = bucketName(pick, period, locale)
                stringResource(R.string.usage_chart_selected, name, formatBytes(pick.receivedBytes), formatBytes(pick.sentBytes))
            } else {
                stringResource(if (period == UsagePeriod.Day) R.string.usage_chart_hint_hour else R.string.usage_chart_hint_day)
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.testTag("usage-chart-selected"),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .testTag("usage-chart")
                .semantics { contentDescription = description }
                .pointerInput(buckets, gutter) {
                    detectTapGestures { tap ->
                        if (buckets.isEmpty() || tap.x < gutter) return@detectTapGestures
                        val index = barAt(tap.x, size.width.toFloat(), gutter, buckets.size)
                        selected = if (selected == index) null else index
                    }
                },
        ) {
            val chart = ChartPaint(colors.received, colors.sent, scheme.outlineVariant, scheme.surfaceVariant, measurer, labelStyle)
            drawUsage(buckets, axis, selected, Scale(top, scale, gutter), chart)
        }
        Legend()
    }
}

/** Lo que hace falta para pintar, sacado del tema. */
private class ChartPaint(
    val received: Color,
    val sent: Color,
    val grid: Color,
    val highlight: Color,
    val measurer: TextMeasurer,
    val label: TextStyle,
)

/** La escala: hasta dónde llega ([top]), sus marcas ya medidas y el margen que ocupan a la izquierda. */
private class Scale(val top: Long, val marks: Map<Float, TextLayoutResult>, val gutter: Float)

private fun DrawScope.drawUsage(buckets: List<UsageBucket>, axis: Map<Int, String>, selected: Int?, scale: Scale, paint: ChartPaint) {
    val top = scale.top
    val gutter = scale.gutter
    val labelHeight = scale.marks.getValue(0f).size.height
    val plotTop = labelHeight / 2f
    val plotBottom = size.height - labelHeight - LABEL_GAP.toPx()
    val plotHeight = plotBottom - plotTop
    val plotWidth = size.width - gutter
    val slot = if (buckets.isEmpty()) plotWidth else plotWidth / buckets.size

    selected?.let { drawRect(paint.highlight, Offset(gutter + it * slot, plotTop), Size(slot, plotHeight)) }
    scale.marks.forEach { (fraction, layout) ->
        val y = plotBottom - plotHeight * fraction
        drawLine(paint.grid, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        drawText(layout, topLeft = Offset(gutter - LABEL_GAP.toPx() - layout.size.width, y - layout.size.height / 2f))
    }

    val barWidth = minOf(slot * BAR_FRACTION, BAR_MAX_WIDTH.toPx())
    buckets.forEachIndexed { i, bucket ->
        val left = gutter + i * slot + (slot - barWidth) / 2
        val received = segment(bucket.receivedBytes, top, plotHeight)
        val sent = segment(bucket.sentBytes, top, plotHeight)
        drawBar(paint.received, left, plotBottom - received, barWidth, received, rounded = sent == 0f)
        if (sent > 0f) {
            val gap = if (received > 0f) SEGMENT_GAP.toPx() else 0f
            drawBar(paint.sent, left, plotBottom - received - gap - sent, barWidth, sent, rounded = true)
        }
    }

    axis.forEach { (i, text) ->
        val layout = paint.measurer.measure(text, paint.label)
        val x = (gutter + i * slot + slot / 2 - layout.size.width / 2).coerceIn(gutter, size.width - layout.size.width)
        drawText(layout, topLeft = Offset(x, plotBottom + LABEL_GAP.toPx()))
    }
}

/** Qué barra hay bajo [x]: las barras empiezan tras el margen de la escala, que se mide igual que al pintar. */
private fun barAt(x: Float, width: Float, gutter: Float, count: Int): Int =
    ((x - gutter) / ((width - gutter) / count)).toInt().coerceIn(0, count - 1)

/** El alto de [bytes] en una escala que llega a [top]; si hay algo, al menos se ve. */
private fun DrawScope.segment(bytes: Long, top: Long, height: Float): Float =
    if (bytes <= 0) 0f else maxOf(height * bytes / top, MIN_SEGMENT.toPx())

/** Una barra apoyada abajo y redondeada arriba si es lo último de la pila. */
private fun DrawScope.drawBar(color: Color, left: Float, top: Float, width: Float, height: Float, rounded: Boolean) {
    if (height <= 0f) return
    val r = if (rounded) CornerRadius(minOf(BAR_RADIUS.toPx(), height, width / 2)) else CornerRadius.Zero
    val path = Path().apply {
        addRoundRect(RoundRect(left, top, left + width, top + height, topLeftCornerRadius = r, topRightCornerRadius = r))
    }
    drawPath(path, color)
}

@Composable
private fun Legend() {
    val colors = LocalChartColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(colors.received, stringResource(R.string.usage_received))
        LegendItem(colors.sent, stringResource(R.string.usage_sent))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val UsagePeriod.label: Int
    get() = when (this) {
        UsagePeriod.Day -> R.string.usage_period_day
        UsagePeriod.Week -> R.string.usage_period_week
        UsagePeriod.Month -> R.string.usage_period_month
    }

/** Qué barras llevan etiqueta en el eje y cuál: cada 6 horas, cada día de la semana o cada 7 días (la última siempre). */
@Composable
private fun axisLabels(buckets: List<UsageBucket>, period: UsagePeriod, locale: Locale): Map<Int, String> {
    val hourLabel = stringResource(R.string.usage_chart_hour_label)
    return when (period) {
        UsagePeriod.Day -> buckets.withIndex()
            .filter { it.value.start.hour % HOUR_LABEL_EVERY == 0 }
            .associate { it.index to String.format(locale, hourLabel, it.value.start.hour) }
        UsagePeriod.Week -> buckets.withIndex().associate { it.index to it.value.start.dayOfWeek.getDisplayName(DayStyle.SHORT, locale) }
        UsagePeriod.Month -> buckets.indices.reversed().filter { (buckets.lastIndex - it) % MONTH_LABEL_EVERY == 0 }
            .associateWith { pattern(locale, "dMMM").format(buckets[it].start) }
    }
}

/** "De 14:00 a 15:00" o "mar, 18 nov". */
@Composable
private fun bucketName(bucket: UsageBucket, period: UsagePeriod, locale: Locale): String = when (period) {
    UsagePeriod.Day -> stringResource(R.string.usage_chart_hour_range, bucket.start.hour, bucket.start.plusHours(1).hour)
    UsagePeriod.Week, UsagePeriod.Month -> pattern(locale, "EEEdMMM").format(bucket.start)
}

/** La forma de escribir la fecha que se usa en [locale] con esos campos ("18 nov", "Nov 18"). */
private fun pattern(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
