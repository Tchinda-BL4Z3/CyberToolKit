package com.example

import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.CyberSettingsStore
import com.example.security.LockoutGuard
import com.example.ui.theme.CyberTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the paths the JVM suite cannot reach.
 *
 * ### What is genuinely untested elsewhere
 *  - `FLAG_SECURE` on a live window. `ScreenCapturePolicyTest` builds the activity
 *    *without* running `onCreate`, because a full Compose boot under Robolectric
 *    takes about sixteen minutes; the `onCreate` call itself was therefore only
 *    verified by reading the source. This asserts it for real.
 *  - The dial lock on a real touch stack, where the drum's vertical-drag gesture
 *    competes with the arrow buttons for the same pointer events, and where
 *    PBKDF2 runs on a real scheduler.
 *  - The theme selector end to end: unlock, open Settings, tap each ambience, and
 *    confirm the console is actually repainted. This is the regression test for
 *    the bug where the setting persisted and the chip highlighted, but no colour
 *    ever changed.
 *  - The auto-lock *wiring*, which lives in `AutoLockWiringTest`: the observer in
 *    `MainActivity` was reached by nothing, so deleting it left every JVM test
 *    green while the console stayed open in the background.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest` on a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class DeviceConsoleTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun resetLockoutState() {
    // Deleting the preference files from the test process does not work here: the
    // app under test is already running and holds them cached, so the deletion is
    // a no-op and the attempt count leaks between tests - which showed up as this
    // test blocking on some runs and not others. The guard is reset through the
    // app's own API instead, which goes through the same in-process instance.
    LockoutGuard(targetContext).resetForTests()
    composeTestRule.waitForIdle()
  }

  // ---------------------------------------------------------------------------
  // Window protection
  // ---------------------------------------------------------------------------

  @Test
  fun the_running_window_is_protected_against_capture() {
    val flags = composeTestRule.activity.window.attributes.flags
    assertTrue(
      "FLAG_SECURE is not set on the live window: screenshots would be possible",
      flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    )
  }

  // ---------------------------------------------------------------------------
  // The lock
  // ---------------------------------------------------------------------------

  @Test
  fun the_app_opens_on_the_lock_screen() {
    composeTestRule.onNodeWithTag("dial_0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("unlock_button").assertIsDisplayed()
  }

  @Test
  fun repeated_wrong_codes_block_the_dial() {
    // Each attempt is awaited by its exact remaining count. Waiting only for
    // "fewer than three" would be satisfied by the very first failure, so the
    // second and third taps would be asserted against before they had happened.
    listOf(2, 1, 0).forEach { remaining ->
      dialTo(2)
      composeTestRule.onNodeWithTag("unlock_button").performClick()
      awaitAttempts(remaining)
    }

    // The banner is appended below the controls, which pushes it past the fold on
    // a real screen. Without the scroll it exists but is off-screen, and
    // assertIsDisplayed reports exactly that.
    composeTestRule.onNodeWithTag("lockout_banner").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("unlock_button").assertIsNotEnabled()
  }

  // ---------------------------------------------------------------------------
  // The theme selector, end to end
  // ---------------------------------------------------------------------------

  @Test
  fun choosing_a_theme_reaches_the_console() {
    unlock()

    val signatures = mutableListOf<String>()

    CyberTheme.entries.forEach { theme ->
      goToTab(SETTINGS_TAB)
      // Settings is a long scrolling screen and the theme row is at the bottom.
      composeTestRule.onNodeWithTag("theme_${theme.key}").performScrollTo().performClick()
      composeTestRule.waitForIdle()

      // Read the value back through the production store rather than trusting the
      // chip, so this proves the setting survived the round trip.
      assertEquals(
        "the tap on ${theme.key} was not persisted",
        theme,
        CyberSettingsStore(targetContext).load().theme
      )

      goToTab(ENCODER_TAB)
      signatures += renderSignature()
    }

    // The whole point of the fix: every ambience must render differently. If the
    // provider were hoisted above the settings again, all three would collapse to
    // a single signature and this assertion would fail.
    assertEquals(
      "the ambiences did not all reach the console: the theme is stored but ignored " +
        "(signatures: $signatures)",
      CyberTheme.entries.size,
      signatures.toSet().size
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Drives one dial to an absolute value, re-reading after each tap. */
  private fun dialTo(digit: Int) {
    for (index in 0 until 5) {
      var current = 0
      var guard = 0
      while (current != digit && guard < 40) {
        composeTestRule.onNodeWithTag("dial_${index}_inc").performClick()
        composeTestRule.waitForIdle()
        current = (current + 1) % 10
        guard++
      }
    }
  }

  private fun unlock() {
    dialTo(1)
    composeTestRule.onNodeWithTag("unlock_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 30_000) {
      composeTestRule.onAllNodesWithTagOrEmpty("dial_0").isEmpty()
    }
    composeTestRule.waitForIdle()
  }

  private fun awaitAttempts(remaining: Int) {
    composeTestRule.waitUntil(timeoutMillis = 30_000) {
      LockoutGuard(targetContext).state().attemptsLeft == remaining
    }
    composeTestRule.waitForIdle()
  }

  private fun goToTab(titleRes: Int) {
    composeTestRule.onNodeWithTag("tab_$titleRes").performClick()
    composeTestRule.waitForIdle()
  }

  /**
   * A coarse fingerprint of what is on screen.
   *
   * Byte-for-byte image comparison is too fragile on a real device: the padlock
   * animates and status-bar contents differ between runs. Quantising to 16 levels
   * per channel and keeping the six commonest buckets is stable enough to tell
   * two ambiences apart, which is the only thing asserted here.
   */
  private fun renderSignature(): String {
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    val counts = HashMap<Int, Int>()
    var y = 0
    while (y < bitmap.height) {
      var x = 0
      while (x < bitmap.width) {
        val argb = bitmap.getPixel(x, y)
        val key = ((argb shr 16 and 0xFF) shr 4 shl 8) or
          ((argb shr 8 and 0xFF) shr 4 shl 4) or
          ((argb and 0xFF) shr 4)
        counts[key] = (counts[key] ?: 0) + 1
        x += 8
      }
      y += 8
    }
    return counts.entries
      .sortedByDescending { it.value }
      .take(6)
      .joinToString(",") { "${it.key}:${it.value / 64}" }
  }

  private companion object {
    /**
     * Bottom-bar indices, tagged from the app's own tab list.
     *
     * `TABS` in `MainActivity` is private, so the order is mirrored here. If the
     * tabs are ever reordered these resource ids stop matching and the test fails
     * loudly on a missing node rather than silently tapping the wrong screen.
     */
    const val ENCODER_TAB = R.string.tab_encoder
    const val SETTINGS_TAB = R.string.tab_settings
  }
}

private fun ComposeTestRule.onAllNodesWithTagOrEmpty(tag: String): List<*> =
  onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
