package com.example.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.example.crypto.CodecFormat
import com.example.crypto.CodecMode
import com.example.data.CyberSettings
import com.example.data.CyberSettingsStore
import com.example.payloads.PayloadEnvironment
import com.example.ui.theme.CyberTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the console.
 *
 * ### Why the state moved here
 * Everything used to live in `CyberToolkitApp` as `rememberSaveable`, which has
 * two consequences that were both bugs here:
 *  - `rememberSaveable` writes its value into the instance-state `Bundle`, which
 *    the platform persists to disk across process death. A lock passphrase typed
 *    in the Crypto tab, and worse the `isUnlocked = true` flag, were being
 *    written to disk in clear. Reopening the app after a process kill found it
 *    already unlocked;
 *  - one state holder at the root meant any single write recomposed all five tabs.
 *
 * A `ViewModel` gives the right lifetime for each kind of state:
 *  - **volatile** ([isUnlocked], the session buffers) - survives configuration
 *    changes, dies with the process, never touches the Bundle;
 *  - **durable** ([settings]) - written to `SharedPreferences`.
 */
class CyberToolkitViewModel(
  application: Application,
  private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

  private val settingsStore = CyberSettingsStore(application)

  private val _settings = MutableStateFlow(settingsStore.load())
  val settings: StateFlow<CyberSettings> = _settings.asStateFlow()

  /** Durable settings: applied and persisted on every change. */
  fun updateSettings(transform: (CyberSettings) -> CyberSettings) {
    val next = transform(_settings.value)
    if (next == _settings.value) return
    _settings.value = next
    settingsStore.save(next)
  }

  fun resetSettings() {
    settingsStore.reset()
    _settings.value = settingsStore.load()
  }

  val theme: CyberTheme get() = _settings.value.theme

  // ---------------------------------------------------------------------------
  // Lock state - deliberately volatile
  // ---------------------------------------------------------------------------

  /**
   * False on every fresh process. There is no path that restores it from disk,
   * which is what makes "kill the app to bypass the dial lock" impossible.
   */
  var isUnlocked by mutableStateOf(false)
    private set

  fun unlock() {
    isUnlocked = true
  }

  fun lock() {
    isUnlocked = false
    wipeSecrets()
  }

  /**
   * Drops the sensitive session buffers. Called on lock and on "reset session
   * data" so a passphrase never lingers in memory or in a saved-state Bundle.
   */
  fun wipeSecrets() {
    cryptoSecret = ""
    aesPayload = ""
  }

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------

  /**
   * The visible tab, remembered across process death.
   *
   * Held in a [SavedStateHandle] rather than a plain field: a `ViewModel` survives
   * a configuration change on its own, so the old `mutableIntStateOf(0)` looked
   * fine until the process was reclaimed, which sent the operator back to the
   * Encoder with no warning.
   *
   * Nothing security-relevant is stored here. The instance-state `Bundle` is
   * written to disk, which is exactly why `isUnlocked` deliberately stays out of
   * it - see the class documentation.
   */
  val selectedTab: StateFlow<Int> = savedStateHandle
    .getStateFlow(KEY_SELECTED_TAB, 0)

  fun selectTab(index: Int) {
    savedStateHandle[KEY_SELECTED_TAB] = index
  }


  // ---------------------------------------------------------------------------
  // Tab 1 - Encoder (session state, kept across tab switches and rotations)
  // ---------------------------------------------------------------------------

  var encoderInput by mutableStateOf("")
  var encoderMode by mutableStateOf(CodecMode.ENCODE)
  var encoderFormat by mutableStateOf(CodecFormat.BASE64)

  // ---------------------------------------------------------------------------
  // Tab 2 - Crypto
  // ---------------------------------------------------------------------------

  var cryptoInput by mutableStateOf("CyberToolkit")
  var cryptoSecret by mutableStateOf("ethical-hacker-key")
  var cryptoIterations by mutableIntStateOf(com.example.crypto.CryptoCore.DEFAULT_ITERATIONS / 1000)
  var aesPayload by mutableStateOf("")
  var passwordHashResult by mutableStateOf("")

  // ---------------------------------------------------------------------------
  // Tab 3 - Cheat sheets
  // ---------------------------------------------------------------------------

  var cheatSearch by mutableStateOf("")
  var cheatCategory by mutableStateOf(ALL_CATEGORY)

  // ---------------------------------------------------------------------------
  // Tab 4 - Payloads
  // ---------------------------------------------------------------------------

  /**
   * Selected shell, kept per session.
   *
   * The host and port are deliberately *not* held here. They used to exist both
   * as `rememberSaveable` state in the root and as the "lab defaults" in
   * Settings, which meant editing the host in the Payloads tab and editing the
   * default in Settings wrote to two different places and one silently won.
   * They now live only in [settings] and both screens read and write that.
   */
  var payloadEnv by mutableStateOf(PayloadEnvironment.BASH.key)

  fun selectEnvironment(key: String) {
    payloadEnv = key
  }

  /**
   * "Reset session data" in the Settings tab: clears the live buffers and the
   * clipboard residue this app may have left, but keeps the lock code and the
   * saved settings - wiping those would be a different, far more destructive,
   * action and is exposed separately.
   */
  fun clearSessionData() {
    encoderInput = ""
    cryptoInput = ""
    cheatSearch = ""
    aesPayload = ""
    passwordHashResult = ""
    wipeSecrets()
  }

  companion object {
    const val ALL_CATEGORY = "All"
    private const val KEY_SELECTED_TAB = "selected_tab"
  }
}
