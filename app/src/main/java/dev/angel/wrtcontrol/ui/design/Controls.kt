package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Dos o tres cifras grandes en una tarjeta, separadas por líneas verticales. */
@Composable
fun StatStrip(stats: List<Stat>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    GroupCard(modifier) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 14.dp)) {
            stats.forEachIndexed { i, stat ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(palette.separator))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        stat.dot?.let { Box(Modifier.size(7.dp).clip(CircleShape).background(it)) }
                        Text(stat.label, style = MaterialTheme.typography.bodySmall, color = palette.secondary, maxLines = 1)
                    }
                    Text(stat.value, style = BigNumber, color = palette.label, maxLines = 1)
                }
            }
        }
    }
}

/** El estilo de las cifras protagonistas. */
val BigNumber
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)

/** Pestañas en píldora sobre un carril gris, sin marcas: la elegida, más clara. */
@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.raised).padding(3.dp)) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Text(
                option,
                style = MaterialTheme.typography.labelLarge,
                color = palette.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) palette.secondary.copy(alpha = 0.45f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
            )
        }
    }
}

/** Un control redondo con su nombre y cómo está ("Pausar 1 h", "Bloqueado"); [active] lo pinta de color. */
@Composable
fun CircleAction(
    icon: Int,
    label: String,
    caption: String?,
    active: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val color = tint ?: palette.accent
    Column(modifier.clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(if (active) color else palette.raised),
            contentAlignment = Alignment.Center,
        ) {
            val iconTint = if (active) Color.White else palette.label
            Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = palette.label, maxLines = 1, textAlign = TextAlign.Center)
        caption?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = palette.secondary, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

/** Una función en la cuadrícula: el icono de color y su nombre debajo. */
@Composable
fun FeatureTile(icon: Int, label: String, tint: Color, modifier: Modifier = Modifier, caption: String? = null, onClick: () -> Unit = {}) {
    val palette = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.label, maxLines = 1, textAlign = TextAlign.Center)
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
