package com.qrhry.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Forest = Color(0xFF245B4B)
private val ForestDeep = Color(0xFF163C32)
private val Moss = Color(0xFF49745D)
private val Sand = Color(0xFFF5F3EB)
private val Paper = Color(0xFFFFFEFA)
private val Ink = Color(0xFF1B2823)
private val MutedInk = Color(0xFF4C5C54)
private val WarmAccent = Color(0xFF8A4B26)
private val DarkBackground = Color(0xFF101A16)
private val DarkSurface = Color(0xFF1A2721)
private val DarkElevated = Color(0xFF24332B)
private val LightText = Color(0xFFE5F1E9)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E9DD),
    onPrimaryContainer = ForestDeep,
    secondary = Moss,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EADF),
    onSecondaryContainer = ForestDeep,
    tertiary = WarmAccent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E3D5),
    onTertiaryContainer = Color(0xFF512A14),
    background = Sand,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8E9E1),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFF78877D),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA4D0B4),
    onPrimary = Color(0xFF183B2D),
    primaryContainer = Color(0xFF28533F),
    onPrimaryContainer = LightText,
    secondary = Color(0xFFB9D0B9),
    onSecondary = Color(0xFF263B2D),
    secondaryContainer = Color(0xFF344B3A),
    onSecondaryContainer = Color(0xFFDCEBDD),
    tertiary = Color(0xFFEABF9F),
    onTertiary = Color(0xFF482B18),
    tertiaryContainer = Color(0xFF684329),
    onTertiaryContainer = Color(0xFFFFDBC2),
    background = DarkBackground,
    onBackground = Color(0xFFE2EAE4),
    surface = DarkSurface,
    onSurface = Color(0xFFE2EAE4),
    surfaceVariant = DarkElevated,
    onSurfaceVariant = Color(0xFFC0CDC3),
    outline = Color(0xFF899A8E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val QRhryTypography = Typography().let { defaults ->
    defaults.copy(
        displaySmall = defaults.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = defaults.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = defaults.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = defaults.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = defaults.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

private val QRhryShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun QRhryTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = QRhryTypography,
        shapes = QRhryShapes,
        content = content
    )
}
