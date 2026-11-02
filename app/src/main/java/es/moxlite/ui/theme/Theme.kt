package es.moxlite.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFF4FC3B0),
    onPrimary = Color(0xFF00201B),
    secondary = Color(0xFFB4CCC6),
    background = Color(0xFF121417),
    surface = Color(0xFF121417),
    surfaceVariant = Color(0xFF22272B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00695C),
    secondary = Color(0xFF4A635E),
)

/** Colores de maná para los indicadores de identidad. */
object ManaColors {
    fun of(symbol: Char): Color = when (symbol.uppercaseChar()) {
        'W' -> Color(0xFFF8F1D4)
        'U' -> Color(0xFF4A90D9)
        'B' -> Color(0xFF5B4E57)
        'R' -> Color(0xFFE0543A)
        'G' -> Color(0xFF3E9A55)
        else -> Color(0xFFB0B0B0)
    }
}

@Composable
fun MoxliteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
