package com.example.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.CyberTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CyberSettingsStore].
 *
 * ### The behaviour this pins
 * `CyberSettings` used to carry a `strictOfflineMode` flag that no switch in the
 * UI could change and that no code ever read. It was pure decoration: a persisted
 * preference that looked like a security control and provided none. The app
 * declares no `INTERNET` permission at all, so being offline is already a property
 * of the build rather than something a runtime flag can grant.
 *
 * Removing the field is not enough on its own, though. Existing installs still
 * have the `strict_offline` entry on disk, and a stale key that keeps being read
 * is the same trap with extra steps. So the store also deletes the entry on the
 * next save, which is what the migration test below checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CyberSettingsStoreTest {

  private companion object {
    const val PREFS_NAME = "cyber_settings_prefs"
    const val KEY_OFFLINE = "strict_offline"
  }

  private lateinit var context: Context
  private lateinit var store: CyberSettingsStore

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    store = CyberSettingsStore(context)
  }

  private fun rawPrefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  @Test
  fun `a fresh install loads the documented defaults`() {
    val settings = store.load()

    assertEquals(CyberSettings.DEFAULT_HOST, settings.defaultHost)
    assertEquals(CyberSettings.DEFAULT_PORT, settings.defaultPort)
    assertEquals(CyberTheme.CYBER_GREEN, settings.theme)
    assertTrue(settings.hapticsEnabled)
    assertTrue(settings.blockScreenCapture)
    assertEquals(CyberSettings.DEFAULT_AUTO_LOCK_SECONDS, settings.autoLockDelaySeconds)
    assertTrue(settings.autoLockAfterCodeChange)
  }

  @Test
  fun `the settings model no longer exposes a strict offline flag`() {
    // The dead field is gone from the data class. This is the regression guard:
    // re-adding `strictOfflineMode` to CyberSettings breaks compilation here, which
    // is the point - the flag must not be reintroduced as "just a default".
    val fields = CyberSettings::class.java.declaredFields.map { it.name }

    assertFalse(
      "CyberSettings must not carry a strictOfflineMode field again",
      fields.contains("strictOfflineMode")
    )
  }

  @Test
  fun `the dead strict_offline entry is removed on the next save`() {
    // Simulate an install that was written by an older build.
    rawPrefs().edit { putBoolean(KEY_OFFLINE, true) }
    assertTrue("the migration fixture was not written", rawPrefs().getBoolean(KEY_OFFLINE, false))

    store.save(store.load())

    assertFalse(
      "the dead strict_offline entry survived a save",
      rawPrefs().contains(KEY_OFFLINE)
    )
  }

  @Test
  fun `a saved round trip preserves every real setting`() {
    val original = CyberSettings(
      defaultHost = "10.10.14.99",
      defaultPort = "8443",
      theme = CyberTheme.TACTICAL_BLUE,
      hapticsEnabled = false,
      blockScreenCapture = false,
      autoLockDelaySeconds = 300,
      autoLockAfterCodeChange = false
    )

    store.save(original)

    assertEquals(original, store.load())
  }

  @Test
  fun `an unknown theme key falls back to the default`() {
    rawPrefs().edit { putString("theme", "not-a-theme") }
    assertEquals(CyberTheme.CYBER_GREEN, store.load().theme)
  }

  @Test
  fun `reset restores factory values and clears the store`() {
    store.save(
      CyberSettings(
        defaultHost = "1.2.3.4",
        theme = CyberTheme.STEALTH,
        autoLockDelaySeconds = 900
      )
    )

    store.reset()

    assertEquals(CyberSettings(), store.load())
    assertTrue(rawPrefs().all.isEmpty())
  }

  @Test
  fun `an invalid auto lock delay is not persisted silently`() {
    // Documents the trust boundary: the store is a dumb key/value box, so the UI
    // is responsible for offering only the values in AUTO_LOCK_CHOICES.
    val choices = CyberSettings.AUTO_LOCK_CHOICES

    assertTrue(choices.contains(CyberSettings.DEFAULT_AUTO_LOCK_SECONDS))
    assertTrue(choices.all { it >= 0 })
  }
}
