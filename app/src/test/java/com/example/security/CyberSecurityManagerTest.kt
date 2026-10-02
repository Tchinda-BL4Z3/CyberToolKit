package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression suite for lock-code storage.
 *
 * The critical property is [the code is never stored in clear text]: the
 * previous build wrote `"1,1,1,1,1"` into `SharedPreferences`, which any backup
 * agent or filesystem reader could read. These tests assert on the *raw
 * preference file contents* rather than on the accessors, so a future change
 * that stores the digits anywhere reachable fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CyberSecurityManagerTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    prefsFile().delete()
    // The manager is a singleton reading a fixed prefs name, so poke the file
    // it actually uses rather than a fixture.
    securityPrefs().edit().clear().commit()
  }

  private fun prefsFile() =
    java.io.File(context.applicationContext.filesDir.parentFile, "shared_prefs/cyber_security_prefs.xml")

  private fun securityPrefs() =
    context.applicationContext
      .getSharedPreferences("cyber_security_prefs", Context.MODE_PRIVATE)

  private fun rawPrefsText(): String =
    java.io.File(context.applicationContext.filesDir.parentFile, "shared_prefs/cyber_security_prefs.xml")
      .takeIf { it.exists() }?.readText() ?: ""

  // ---- fresh install -------------------------------------------------------

  @Test
  fun `a fresh install unlocks with the factory code`() {
    assertTrue(
      CyberSecurityManager.verifyCombination(context, CyberSecurityManager.FACTORY_COMBINATION)
    )
  }

  @Test
  fun `a fresh install is flagged as not user-configured`() {
    assertFalse(CyberSecurityManager.isUserConfigured(context))
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
  }

  @Test
  fun `the factory code is five digits`() {
    assertEquals(5, CyberSecurityManager.FACTORY_COMBINATION.size)
    assertTrue(CyberSecurityManager.FACTORY_COMBINATION.all { it in 0..9 })
  }

  // ---- storage -------------------------------------------------------------

  @Test
  fun `the code is never stored in clear text`() {
    CyberSecurityManager.setCombination(context, listOf(4, 7, 1, 9, 3))
    val raw = rawPrefsText()
    assertTrue("nothing should have been written", raw.isNotEmpty())
    assertFalse("digits must not appear verbatim", raw.contains("47193"))
    assertFalse(raw.contains("4,7,1,9,3"))
    assertFalse(raw.contains("4 - 7 - 1 - 9 - 3"))
    assertTrue("a PBKDF2 record should be stored", raw.contains("pbkdf2"))
  }

  @Test
  fun `two installs of the same code do not produce the same stored record`() {
    CyberSecurityManager.setCombination(context, listOf(4, 7, 1, 9, 3))
    val first = securityPrefs().getString("combination_hash_v2", null)
    CyberSecurityManager.setCombination(context, listOf(4, 7, 1, 9, 3))
    val second = securityPrefs().getString("combination_hash_v2", null)
    assertNotEquals("the salt must be random per write", first, second)
  }

  // ---- rotation ------------------------------------------------------------

  @Test
  fun `a rotated code replaces the old one and the old one stops working`() {
    assertTrue(CyberSecurityManager.verifyCombination(context, listOf(1, 1, 1, 1, 1)))
    assertTrue(CyberSecurityManager.setCombination(context, listOf(8, 3, 5, 2, 9)))

    assertTrue(CyberSecurityManager.verifyCombination(context, listOf(8, 3, 5, 2, 9)))
    assertFalse(CyberSecurityManager.verifyCombination(context, listOf(1, 1, 1, 1, 1)))
    assertTrue(CyberSecurityManager.isUserConfigured(context))
    assertFalse(CyberSecurityManager.isUsingFactoryCode(context))
  }

  @Test
  fun `clearing re-seeds the factory code and drops the user-configured flag`() {
    CyberSecurityManager.setCombination(context, listOf(8, 3, 5, 2, 9))
    CyberSecurityManager.clearCombination(context)

    assertTrue(
      CyberSecurityManager.verifyCombination(context, CyberSecurityManager.FACTORY_COMBINATION)
    )
    assertFalse(CyberSecurityManager.verifyCombination(context, listOf(8, 3, 5, 2, 9)))
    assertFalse(CyberSecurityManager.isUserConfigured(context))
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
  }

  // ---- validation ----------------------------------------------------------

  @Test
  fun `malformed codes are rejected by both verify and set`() {
    listOf(
      listOf(1, 2, 3, 4),
      listOf(1, 2, 3, 4, 5, 6),
      listOf(1, 2, 3, 4, 10),
      listOf(1, 2, 3, 4, -1),
      emptyList()
    ).forEach { candidate ->
      assertFalse("verify should reject $candidate", CyberSecurityManager.verifyCombination(context, candidate))
      assertFalse("set should reject $candidate", CyberSecurityManager.setCombination(context, candidate))
    }
  }

  // ---- legacy migration ----------------------------------------------------

  @Test
  fun `a legacy clear-text code is migrated, kept working and then erased`() {
    securityPrefs().edit().putString("secret_combination", "2,4,6,8,0").commit()

    assertTrue(CyberSecurityManager.verifyCombination(context, listOf(2, 4, 6, 8, 0)))
    assertTrue("the migrated code must count as configured", CyberSecurityManager.isUserConfigured(context))
    assertFalse("the plaintext must be gone", CyberSecurityManager.isUsingFactoryCode(context))

    val raw = rawPrefsText()
    assertFalse("the legacy key must be dropped", raw.contains("secret_combination"))
    assertFalse("the digits must not survive in clear", raw.contains("24680"))
    assertTrue("the migrated code must be re-hashed", raw.contains("pbkdf2"))
  }

  @Test
  fun `a legacy code in spaced form is migrated too`() {
    securityPrefs().edit().putString("secret_combination", "9 - 8 - 7 - 6 - 5").commit()
    assertTrue(CyberSecurityManager.verifyCombination(context, listOf(9, 8, 7, 6, 5)))
  }

  @Test
  fun `a corrupt legacy value falls back to the factory code instead of bricking the app`() {
    securityPrefs().edit().putString("secret_combination", "not-a-code").commit()
    assertTrue(
      CyberSecurityManager.verifyCombination(context, CyberSecurityManager.FACTORY_COMBINATION)
    )
    assertFalse(rawPrefsText().contains("not-a-code"))
  }

  // ---- pure helpers --------------------------------------------------------

  @Test
  fun `parseDigits accepts five digits in any separator style`() {
    assertEquals(listOf(1, 2, 3, 4, 5), CyberSecurityManager.parseDigits("12345"))
    assertEquals(listOf(0, 0, 0, 0, 0), CyberSecurityManager.parseDigits("00000"))
    assertEquals(listOf(9, 8, 7, 6, 5), CyberSecurityManager.parseDigits("9-8-7-6-5"))
    assertEquals(listOf(2, 4, 6, 8, 0), CyberSecurityManager.parseDigits("2,4,6,8,0"))
  }

  @Test
  fun `parseDigits rejects anything that is not exactly five digits`() {
    listOf("1234", "123456", "abcde", "", "12a45").forEach {
      assertNull("expected null for '$it'", CyberSecurityManager.parseDigits(it))
    }
  }

  @Test
  fun `combinationToString renders a readable dial sequence`() {
    val code = listOf(2, 4, 6, 8, 0)
    assertEquals("2 - 4 - 6 - 8 - 0", CyberSecurityManager.combinationToString(code))
    assertEquals("2,4,6,8,0", CyberSecurityManager.combinationToString(code, ","))
  }

  @Test
  fun `weak combinations are the ones an attacker tries first`() {
    listOf(
      listOf(1, 1, 1, 1, 1),
      listOf(0, 0, 0, 0, 0),
      listOf(1, 2, 3, 4, 5),
      listOf(5, 4, 3, 2, 1),
      listOf(0, 1, 2, 3, 4),
      listOf(9, 8, 7, 6, 5),
      listOf(1, 2, 1, 2, 1)
    ).forEach { assertTrue("expected weak: $it", CyberSecurityManager.isWeakCombination(it)) }
  }

  @Test
  fun `a non-trivial code is accepted`() {
    listOf(
      listOf(4, 7, 1, 9, 3),
      listOf(8, 3, 5, 2, 9),
      listOf(0, 2, 4, 6, 8)
    ).forEach { assertFalse("expected strong: $it", CyberSecurityManager.isWeakCombination(it)) }
  }

  @Test
  fun `a code of the wrong length counts as weak so it can never be saved`() {
    assertTrue(CyberSecurityManager.isWeakCombination(listOf(1, 2, 3)))
    assertTrue(CyberSecurityManager.isWeakCombination(emptyList()))
  }

  // ---- factory-code cache --------------------------------------------------

  /**
   * The cache exists so opening Settings does not pay a full PBKDF2 derivation.
   * These tests pin the *correctness* side of that trade: the cached answer must
   * still be right after every operation that can change the active code.
   */
  @Test
  fun `a fresh install reports the factory code`() {
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
  }

  @Test
  fun `setting a different code clears the factory-code flag`() {
    assertTrue(CyberSecurityManager.setCombination(context, listOf(4, 7, 2, 9, 3)))
    assertFalse(CyberSecurityManager.isUsingFactoryCode(context))
  }

  /** Choosing the factory digits explicitly is still the factory code. */
  @Test
  fun `setting the factory digits themselves keeps the flag set`() {
    assertTrue(CyberSecurityManager.setCombination(context, listOf(1, 1, 1, 1, 1)))
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
  }

  @Test
  fun `clearing re-seeds the factory code and the flag with it`() {
    CyberSecurityManager.setCombination(context, listOf(4, 7, 2, 9, 3))
    assertFalse(CyberSecurityManager.isUsingFactoryCode(context))
    CyberSecurityManager.clearCombination(context)
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
    assertTrue(CyberSecurityManager.verifyCombination(context, listOf(1, 1, 1, 1, 1)))
  }

  @Test
  fun `repeated rotations keep the flag accurate`() {
    listOf(listOf(4, 7, 2, 9, 3), listOf(1, 1, 1, 1, 1), listOf(8, 5, 3, 1, 9))
      .forEach { code ->
        CyberSecurityManager.setCombination(context, code)
        assertEquals(
          code == listOf(1, 1, 1, 1, 1),
          CyberSecurityManager.isUsingFactoryCode(context)
        )
      }
  }

  /**
   * A wiped app must not inherit a stale "yes, still the factory code". The
   * cache is only trusted when a hash is actually present, so this holds even if
   * the boolean outlives the hash.
   */
  @Test
  fun `a wiped install does not inherit a stale factory-code flag`() {
    CyberSecurityManager.setCombination(context, listOf(4, 7, 2, 9, 3))
    assertFalse(CyberSecurityManager.isUsingFactoryCode(context))
    CyberSecurityManager.clearCombination(context)
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
    securityPrefs().edit().remove("combination_hash_v2").commit()
    // Re-seeds on the next access, and the fresh seed is the factory code.
    assertTrue(CyberSecurityManager.isUsingFactoryCode(context))
  }

  /** The cached value is a boolean, never the code or anything derived from it. */
  @Test
  fun `the cache stores a flag, never code material`() {
    CyberSecurityManager.setCombination(context, listOf(4, 7, 2, 9, 3))
    val entry = securityPrefs().all["uses_factory_code_cached"]
    assertTrue("expected a Boolean flag", entry is Boolean)
    assertEquals(false, entry)
  }
}
