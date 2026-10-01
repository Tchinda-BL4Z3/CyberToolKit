package com.example

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.CyberSettings
import com.example.security.AutoLockSession
import com.example.security.CyberSecurityManager
import com.example.security.LockoutGuard
import com.example.ui.CyberToolkitViewModel
import com.example.ui.crypto.CryptoTabScreen
import com.example.ui.encoder.EncoderTabScreen
import com.example.ui.lock.HomeScreenLock
import com.example.ui.payloads.PayloadsTabScreen
import com.example.ui.sheets.SheetsTabScreen
import com.example.ui.settings.SettingsTabScreen
import com.example.ui.theme.CyberToolkitTheme
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily

/**
 * Single-activity host.
 *
 * ### Responsibilities that used to be missing
 *  - **`FLAG_SECURE`**: the operator can opt into blocking screenshots and the
 *    task-switcher thumbnail, applied from the activity rather than only
 *    mentioned in a settings switch that did nothing;
 *  - **auto-lock on background**: the previous build left the console unlocked
 *    after the app went to the background, so the payload generator and the
 *    secret-key field were visible to the next person holding the device;
 *  - **edge-to-edge insets**: `adjustResize` plus `imePadding` means the secret
 *    fields are not covered by the keyboard;
 *  - **lifecycle-aware state collection** (`collectAsStateWithLifecycle`), so a
 *    backgrounded app does not recompose.
 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    applyScreenCapturePolicy(blocked = true)
    setContent {
      // The theme is applied inside [CyberToolkitApp], not here.
      //
      // It used to be wrapped around this call with the default palette, which
      // meant the settings were collected *below* the theme provider and could
      // never influence it: picking "Tactical Blue" moved the chip highlight
      // and changed no colour. The provider has to sit above the content that
      // reads the setting.
      CyberToolkitApp()
    }
  }

  /**
   * Applies (or clears) `FLAG_SECURE`.
   *
   * Called twice: once unconditionally in [onCreate] so the flag is present
   * before the first frame, and again whenever the preference changes.
   */
  fun applyScreenCapturePolicy(blocked: Boolean) {
    if (blocked) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }
}

/** A bottom-bar destination. */
private data class NavTab(val titleRes: Int, val subtitleRes: Int, val icon: ImageVector)

private val TABS = listOf(
  NavTab(R.string.tab_encoder, R.string.subtitle_encoder, Icons.Default.SyncAlt),
  NavTab(R.string.tab_crypto, R.string.subtitle_crypto, Icons.Default.Fingerprint),
  NavTab(R.string.tab_sheets, R.string.subtitle_sheets, Icons.Default.Terminal),
  NavTab(R.string.tab_payloads, R.string.subtitle_payloads, Icons.Default.Code),
  NavTab(R.string.tab_settings, R.string.subtitle_settings, Icons.Default.Settings)
)

