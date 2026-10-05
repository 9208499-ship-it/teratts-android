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
    val effectiveVoiceFile = voiceFile ?: remember(voiceFile) {
        context.getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
            .getString("selected_voice", "ru_f1.json")
    }

    val colorScheme = when {
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
