package xin.dponnood.remoteservice.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val RemoteServicesLightColors = lightColorScheme(
    primary = Color(0xFF2F6B5D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EAE0),
    onPrimaryContainer = Color(0xFF173D32),
    secondary = Color(0xFF5B6B62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EAE3),
    onSecondaryContainer = Color(0xFF26352D),
    tertiary = Color(0xFF806548),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E4D4),
    onTertiaryContainer = Color(0xFF3A2B1D),
    background = Color(0xFFF5F6F1),
    surface = Color.White.copy(alpha = 0.82f),
    surfaceVariant = Color(0xFFE8EBE5),
    onSurface = Color(0xFF1B211D),
    onSurfaceVariant = Color(0xFF556159),
    outline = Color(0xFF7E8980),
    outlineVariant = Color(0xFFC6CEC6),
    surfaceContainerLowest = Color.White.copy(alpha = 0.52f),
    surfaceContainerLow = Color.White.copy(alpha = 0.64f),
    surfaceContainer = Color.White.copy(alpha = 0.73f),
    surfaceContainerHigh = Color.White.copy(alpha = 0.82f),
    surfaceContainerHighest = Color.White.copy(alpha = 0.90f),
)

private val RemoteServicesDarkColors = darkColorScheme(
    primary = Color(0xFFA6D6C8),
    onPrimary = Color(0xFF153B31),
    primaryContainer = Color(0xFF315C50),
    onPrimaryContainer = Color(0xFFD4EEE4),
    secondary = Color(0xFFC0CEC4),
    onSecondary = Color(0xFF26362E),
    secondaryContainer = Color(0xFF34443B),
    onSecondaryContainer = Color(0xFFDCE8DF),
    tertiary = Color(0xFFD8BC9E),
    onTertiary = Color(0xFF3C2D1E),
    tertiaryContainer = Color(0xFF564431),
    onTertiaryContainer = Color(0xFFF3DFC6),
    background = Color(0xFF171B18),
    surface = Color(0xED202722),
    surfaceVariant = Color(0xFF3C453F),
    onSurface = Color(0xFFE7EAE5),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF929E95),
    outlineVariant = Color(0xFF515B53),
    surfaceContainerLowest = Color(0xCC202722),
    surfaceContainerLow = Color(0xD9222A25),
    surfaceContainer = Color(0xE0242D27),
    surfaceContainerHigh = Color(0xE82A332D),
    surfaceContainerHighest = Color(0xF0333C35),
)

/** Shared Material 3 theme for the Remote Services product. */
@Composable
fun RemoteServicesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    ambientMotionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) RemoteServicesDarkColors else RemoteServicesLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
    ) {
        val reduceMotion = rememberReduceMotion()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background),
        ) {
            RemoteServicesAmbientBackground(
                modifier = Modifier.fillMaxSize(),
                darkTheme = darkTheme,
                reduceMotion = reduceMotion || !ambientMotionEnabled,
            )
            content()
        }
    }
}
