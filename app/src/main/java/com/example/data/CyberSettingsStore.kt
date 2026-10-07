package com.example.data

import android.content.Context
import androidx.core.content.edit
import com.example.ui.theme.CyberTheme

/**
 * Everything the Settings tab controls, in one immutable snapshot.
 *
 * Defaults reproduce the values the app shipped with, so an upgrade changes
 * nothing for the operator until they touch a switch.
 */
data class CyberSettings(
  val defaultHost: String = DEFAULT_HOST,
  val defaultPort: String = DEFAULT_PORT,
  val theme: CyberTheme = CyberTheme.CYBER_GREEN,
  val hapticsEnabled: Boolean = true,
  val blockScreenCapture: Boolean = true,
  val autoLockDelaySeconds: Int = DEFAULT_AUTO_LOCK_SECONDS,
  val autoLockAfterCodeChange: Boolean = true,
  /**
   * Master switch for the SSH client. Defaults to off.
   *
   * Android grants `INTERNET` at install with no runtime prompt, so the consent
   * decision cannot live in a permission dialog the way a camera or location
   * prompt would. This flag is the substitute: until the operator turns it on,
   * [com.example.ssh.SshSession] refuses every connection, and the UI shows the
   * reason instead of failing silently.
   */
  val sshEnabled: Boolean = false
) {
  companion object {
    const val DEFAULT_HOST = "10.10.14.21"
    const val DEFAULT_PORT = "4444"
    const val DEFAULT_AUTO_LOCK_SECONDS = 60

    /** 0 = lock the instant the app leaves the foreground. */
    val AUTO_LOCK_CHOICES = listOf(0, 15, 30, 60, 300, 900)
  }
}

/**
 * Plain `SharedPreferences` persistence for [CyberSettings].
 *
 * Deliberately separate from the lock-code store ([com.example.security.CyberSecurityManager])
 * so "reset session data" can wipe the lab defaults without touching the lock.
 */
class CyberSettingsStore(context: Context) {

  private val prefs = context.applicationContext
    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun load(): CyberSettings = CyberSettings(
    defaultHost = prefs.getString(KEY_HOST, null) ?: CyberSettings.DEFAULT_HOST,
    defaultPort = prefs.getString(KEY_PORT, null) ?: CyberSettings.DEFAULT_PORT,
    theme = CyberTheme.fromKey(prefs.getString(KEY_THEME, null)),
    hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
    // `strict_offline` used to be read here into a `strictOfflineMode` field that
    // nothing ever consulted and that no switch could change. It is gone rather
    // than left as a dead preference: the app declares no INTERNET permission, so
    // being offline is a property of the build, not a runtime setting. A
    // persisted flag that pretends to be configurable is worse than none, because
    // it reads as protection that is not there.
    //
    // The preference entry itself is actively removed on the next save, so an
    // existing install drops it instead of carrying a dead flag forever.
    blockScreenCapture = prefs.getBoolean(KEY_BLOCK_CAPTURE, true),
    autoLockDelaySeconds = prefs.getInt(KEY_AUTO_LOCK, CyberSettings.DEFAULT_AUTO_LOCK_SECONDS),
    autoLockAfterCodeChange = prefs.getBoolean(KEY_AUTOLOCK_ON_CHANGE, true),
    sshEnabled = prefs.getBoolean(KEY_SSH_ENABLED, false)
  )

  fun save(settings: CyberSettings) {
    prefs.edit {
      putString(KEY_HOST, settings.defaultHost)
      putString(KEY_PORT, settings.defaultPort)
      putString(KEY_THEME, settings.theme.key)
      putBoolean(KEY_HAPTICS, settings.hapticsEnabled)
      putBoolean(KEY_BLOCK_CAPTURE, settings.blockScreenCapture)
      putInt(KEY_AUTO_LOCK, settings.autoLockDelaySeconds)
      putBoolean(KEY_AUTOLOCK_ON_CHANGE, settings.autoLockAfterCodeChange)
      putBoolean(KEY_SSH_ENABLED, settings.sshEnabled)
      // Drop the dead `strict_offline` entry written by older builds.
      remove(KEY_OFFLINE)
    }
  }

  /** Restores every setting to its factory value. */
  fun reset() {
    prefs.edit { clear() }
  }

  private companion object {
    const val PREFS_NAME = "cyber_settings_prefs"
    const val KEY_HOST = "default_host"
    const val KEY_PORT = "default_port"
    const val KEY_THEME = "theme"
    const val KEY_HAPTICS = "haptics_enabled"
    const val KEY_OFFLINE = "strict_offline"
    const val KEY_BLOCK_CAPTURE = "block_screen_capture"
    const val KEY_AUTO_LOCK = "auto_lock_seconds"
    const val KEY_AUTOLOCK_ON_CHANGE = "autolock_on_code_change"
    const val KEY_SSH_ENABLED = "ssh_enabled"
  }
}
