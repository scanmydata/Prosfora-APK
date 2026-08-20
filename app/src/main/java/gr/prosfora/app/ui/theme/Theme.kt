package gr.prosfora.app.ui.theme

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

// Χρώματα tovapsimo.gr — από το logo/splash
private val BrandGreen = Color(0xFF00E2A2)
private val BrandGreenDark = Color(0xFF00A87A)
private val Ink = Color(0xFF1E1E1E)

private val LightColors = lightColorScheme(
    primary = BrandGreenDark,
    onPrimary = Color.White,
    secondary = BrandGreen,
    onSecondary = Ink,
)

private val DarkColors = darkColorScheme(
    primary = BrandGreen,
    onPrimary = Ink,
    secondary = BrandGreenDark,
)

@Composable
fun ProsforaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
