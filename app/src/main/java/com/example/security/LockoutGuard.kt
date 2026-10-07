package com.example.security

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit

/**
 * Anti-bruteforce ledger for the dial lock.
 *
 * ### The two bugs this replaces
 * 1. The failed-attempt counter and the lockout deadline lived in
 *    `rememberSaveable`. That state is written into the instance-state `Bundle`
 *    and restored on process death, so *killing the app was enough to reset the
 *    counter* - three free guesses, forever. Counters now live in
 *    `SharedPreferences`, which no amount of process death clears.
 * 2. The deadline was compared against `System.currentTimeMillis()`, which the
 *    device owner controls: set the clock back and the lockout evaporates.
 *
 * ### How the lockout is now evaluated
 * The lock is active while **either** clock says so:
 *  - `elapsedRealtime()` (monotonic, unaffected by wall-clock changes) survives
 *    a clock change within a boot;
 *  - the wall clock, paired with a high-water mark, survives a reboot.
 *
 * A wall clock that moves *backwards* past the high-water mark is treated as
 * tampering and the remaining lockout is replayed in full.
 *
 * ### Residual risk (accepted, documented in docu/THREAT_MODEL.md)
 * An attacker with physical access who reboots the device *and* edits the app's
 * private files with root can still reset the ledger. Defending against that
 * means hardware-backed counters (`KeyStore`-wrapped monotonic state), which is
 * out of scope for a local UI lock.
 */
class LockoutGuard(context: Context) {

  private val prefs = context.applicationContext
    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Attempts still available before the console locks. */
  val maxAttempts: Int get() = MAX_ATTEMPTS

  data class State(
    val lockedOut: Boolean,
    val remainingSeconds: Int,
    val attemptsLeft: Int,
    val maxAttempts: Int,
    /** Escalation tier: 0 = the base duration, higher = longer each round. */
    val level: Int,
    val clockRollbackDetected: Boolean,
    /** Wall-clock instant the current lockout began, for diagnostics. */
    val lockedAtWall: Long = 0L
  )

