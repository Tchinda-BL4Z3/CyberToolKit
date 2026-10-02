package com.example.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.crypto.CryptoCore

/**
 * Persistence for the 5-dial lock code.
 *
 * ### What changed and why
 * The code used to be written to `SharedPreferences` in clear text
 * (`"1,1,1,1,1"`). That made it readable by `adb backup`, by any backup agent
 * pointed at the app, and by anyone with the APK plus filesystem access. It is
 * now stored as a PBKDF2-HMAC-SHA256 hash with a random 128-bit salt and a
 * recorded iteration count, so an attacker who exfiltrates the preferences only
 * gets an offline cracking target instead of the code itself.
 *
 * ### Backward compatibility
 * Installations that still carry the legacy `secret_combination` string are
 * migrated in place: the plaintext is verified once, then re-saved hashed and
 * the plaintext key is dropped. The operator's existing code keeps working.
 */
object CyberSecurityManager {

  private const val PREFS_NAME = "cyber_security_prefs"
  private const val KEY_STORED_HASH = "combination_hash_v2"
  private const val KEY_LEGACY_PLAINTEXT = "secret_combination"
  private const val KEY_USER_CONFIGURED = "user_configured"

  /**
   * Cached answer to "is the active code still the factory one?".
   *
   * Answering that honestly costs a full PBKDF2 derivation - 210k iterations,
   * several seconds on the low-end ARM hardware this app is expected to run on.
   * Settings used to pay that cost every single time the tab was opened, just to
   * decide whether to show the "replace the public default" warning.
   *
   * The value is stored next to the hash and rewritten by the only two functions
   * that can change the active code ([setCombination], [clearCombination]), so the
   * cache cannot outlive the fact it reports. A legacy install that predates the
   * key, or one whose cached value was lost, falls back to computing the real
   * answer and repairs the cache.
   */
  private const val KEY_USES_FACTORY_CACHE = "uses_factory_code_cached"

  /**
   * The factory code shipped with the app. It is public by necessity - the
   * first-run screen has to show it - but [isUsingFactoryCode] lets Settings
   * nag the operator into replacing it.
   */
  val FACTORY_COMBINATION: List<Int> = listOf(1, 1, 1, 1, 1)

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * True once the operator has explicitly chosen a combination.
   *
   * A fresh install starts on the factory code, so this stays false until the
   * code-rotation form succeeds; Settings uses it to nag about the public
   * default.
   */
  fun isUserConfigured(context: Context): Boolean =
    prefs(context).getBoolean(KEY_USER_CONFIGURED, false) && storedHash(context) != null

  /**
   * True when the active code is still the factory one.
   *
   * Cached: see [KEY_USES_FACTORY_CACHE]. The cache is only trusted when a hash
   * is actually present, so wiping the app cannot leave a stale "yes, still the
   * factory code" behind.
   */
  fun isUsingFactoryCode(context: Context): Boolean {
    val store = prefs(context)
    val hasHash = !store.getString(KEY_STORED_HASH, null).isNullOrBlank()
    if (hasHash && store.contains(KEY_USES_FACTORY_CACHE)) {
      return store.getBoolean(KEY_USES_FACTORY_CACHE, true)
    }
    val answer = verifyCombination(context, FACTORY_COMBINATION)
    store.edit { putBoolean(KEY_USES_FACTORY_CACHE, answer) }
    return answer
  }

  /**
   * Verifies [digits] against the stored hash in constant time.
   *
   * Returns false for a malformed code. A wiped or never-configured app falls
   * back to the factory combination, so the console is never unreachable.
   */
  fun verifyCombination(context: Context, digits: List<Int>): Boolean {
    if (digits.size != COMBINATION_LENGTH || digits.any { it !in 0..9 }) return false
    val stored = storedHash(context) ?: return false
    return CryptoCore.passwordVerify(digits.joinToString(""), stored)
  }

