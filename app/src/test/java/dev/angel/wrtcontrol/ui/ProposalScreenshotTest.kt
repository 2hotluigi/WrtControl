package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.UsageBucket
import dev.angel.wrtcontrol.openwrt.UsagePeriod
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.design.CenterTopBar
import dev.angel.wrtcontrol.ui.design.CircleAction
import dev.angel.wrtcontrol.ui.design.FeatureTile
import dev.angel.wrtcontrol.ui.design.FloatingNavBar
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.NavItem
import dev.angel.wrtcontrol.ui.design.PillTabs
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.design.Stat
import dev.angel.wrtcontrol.ui.design.StatStrip
import dev.angel.wrtcontrol.ui.design.SwitchRow
import dev.angel.wrtcontrol.ui.home.UsageChart
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/**
 * Propuesta de diseño (no es la app todavía): Inicio, Dispositivos y la ficha de un dispositivo con el
 * lenguaje nuevo de ui/design, con datos de ejemplo. Se guardan en `app/src/test/screenshots/propuesta-*`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-w392dp-h860dp-xxhdpi")
class ProposalScreenshotTest {

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    private fun both(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("src/test/screenshots/propuesta-$name-${if (dark) "oscuro" else "claro"}.png", roborazziOptions = options) {
                WrtTheme(dark = dark) {
                    Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) { content() }
                }
            }
        }
    }

    private val nav = listOf(NavItem(R.drawable.ic_home, "Inicio"), NavItem(R.drawable.ic_devices, "Dispositivos"), NavItem(R.drawable.ic_family, "Familia"))

    @Composable
    private fun Screen(title: String, selectedTab: Int?, start: Int? = null, end: Int? = null, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                CenterTopBar(title, start = start, end = end)
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(CardGap)) { content() }
            }
            if (selectedTab != null) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    FloatingNavBar(nav, selectedTab, onSelect = {}, round = R.drawable.ic_shield, roundBadge = true)
                }
            }
        }
    }

    private val hours = (0 until 24).map { i ->
        val evening = listOf(2, 1, 1, 1, 1, 1, 3, 6, 5, 4, 4, 5, 6, 5, 4, 5, 7, 10, 14, 18, 20, 17, 11, 5)
        val start = LocalDateTime.of(2026, 11, 24, 14, 0).minusHours(23L - i)
        val rx = evening[start.hour] * 90L * 1024 * 1024
        UsageBucket(start, rx, rx / 9)
    }

    @Test
    fun inicio() = both("inicio") {
        val p = LocalPalette.current
        Screen("Casa", selectedTab = 0, start = R.drawable.ic_router, end = R.drawable.ic_settings) {
            // Internet: cómo está y la velocidad, con la curva de las últimas horas.
            GroupCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Internet", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(p.good))
                            Text("Conectado", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = p.label)
                        }
                        Text("Sin cortes en 2 días · 1 Gbps", style = MaterialTheme.typography.bodySmall, color = p.secondary)
                    }
                    Sparkline(hours.map { it.totalBytes.toFloat() }, Modifier.width(110.dp).height(52.dp))
                }
            }
            StatStrip(listOf(Stat("Dispositivos", "20"), Stat("En línea", "17", p.good), Stat("Nuevos", "2", p.warning)))
            GroupCard {
                GroupRow(
                    "2 dispositivos nuevos",
                    supporting = "Sin internet hasta que los apruebes",
                    leading = { RowIcon(R.drawable.ic_shield, p.warning) },
                    chevron = true,
                    divider = false,
                )
            }
            GroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("Últimas 24 horas", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
                            Text("15,2 GB", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = p.label)
                        }
                        Text("↓ 13,7 · ↑ 1,5 GB", style = MaterialTheme.typography.bodySmall, color = p.secondary)
                    }
                    UsageChart(hours, UsagePeriod.Day)
                }
            }
            GroupCard {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    FeatureTile(R.drawable.ic_wifi, "Wi-Fi", p.accent, Modifier.weight(1f))
                    FeatureTile(R.drawable.ic_person, "Invitados", p.good, Modifier.weight(1f))
                    FeatureTile(R.drawable.ic_bar_chart, "Consumo", p.accent, Modifier.weight(1f))
                }
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    FeatureTile(R.drawable.ic_lock, "Fuera de casa", p.warning, Modifier.weight(1f))
                    FeatureTile(R.drawable.ic_extension, "Funciones", p.accent, Modifier.weight(1f))
                    FeatureTile(R.drawable.ic_router, "Router", p.secondary, Modifier.weight(1f))
                }
            }
        }
    }

    @Test
    fun dispositivos() = both("dispositivos") {
        val p = LocalPalette.current
        Screen("Dispositivos", selectedTab = 1, end = R.drawable.ic_search) {
            StatStrip(listOf(Stat("Total", "20"), Stat("En línea", "17", p.good), Stat("Fuera", "3", p.tertiary)))
            PillTabs(listOf("Todos", "Por persona", "Por tipo"), selected = 0, onSelect = {})
            Column {
                SectionHeader("En línea")
                GroupCard {
                    Device(R.drawable.ic_kind_tv, "Tele de la sala", "192.168.8.21 · Samsung", "96 GB", online = true)
                    Device(R.drawable.ic_kind_computer, "Portátil de Ana", "192.168.8.34 · Lenovo", "41 GB", online = true)
                    Device(R.drawable.ic_kind_phone, "iPhone de Mari", "192.168.8.115 · Apple", "6,2 GB", online = true)
                    Device(R.drawable.ic_kind_smart, "Cámara cochera", "192.168.8.68 · TP-Link", "5,1 GB", online = true)
                    Device(R.drawable.ic_kind_network, "Aparato TP-Link", "192.168.8.2 · TP-Link", "380 MB", online = true, last = true)
                }
            }
            Column {
                SectionHeader("Sin conexión")
                GroupCard {
                    Device(R.drawable.ic_kind_phone, "Teléfono de Luis", "Visto hace 3 h", null, online = false, last = true)
                }
            }
        }
    }

    @Composable
    private fun Device(icon: Int, name: String, detail: String, month: String?, online: Boolean, last: Boolean = false) {
        val p = LocalPalette.current
        GroupRow(
            name,
            supporting = detail,
            value = month,
            leading = { RowIcon(icon, if (online) p.accent else p.tertiary, badge = if (online) p.good else null) },
            chevron = true,
            divider = !last,
        )
    }

    @Test
    fun ficha() = both("ficha") {
        val p = LocalPalette.current
        Screen("iPhone de Mari", selectedTab = null, start = R.drawable.ic_back) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_kind_phone), contentDescription = null, tint = p.accent, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(p.good))
                    Text("En línea · de Mari", style = MaterialTheme.typography.bodyMedium, color = p.secondary)
                }
            }
            StatStrip(listOf(Stat("Este mes", "6,2 GB"), Stat("Hoy", "412 MB")))
            Column {
                SectionHeader("Internet")
                GroupCard {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        CircleAction(R.drawable.ic_timer, "30 min", "Pausar", active = false, modifier = Modifier.weight(1f))
                        CircleAction(R.drawable.ic_timer, "1 hora", "Pausar", active = false, modifier = Modifier.weight(1f))
                        CircleAction(R.drawable.ic_pause, "Pausado", "hasta 18:30", active = true, modifier = Modifier.weight(1f))
                        CircleAction(R.drawable.ic_block, "Bloquear", "Apagado", active = false, modifier = Modifier.weight(1f))
                    }
                }
            }
            Column {
                SectionHeader("Detalles")
                GroupCard {
                    GroupRow("Nombre", value = "iPhone de Mari", chevron = true)
                    GroupRow("Persona", value = "Mari", chevron = true)
                    GroupRow("Dirección IP", value = "192.168.8.115")
                    GroupRow("Fabricante", value = "Apple")
                    GroupRow("Conectado por", value = "Wi-Fi 5 GHz · buena", divider = false)
                }
            }
            GroupCard { SwitchRow("Dirección fija", checked = true, onChange = {}, divider = false, supporting = "Siempre 192.168.8.115") }
        }
    }

    /** La curva pequeña de la tarjeta de internet. */
    @Composable
    private fun Sparkline(values: List<Float>, modifier: Modifier) {
        val p = LocalPalette.current
        Canvas(modifier) {
            val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1f
            val step = size.width / (values.size - 1)
            val points = values.mapIndexed { i, v -> Offset(i * step, size.height - size.height * v / max * 0.9f) }
            val line = Path().apply { points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.45f), p.accent.copy(alpha = 0f))))
            drawPath(line, p.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
