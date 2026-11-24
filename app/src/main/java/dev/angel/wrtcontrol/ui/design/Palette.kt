package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * El lenguaje visual nuevo (propuesta): fondo negro y tarjetas gris muy oscuro sin sombras ni bordes
 * en oscuro; gris claro y tarjetas blancas en claro. Listas agrupadas (etiqueta a la izquierda, valor
 * a la derecha, línea fina entre filas), cifras grandes como protagonistas y controles redondos.
 */

/** Los colores del lenguaje nuevo. Los de estado (bien, aviso, problema) solo dicen cómo está algo. */
@Immutable
data class Palette(
    val background: Color,
    val card: Color,
    /** Lo que va sobre una tarjeta: el fondo de un botón redondo, el carril de las pestañas. */
    val raised: Color,
    val separator: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
    val good: Color,
    val warning: Color,
    val bad: Color,
)

val DarkPalette = Palette(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    raised = Color(0xFF2C2C2E),
    separator = Color(0xFF38383A),
    label = Color(0xFFFFFFFF),
    secondary = Color(0xFF98989F),
    tertiary = Color(0xFF636366),
    accent = Color(0xFF3D7BFF),
    good = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    bad = Color(0xFFFF453A),
)

val LightPalette = Palette(
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    raised = Color(0xFFEDEDF2),
    separator = Color(0xFFE3E3E8),
    label = Color(0xFF111114),
    secondary = Color(0xFF6C6C72),
    tertiary = Color(0xFFAEAEB2),
    accent = Color(0xFF2350E6),
    good = Color(0xFF1FA855),
    warning = Color(0xFFE07F00),
    bad = Color(0xFFE5342B),
)

/** La paleta en uso: la pone WrtTheme según el modo claro u oscuro. */
val LocalPalette = staticCompositionLocalOf { DarkPalette }
