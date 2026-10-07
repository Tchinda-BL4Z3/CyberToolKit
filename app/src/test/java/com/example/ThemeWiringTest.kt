package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.CyberToolkitViewModel
import com.example.ui.theme.CyberTheme
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.paletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Non-regression test for the theme selector.
 *
 * ### The bug this pins
 * `MainActivity.onCreate` used to wrap the app in `CyberToolkitTheme { ... }`
 * with no argument, so it always resolved to `CyberTheme.CYBER_GREEN`. The
 * settings were collected *inside* that provider, one level below where they
 * were needed, so `settings.theme` could never reach the palette. Picking
 * "Tactical Blue" in Settings moved the selected chip - the chip reads
 * `settings.theme` to decide what to highlight - and changed no colour at all.
 *
 * ### Why this reads the rendered pixels
 * A test that only checks the setting round-trips through `CyberSettingsStore`
 * passes while the app stays green: the store was never the broken part, the
 * composition tree was. Reading `LocalCyberPalette` from a sibling of the app has
 * the same blind spot, because a sibling is outside the provider. So this renders
 * the real app and inspects what the user would actually see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ThemeWiringTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var application: Application

  @Before
  fun setUp() {
    application = ApplicationProvider.getApplicationContext()
  }

  /** Renders the app once; [capture] then samples the live composition. */
  private fun renderApp(viewModel: CyberToolkitViewModel, capture: () -> Unit) {
    composeTestRule.setContent {
      MyApplicationTheme { CyberToolkitApp(viewModel = viewModel) }
    }
    composeTestRule.waitForIdle()
    capture()
  }

  /**
   * Draws the real view hierarchy into a bitmap.
   *
   * `captureToImage()` is not usable here: it goes through `PixelCopy` and waits
   * for a window redraw that Robolectric never delivers, so it times out after
   * two seconds. Drawing the `View` tree manually produces the same pixels and
   * works under `@GraphicsMode(NATIVE)`.
   */
  private fun captureScreen(): Screen {
    val view: View = composeTestRule.activity.window.decorView
    if (view.width == 0 || view.height == 0) {
      val widthSpec = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
      val heightSpec = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
      view.measure(widthSpec, heightSpec)
      view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    val bitmap = Bitmap.createBitmap(
      view.width.coerceAtLeast(1),
      view.height.coerceAtLeast(1),
      Bitmap.Config.ARGB_8888
    )
    view.draw(Canvas(bitmap))
    return bitmap.sample()
  }

  /**
   * The most frequent colour on screen, i.e. the background.
   *
   * Sampling the whole screen rather than one guessed pixel keeps the assertion
   * stable against padding, insets and status-bar rendering. Returning the
   * dominant colour rather than the whole set matters too: the three ambiences
   * share near-identical dark surfaces, so a "this other theme is absent"
   * assertion over every sampled pixel produces false positives, while the
   * background is both unambiguous and what the operator actually sees.
   */
  private fun Bitmap.sample(): Screen {
    val counts = HashMap<Int, Int>()
    var y = 0
    while (y < height) {
      var x = 0
      while (x < width) {
        val argb = getPixel(x, y)
        counts[argb] = (counts[argb] ?: 0) + 1
        x += 4
      }
      y += 4
    }
    val dominant = counts.maxByOrNull { it.value }?.key
      ?: error("the composition rendered no pixels")
    return Screen(dominant = dominant, distinct = counts.keys)
  }

  private data class Screen(val dominant: Int, val distinct: Set<Int>)

  private fun Int.sameColour(color: Color, tolerance: Int = 2): Boolean {
    val target = color.toArgbCompat()
    val tr = (target shr 16) and 0xFF
    val tg = (target shr 8) and 0xFF
    val tb = target and 0xFF
    return abs(((this shr 16) and 0xFF) - tr) <= tolerance &&
      abs(((this shr 8) and 0xFF) - tg) <= tolerance &&
      abs((this and 0xFF) - tb) <= tolerance
  }

  private fun Color.toArgbCompat(): Int {
    val argb = android.graphics.Color.argb(
      (alpha * 255f).toInt().coerceIn(0, 255),
      (red * 255f).toInt().coerceIn(0, 255),
      (green * 255f).toInt().coerceIn(0, 255),
      (blue * 255f).toInt().coerceIn(0, 255)
    )
    return argb
  }

  private fun assertScreenUses(screen: Screen, expected: CyberTheme) {
    val palette = paletteFor(expected)
    assertTrue(
      "${expected.key}: the screen background is not that ambience's background " +
        "(expected ${palette.bg}, got ${screen.dominant})",
      screen.dominant.sameColour(palette.bg)
    )
    // The accent is what makes the three ambiences read as different at a glance,
    // so assert it reached the screen and not only the backdrop. Accents are
    // saturated and far apart, so a tight tolerance is safe here.
    assertTrue(
      "${expected.key}: the accent colour never reached the screen",
      screen.distinct.any { it.sameColour(palette.primary) }
    )
  }

  @Test
  fun `the default install renders the default ambience`() {
    val viewModel = CyberToolkitViewModel(application, SavedStateHandle())
    var screen: Screen? = null
    renderApp(viewModel) { screen = captureScreen() }
    assertScreenUses(requireNotNull(screen), CyberTheme.CYBER_GREEN)
  }

  @Test
  fun `choosing a theme repaints the screen`() {
    val viewModel = CyberToolkitViewModel(application, SavedStateHandle())
    var screen: Screen? = null

    renderApp(viewModel) { screen = captureScreen() }

    // Guard the premise: if two ambiences shared a background, a theme that was
    // ignored could not be detected here.
    CyberTheme.entries.forEach { theme ->
      CyberTheme.entries.filter { it != theme }.forEach { other ->
        assertNotEquals(
          "${theme.key} shares its background with ${other.key}",
          paletteFor(theme).bg,
          paletteFor(other).bg
        )
      }
    }

    CyberTheme.entries.forEach { theme ->
      viewModel.updateSettings { it.copy(theme = theme) }
      composeTestRule.waitForIdle()
      screen = captureScreen()
      assertScreenUses(requireNotNull(screen), theme)
    }
  }

  @Test
  fun `the chosen theme survives a restart`() {
    val first = CyberToolkitViewModel(application, SavedStateHandle())
    first.updateSettings { it.copy(theme = CyberTheme.STEALTH) }

    // A fresh ViewModel reads the store back, which is what happens after the
    // process is killed and relaunched.
    val afterRestart = CyberToolkitViewModel(application, SavedStateHandle())
    assertEquals(CyberTheme.STEALTH, afterRestart.settings.value.theme)

    var screen: Screen? = null
    renderApp(afterRestart) { screen = captureScreen() }
    assertScreenUses(requireNotNull(screen), CyberTheme.STEALTH)
  }

  @Test
  fun `every theme resolves to a distinct, fully opaque palette`() {
    val palettes = CyberTheme.entries.map { paletteFor(it) }
    assertEquals(CyberTheme.entries.size, palettes.map { it.primary }.toSet().size)
    palettes.forEach { palette ->
      assertEquals(1f, palette.primary.alpha, 0.001f)
      assertEquals(1f, palette.bg.alpha, 0.001f)
    }
  }

  @Test
  fun `an unrecognised stored key falls back to the default rather than throwing`() {
    assertEquals(CyberTheme.CYBER_GREEN, CyberTheme.fromKey("not_a_theme"))
    assertEquals(CyberTheme.CYBER_GREEN, CyberTheme.fromKey(null))
    assertEquals(CyberTheme.CYBER_GREEN, CyberTheme.fromKey(""))
    CyberTheme.entries.forEach { assertEquals(it, CyberTheme.fromKey(it.key)) }
  }
}
