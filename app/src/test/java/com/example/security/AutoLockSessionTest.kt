package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AutoLockSession].
 *
 * The rule this covers was previously an inline `DisposableEffect` in
 * `MainActivity`, so it had no coverage: only a device could exercise it. Each
 * case below is a real way a background transition can be observed, including the
 * clock-rewind bypass that the original wall-clock implementation allowed.
 */
class AutoLockSessionTest {

  /** A monotonic clock the test drives by hand. */
  private class TestClock(var value: Long = 0L) {
    fun read(): Long = value
    fun advance(ms: Long) { value += ms }
  }

  private fun session(clock: TestClock) = AutoLockSession(clock::read)

  @Test
  fun `a session left open past the delay is re-locked`() {
    val clock = TestClock(1_000L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(30_000L)

    assertTrue(
      "30s away with a 30s delay must re-lock",
      autoLock.onStart(isUnlocked = true, delaySeconds = 30)
    )
  }

  @Test
  fun `a short trip outside does not re-lock`() {
    val clock = TestClock(1_000L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(5_000L)

    assertFalse(
      "5s away with a 30s delay must stay unlocked",
      autoLock.onStart(isUnlocked = true, delaySeconds = 30)
    )
  }

  @Test
  fun `the boundary instant counts as elapsed`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(30_000L)

    assertTrue(autoLock.onStart(isUnlocked = true, delaySeconds = 30))
  }

  @Test
  fun `one millisecond short of the delay does not re-lock`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(29_999L)

    assertFalse(autoLock.onStart(isUnlocked = true, delaySeconds = 30))
  }

  @Test
  fun `a zero delay re-locks on the next foreground`() {
    val clock = TestClock(500L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    assertTrue(autoLock.onStart(isUnlocked = true, delaySeconds = 0))
  }

  @Test
  fun `a negative delay is treated as immediate rather than never`() {
    val clock = TestClock(500L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    assertTrue(
      "a nonsense delay must fail closed",
      autoLock.onStart(isUnlocked = true, delaySeconds = -1)
    )
  }

  @Test
  fun `a foreground transition with no recorded absence does nothing`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    assertFalse(autoLock.onStart(isUnlocked = true, delaySeconds = 0))
    assertFalse(autoLock.isBackgrounded)
  }

  @Test
  fun `an already locked session is not stamped`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = false)
    assertFalse(autoLock.isBackgrounded)

    // Long absence while locked must not re-lock anything on the way back.
    clock.advance(600_000L)
    assertFalse(autoLock.onStart(isUnlocked = false, delaySeconds = 0))
  }

  @Test
  fun `an absence consumed once cannot re-lock a second time`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(60_000L)
    assertTrue(autoLock.onStart(isUnlocked = true, delaySeconds = 30))
    assertFalse(autoLock.isBackgrounded)

    // A spurious second ON_START must not re-lock: the absence was already used.
    assertFalse(autoLock.onStart(isUnlocked = true, delaySeconds = 30))
  }

  @Test
  fun `consecutive absences are tracked independently`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(60_000L)
    assertTrue(autoLock.onStart(isUnlocked = true, delaySeconds = 30))

    // Second trip, this time short.
    autoLock.onStop(isUnlocked = true)
    clock.advance(1_000L)
    assertFalse(autoLock.onStart(isUnlocked = true, delaySeconds = 30))
  }

  @Test
  fun `reset forgets a pending absence`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    autoLock.reset()
    clock.advance(600_000L)

    assertFalse(autoLock.isBackgrounded)
    assertFalse(autoLock.onStart(isUnlocked = true, delaySeconds = 1))
  }

  /**
   * The regression this class was written for.
   *
   * The original implementation sampled `System.currentTimeMillis()`. Moving the
   * device clock backwards during the absence made `elapsed` negative, so
   * `elapsed >= delay` was false and the app stayed unlocked. Only a monotonic
   * source is immune, and a defensive test keeps the wall clock from creeping
   * back in.
   */
  @Test
  fun `a clock that jumps backwards still re-locks`() {
    val clock = TestClock(10_000_000L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    // The user sets the clock back an hour, or NTP corrects it.
    clock.value -= 3_600_000L

    assertTrue(
      "a rewound clock must not extend the session",
      autoLock.onStart(isUnlocked = true, delaySeconds = 30)
    )
  }

  @Test
  fun `a zero reading at the very start of boot is a real instant`() {
    // `elapsedRealtime()` returns 0 for the first milliseconds after boot. A 0L
    // sentinel would have hidden this absence entirely.
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    assertTrue(autoLock.isBackgrounded)
    clock.advance(60_000L)
    assertTrue(autoLock.onStart(isUnlocked = true, delaySeconds = 30))
  }

  @Test
  fun `the delay is interpreted in seconds, not milliseconds`() {
    val clock = TestClock(0L)
    val autoLock = session(clock)

    autoLock.onStop(isUnlocked = true)
    clock.advance(1_500L)
    assertFalse("1.5s is under a 2s delay", autoLock.onStart(isUnlocked = true, delaySeconds = 2))
    assertEquals(2_000L, 2L * 1000L)
  }
}
