package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression suite for the anti-bruteforce ledger.
 *
 * This is the ledger whose previous implementation lived in
 * `rememberSaveable`, i.e. inside the instance-state `Bundle`. That is why
 * force-stopping the app used to hand back a full three-attempt budget. The
 * counter now lives in `SharedPreferences`.
 *
 * Timestamps are injected through the `*At` seam rather than the real clock, so
 * the two paths that matter most - a wall clock moved backwards, and a reboot
 * that clears `elapsedRealtime` - are reachable from a JVM test at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockoutGuardTest {

  private lateinit var context: Context
  private lateinit var guard: LockoutGuard

  /** Fixed origin so the arithmetic below is readable. */
  private val boot = 1_700_000_000_000L
  private val up = 5_000L

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    guard = LockoutGuard(context)
    guard.resetForTests()
  }

  /** Spends the whole budget and returns the state that results. */
  private fun burnBudget(): LockoutGuard.State = burnBudget(boot, up)

  private fun burnBudget(wall: Long, mono: Long): LockoutGuard.State {
    var last = guard.stateAt(wall, mono)
    repeat(LockoutGuard.MAX_ATTEMPTS) { last = guard.registerFailureAt(wall, mono) }
    return last
  }

  // ---- attempt budget ------------------------------------------------------

  @Test
  fun `a fresh ledger grants the full attempt budget`() {
    val state = guard.stateAt(boot, up)
    assertFalse(state.lockedOut)
    assertEquals(3, state.attemptsLeft)
    assertEquals(3, state.maxAttempts)
  }

  @Test
  fun `attempts decrement one at a time`() {
    assertEquals(2, guard.registerFailureAt(boot, up).attemptsLeft)
    assertEquals(1, guard.registerFailureAt(boot, up).attemptsLeft)
    assertFalse(guard.stateAt(boot, up).lockedOut)
  }

  @Test
  fun `the third failure trips the lockout`() {
    val locked = burnBudget()
    assertTrue(locked.lockedOut)
    assertEquals(0, locked.attemptsLeft)
    assertEquals(10 * 60, locked.remainingSeconds)
  }

  @Test
  fun `failures during a lockout are ignored and do not extend it`() {
    burnBudget()
    val first = guard.stateAt(boot, up)
    val later = guard.stateAt(boot + 60_000L, up + 60_000L)
    assertEquals(10 * 60 - 60, later.remainingSeconds)
    // And a further failure must not push the deadline out.
    val afterExtra = guard.registerFailureAt(boot + 60_000L, up + 60_000L)
    assertEquals(later.remainingSeconds, afterExtra.remainingSeconds)
    assertTrue(first.remainingSeconds > later.remainingSeconds)
  }

  @Test
  fun `the lock lifts exactly when the deadline has passed`() {
    burnBudget()
    assertTrue(guard.stateAt(boot + 10 * 60_000L - 1L, up + 10 * 60_000L - 1L).lockedOut)
    val at = guard.stateAt(boot + 10 * 60_000L, up + 10 * 60_000L)
    assertFalse(at.lockedOut)
    // Budget is still spent; the operator has to wait and then try again.
    assertEquals(0, at.attemptsLeft)
  }

  // ---- persistence ---------------------------------------------------------

  @Test
  fun `the lockout survives a new guard instance, that is process death`() {
    burnBudget()
    val afterRestart = LockoutGuard(context)
    assertTrue(
      "the lockout must outlive the process",
      afterRestart.stateAt(boot, up).lockedOut
    )
    assertEquals(0, afterRestart.stateAt(boot, up).attemptsLeft)
  }

  @Test
  fun `a success clears the lockout and restores the budget`() {
    burnBudget()
    guard.registerSuccess()
    val state = guard.stateAt(boot + 60_000L, up + 60_000L)
    assertFalse(state.lockedOut)
    assertEquals(3, state.attemptsLeft)
    assertEquals(0, state.level)
  }

  // ---- clock tampering -----------------------------------------------------

  @Test
  fun `moving the wall clock backwards replays the lockout in full`() {
    burnBudget()
    val rolledBack = guard.stateAt(boot - 86_400_000L, up + 1_000L)
    assertTrue(rolledBack.lockedOut)
    assertTrue(rolledBack.clockRollbackDetected)
    assertTrue(
      "the full remaining time must be replayed, got ${rolledBack.remainingSeconds}",
      rolledBack.remainingSeconds >= 9 * 60
    )
  }

  @Test
  fun `a small wall-clock skew is not treated as tampering`() {
    burnBudget()
    val skewed = guard.stateAt(boot - 30_000L, up + 30_000L)
    assertFalse(skewed.clockRollbackDetected)
  }

  @Test
  fun `a reboot keeps the lockout alive on the wall clock alone`() {
    burnBudget()
    // elapsedRealtime restarts near zero after a reboot; the wall clock does not.
    val afterReboot = guard.stateAt(boot + 60_000L, 0L)
    assertTrue("a reboot must not clear the lockout", afterReboot.lockedOut)
    assertTrue(afterReboot.remainingSeconds > 8 * 60)
  }

  // ---- escalation ----------------------------------------------------------

  @Test
  fun `penalties double and are capped at 24 hours`() {
    val minute = 60_000L
    assertEquals(10 * minute, LockoutGuard.durationForLevel(0))
    assertEquals(20 * minute, LockoutGuard.durationForLevel(1))
    assertEquals(40 * minute, LockoutGuard.durationForLevel(2))
    assertEquals(80 * minute, LockoutGuard.durationForLevel(3))
    assertEquals(160 * minute, LockoutGuard.durationForLevel(4))
    assertEquals(320 * minute, LockoutGuard.durationForLevel(5))
    assertEquals(640 * minute, LockoutGuard.durationForLevel(6))
    assertEquals(1_280 * minute, LockoutGuard.durationForLevel(7))
    // 2 560 min would be 42 h, so the ceiling clamps it instead of growing.
    assertEquals(24 * 60 * minute, LockoutGuard.durationForLevel(8))
    assertEquals(24 * 60 * minute, LockoutGuard.durationForLevel(40))
    assertEquals(24 * 60 * minute, LockoutGuard.durationForLevel(Int.MAX_VALUE))
  }

  @Test
  fun `escalation grows for someone who never gets in`() {
    // Model the attacker: the lockout expires, they try three more times, repeat.
    var wall = boot
    var mono = up

    assertEquals(1, burnBudget(wall, mono).level)

    wall += 11 * 60_000L; mono += 11 * 60_000L
    assertEquals(2, burnBudget(wall, mono).level)

    // Second round waits out the 20-minute penalty.
    wall += 21 * 60_000L; mono += 21 * 60_000L
    assertEquals(3, burnBudget(wall, mono).level)
  }

  @Test
  fun `a successful unlock resets the escalation`() {
    burnBudget()
    assertEquals(1, guard.stateAt(boot, up).level)

    guard.registerSuccess()
    assertEquals(0, guard.stateAt(boot, up).level)

    // And the next lockout is back to the base duration.
    assertEquals(10 * 60, burnBudget(boot, up).remainingSeconds)
  }

  @Test
  fun `noteActivity only ever moves the high-water mark forward`() {
    guard.noteActivityAt(boot)
    assertFalse(guard.stateAt(boot, up).clockRollbackDetected)

    // An older instant must not drag the mark back and fabricate a rollback.
    guard.noteActivityAt(boot - 10 * 60_000L)
    assertFalse(guard.stateAt(boot, up).clockRollbackDetected)
  }
}