  fun state(): State =
    stateAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())

  /**
   * Pure evaluation of the ledger at an explicit instant.
   *
   * Split out from [state] purely so the clock-rollback and reboot branches -
   * the two paths that are impossible to reach from a test that uses the real
   * clock - can be exercised deterministically. The production path is
   * [state], which is a one-line delegation.
   */
  internal fun stateAt(nowWall: Long, nowMono: Long): State {
    val lockedAtWall = prefs.getLong(KEY_LOCKED_AT_WALL, 0L)
    val untilWall = prefs.getLong(KEY_UNTIL_WALL, 0L)
    val untilMono = prefs.getLong(KEY_UNTIL_MONO, 0L)
    val highWater = prefs.getLong(KEY_WALL_HIGH_WATER, 0L)

    val rollbackDetected = highWater > nowWall + CLOCK_TOLERANCE_MS

    val wallActive = untilWall > 0L && (nowWall < untilWall || rollbackDetected)
    val monoActive = untilMono > nowMono
    val lockedOut = wallActive || monoActive

    // The lock really lifts when the *first* clause goes false, so take the
    // earliest deadline: the countdown can never overshoot the real one.
    val remainingMs = if (lockedOut) {
      minOf(
        if (wallActive) untilWall - nowWall else Long.MAX_VALUE,
        if (monoActive) untilMono - nowMono else Long.MAX_VALUE
      ).coerceAtLeast(0L)
    } else {
      0L
    }

    return State(
      lockedOut = lockedOut,
      remainingSeconds = ((remainingMs + 999L) / 1000L).toInt(),
      attemptsLeft = if (lockedOut) 0 else prefs.getInt(KEY_ATTEMPTS_LEFT, MAX_ATTEMPTS),
      maxAttempts = MAX_ATTEMPTS,
      level = prefs.getInt(KEY_LEVEL, 0),
      clockRollbackDetected = rollbackDetected,
      lockedAtWall = lockedAtWall
    )
  }

  /**
   * Records a failed attempt.
   *
   * @return the state *after* the failure: attempts remaining, or a fresh
   *         lockout when the budget was exhausted.
   */
  fun registerFailure(): State =
    registerFailureAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())

  internal fun registerFailureAt(nowWall: Long, nowMono: Long): State {
    val current = stateAt(nowWall, nowMono)
    if (current.lockedOut) return current

    val left = current.attemptsLeft - 1
    if (left > 0) {
      prefs.edit { putInt(KEY_ATTEMPTS_LEFT, left) }
      return stateAt(nowWall, nowMono)
    }

    val level = prefs.getInt(KEY_LEVEL, 0)
    val durationMs = durationForLevel(level)

    prefs.edit {
      putInt(KEY_ATTEMPTS_LEFT, 0)
      putInt(KEY_LEVEL, level + 1)
      putLong(KEY_LOCKED_AT_WALL, nowWall)
      putLong(KEY_UNTIL_WALL, nowWall + durationMs)
      putLong(KEY_UNTIL_MONO, nowMono + durationMs)
      putLong(KEY_WALL_HIGH_WATER, nowWall)
    }

    return stateAt(nowWall, nowMono)
  }

  /**
   * Clears the ledger after a successful unlock.
   *
   * This also resets the escalation tier, which is deliberate: a genuine
   * successful unlock is the signal that the operator is the owner, not an
   * attacker working through the list. Escalation only grows for someone who
   * never gets in, which is the case worth slowing down.
   */
  fun registerSuccess() {
    prefs.edit {
      remove(KEY_LOCKED_AT_WALL)
      remove(KEY_UNTIL_WALL)
      remove(KEY_UNTIL_MONO)
      remove(KEY_LEVEL)
      putInt(KEY_ATTEMPTS_LEFT, MAX_ATTEMPTS)
    }
  }

  /**
   * Emergency reset. Not surfaced in the release UI - the previous build shipped
   * a "DÉBLOQUER MAINTENANT" button that nullified the whole lockout.
   * Kept for tests and for a future, debug-gated escape hatch.
   */
  fun resetForTests() {
    prefs.edit { clear() }
  }

  /**
   * Records that the app is in the foreground, refreshing the wall-clock
   * high-water mark. Called on resume, never on a timer, so it costs one write
   * per foreground transition rather than one per poll.
   */
  fun noteActivity() = noteActivityAt(System.currentTimeMillis())

  /** Seam for [noteActivity] so the forward-only property is testable. */
  internal fun noteActivityAt(nowWall: Long) = rememberHighWater(nowWall)

  private fun rememberHighWater(nowWall: Long) {
    val highWater = prefs.getLong(KEY_WALL_HIGH_WATER, 0L)
    if (nowWall > highWater) {
      prefs.edit { putLong(KEY_WALL_HIGH_WATER, nowWall) }
    }
  }

  companion object {
    private const val PREFS_NAME = "cyber_lockout_prefs"
    private const val KEY_ATTEMPTS_LEFT = "attempts_left"
    private const val KEY_LEVEL = "lockout_level"
    private const val KEY_LOCKED_AT_WALL = "locked_at_wall"
    private const val KEY_UNTIL_WALL = "lockout_until_wall"
    private const val KEY_UNTIL_MONO = "lockout_until_mono"
    private const val KEY_WALL_HIGH_WATER = "wall_high_water"

    const val MAX_ATTEMPTS = 3
    private const val BASE_DURATION_MS = 10L * 60L * 1000L

    /** Hard ceiling on a single lockout, however many rounds came before it. */
    const val MAX_DURATION_MS = 24L * 60L * 60L * 1000L
    private const val CLOCK_TOLERANCE_MS = 2L * 60L * 1000L

    /**
     * Each successive lockout doubles from 10 minutes, capped at 24 h.
     *
     * The clamp lives here rather than at the call site so that the ceiling
     * cannot be bypassed by a caller that forgets it.
     */
    fun durationForLevel(level: Int): Long {
      val shift = level.coerceIn(0, 32)
      val scaled = if (shift >= Long.SIZE_BITS - 1) {
        Long.MAX_VALUE
      } else {
        BASE_DURATION_MS shl shift
      }
      return scaled.coerceAtMost(MAX_DURATION_MS)
    }
  }
}
