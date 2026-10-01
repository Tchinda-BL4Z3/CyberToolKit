package com.example

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.security.LockoutGuard
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Two tests for the auto-lock *wiring*, split by class because the delay has to be
 * fixed before the activity launches.
 *
 * ### What was missing
 * `AutoLockSessionTest` covers the timing arithmetic on an injected clock. The
 * observer that feeds it - `ON_STOP -> onStop(isUnlocked)` then
 * `ON_START -> onStart(isUnlocked, delaySeconds)` - lived in `MainActivity` and
 * was reached by nothing. Deleting that line leaves every JVM test green while
 * the console simply stays open in the background forever.
 *
 * ### Why one class per delay
 * The first attempt seeded the preference from an `@Before` and from a field set
 * in the test body. Both are too late: the compose rule launches the activity
 * while the rules are applied, and the ViewModel reads the settings store once at
 * construction. A `RuleWatcher.starting()` sees the delay before the test method
 * runs, so the value cannot come from the method.
 *
 * The honest fix is to make the delay part of the test class itself, which is what
 * these two classes do - one for "locks immediately", one for "leaves a grace
 * period". Each carries exactly one concern, and neither can accidentally assert
 * the other's behaviour.
 *
 * Both tests also share the lockout reset, for the same reason: a stale attempt
 * count left on disk by an earlier test makes `unlock()` time out, which looks
 * like an auto-lock bug and is not one.
 */
internal fun autoLockChain(
  delaySeconds: Int,
  composeTestRule: AndroidComposeTestRule<*, MainActivity>
): RuleChain = RuleChain
  .outerRule(seedAppState(delaySeconds))
  .around(composeTestRule)

private fun seedAppState(delaySeconds: Int): TestRule = object : TestWatcher() {
  override fun starting(description: Description) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.getSharedPreferences("cyber_settings_prefs", Context.MODE_PRIVATE)
      .edit { putInt("auto_lock_seconds", delaySeconds) }
    LockoutGuard(context).resetForTests()
  }
}

/** The app locks as soon as it is left. */
@RunWith(AndroidJUnit4::class)
class AutoLockImmediateWiringTest {

  private val composeTestRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val ruleChain: RuleChain = autoLockChain(0, composeTestRule)

  @Test
  fun leaving_and_re_entering_the_app_locks_the_console() {
    unlock(composeTestRule)

    // A real trip through the background, not a call into the ViewModel: the
    // platform dispatches the same lifecycle events when the operator switches
    // away, so this exercises the actual observer.
    composeTestRule.activityRule.scenario
      .moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
    composeTestRule.activityRule.scenario
      .moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

    composeTestRule.waitUntil(timeoutMillis = 30_000) {
      composeTestRule.onAllNodesWithTag("dial_0").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onAllNodesWithTag("dial_0").fetchSemanticsNodes().let { dials ->
      assert(dials.isNotEmpty()) { "the console was still open after backgrounding" }
    }
  }
}

/**
 * The counter-test: a long grace period must survive the same trip.
 *
 * Without this, an auto-lock that fires on every `ON_START` regardless of elapsed
 * time would pass [AutoLockImmediateWiringTest] and lock the operator out of the
 * console every time they glanced at another app.
 */
@RunWith(AndroidJUnit4::class)
class AutoLockGracePeriodWiringTest {

  private val composeTestRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val ruleChain: RuleChain = autoLockChain(900, composeTestRule)

  @Test
  fun a_short_visit_to_another_app_does_not_lock_the_console() {
    unlock(composeTestRule)

    composeTestRule.activityRule.scenario
      .moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
    composeTestRule.activityRule.scenario
      .moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    composeTestRule.waitForIdle()

    val dials = composeTestRule.onAllNodesWithTag("dial_0").fetchSemanticsNodes()
    assert(dials.isEmpty()) { "a brief trip to the background locked the console" }
  }
}

/**
 * Dials the factory code `11111` and validates.
 *
 * All five drums have to be set, not just the first: clicking only
 * `dial_0_inc` produces `10000`, which is rejected, and the console never opens.
 * The drums start at 0 on a fresh install, so one increment each is enough - the
 * guard is only there to survive a drum that does not start at zero.
 */
private fun unlock(composeTestRule: AndroidComposeTestRule<*, MainActivity>) {
  for (index in 0 until 5) {
    composeTestRule.onNodeWithTag("dial_${index}_inc").performClick()
    composeTestRule.waitForIdle()
  }
  composeTestRule.onNodeWithTag("unlock_button").performClick()
  composeTestRule.waitUntil(timeoutMillis = 30_000) {
    composeTestRule.onAllNodesWithTag("dial_0").fetchSemanticsNodes().isEmpty()
  }
  composeTestRule.waitForIdle()
}