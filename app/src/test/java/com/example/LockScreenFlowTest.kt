package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.example.security.LockoutGuard
import com.example.ui.CyberToolkitViewModel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end tests of the dial lock, driven through the real UI.
 *
 * ### Why this exists
 * The lock is the most security-relevant screen in the app and it had no test at
 * all. The suite covered [com.example.security.CyberSecurityManager] and
 * [LockoutGuard] as units, so every seam between the composable, the ViewModel and
 * the guards was untested: a lockout that shows a banner without refusing the
 * correct code, a button that stays enabled, dials that never reach the verifier -
 * all of that would have shipped on a green build.
 *
 * ### Why every lookup uses a testTag
 * The lock screen is bilingual (`values` is French, `values-en` is English) and
 * Robolectric runs under `en`. An earlier draft of this suite matched on the
 * French `contentDescription` and silently found no node at all, so it looked
 * green while testing nothing. Tags are locale-independent and survive rewording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [36],
  // Robolectric's default device is 320x470, which is smaller than the lock
  // screen: the tumbler dials fall below the fold and every interaction fails
  // with "the component is not displayed". A realistic phone viewport keeps the
  // whole dial row on screen.
  qualifiers = "w411dp-h891dp-xhdpi"
)
class LockScreenFlowTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var application: Application
  private lateinit var viewModel: CyberToolkitViewModel

  @Before
  fun setUp() {
    application = ApplicationProvider.getApplicationContext()
    // A "fresh install" has to be faked: the manager and the guard are singletons
    // backed by fixed preference files. The whole directory is wiped rather than a
    // named file, because those names are `private const` and cannot be read back
    // for reflection; hard-coding them would silently weaken the test on rename.
    File(application.filesDir.parentFile, "shared_prefs").listFiles()?.forEach { it.delete() }
    viewModel = CyberToolkitViewModel(application, SavedStateHandle())
    composeTestRule.setContent {
      MyApplicationTheme { CyberToolkitApp(viewModel = viewModel) }
    }
    composeTestRule.waitForIdle()
  }

  private var steps = 0

  private fun guard(): LockoutGuard = LockoutGuard(application)

  // ---------------------------------------------------------------------------
  // Driving the tumbler dials
  // ---------------------------------------------------------------------------

  private fun dialControl(index: Int, increment: Boolean): SemanticsNodeInteraction =
    composeTestRule.onNodeWithTag("dial_${index}_${if (increment) "inc" else "dec"}")

  /**
   * Drives a dial to an absolute value, converging on what is actually rendered.
   *
   * A fixed number of clicks does not work: the dials keep their value after a
   * rejected attempt, and a click occasionally lands on the drum instead of the
   * arrow (the drum itself increments on tap), so "increment N times" silently
   * produced the wrong digit. This loop re-reads the accessibility label after
   * every step and corrects, which also handles the modulo wrap for free.
   */
  private fun setDial(index: Int, target: Int) {
    while (currentDigit(index) != target) {
      val current = currentDigit(index)
      dialControl(index, increment = current < target).performClick()
      composeTestRule.waitForIdle()
      steps++
      check(steps < 200) { "dial $index never reached $target (stuck on $current)" }
    }
  }

  /** Reads the selected digit off the dial's own accessibility label. */
  private fun currentDigit(index: Int): Int {
    val label = composeTestRule
      .onNodeWithTag("dial_${index}_value", useUnmergedTree = true)
      .fetchSemanticsNode()
      .config
      .getOrNull(SemanticsProperties.ContentDescription)
      ?.joinToString("")
      ?: error("dial $index exposes no digit")
    val prefix = application.getString(R.string.cd_dial_value, 0)
      .substringBefore("0")
      .trim()
    return label.removePrefix(prefix).trim().toIntOrNull()
      ?: error("cannot read a digit out of \"$label\"")
  }

  private fun setAllDials(digit: Int) {
    repeat(5) { setDial(it, digit) }
  }

  /** Resolved through the same resources the screen uses, so it matches any locale. */
  private fun digitLabel(digit: Int): String =
    application.getString(R.string.cd_dial_value, digit)

  private fun pressUnlock() {
    composeTestRule.onNodeWithTag("unlock_button").performClick()
  }

  /**
   * Waits for the PBKDF2 check (210k iterations, on `Dispatchers.Default`) to come
   * back and the guard to record the failure.
   *
   * The durable [LockoutGuard] state is the signal rather than any on-screen text:
   * it is what the attacker's outcome actually depends on, and it removes the
   * locale dependency.
   */
  private fun awaitRejected(expectedAttemptsLeft: Int) {
    composeTestRule.waitUntil(timeoutMillis = 20_000) {
      guard().state().attemptsLeft == expectedAttemptsLeft
    }
    composeTestRule.waitForIdle()
  }

  /**
   * Waits for the *rendered* lockout, not for the stored attempt counter.
   *
   * The third failure writes the counter and updates the composable's state in two
   * separate steps, so there is a window where the preference file already says
   * "0 attempts left" while the dials are still enabled. Asserting on the counter
   * alone made this suite pass or fail depending on scheduling: it passed under
   * `testDebugUnitTest` and failed under `verifyRoborazziDebug`. The banner is
   * what the operator actually sees, so that is what the wait is on.
   */
  private fun awaitLockedOut() {
    composeTestRule.waitUntil(timeoutMillis = 20_000) {
      composeTestRule.onAllNodesWithTagOrEmpty("lockout_banner").isNotEmpty()
    }
    composeTestRule.waitForIdle()
  }

  private fun awaitAccepted() {
    composeTestRule.waitUntil(timeoutMillis = 20_000) { viewModel.isUnlocked }
    composeTestRule.waitForIdle()
  }

  private fun failWith(digit: Int, expectedAttemptsLeft: Int) {
    setAllDials(digit)
    pressUnlock()
    awaitRejected(expectedAttemptsLeft)
    if (expectedAttemptsLeft == 0) awaitLockedOut()
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `a fresh install starts locked with a full attempt budget`() {
    composeTestRule.onNodeWithTag("dial_0").assertIsDisplayed()
    composeTestRule.onNodeWithTag("unlock_button").assertIsDisplayed()
    assertEquals("a fresh process must never be unlocked", false, viewModel.isUnlocked)
    assertEquals(3, guard().state().attemptsLeft)
  }

  @Test
  fun `the factory code unlocks the app`() {
    setAllDials(1)
    pressUnlock()
    awaitAccepted()

    assertTrue(
      "the lock screen is still on screen after the correct code",
      composeTestRule.onAllNodesWithTagOrEmpty("dial_0").isEmpty()
    )
    composeTestRule.onAllNodesWithTagOrEmpty("unlock_button").isEmpty()
  }

  @Test
  fun `a wrong code is rejected and the attempt budget drops`() {
    failWith(digit = 2, expectedAttemptsLeft = 2)
    assertEquals(false, viewModel.isUnlocked)
    composeTestRule.onNodeWithTag("lock_status").assertIsDisplayed()
  }

  @Test
  fun `three wrong codes lock the dial out and disable the button`() {
    failWith(2, expectedAttemptsLeft = 2)
    failWith(2, expectedAttemptsLeft = 1)
    failWith(2, expectedAttemptsLeft = 0)

    assertTrue("the guard should be locked out", guard().state().lockedOut)
    composeTestRule.onNodeWithTag("lockout_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("unlock_button").assertIsNotEnabled()
    assertEquals(false, viewModel.isUnlocked)
  }

  @Test
  fun `a locked-out lock cannot be talked out of its verdict`() {
    failWith(2, expectedAttemptsLeft = 2)
    failWith(2, expectedAttemptsLeft = 1)
    failWith(2, expectedAttemptsLeft = 0)

    // The dials are frozen, so the correct code cannot even be dialled in. The
    // guards reject the taps rather than accepting them and ignoring the result,
    // which means the lockout cannot be bypassed by brute-forcing the wheels.
    composeTestRule.onNodeWithTag("dial_0_inc").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("dial_0_dec").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("unlock_button").assertIsNotEnabled()
    assertEquals("a locked-out lock became unlocked", false, viewModel.isUnlocked)

    // And the verdict is durable: a fresh guard still refuses.
    assertTrue("the lockout did not survive in durable state", guard().state().lockedOut)
  }

  @Test
  fun `the dials do not move while locked out`() {
    failWith(2, expectedAttemptsLeft = 2)
    failWith(2, expectedAttemptsLeft = 1)
    failWith(2, expectedAttemptsLeft = 0)

    // The dial arrows are disabled, not merely ignored: assert the control itself.
    composeTestRule.onNodeWithTag("dial_0_inc").assertIsNotEnabled()
  }

  @Test
  fun `a successful unlock resets the attempt budget on disk`() {
    failWith(2, expectedAttemptsLeft = 2)
    failWith(2, expectedAttemptsLeft = 1)

    setAllDials(1)
    pressUnlock()
    awaitAccepted()

    // Durable state, so prove it was cleared rather than only recomposed away.
    val state = guard().state()
    assertEquals("the attempt budget was not reset by the successful unlock", 3, state.attemptsLeft)
    assertTrue("the guard should not be locked out", !state.lockedOut)
  }

  @Test
  fun `the dials wrap around in both directions`() {
    // A bare onNodeWithText("9") is ambiguous here: the drum renders the
    // neighbouring digits too, so several nodes match. The dial's own
    // accessibility label reports the selected value unambiguously.
    assertEquals(0, currentDigit(0))

    // 0 - 1 wraps to 9.
    dialControl(0, increment = false).performClick()
    composeTestRule.waitForIdle()
    assertEquals("0 - 1 must wrap to 9", 9, currentDigit(0))

    // 9 + 1 wraps back to 0.
    dialControl(0, increment = true).performClick()
    composeTestRule.waitForIdle()
    assertEquals("9 + 1 must wrap to 0", 0, currentDigit(0))

    // And forwards past 9 without getting stuck.
    repeat(12) { dialControl(0, increment = true).performClick() }
    composeTestRule.waitForIdle()
    assertEquals("12 steps from 0 must land on 2", 2, currentDigit(0))
  }
}

/** `onAllNodesWithTag` that yields an empty list instead of throwing when absent. */
private fun ComposeTestRule.onAllNodesWithTagOrEmpty(tag: String): List<*> =
  onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
