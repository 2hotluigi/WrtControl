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
import dev.angel.wrtcontrol.ui.design.DarkPalette
import dev.angel.wrtcontrol.ui.design.LightPalette
import dev.angel.wrtcontrol.ui.design.LocalPalette

/*
 * La cara de WrtControl: listas agrupadas sobre un fondo neutro. En oscuro, fondo negro y tarjetas
 * gris muy oscuro sin sombras ni bordes; en claro, gris claro y tarjetas blancas. Títulos compactos
 * en Manrope. Es la misma en todos los teléfonos: no usa el color dinámico de Android.
 *
 * El azul es la marca y lo que se puede tocar. Verde, naranja y rojo dicen cómo está algo (bien,
 * aviso, problema) y no se usan para otra cosa: están en [StatusColors] y en la paleta de
 * ui/design ([dev.angel.wrtcontrol.ui.design.Palette]), aparte del esquema de Material, salvo el
 * rojo (`error`) y el naranja de los avisos (`tertiary`). El esquema de Material sale de esa paleta:
 * las pantallas que aún usan sus componentes se ven con los mismos colores.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF2350E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EBFF),
    onPrimaryContainer = Color(0xFF0F2A80),
    secondary = Color(0xFF6C6C72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EBFF),
    onSecondaryContainer = Color(0xFF0F2A80),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1DC),
    onTertiaryContainer = Color(0xFF5C3600),
    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E6),
    onErrorContainer = Color(0xFF7A1410),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFF6C6C72),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE3E3E8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3D7BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12264D),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF98989F),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF12264D),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF2E2210),
    onTertiaryContainer = Color(0xFFFFD9A0),
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF3A1513),
    onErrorContainer = Color(0xFFFFD4D0),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989F),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF38383A),
    surfaceContainerLowest = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerHighest = Color(0xFF1C1C1E),
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
    StatusColors(ok = Color(0xFF1B8A47), okContainer = Color(0xFFE1F5E8), onOkContainer = Color(0xFF0E4A2B), signal = Color(0xFF30D158))
private val DarkStatus =
    StatusColors(ok = Color(0xFF30D158), okContainer = Color(0xFF12301C), onOkContainer = Color(0xFFBFEBD0), signal = Color(0xFF30D158))

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
        LocalPalette provides if (dark) DarkPalette else LightPalette,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = WrtTypography,
            shapes = WrtShapes,
            content = content,
        )
    }
}
