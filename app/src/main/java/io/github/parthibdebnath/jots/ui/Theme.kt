package io.github.parthibdebnath.jots.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Accent = Color(0xFFEB7A1F)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4CC),
    onPrimaryContainer = Color(0xFF4A2300),
    secondary = Color(0xFF6B5B4E),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFF5E5E66),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F7F9),
    outline = Color(0xFFCACAD0),
    outlineVariant = Color(0xFFE3E3E8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF9A45),
    onPrimary = Color(0xFF3A1A00),
    primaryContainer = Color(0xFF5A2E06),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFD8C2B2),
    background = Color(0xFF0F0F11),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF2A2A30),
    onSurfaceVariant = Color(0xFFB4B4BC),
    surfaceContainer = Color(0xFF1B1B1F),
    surfaceContainerHigh = Color(0xFF232328),
    outline = Color(0xFF4A4A52),
    outlineVariant = Color(0xFF333339),
)

@Composable
fun JotsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Icons that Material's set doesn't have. */
object JotsIcons {
    val Eraser: ImageVector by lazy {
        ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineCap = StrokeCap.Round,
            ) {
                // A block eraser lying at an angle, with its rubber end marked off.
                moveTo(14.5f, 4.2f)
                lineTo(20.3f, 10f)
                lineTo(11.2f, 19.1f)
                lineTo(7.6f, 19.1f)
                lineTo(3.7f, 15.2f)
                close()
                moveTo(9.0f, 9.7f)
                lineTo(14.8f, 15.5f)
                moveTo(11.2f, 19.1f)
                lineTo(20.5f, 19.1f)
            }
        }.build()
    }

    val Lasso: ImageVector by lazy {
        ImageVector.Builder("Lasso", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero,
            ) {
                // A loop with a short tail, like a lasso drawn with a pen.
                moveTo(12f, 4.5f)
                curveTo(17.2f, 4.5f, 20.5f, 6.9f, 20.5f, 9.6f)
                curveTo(20.5f, 12.3f, 17.2f, 14.6f, 12f, 14.6f)
                curveTo(6.8f, 14.6f, 3.5f, 12.3f, 3.5f, 9.6f)
                curveTo(3.5f, 6.9f, 6.8f, 4.5f, 12f, 4.5f)
                moveTo(8.2f, 14.1f)
                curveTo(7.0f, 16.0f, 8.6f, 17.6f, 10.4f, 16.9f)
                moveTo(10.4f, 16.9f)
                curveTo(9.6f, 18.6f, 9.9f, 19.8f, 11.2f, 20.6f)
            }
        }.build()
    }
}
