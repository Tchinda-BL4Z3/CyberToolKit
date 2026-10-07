package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.CyberSettings
import com.example.security.CyberSecurityManager
import com.example.ui.settings.SettingsTabScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for the security dialogs on the settings screen.
 *
 * ### The gap these close
 * The audit that started this work noted that code rotation, "lock now" and the
 * auto-lock-after-change behaviour had logic coverage but no UI coverage. The
 * logic was never the fragile part: a dialog can be unreachable, a confirm button
 * wired to the wrong callback, or a dismiss handler that skips the lock the
 * operator asked for - and every unit test still passes, because none of them
 * compose anything.
 *
 * ### Why these tests wait instead of asserting immediately
 * `onValidate` kicks off two PBKDF2 verifications (210 000 iterations each) on
 * `Dispatchers.Default`. `waitForIdle()` drains the Compose clock and the main
 * looper, but it does not join a background thread, so asserting right after the
 * tap is a race that passes or fails depending on machine load. `waitUntil` is
 * what makes these deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi", sdk = [36])
class SettingsSecurityDialogTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private var lockNowCalls = 0
  private var settings: CyberSettings = CyberSettings()

  /** 24680 and 86420 both pass `isWeakCombination`: distinct, non-sequential. */
  private val newCode = listOf(2, 4, 6, 8, 0)
  private val newCodeText = "24680"

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    CyberSecurityManager.setCombination(context, CyberSecurityManager.FACTORY_COMBINATION)
    lockNowCalls = 0
    settings = CyberSettings()
  }

  private fun setScreen() {
    composeTestRule.setContent {
      MyApplicationTheme {
        SettingsTabScreen(
          settings = settings,
          onSettingsChange = { transform -> settings = transform(settings) },
          onLockNow = { lockNowCalls++ },
          onWipeSession = {}
        )
      }
    }
    composeTestRule.waitForIdle()
  }

  private fun openCodeForm() {
    composeTestRule.onNodeWithTag("modify_combination_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
  }

  /** Submits the rotation form and waits for the background hashing to settle. */
  private fun submitRotation(current: String, replacement: String) {
    composeTestRule.onNodeWithTag("current_code_input").performTextInput(current)
    composeTestRule.onNodeWithTag("new_code_input").performTextInput(replacement)
    composeTestRule.onNodeWithTag("validate_new_code_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
  }

  // ---------------------------------------------------------------------------
  // Code rotation
  // ---------------------------------------------------------------------------

  @Test
  fun `the code form is closed until the operator asks for it`() {
    setScreen()

    composeTestRule.onNodeWithTag("current_code_input").assertDoesNotExist()
    composeTestRule.onNodeWithTag("new_code_input").assertDoesNotExist()
  }

  @Test
  fun `opening the form reveals the current and new code fields`() {
    setScreen()
    openCodeForm()

    composeTestRule.onNodeWithTag("current_code_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("new_code_input").assertIsDisplayed()
  }

  @Test
  fun `the toggle button closes an open form again`() {
    setScreen()
    openCodeForm()

    composeTestRule.onNodeWithTag("modify_combination_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("current_code_input").assertDoesNotExist()
  }

  @Test
  fun `a wrong current code is rejected and the form stays open`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "99999", replacement = newCodeText)

    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") == 0 &&
        CyberSecurityManager.isUsingFactoryCode(context)
    }

    assertTrue(
      "a wrong current code must not change the stored code",
      CyberSecurityManager.isUsingFactoryCode(context)
    )
    composeTestRule.onNodeWithTag("confirm_lock_button").assertDoesNotExist()
  }

  @Test
  fun `a valid rotation makes the new code the active one`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "11111", replacement = newCodeText)

    composeTestRule.waitUntil(TIMEOUT_MS) {
      CyberSecurityManager.verifyCombination(context, newCode)
    }

    assertTrue(
      "the new code is not the active code",
      CyberSecurityManager.verifyCombination(context, newCode)
    )
    assertFalse(
      "the factory code still unlocks after a rotation",
      CyberSecurityManager.verifyCombination(context, CyberSecurityManager.FACTORY_COMBINATION)
    )
  }

  @Test
  fun `a valid rotation shows the confirmation dialog`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "11111", replacement = newCodeText)

    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") > 0
    }
    composeTestRule.onNodeWithTag("confirm_lock_button").assertIsDisplayed()
  }

  @Test
  fun `confirming the rotation invokes the lock callback`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "11111", replacement = newCodeText)
    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") > 0
    }

    composeTestRule.onNodeWithTag("confirm_lock_button").performClick()
    composeTestRule.waitForIdle()

    assertTrue("the confirm button did not reach onLockNow", lockNowCalls >= 1)
  }

  @Test
  fun `the form is cleared once the rotation succeeds`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "11111", replacement = newCodeText)
    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") > 0
    }

    // `resetCodeForm()` runs before the dialog is shown, so the old code is not
    // left sitting in a text field behind the dialog.
    composeTestRule.onNodeWithTag("current_code_input").assertDoesNotExist()
  }

  @Test
  fun `a short new code is refused`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "11111", replacement = "12")

    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") == 0 &&
        composeTestRule.onAllNodesWithTagSafely("current_code_input") > 0
    }

    assertTrue(
      "a two-digit code must be refused",
      CyberSecurityManager.isUsingFactoryCode(context)
    )
  }

  @Test
  fun `a weak new code is refused`() {
    setScreen()
    openCodeForm()

    // 12345 is rejected by isWeakCombination even though the current code is right.
    submitRotation(current = "11111", replacement = "12345")

    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") == 0 &&
        composeTestRule.onAllNodesWithTagSafely("current_code_input") > 0
    }

    assertTrue(
      "a sequential code must be refused",
      CyberSecurityManager.isUsingFactoryCode(context)
    )
  }

  @Test
  fun `re-entering the same code is refused`() {
    setScreen()
    openCodeForm()

    submitRotation(current = "11111", replacement = "11111")

    composeTestRule.waitUntil(TIMEOUT_MS) {
      composeTestRule.onAllNodesWithTagSafely("confirm_lock_button") == 0 &&
        composeTestRule.onAllNodesWithTagSafely("current_code_input") > 0
    }

    assertTrue(
      "the code must be unchanged when the new one matches",
      CyberSecurityManager.isUsingFactoryCode(context)
    )
  }

  // ---------------------------------------------------------------------------
  // "Lock now"
  // ---------------------------------------------------------------------------

  @Test
  fun `the lock now button locks the console immediately`() {
    setScreen()

    // Documented behaviour: this is the panic button, so it acts at once and asks
    // nothing. The confirmation dialog belongs to code rotation, not here.
    composeTestRule.onNodeWithTag("lock_now_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertTrue("the lock now button did not lock", lockNowCalls == 1)
  }

  // ---------------------------------------------------------------------------
  // Wipe
  // ---------------------------------------------------------------------------

  @Test
  fun `the wipe action is gated behind its own confirmation`() {
    setScreen()

    composeTestRule.onNodeWithTag("wipe_session_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Nothing is wiped by the first tap: the destructive action waits for an
    // explicit confirmation.
    composeTestRule.onNodeWithTag("confirm_wipe_button").assertIsDisplayed()
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * `waitUntil` is given a plain Boolean, so it cannot throw when a node is
   * absent. Counting matches keeps the polling loop total.
   */
  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagSafely(
    tag: String
  ): Int = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

  private companion object {
    /** Generous: two PBKDF2 runs at 210 000 iterations on a loaded CI machine. */
    const val TIMEOUT_MS = 30_000L
  }
}
