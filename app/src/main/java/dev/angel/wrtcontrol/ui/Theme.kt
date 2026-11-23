package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.angel.wrtcontrol.R

/*
 * La cara de WrtControl ("Cobalto"): azul cobalto sobre blanco frío, tarjetas blancas y títulos
 * compactos en Manrope. Es la misma en todos los teléfonos: no usa el color dinámico de Android.
 *
 * El azul es la marca y lo que se puede tocar. Verde, ámbar y rojo dicen cómo está algo (bien,
 * aviso, problema) y no se usan para otra cosa: están en [StatusColors], aparte del esquema de
 * Material, salvo el rojo (`error`) y el ámbar de los avisos (`tertiary`).
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF2346D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FB),
    onPrimaryContainer = Color(0xFF14257A),
    secondary = Color(0xFF4A5578),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9FB),
    onSecondaryContainer = Color(0xFF14257A),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBF0D9),
    onTertiaryContainer = Color(0xFF5C3F05),
    error = Color(0xFFC62F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E5),
    onErrorContainer = Color(0xFF7A1414),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF141A2E),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF141A2E),
    surfaceVariant = Color(0xFFE9EDF6),
    onSurfaceVariant = Color(0xFF5E6680),
    outline = Color(0xFFB4BCD3),
    outlineVariant = Color(0xFFE0E5F0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EA4FF),
    onPrimary = Color(0xFF0B1236),
    primaryContainer = Color(0xFF1F2A55),
    onPrimaryContainer = Color(0xFFDFE5FF),
    secondary = Color(0xFFB3BCDA),
    onSecondary = Color(0xFF1C2340),
    secondaryContainer = Color(0xFF242C4D),
    onSecondaryContainer = Color(0xFFDFE5FF),
    tertiary = Color(0xFFE8A63A),
    onTertiary = Color(0xFF2B1D00),
    tertiaryContainer = Color(0xFF2E2613),
    onTertiaryContainer = Color(0xFFF1DCAE),
    error = Color(0xFFFF8F85),
    onError = Color(0xFF3A0B08),
    errorContainer = Color(0xFF3A1614),
    onErrorContainer = Color(0xFFFFD9D5),
    background = Color(0xFF0E111B),
    onBackground = Color(0xFFE8EBF7),
    surface = Color(0xFF0E111B),
    onSurface = Color(0xFFE8EBF7),
    surfaceVariant = Color(0xFF1E2333),
    onSurfaceVariant = Color(0xFF939BB5),
    outline = Color(0xFF4A5270),
    outlineVariant = Color(0xFF272D42),
    surfaceContainerLowest = Color(0xFF0B0E16),
    surfaceContainerLow = Color(0xFF131725),
    surfaceContainer = Color(0xFF131725),
    surfaceContainerHigh = Color(0xFF171B29),
    surfaceContainerHighest = Color(0xFF171B29),
)

/** Los colores de "va bien": el verde no existe en el esquema de Material. */
@Immutable
data class StatusColors(
    val ok: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    /** El punto de "en marcha" sobre el azul de marca. */
    val signal: Color,
)

private val LightStatus =
    StatusColors(ok = Color(0xFF1B7F4B), okContainer = Color(0xFFDDF3E6), onOkContainer = Color(0xFF0E4A2B), signal = Color(0xFF7BE0A2))
private val DarkStatus =
    StatusColors(ok = Color(0xFF5FD08A), okContainer = Color(0xFF14301F), onOkContainer = Color(0xFFBFEBD0), signal = Color(0xFF1B7F4B))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/**
 * Las series de las gráficas: lo que baja de internet y lo que sube. Ni verde, ni ámbar, ni rojo
 * (son de [StatusColors]). Comprobadas sobre las tarjetas en claro y en oscuro: se distinguen
 * también con daltonismo y contrastan con el fondo al menos 3:1.
 */
@Immutable
data class ChartColors(val received: Color, val sent: Color)

private val LightChart = ChartColors(received = Color(0xFF2346D6), sent = Color(0xFF0F9AA8))
private val DarkChart = ChartColors(received = Color(0xFF4F6FEA), sent = Color(0xFF1AA0A0))

val LocalChartColors = staticCompositionLocalOf { LightChart }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) =
    Font(R.font.manrope, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Los títulos van apretados y con peso; el texto, a su aire. */
private val WrtTypography = Typography().run {
    fun TextStyle.title(weight: Int, tracking: Double = -0.02) =
        copy(fontFamily = Manrope, fontWeight = FontWeight(weight), letterSpacing = tracking.em)

    fun TextStyle.text(weight: Int = 500) = copy(fontFamily = Manrope, fontWeight = FontWeight(weight))
    copy(
        displayLarge = displayLarge.title(800),
        displayMedium = displayMedium.title(800),
        displaySmall = displaySmall.title(800),
        headlineLarge = headlineLarge.title(800),
        headlineMedium = headlineMedium.title(800),
        headlineSmall = headlineSmall.title(800),
        titleLarge = titleLarge.title(800),
        titleMedium = titleMedium.title(700, tracking = -0.01),
        titleSmall = titleSmall.title(700, tracking = 0.0),
        bodyLarge = bodyLarge.text(),
        bodyMedium = bodyMedium.text(),
        bodySmall = bodySmall.text(),
        labelLarge = labelLarge.text(700),
        labelMedium = labelMedium.text(600),
        labelSmall = labelSmall.text(600),
    )
}

private val WrtShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun WrtTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
        LocalChartColors provides if (dark) DarkChart else LightChart,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = WrtTypography,
            shapes = WrtShapes,
            content = content,
        )
    }
}
