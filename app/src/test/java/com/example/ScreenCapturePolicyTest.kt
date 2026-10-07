package com.example

import android.view.WindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the screen-capture policy.
 *
 * ### Why this matters
 * `FLAG_SECURE` is what stops the console being photographed, screen-recorded or
 * mirrored, and it is the only thing between a stolen device and a gallery full
 * of screenshots. The helper existed with no test asserting the flag actually
 * lands on the window, so a refactor could have dropped it silently.
 *
 * ### Known gap
 * That `MainActivity.onCreate` calls this helper with `blocked = true` is *not*
 * covered here. Booting the activity under Robolectric takes about sixteen
 * minutes, because the lock screen runs infinite animations that never settle
 * under the test clock. That call is therefore verified by reading `onCreate`,
 * and on a real device by the instrumented test in `androidTest`. Everything the
 * helper itself can get wrong is covered below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenCapturePolicyTest {

  /**
   * Builds the activity *without* running `onCreate`.
   *
   * That is what keeps this fast: a full Compose boot under Robolectric costs
   * minutes for assertions a one-line `addFlags`/`clearFlags` pair cannot fail.
   */
  private fun withUnstartedActivity(block: (MainActivity) -> Unit) {
    val controller = Robolectric.buildActivity(MainActivity::class.java)
    try {
      block(controller.get())
    } finally {
      controller.destroy()
    }
  }

  private fun MainActivity.hasSecureFlag(): Boolean =
    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

  @Test
  fun `blocking capture sets FLAG_SECURE and clearing removes it`() {
    withUnstartedActivity { activity ->
      assertFalse("precondition: a bare window is not secure", activity.hasSecureFlag())

      activity.applyScreenCapturePolicy(blocked = true)
      assertTrue("blocking capture must set FLAG_SECURE", activity.hasSecureFlag())

      activity.applyScreenCapturePolicy(blocked = false)
      assertFalse("clearing the policy must remove FLAG_SECURE", activity.hasSecureFlag())
    }
  }

  @Test
  fun `re-applying the policy is idempotent and preserves other window flags`() {
    withUnstartedActivity { activity ->
      activity.applyScreenCapturePolicy(blocked = true)
      val before = activity.window.attributes.flags

      activity.applyScreenCapturePolicy(blocked = true)
      activity.applyScreenCapturePolicy(blocked = true)
      assertTrue("repeated application must keep the flag set", activity.hasSecureFlag())
      assertEqualsBits("repeated application changed other flags", before, activity.window.attributes.flags)

      activity.applyScreenCapturePolicy(blocked = false)
      activity.applyScreenCapturePolicy(blocked = true)
      assertTrue("a clear/set round-trip must end secure", activity.hasSecureFlag())
      assertEqualsBits("a clear/set round-trip changed other flags", before, activity.window.attributes.flags)
    }
  }

  /** Every window flag other than `FLAG_SECURE` must be left untouched. */
  private fun assertEqualsBits(message: String, expected: Int, actual: Int) {
    val stray = expected and actual.inv() and WindowManager.LayoutParams.FLAG_SECURE.inv()
    check(stray == 0) { "$message: 0x${stray.toString(16)} was dropped" }
  }
}
