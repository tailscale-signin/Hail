package com.aistra.hail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.aistra.hail.R

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = remember(context, LocalConfiguration.current, dark) {
        fun color(id: Int) = Color(ContextCompat.getColor(context, id))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = color(R.color.md_theme_primary),
            onPrimary = color(R.color.md_theme_onPrimary),
            primaryContainer = color(R.color.md_theme_primaryContainer),
            onPrimaryContainer = color(R.color.md_theme_onPrimaryContainer),
            secondary = color(R.color.md_theme_secondary),
            onSecondary = color(R.color.md_theme_onSecondary),
            secondaryContainer = color(R.color.md_theme_secondaryContainer),
            onSecondaryContainer = color(R.color.md_theme_onSecondaryContainer),
            tertiary = color(R.color.md_theme_tertiary),
            onTertiary = color(R.color.md_theme_onTertiary),
            tertiaryContainer = color(R.color.md_theme_tertiaryContainer),
            onTertiaryContainer = color(R.color.md_theme_onTertiaryContainer),
            error = color(R.color.md_theme_error),
            onError = color(R.color.md_theme_onError),
            errorContainer = color(R.color.md_theme_errorContainer),
            onErrorContainer = color(R.color.md_theme_onErrorContainer),
            background = color(R.color.md_theme_background),
            onBackground = color(R.color.md_theme_onBackground),
            surface = color(R.color.md_theme_surface),
            onSurface = color(R.color.md_theme_onSurface),
            surfaceVariant = color(R.color.md_theme_surfaceVariant),
            onSurfaceVariant = color(R.color.md_theme_onSurfaceVariant),
            outline = color(R.color.md_theme_outline),
            outlineVariant = color(R.color.md_theme_outlineVariant),
            scrim = color(R.color.md_theme_scrim),
            inverseSurface = color(R.color.md_theme_inverseSurface),
            inverseOnSurface = color(R.color.md_theme_inverseOnSurface),
            inversePrimary = color(R.color.md_theme_inversePrimary),
            surfaceDim = color(R.color.md_theme_surfaceDim),
            surfaceBright = color(R.color.md_theme_surfaceBright),
            surfaceContainerLowest = color(R.color.md_theme_surfaceContainerLowest),
            surfaceContainerLow = color(R.color.md_theme_surfaceContainerLow),
            surfaceContainer = color(R.color.md_theme_surfaceContainer),
            surfaceContainerHigh = color(R.color.md_theme_surfaceContainerHigh),
            surfaceContainerHighest = color(R.color.md_theme_surfaceContainerHighest),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