  /**
   * Hashes and stores [digits].
   *
   * @return false when the code is malformed or the platform cannot derive a key.
   */
  fun setCombination(context: Context, digits: List<Int>): Boolean {
    if (digits.size != COMBINATION_LENGTH || digits.any { it !in 0..9 }) return false
    val hash = CryptoCore.passwordHash(digits.joinToString(""))
    if (hash.isEmpty()) return false
    // Compared by value, not by verifying against the hash written above: that
    // hash *is* the hash of `digits`, so it would always match and report every
    // code as the factory one.
    val stillFactory = digits == FACTORY_COMBINATION
    prefs(context).edit {
      putString(KEY_STORED_HASH, hash)
      putBoolean(KEY_USER_CONFIGURED, true)
      // Kept in step with the hash above: this is the only place the active code
      // becomes a user-chosen one, so the cache is written here and nowhere else.
      putBoolean(KEY_USES_FACTORY_CACHE, stillFactory)
      remove(KEY_LEGACY_PLAINTEXT)
    }
    return true
  }

  /** Removes the stored code and re-seeds it with the factory combination. */
  fun clearCombination(context: Context) {
    val seed = CryptoCore.passwordHash(FACTORY_COMBINATION.joinToString(""))
    prefs(context).edit {
      remove(KEY_STORED_HASH)
      remove(KEY_LEGACY_PLAINTEXT)
      putBoolean(KEY_USER_CONFIGURED, false)
      // Clearing re-seeds the factory combination, so the cache says "factory"
      // without needing a derivation.
      putBoolean(KEY_USES_FACTORY_CACHE, true)
    }
    if (seed.isNotEmpty()) {
      prefs(context).edit { putString(KEY_STORED_HASH, seed) }
    }
  }

  /**
   * Returns the stored hash, transparently seeding or migrating as needed:
   *  - a legacy clear-text code is verified once, re-hashed, then wiped;
   *  - a brand-new install is seeded with the factory combination, so the
   *    constant-time path is the only comparison path in the app.
   */
  private fun storedHash(context: Context): String? {
    val store = prefs(context)
    store.getString(KEY_STORED_HASH, null)
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    val legacy = store.getString(KEY_LEGACY_PLAINTEXT, null)
    if (legacy != null) {
      val digits = parseDigits(legacy)
      store.edit { remove(KEY_LEGACY_PLAINTEXT) }
      if (digits != null && setCombination(context, digits)) return storedHash(context)
      if (digits != null) store.edit { putBoolean(KEY_USER_CONFIGURED, true) }
    }

    val seed = CryptoCore.passwordHash(FACTORY_COMBINATION.joinToString(""))
    if (seed.isEmpty()) return null
    store.edit {
      putString(KEY_STORED_HASH, seed)
      // A first-run seed is by definition the factory combination; recording that
      // here spares the first Settings visit a derivation it would otherwise pay.
      putBoolean(KEY_USES_FACTORY_CACHE, true)
    }
    return seed
  }

  // ---------------------------------------------------------------------------
  // Pure helpers (kept at package level so they stay unit-testable)
  // ---------------------------------------------------------------------------

  const val COMBINATION_LENGTH = 5

  fun combinationToString(combination: List<Int>, separator: String = " - "): String =
    combination.joinToString(separator)

  fun parseDigits(input: String): List<Int>? {
    val digits = input.filter { it.isDigit() }
    if (digits.length != COMBINATION_LENGTH) return null
    return digits.map { it.digitToInt() }
  }

  /**
   * Rejects the codes an attacker tries first: 11111, 12345, 54321, 12121…
   *
   * Only applied to *new* codes, so existing locks are never invalidated.
   */
  fun isWeakCombination(digits: List<Int>): Boolean {
    if (digits.size != COMBINATION_LENGTH) return true
    // 11111, 00000 …
    if (digits.distinct().size == 1) return true
    // 01234, 12345 … 56789 and 98765, 87654 … 43210
    val ascending = digits.zipWithNext().all { (a, b) -> (a + 1) % 10 == b }
    val descending = digits.zipWithNext().all { (a, b) -> (a + 9) % 10 == b }
    if (ascending || descending) return true
    // Same pair repeated, e.g. 12121 / 38383.
    if (digits == listOf(digits[0], digits[1], digits[0], digits[1], digits[0])) return true
    return false
  }
}
