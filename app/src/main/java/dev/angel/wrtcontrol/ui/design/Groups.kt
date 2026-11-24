package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.angel.wrtcontrol.R

private val CardShape = RoundedCornerShape(18.dp)
internal val RowPadding = 16.dp

/** Una tarjeta del grupo: el contenido va de borde a borde (las filas ponen su margen). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(LocalPalette.current.card), content = content)
}

/** El título pequeño en mayúsculas encima de un grupo. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
        color = LocalPalette.current.secondary,
        modifier = modifier.padding(start = RowPadding, end = RowPadding, top = 8.dp, bottom = 2.dp),
    )
}

/** La explicación pequeña debajo de un grupo. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPalette.current.secondary,
        modifier = modifier.padding(horizontal = RowPadding),
    )
}

/**
 * Una fila de un grupo: [label] a la izquierda y [value] a la derecha, con `›` si lleva a otro sitio.
 * [divider]: la línea fina de debajo (no en la última fila), que empieza donde el texto.
 */
@Composable
fun GroupRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    supportingLines: Int = 2,
    labelLines: Int = 1,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: Boolean = false,
    divider: Boolean = true,
    labelColor: Color? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val inset = if (leading != null) RowPadding + LeadingSize + 12.dp else RowPadding
    // El valor ocupa como mucho [VALUE_SHARE] del ancho; la etiqueta, lo que quede.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val valueMax = maxWidth * VALUE_SHARE
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 52.dp)
                .drawBehind {
                    if (divider) {
                        val y = size.height - 0.5.dp.toPx()
                        drawLine(palette.separator, Offset(inset.toPx(), y), Offset(size.width, y), strokeWidth = 1.dp.toPx() / 2)
                    }
                }
                .padding(horizontal = RowPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.let { Box(Modifier.size(LeadingSize), contentAlignment = Alignment.Center) { it() } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = labelColor ?: palette.label,
                    maxLines = labelLines,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondary,
                        maxLines = supportingLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Si el valor no cabe, se corta él y no la etiqueta.
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = valueColor ?: palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = valueMax),
                )
            }
            trailing?.invoke(this)
            if (chevron) {
                val chevronIcon = painterResource(R.drawable.ic_chevron)
                Icon(chevronIcon, contentDescription = null, tint = palette.tertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Lo más que puede ocupar el valor de una fila. */
private const val VALUE_SHARE = 0.6f

private val LeadingSize = 32.dp

/** Un icono de color sobre un cuadrado redondeado, para el principio de una fila. */
@Composable
fun RowIcon(icon: Int, tint: Color, badge: Color? = null) {
    Box {
        Box(
            Modifier.size(LeadingSize).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        badge?.let {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 0.dp).size(10.dp)
                    .border(2.dp, LocalPalette.current.card, CircleShape).padding(1.dp).clip(CircleShape).background(it),
            )
        }
    }
}

/** Lo apagado se ve, pero más tenue. */
private const val DISABLED_ALPHA = 0.5f

/** Una fila con interruptor. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    supporting: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    GroupRow(
        label,
        supporting = supporting,
        leading = leading,
        divider = divider,
        onClick = { if (enabled) onChange(!checked) },
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = palette.accent,
                    checkedBorderColor = palette.accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = palette.raised,
                    uncheckedBorderColor = palette.raised,
                    disabledUncheckedThumbColor = Color.White.copy(alpha = DISABLED_ALPHA),
                    disabledUncheckedTrackColor = palette.raised.copy(alpha = DISABLED_ALPHA),
                    disabledUncheckedBorderColor = palette.raised.copy(alpha = DISABLED_ALPHA),
                ),
                thumbContent = null,
            )
        },
    )
}

/** Una acción sola en su grupo, centrada: azul, o roja si no tiene vuelta atrás. */
@Composable
fun ActionRow(
    text: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    GroupCard(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> palette.tertiary
                destructive -> palette.bad
                else -> palette.accent
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        )
    }
}

/** Una cifra con su etiqueta encima; [dot]: un punto de color delante de la etiqueta. */
data class Stat(val label: String, val value: String, val dot: Color? = null)
