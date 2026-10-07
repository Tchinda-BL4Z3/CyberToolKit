package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Material 3 scheme derived from the active [CyberPalette], so the handful of
 * components that read `MaterialTheme.colorScheme` (ripples, disabled states,
 * the dropdown surface) stay in step with the hand-rolled tokens the screens use.
 */
fun cyberColorScheme(palette: CyberPalette) = darkColorScheme(
  primary = palette.primary,
  onPrimary = Color(0xFF04140C),
  primaryContainer = palette.primaryDim,
  onPrimaryContainer = palette.primary,
  secondary = palette.primary,
  onSecondary = Color(0xFF04140C),
  background = palette.bg,
  onBackground = palette.foreground,
  surface = palette.surface,
  onSurface = palette.foreground,
  surfaceVariant = palette.surfaceRaised,
  onSurfaceVariant = palette.mutedForeground,
  surfaceContainer = palette.surface,
  surfaceContainerHigh = palette.surfaceRaised,
  surfaceContainerHighest = palette.surfaceRaised,
  outline = palette.border,
  outlineVariant = palette.border,
  error = palette.destructive,
  onError = Color.White
)

/**
 * Root theme. The app is dark-only by design (it is a tactical console), so the
 * system light mode is intentionally not consulted.
 */
@Composable
fun CyberToolkitTheme(
  theme: CyberTheme = CyberTheme.CYBER_GREEN,
  content: @Composable () -> Unit
) {
  val palette = paletteFor(theme)
  CompositionLocalProvider(LocalCyberPalette provides palette) {
    MaterialTheme(
      colorScheme = cyberColorScheme(palette),
      typography = Typography,
      content = content
    )
  }
}

/**
 * Back-compat alias kept so the existing screenshot test and any previews keep
 * working. Equivalent to `CyberToolkitTheme(CyberTheme.CYBER_GREEN)`.
 */
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  CyberToolkitTheme(theme = CyberTheme.CYBER_GREEN, content = content)
}
