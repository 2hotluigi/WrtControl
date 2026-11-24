package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Un botón de la barra de arriba: el icono y lo que dice el lector de pantalla. */
data class BarAction(val icon: Int, val description: String, val onClick: () -> Unit, val tag: String? = null)

/**
 * La barra de arriba: el título en el centro (con [subtitle] debajo, si hay) y a los lados la
 * vuelta atrás ([start]) o un icono ([end]).
 */
@Composable
fun CenterTopBar(
    title: String,
    subtitle: String? = null,
    subtitleAccent: Boolean = false,
    start: BarAction? = null,
    end: BarAction? = null,
    titleTag: String? = null,
    subtitleTag: String? = null,
) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(if (subtitle != null) 60.dp else 56.dp).padding(horizontal = 8.dp)) {
        start?.let { BarIcon(it, Modifier.align(Alignment.CenterStart)) }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (subtitleAccent) palette.accent else palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleTag?.let { tag -> Modifier.testTag(tag) } ?: Modifier,
                )
            }
        }
        end?.let { BarIcon(it, Modifier.align(Alignment.CenterEnd)) }
    }
}

@Composable
private fun BarIcon(action: BarAction, modifier: Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClick = action.onClick)
            .then(action.tag?.let { Modifier.testTag(it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val tint = LocalPalette.current.label
        Icon(painterResource(action.icon), contentDescription = action.description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Una pestaña de la barra flotante; [tag], para las pruebas. */
data class NavItem(val icon: Int, val label: String, val tag: String? = null)

/**
 * La navegación de abajo: una píldora flotante con las pestañas y, aparte, un botón redondo
 * ([round]) para lo que se mira de un vistazo (los avisos).
 */
@Composable
fun FloatingNavBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    round: NavItem,
    roundSelected: Boolean = false,
    roundBadge: Boolean = false,
    onRound: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val bar = palette.card.copy(alpha = 0.96f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp)).background(bar)
                .border(1.dp, palette.separator, RoundedCornerShape(32.dp)).padding(5.dp),
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(27.dp))
                        .background(if (on) palette.raised else Color.Transparent).clickable { onSelect(i) }
                        .then(item.tag?.let { Modifier.testTag(it) } ?: Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val color = if (on) palette.accent else palette.label
                    Icon(painterResource(item.icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (roundSelected) palette.raised else bar)
                .border(1.dp, palette.separator, CircleShape).clickable(onClick = onRound)
                .then(round.tag?.let { Modifier.testTag(it) } ?: Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val roundTint = if (roundSelected) palette.accent else palette.label
            Icon(painterResource(round.icon), contentDescription = round.label, tint = roundTint, modifier = Modifier.size(26.dp))
            if (roundBadge) Box(Modifier.align(Alignment.TopEnd).padding(14.dp).size(10.dp).clip(CircleShape).background(palette.bad))
        }
    }
}

/** Separación estándar entre tarjetas. */
val CardGap: Dp = 14.dp
