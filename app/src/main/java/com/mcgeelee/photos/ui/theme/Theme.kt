package com.mcgeelee.photos.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Iris,
    onPrimary = Color.White,
    primaryContainer = IrisLight,
    onPrimaryContainer = Color(0xFF25205D),
    secondary = Color(0xFF606271),
    secondaryContainer = Color(0xFFE5E1F0),
    tertiary = Color(0xFF4D6638),
    tertiaryContainer = Meadow,
    onTertiaryContainer = Color(0xFF263D15),
    background = Canvas,
    surface = Canvas,
    onSurface = Ink,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F1FA),
    surfaceContainer = Color(0xFFEFEBF5),
    surfaceContainerHigh = Color(0xFFE9E5EF),
    surfaceContainerHighest = Color(0xFFE3DFEA),
)
private val DarkColors = darkColorScheme(
    primary = IrisDark,
    onPrimary = Color(0xFF2E286F),
    primaryContainer = Color(0xFF403C85),
    onPrimaryContainer = IrisLight,
    tertiary = Color(0xFFB9D29F),
    tertiaryContainer = Color(0xFF364F24),
    background = Color(0xFF14131A),
    surface = Color(0xFF14131A),
    onSurface = Color(0xFFE8E1EF),
    surfaceContainerLowest = Color(0xFF100F15),
    surfaceContainerLow = Color(0xFF1C1B23),
    surfaceContainer = Color(0xFF22212A),
    surfaceContainerHigh = Color(0xFF2D2B35),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhotosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors

    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = Typography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
