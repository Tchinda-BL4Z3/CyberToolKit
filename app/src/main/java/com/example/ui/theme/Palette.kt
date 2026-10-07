package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The full colour contract of the app, resolved for one of the three tactical
 * ambiences.
 *
 * There used to be two independent palettes in the codebase - one in
 * `MainActivity.kt` (`0xFF0B0F19` emerald) and one in `ui/theme/Color.kt`
 * (`0xFF0D1117` neon green) - and both exported a top-level `CyberBorder` with
 * *different values*. Anything importing the wrong one silently got the wrong
 * grey. There is now exactly one definition, consumed through
 * [LocalCyberPalette].
 */
@Immutable
data class CyberPalette(
  val bg: Color,
  val surface: Color,
  val surfaceRaised: Color,
  val terminalBg: Color,
  val terminalBorder: Color,
  val primary: Color,
  val primaryDim: Color,
  val foreground: Color,
  val mutedForeground: Color,
  val border: Color,
  val destructive: Color
)

enum class CyberTheme(val key: String, val label: String) {
  CYBER_GREEN("cyber_green", "Cyber Green"),
  TACTICAL_BLUE("tactical_blue", "Tactical Blue"),
  STEALTH("stealth", "Stealth");

  companion object {
    fun fromKey(key: String?): CyberTheme =
      entries.firstOrNull { it.key == key } ?: CYBER_GREEN
  }
}

/** Original emerald scheme: the app's identity colour. */
val CyberGreenPalette = CyberPalette(
  bg = Color(0xFF0B0F19),
  surface = Color(0xFF111827),
  surfaceRaised = Color(0xFF1A2138),
  terminalBg = Color(0xFF070A12),
  terminalBorder = Color(0xFF1F4D4D),
  primary = Color(0xFF10B981),
  primaryDim = Color(0xFF1A3D33),
  foreground = Color(0xFFF8FAFC),
  mutedForeground = Color(0xFF7E8AA8),
  border = Color(0xFF2A3559),
  destructive = Color(0xFFEF4444)
)

/** Cooler, higher-contrast scheme for bright environments. */
val TacticalBluePalette = CyberPalette(
  bg = Color(0xFF070C18),
  surface = Color(0xFF0E1626),
  surfaceRaised = Color(0xFF16233A),
  terminalBg = Color(0xFF05090F),
  terminalBorder = Color(0xFF1E3A5F),
  primary = Color(0xFF38BDF8),
  primaryDim = Color(0xFF12314A),
  foreground = Color(0xFFF1F5F9),
  mutedForeground = Color(0xFF8296B4),
  border = Color(0xFF24384F),
  destructive = Color(0xFFF87171)
)

/** Low-glare amber/olive scheme: less luminance for dark rooms. */
val StealthPalette = CyberPalette(
  bg = Color(0xFF0A0A0A),
  surface = Color(0xFF141414),
  surfaceRaised = Color(0xFF1F1F1F),
  terminalBg = Color(0xFF080808),
  terminalBorder = Color(0xFF4A3D1E),
  primary = Color(0xFFC9A227),
  primaryDim = Color(0xFF3A3011),
  foreground = Color(0xFFE8E6E0),
  mutedForeground = Color(0xFF8A867C),
  border = Color(0xFF33332F),
  destructive = Color(0xFFD97706)
)

fun paletteFor(theme: CyberTheme): CyberPalette = when (theme) {
  CyberTheme.CYBER_GREEN -> CyberGreenPalette
  CyberTheme.TACTICAL_BLUE -> TacticalBluePalette
  CyberTheme.STEALTH -> StealthPalette
}

/**
 * Provided once at the root by [CyberToolkitTheme].
 *
 * `staticCompositionLocalOf` is deliberate: the palette only changes when the
 * operator picks a theme, and every screen must be invalidated at that point, so
 * there is no benefit in tracking reads individually.
 */
val LocalCyberPalette = staticCompositionLocalOf { CyberGreenPalette }
