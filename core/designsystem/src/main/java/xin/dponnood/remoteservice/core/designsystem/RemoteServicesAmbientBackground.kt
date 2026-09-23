package xin.dponnood.remoteservice.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max

/** A low-cost ambient light field behind the app's translucent surfaces. */
@Composable
fun RemoteServicesAmbientBackground(
    modifier: Modifier = Modifier,
    darkTheme: Boolean,
    reduceMotion: Boolean,
) {
    if (reduceMotion) {
        Canvas(modifier) { drawAmbientField(darkTheme, horizontalDrift = 0.5f, verticalDrift = 0.5f) }
    } else {
        val transition = rememberInfiniteTransition(label = "ambient-glass")
        val horizontalDrift by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 28_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambient-horizontal-drift",
        )
        val verticalDrift by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 34_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambient-vertical-drift",
        )
        Canvas(modifier) {
            drawAmbientField(darkTheme, horizontalDrift, verticalDrift)
        }
    }
}

private fun DrawScope.drawAmbientField(
    darkTheme: Boolean,
    horizontalDrift: Float,
    verticalDrift: Float,
) {
    val background = if (darkTheme) Color(0xFF171B18) else Color(0xFFF5F6F1)
    drawRect(background)

    val width = size.width
    val height = size.height
    val radius = max(width, height) * 0.58f
    if (darkTheme) {
        drawAmbientGlow(
            Color(0xFF64BFA8),
            Offset(width * (0.88f - horizontalDrift * 0.22f), height * (0.12f + verticalDrift * 0.22f)),
            radius,
            0.28f,
        )
        drawAmbientGlow(
            Color(0xFFC59A73),
            Offset(width * (0.12f + verticalDrift * 0.20f), height * (0.76f - horizontalDrift * 0.12f)),
            radius * 0.76f,
            0.16f,
        )
        drawAmbientGlow(
            Color(0xFF7595A2),
            Offset(width * (0.74f - verticalDrift * 0.14f), height * (0.90f - horizontalDrift * 0.18f)),
            radius * 0.68f,
            0.14f,
        )
    } else {
        drawAmbientGlow(
            Color(0xFF9CCFC0),
            Offset(width * (0.90f - horizontalDrift * 0.20f), height * (0.10f + verticalDrift * 0.24f)),
            radius,
            0.42f,
        )
        drawAmbientGlow(
            Color(0xFFE3C8AD),
            Offset(width * (0.12f + verticalDrift * 0.22f), height * (0.78f - horizontalDrift * 0.14f)),
            radius * 0.78f,
            0.28f,
        )
        drawAmbientGlow(
            Color(0xFFAEC9CF),
            Offset(width * (0.76f - verticalDrift * 0.14f), height * (0.92f - horizontalDrift * 0.18f)),
            radius * 0.68f,
            0.20f,
        )
    }
}

private fun DrawScope.drawAmbientGlow(
    color: Color,
    center: Offset,
    radius: Float,
    peakAlpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = peakAlpha),
                0.42f to color.copy(alpha = peakAlpha * 0.46f),
                1f to Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        center = center,
        radius = radius,
    )
}
