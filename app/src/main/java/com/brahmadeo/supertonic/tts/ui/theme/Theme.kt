@file:Suppress("DEPRECATION")
package com.brahmadeo.supertonic.tts.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app's own colours, chosen in the settings («Оформление»): as before (by the voice, or the
 * wallpaper's colours on Android 12+), warm dark brown, or indigo; dark or light as the system has
 * it, or always one of them. (The brown one people took for "built in" came from the wallpaper.)
 */
object AppTheme {
    const val AUTO = "auto"
    const val BROWN = "brown"
    const val INDIGO = "indigo"
    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    val style = androidx.compose.runtime.mutableStateOf(AUTO)
    val mode = androidx.compose.runtime.mutableStateOf(MODE_SYSTEM)
    @Volatile private var loaded = false

    private fun p(c: android.content.Context) = c.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)

    fun load(c: android.content.Context) {
        if (loaded) return
        loaded = true
        style.value = p(c).getString("app_theme", AUTO)?.takeIf { it == BROWN || it == INDIGO } ?: AUTO
        mode.value = p(c).getString("app_theme_mode", MODE_SYSTEM)?.takeIf { it == MODE_DARK || it == MODE_LIGHT } ?: MODE_SYSTEM
    }

    fun setStyle(c: android.content.Context, s: String) { style.value = s; p(c).edit().putString("app_theme", s).apply() }
    fun setMode(c: android.content.Context, m: String) { mode.value = m; p(c).edit().putString("app_theme_mode", m).apply() }
}

private val BrownDarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFD9A55B),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF2A1D0E),
    secondary = androidx.compose.ui.graphics.Color(0xFFCBB59A),
    tertiary = androidx.compose.ui.graphics.Color(0xFFA9C79A),
    background = androidx.compose.ui.graphics.Color(0xFF1E1A16),
    surface = androidx.compose.ui.graphics.Color(0xFF1E1A16),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF3A322A),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF29231E),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF4A3A28),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF5A3F1E)
)

private val BrownLightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF8B5E34),
    secondary = androidx.compose.ui.graphics.Color(0xFF75604A),
    tertiary = androidx.compose.ui.graphics.Color(0xFF55704A),
    background = androidx.compose.ui.graphics.Color(0xFFF6EEDF),
    surface = androidx.compose.ui.graphics.Color(0xFFF6EEDF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE8DCC6),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEDE2CC),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE6D3B5),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFF0D7B4)
)

private val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    secondary = IndigoGrey80,
    tertiary = Teal80,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = Indigo40,
    secondary = IndigoGrey40,
    tertiary = Teal40,
    surface = SurfaceLight,
    surfaceContainer = SurfaceContainerLight
)

private val FemaleDarkColorScheme = darkColorScheme(
    primary = Pink80,
    secondary = PinkGrey80,
    tertiary = Teal80,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark
)

private val FemaleLightColorScheme = lightColorScheme(
    primary = Pink40,
    secondary = PinkGrey40,
    tertiary = Teal40,
    surface = SurfaceLight,
    surfaceContainer = SurfaceContainerLight
)

private val MaleDarkColorScheme = darkColorScheme(
    primary = Blue80,
    secondary = BlueGrey80,
    tertiary = Teal80,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark
)

private val MaleLightColorScheme = lightColorScheme(
    primary = Blue40,
    secondary = BlueGrey40,
    tertiary = Teal40,
    surface = SurfaceLight,
    surfaceContainer = SurfaceContainerLight
)

@Composable
fun SupertonicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    voiceFile: String? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    AppTheme.load(context)
    val chosen = AppTheme.style.value
    // dark or light: as the system has it, or as chosen
    val darkTheme = when (AppTheme.mode.value) { AppTheme.MODE_DARK -> true; AppTheme.MODE_LIGHT -> false; else -> darkTheme }
    val effectiveVoiceFile = voiceFile ?: remember(voiceFile) {
        context.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
            .getString("selected_voice", "ru_f1.json")
    }

    val colorScheme = when {
        chosen == AppTheme.BROWN -> if (darkTheme) BrownDarkColorScheme else BrownLightColorScheme
        chosen == AppTheme.INDIGO -> if (darkTheme) DarkColorScheme else LightColorScheme
        effectiveVoiceFile?.substringAfterLast('/')?.startsWith("F") == true -> {
            if (darkTheme) FemaleDarkColorScheme else FemaleLightColorScheme
        }
        effectiveVoiceFile?.substringAfterLast('/')?.startsWith("M") == true -> {
            if (darkTheme) MaleDarkColorScheme else MaleLightColorScheme
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            window?.let {
                val controller = WindowCompat.getInsetsController(it, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        typography = Typography,
        content = content
    )
}