/**
 * Console root.
 *
 * The signature is intentionally parameter-free: `GreetingScreenshotTest`
 * renders it inside `MyApplicationTheme`, and the lock screen is the correct
 * default state for a fresh process anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyberToolkitApp(viewModel: CyberToolkitViewModel = viewModel()) {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val lifecycleOwner = LocalLifecycleOwner.current
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()

  val securityManager = CyberSecurityManager
  val lockoutGuard = remember(context) { LockoutGuard(context.applicationContext) }

  CyberToolkitTheme(theme = settings.theme) {
    CyberToolkitContent(
      viewModel = viewModel,
      settings = settings,
      context = context,
      activity = activity,
      lifecycleOwner = lifecycleOwner,
      securityManager = securityManager,
      lockoutGuard = lockoutGuard,
      selectedTab = selectedTab
    )
  }
}

/**
 * The actual application body, rendered inside the theme chosen in Settings.
 *
 * Split out from [CyberToolkitApp] so the theme provider wraps every screen
 * while the settings/lifecycle plumbing stays readable in one piece.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CyberToolkitContent(
  viewModel: CyberToolkitViewModel,
  settings: CyberSettings,
  context: Context,
  activity: MainActivity?,
  lifecycleOwner: LifecycleOwner,
  securityManager: CyberSecurityManager,
  lockoutGuard: LockoutGuard,
  selectedTab: Int
) {
  val palette = LocalCyberPalette.current

  // Refresh the wall-clock high-water mark on every foreground transition, so a
  // clock rollback is detected even if the app was killed while backgrounded.
  DisposableEffect(lifecycleOwner, lockoutGuard) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) lockoutGuard.noteActivity()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Auto-lock: measure time spent outside the app.
  //
  // The decision itself lives in [AutoLockSession] so it can be tested without a
  // device. The elapsed time is sampled on ON_START rather than scheduled on
  // ON_STOP, so a long background stay does not leave a pending timer holding a
  // coroutine alive, and changing the delay in Settings takes effect on the next
  // transition without restarting the activity.
  val autoLock = remember { AutoLockSession() }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> autoLock.onStop(viewModel.isUnlocked)
        Lifecycle.Event.ON_START -> if (autoLock.onStart(viewModel.isUnlocked, settings.autoLockDelaySeconds)) {
          viewModel.lock()
          autoLock.reset()
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Re-apply the capture policy whenever the preference is toggled.
  LaunchedEffect(settings.blockScreenCapture, activity) {
    activity?.applyScreenCapturePolicy(settings.blockScreenCapture)
  }

  // Back from a non-first tab returns to the console home tab; from the first
  // tab it falls through to the system gesture, which is the expected behaviour
  // and was previously "nothing happens".
  BackHandler(enabled = viewModel.isUnlocked && selectedTab != 0) {
    viewModel.selectTab(0)
  }

  Box(modifier = Modifier.fillMaxSize()) {
    AnimatedVisibility(
      visible = !viewModel.isUnlocked,
      enter = fadeIn(animationSpec = tween(350)),
      exit = fadeOut(animationSpec = tween(350))
    ) {
      HomeScreenLock(
        onAttempt = { digits ->
          securityManager.verifyCombination(context, digits)
        },
        onUnlocked = { viewModel.unlock() },
        hapticsEnabled = settings.hapticsEnabled
      )
    }

    AnimatedVisibility(
      visible = viewModel.isUnlocked,
      enter = fadeIn(animationSpec = tween(350)),
      exit = fadeOut(animationSpec = tween(350))
    ) {
      ConsoleScaffold(
        selectedTab = selectedTab,
        viewModel = viewModel,
        settings = settings
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleScaffold(
  viewModel: CyberToolkitViewModel,
  settings: CyberSettings,
  selectedTab: Int
) {
  val palette = LocalCyberPalette.current

  Scaffold(
    containerColor = palette.bg,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = palette.surface,
          titleContentColor = palette.foreground
        ),
        title = {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(32.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(palette.primaryDim)
                  .border(1.dp, palette.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  Icons.Default.Security,
                  contentDescription = stringResource(R.string.cd_app_logo),
                  tint = palette.primary,
                  modifier = Modifier.size(18.dp)
                )
              }
              Spacer(Modifier.width(10.dp))
              Column {
                Text(
                  text = stringResource(R.string.app_name),
                  color = palette.foreground,
                  fontSize = 17.sp,
                  fontWeight = FontWeight.ExtraBold,
                  letterSpacing = 0.5.sp
                )
                Text(
                  text = stringResource(TABS[selectedTab].subtitleRes),
                  color = palette.primary,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.sp
                )
              }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = { viewModel.lock() },
                modifier = Modifier.size(48.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Lock,
                  contentDescription = stringResource(R.string.cd_lock_console),
                  tint = palette.mutedForeground,
                  modifier = Modifier.size(18.dp)
                )
              }
              Spacer(Modifier.width(4.dp))
              OfflinePill()
            }
          }
        }
      )
    },
    bottomBar = {
      NavigationBar(
        containerColor = palette.surface,
        modifier = Modifier.border(0.5.dp, palette.border)
      ) {
        TABS.forEachIndexed { index, tab ->
          val isSelected = selectedTab == index
          NavigationBarItem(
            selected = isSelected,
            onClick = { viewModel.selectTab(index) },
            modifier = Modifier.testTag("tab_${tab.titleRes}"),
            icon = {
              Icon(
                tab.icon,
                contentDescription = stringResource(tab.titleRes)
              )
            },
            label = {
              Text(
                text = stringResource(tab.titleRes),
                fontSize = 11.5.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
              )
            },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = palette.primary,
              selectedTextColor = palette.primary,
              indicatorColor = palette.primaryDim,
              unselectedIconColor = palette.mutedForeground,
              unselectedTextColor = palette.mutedForeground
            )
          )
        }
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .imePadding()
        .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
      when (selectedTab) {
        0 -> EncoderTabScreen(
          input = viewModel.encoderInput,
          onInputChange = { viewModel.encoderInput = it },
          mode = viewModel.encoderMode,
          onModeChange = { viewModel.encoderMode = it },
          format = viewModel.encoderFormat,
          onFormatChange = { viewModel.encoderFormat = it }
        )

        1 -> CryptoTabScreen(
          input = viewModel.cryptoInput,
          onInputChange = { viewModel.cryptoInput = it },
          secret = viewModel.cryptoSecret,
          onSecretChange = { viewModel.cryptoSecret = it },
          iterations = viewModel.cryptoIterations,
          onIterationsChange = { viewModel.cryptoIterations = it },
          aesPayload = viewModel.aesPayload,
          onAesPayloadChange = { viewModel.aesPayload = it },
          passwordHashResult = viewModel.passwordHashResult,
          onPasswordHashChange = { viewModel.passwordHashResult = it }
        )

        2 -> SheetsTabScreen(
          search = viewModel.cheatSearch,
          onSearchChange = { viewModel.cheatSearch = it },
          category = viewModel.cheatCategory,
          onCategoryChange = { viewModel.cheatCategory = it }
        )

        3 -> PayloadsTabScreen(
          host = settings.defaultHost,
          onHostChange = { value ->
            viewModel.updateSettings { it.copy(defaultHost = value) }
          },
          port = settings.defaultPort,
          onPortChange = { value ->
            viewModel.updateSettings { it.copy(defaultPort = value) }
          },
          environmentKey = viewModel.payloadEnv,
          onEnvironmentChange = viewModel::selectEnvironment
        )

        else -> SettingsTabScreen(
          settings = settings,
          onSettingsChange = viewModel::updateSettings,
          onLockNow = { viewModel.lock() },
          onWipeSession = { viewModel.clearSessionData() }
        )
      }
    }
  }
}

@Composable
private fun OfflinePill() {
  val palette = LocalCyberPalette.current
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .background(palette.primaryDim)
      .border(0.8.dp, palette.primary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
      .padding(horizontal = 9.dp, vertical = 4.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(palette.primary)
      )
      Spacer(Modifier.width(5.dp))
      Text(
        text = stringResource(R.string.badge_offline),
        color = palette.primary,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = TerminalFontFamily
      )
    }
  }
}

/** Best-effort lookup of the hosting activity from a composition context. */
private fun android.content.Context.findActivity(): MainActivity? {
  var candidate: Context? = this
  while (candidate is android.content.ContextWrapper) {
    if (candidate is MainActivity) return candidate
    candidate = candidate.baseContext
  }
  return null
}
