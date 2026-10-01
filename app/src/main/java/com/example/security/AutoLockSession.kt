package com.example.security

/**
 * Decides when a session left open in the background must be re-locked.
 *
 * ### Why this class exists
 * The rule used to live inline in a `DisposableEffect` in `MainActivity`, which
 * made it untestable: there was no way to exercise a background transition without
 * a device, so the behaviour below had no coverage at all.
 *
 * ### Why the clock is injected
 * The original code measured the gap with `System.currentTimeMillis()`, which is
 * the *wall* clock. Turning the device clock backwards while the app was
 * backgrounded made the elapsed value negative, the comparison
 * `elapsed >= delay` false, and the app silently stayed unlocked - a clock change
 * was enough to defeat the auto-lock. The default clock here is
 * [android.os.SystemClock.elapsedRealtime], a monotonic source that no user
 * setting can move, and the clock is a parameter so a test can drive it.
 */
class AutoLockSession(
  private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {

  /**
   * Monotonic instant at which the app was last backgrounded, or `null` when it
   * is in the foreground.
   *
   * `null` rather than `0L` as the sentinel: `elapsedRealtime()` is legitimately
   * zero for the first few milliseconds after boot, and a `0L` sentinel would
   * then read as "never left in the background" or, worse, as a gap of zero.
   */
  private var backgroundedAt: Long? = null

  /** True while the app is considered backgrounded. */
  val isBackgrounded: Boolean get() = backgroundedAt != null

  /**
   * Records that the app went to the background.
   *
   * Only called with [isUnlocked] true: a session that was already locked has
   * nothing to protect, and stamping the clock for it would let a later unlock be
   * locked out by an absence that started while the app was closed.
   */
  fun onStop(isUnlocked: Boolean) {
    backgroundedAt = if (isUnlocked) now() else null
  }

  /**
   * Records that the app came back to the foreground and reports whether the
   * session must be re-locked.
   *
   * The elapsed time is sampled here, on the way back in, rather than being
   * scheduled as a timer when the app went out: a pending timer would keep a
   * coroutine alive for the whole absence, and a delay changed in Settings would
   * not take effect until the next cycle.
   */
  fun onStart(isUnlocked: Boolean, delaySeconds: Int): Boolean {
    val leftAt = backgroundedAt
    backgroundedAt = null
    if (leftAt == null || !isUnlocked) return false

    // 0 means "lock as soon as the app is left".
    if (delaySeconds <= 0) return true

    val elapsed = now() - leftAt
    // Fail closed if the source ever went backwards: for a security control an
    // unmeasurable interval must be treated as an unbounded one.
    if (elapsed < 0L) return true

    return elapsed >= delaySeconds * 1000L
  }

  /** Forgets any recorded absence, e.g. when the session is explicitly locked. */
  fun reset() {
    backgroundedAt = null
  }
}
