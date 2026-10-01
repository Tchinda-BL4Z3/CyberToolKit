@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.CyberSettings
import com.example.security.CyberSecurityManager
import com.example.security.LockoutGuard
import com.example.ui.components.CyberBadge
import com.example.ui.components.CyberMessage
import com.example.ui.components.CyberSegmentedControl
import com.example.ui.components.CyberSectionLabel
import com.example.ui.components.CyberTextField
import com.example.ui.theme.CyberTheme
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 5 - system and lock configuration.
 *
 * ### The bug this tab was shipping
 * It took the live combination as a `List<Int>` parameter, kept it in
 * `rememberSaveable`, and offered an eye icon that rendered it as
 * `"3 - 5 - 2 - 9 - 0"`. Combined with the plaintext storage in
 * `CyberSecurityManager`, the "secret" was readable at a glance on the Settings
 * screen and was written into the instance-state bundle on every rotation.
 *
 * Only a PBKDF2 hash is stored now, so the code simply cannot be displayed. The
 * active-code row shows a fixed mask, and rotation re-verifies the typed value
 * against the hash.
 */
@Composable
fun SettingsTabScreen(
  settings: CyberSettings,
  onSettingsChange: ((CyberSettings) -> CyberSettings) -> Unit,
  onLockNow: () -> Unit,
  onWipeSession: () -> Unit,
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val lockoutGuard = remember(context) { LockoutGuard(context.applicationContext) }

  var isEditingCode by remember { mutableStateOf(false) }
  var currentCodeInput by remember { mutableStateOf("") }
  var newCodeInput by remember { mutableStateOf("") }
  var codeError by remember { mutableStateOf<String?>(null) }
  var showPlainDigits by remember { mutableStateOf(false) }
  var isVerifying by remember { mutableStateOf(false) }
  var showConfirmation by remember { mutableStateOf(false) }
  var pendingNewCode by remember { mutableStateOf<List<Int>>(emptyList()) }
  var lockCountdown by remember { mutableIntStateOf(0) }
  var showWipeDialog by remember { mutableStateOf(false) }
  var showWipeDone by remember { mutableStateOf(false) }

  // Re-read whenever a rotation completes, otherwise these would be pinned to
  // whatever was true on first composition and the factory-code warning would
  // survive the very rotation it is warning about.
  var securityRevision by remember { mutableIntStateOf(0) }
  val isUserConfigured = remember(securityRevision) {
    CyberSecurityManager.isUserConfigured(context)
  }
  val usesFactoryCode = remember(securityRevision) {
    CyberSecurityManager.isUsingFactoryCode(context)
  }

  // Whether the post-rotation dialog forces a lock. Read once per dialog so the
  // countdown below cannot disagree with the button the operator is looking at.
  var lockAfterRotation by remember { mutableStateOf(true) }

  // Localised copy, resolved before any coroutine touches it.
  val errCurrentLength = stringResource(R.string.settings_err_current_length)
  val errCurrentWrong = stringResource(R.string.settings_err_current_wrong)
  val errNewLength = stringResource(R.string.settings_err_new_length)
  val errNewSame = stringResource(R.string.settings_err_new_same)
  val errWeak = stringResource(R.string.settings_err_weak)
  val errGeneric = stringResource(R.string.settings_err_generic)
  val dialogTitle = stringResource(R.string.dialog_code_changed_title)
  val dialogBody = stringResource(R.string.dialog_code_changed_body)
  val dialogHint = if (lockAfterRotation) {
    stringResource(R.string.dialog_code_changed_hint)
  } else {
    stringResource(R.string.dialog_code_changed_hint_no_lock)
  }
  val lockNowLabel = stringResource(R.string.settings_lock_now)
  val closeLabel = stringResource(android.R.string.ok)

  fun resetCodeForm() {
    isEditingCode = false
    currentCodeInput = ""
    newCodeInput = ""
    codeError = null
    showPlainDigits = false
  }

  // Only auto-lock when the operator asked for it. With the setting off the
  // dialog is a plain confirmation the user dismisses at their own pace.
  LaunchedEffect(showConfirmation) {
    if (!showConfirmation) return@LaunchedEffect
    if (!lockAfterRotation) return@LaunchedEffect
    lockCountdown = 3
    while (lockCountdown > 0) {
      delay(1000)
      lockCountdown -= 1
    }
    showConfirmation = false
    onLockNow()
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .testTag("settings_screen"),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // ---- Header ---------------------------------------------------------
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.primaryDim)
            .border(1.2.dp, palette.primary, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(26.dp)
          )
        }
        Spacer(Modifier.width(14.dp))
        Column {
          Text(
            text = stringResource(R.string.settings_title),
            color = palette.foreground,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            fontFamily = TerminalFontFamily
          )
          Spacer(Modifier.height(3.dp))
          Text(
            text = stringResource(R.string.settings_subtitle),
            color = palette.primary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }

    // ---- Lock security --------------------------------------------------
    CyberSectionLabel(
      icon = Icons.Default.Lock,
      label = stringResource(R.string.settings_section_lock)
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(14.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Text(
          text = stringResource(R.string.settings_current_code_label),
          color = palette.foreground,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold
        )

        // The code is a PBKDF2 hash on disk: there is nothing to reveal.
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = palette.terminalBg,
          border = BorderStroke(1.dp, palette.terminalBorder)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Default.Security,
              contentDescription = null,
              tint = palette.primary,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
              text = stringResource(
                if (isUserConfigured) R.string.settings_code_masked else R.string.settings_code_stored
              ),
              color = palette.primary,
              fontSize = 13.5.sp,
              fontFamily = TerminalFontFamily,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        if (usesFactoryCode) {
          CyberMessage(
            message = stringResource(R.string.settings_factory_code_warning),
            isError = true
          )
        }

        Button(
          onClick = { if (isEditingCode) resetCodeForm() else isEditingCode = true },
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("modify_combination_button"),
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isEditingCode) palette.primaryDim else palette.primary.copy(alpha = 0.15f),
            contentColor = palette.primary
          ),
          border = BorderStroke(1.2.dp, palette.primary),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(
            imageVector = if (isEditingCode) Icons.Default.Close else Icons.Default.LockReset,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = if (isEditingCode) {
              stringResource(R.string.settings_close_editing)
            } else {
              stringResource(R.string.settings_modify_button)
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
          )
        }

        AnimatedVisibility(
          visible = isEditingCode,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut()
        ) {
          CodeRotationForm(
            currentCodeInput = currentCodeInput,
            onCurrentCodeChange = {
              currentCodeInput = it.take(5)
              codeError = null
            },
            newCodeInput = newCodeInput,
            onNewCodeChange = {
              newCodeInput = it.take(5)
              codeError = null
            },
            showPlainDigits = showPlainDigits,
            onTogglePlainDigits = { showPlainDigits = !showPlainDigits },
            error = codeError,
            isVerifying = isVerifying,
            onCancel = { resetCodeForm() },
            onValidate = {
              val currentDigits = currentCodeInput.toIntListOrNull()
              val newDigits = newCodeInput.toIntListOrNull()
              when {
                isVerifying -> Unit
                currentDigits == null -> codeError = errCurrentLength
                newDigits == null -> codeError = errNewLength
                newDigits == currentDigits -> codeError = errNewSame
                else -> {
                  isVerifying = true
                  scope.launch {
                    // Two PBKDF2 verifications: keep both off the main thread.
                    val accepted = withContext(Dispatchers.Default) {
                      CyberSecurityManager.verifyCombination(context, currentDigits)
                    }
                    val weak = withContext(Dispatchers.Default) {
                      CyberSecurityManager.isWeakCombination(newDigits)
                    }
                    isVerifying = false
                    when {
                      !accepted -> codeError = errCurrentWrong
                      weak -> codeError = errWeak
                      !CyberSecurityManager.setCombination(context, newDigits) -> codeError = errGeneric
                      else -> {
                        pendingNewCode = newDigits
                        lockAfterRotation = settings.autoLockAfterCodeChange
                        securityRevision += 1
                        resetCodeForm()
                        showConfirmation = true
                      }
                    }
                  }
                }
              }
            }
          )
        }

        // Anti-bruteforce status, read live from the real ledger. Cheap: the
        // SharedPreferences map is already in memory.
        val state = remember(lockoutGuard) { lockoutGuard.state() }
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("bruteforce_status"),
          shape = RoundedCornerShape(8.dp),
          color = palette.terminalBg,
          border = BorderStroke(0.8.dp, palette.terminalBorder)
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Default.Security,
              contentDescription = null,
              tint = palette.primary,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
              Text(
                text = stringResource(R.string.settings_bruteforce_title),
                color = palette.primary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = TerminalFontFamily
              )
              Spacer(Modifier.height(2.dp))
              Text(
                text = pluralStringResource(
                  R.plurals.settings_bruteforce_body,
                  state.maxAttempts,
                  state.maxAttempts,
                  formatDuration(
                    LockoutGuard.durationForLevel(if (state.lockedOut) state.level else 0)
                  )
                ),
                color = palette.mutedForeground,
                fontSize = 12.sp
              )
            }
          }
        }

        SettingSwitchRow(
          icon = Icons.Default.WifiOff,
          title = stringResource(R.string.settings_screencapture_title),
          body = stringResource(R.string.settings_screencapture_body),
          checked = settings.blockScreenCapture,
          onCheckedChange = { value ->
            onSettingsChange { it.copy(blockScreenCapture = value) }
          }
        )

        // A real choice, not a switch: the operator picks how long the console
        // stays unlocked after the app leaves the foreground. `AUTO_LOCK_CHOICES`
        // holds 0 = immediate, so a selector avoids the "is it on or off?"
        // ambiguity a switch would leave.
        CyberSectionLabel(
          icon = Icons.Default.Lock,
          label = stringResource(R.string.settings_autolock_title)
        )
        Text(
          text = stringResource(R.string.settings_autolock_body),
          color = palette.mutedForeground,
          fontSize = 12.sp,
          modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        CyberSegmentedControl(
          options = CyberSettings.AUTO_LOCK_CHOICES.map { choice ->
            choice to if (choice == 0) {
              stringResource(R.string.settings_autolock_immediate)
            } else {
              formatDuration(choice.toLong() * 1000L)
            }
          },
          selected = settings.autoLockDelaySeconds,
          onSelect = { choice -> onSettingsChange { it.copy(autoLockDelaySeconds = choice) } },
          modifier = Modifier.fillMaxWidth()
        )

        SettingSwitchRow(
          icon = Icons.Default.LockReset,
          title = stringResource(R.string.settings_autolock_on_change),
          body = "",
          checked = settings.autoLockAfterCodeChange,
          onCheckedChange = { value ->
            onSettingsChange { it.copy(autoLockAfterCodeChange = value) }
          }
        )

        Button(
          onClick = onLockNow,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("lock_now_button"),
          colors = ButtonDefaults.buttonColors(
            containerColor = palette.destructive.copy(alpha = 0.2f),
            contentColor = palette.destructive
          ),
          border = BorderStroke(1.2.dp, palette.destructive),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = lockNowLabel,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
          )
        }
      }
    }

    // ---- Lab defaults ---------------------------------------------------
    CyberSectionLabel(
      icon = Icons.Default.Key,
      label = stringResource(R.string.settings_section_lab)
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(14.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Text(
          text = stringResource(R.string.settings_lab_help),
          color = palette.mutedForeground,
          fontSize = 13.sp
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          CyberTextField(
            value = settings.defaultHost,
            onValueChange = { value -> onSettingsChange { it.copy(defaultHost = value) } },
            label = stringResource(R.string.payload_host_label),
            modifier = Modifier.weight(1.5f),
            testTag = "settings_default_host"
          )
          CyberTextField(
            value = settings.defaultPort,
            onValueChange = { value ->
              onSettingsChange { it.copy(defaultPort = value.filter { ch -> ch.isDigit() }.take(5)) }
            },
            label = stringResource(R.string.payload_port_label),
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            testTag = "settings_default_port"
          )
        }
      }
    }

    // ---- Ergonomy -------------------------------------------------------
    CyberSectionLabel(
      icon = Icons.Default.Palette,
      label = stringResource(R.string.settings_section_ergo)
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(14.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Text(
          text = stringResource(R.string.settings_theme_title),
          color = palette.foreground,
          fontSize = 14.5.sp,
          fontWeight = FontWeight.Bold
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          themeLabels().forEach { (theme, label) ->
            val isSelected = settings.theme == theme
            Surface(
              onClick = { onSettingsChange { it.copy(theme = theme) } },
              shape = RoundedCornerShape(10.dp),
              color = if (isSelected) palette.primaryDim else palette.terminalBg,
              border = BorderStroke(1.dp, if (isSelected) palette.primary else palette.border),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .testTag("theme_${theme.key}")
            ) {
              Box(
                modifier = Modifier.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = label,
                  color = if (isSelected) palette.primary else palette.mutedForeground,
                  fontSize = 12.5.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
              }
            }
          }
        }

        SettingSwitchRow(
          icon = Icons.Default.Vibration,
          title = stringResource(R.string.settings_haptics_title),
          body = stringResource(R.string.settings_haptics_body),
          checked = settings.hapticsEnabled,
          onCheckedChange = { value -> onSettingsChange { it.copy(hapticsEnabled = value) } }
        )
      }
    }

    // ---- Data & privacy -------------------------------------------------
    CyberSectionLabel(
      icon = Icons.Default.CleaningServices,
      label = stringResource(R.string.settings_section_privacy)
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(14.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        SettingStatusRow(
          icon = Icons.Default.WifiOff,
          title = stringResource(R.string.settings_offline_title),
          body = stringResource(R.string.settings_offline_body),
          badge = stringResource(R.string.settings_offline_enforced)
        )

        Text(
          text = stringResource(R.string.settings_wipe_body),
          color = palette.mutedForeground,
          fontSize = 13.sp
        )

        OutlinedButton(
          onClick = { showWipeDialog = true },
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .testTag("wipe_session_button"),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.foreground),
          border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.8f)),
          shape = RoundedCornerShape(10.dp)
        ) {
          Icon(
            Icons.Default.CleaningServices,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.settings_wipe_action),
            color = palette.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = TerminalFontFamily
          )
        }

        if (showWipeDone) {
          Text(
            text = stringResource(R.string.settings_wipe_done),
            color = palette.primary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = TerminalFontFamily,
            modifier = Modifier.testTag("wipe_done")
          )
        }
      }
    }

    // ---- About ----------------------------------------------------------
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface.copy(alpha = 0.6f)),
      shape = RoundedCornerShape(14.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(16.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.settings_about_title),
            color = palette.foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = TerminalFontFamily
          )
        }
        Text(
          text = stringResource(R.string.settings_about_body),
          color = palette.mutedForeground,
          fontSize = 12.sp,
          lineHeight = 17.sp
        )
      }
    }

    Spacer(Modifier.height(16.dp))
  }

  if (showConfirmation) {
    AlertDialog(
      // When auto-lock-after-change is on, the operator asked to be locked, so
      // backing out still locks - that is the whole point of the setting.
      // With it off, the dialog is dismissible and nothing is forced.
      onDismissRequest = {
        if (lockAfterRotation) onLockNow() else showConfirmation = false
      },
      containerColor = palette.surface,
      tonalElevation = 6.dp,
      shape = RoundedCornerShape(16.dp),
      icon = {
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(palette.primaryDim)
            .border(1.5.dp, palette.primary, CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(34.dp)
          )
        }
      },
      title = {
        Text(
          text = dialogTitle,
          color = palette.foreground,
          fontSize = 17.sp,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = TerminalFontFamily,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(
            text = dialogBody,
            color = palette.mutedForeground,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center
          )
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.terminalBg,
            border = BorderStroke(1.2.dp, palette.primary)
          ) {
            Text(
              text = pendingNewCode.joinToString("   "),
              color = palette.primary,
              fontSize = 20.sp,
              fontWeight = FontWeight.ExtraBold,
              fontFamily = TerminalFontFamily,
              letterSpacing = 1.5.sp,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
          }
          Text(
            text = dialogHint,
            color = palette.foreground.copy(alpha = 0.9f),
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
          )
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = palette.primaryDim.copy(alpha = 0.7f),
            border = BorderStroke(0.8.dp, palette.primary)
          ) {
            Text(
              text = if (lockAfterRotation) {
                stringResource(R.string.dialog_lock_countdown, lockCountdown)
              } else {
                stringResource(R.string.dialog_no_auto_lock)
              },
              color = palette.primary,
              fontSize = 12.sp,
              fontFamily = TerminalFontFamily,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { if (lockAfterRotation) onLockNow() else showConfirmation = false },
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("confirm_lock_button"),
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = palette.primary,
            contentColor = palette.terminalBg
          )
        ) {
          Icon(
            imageVector = if (lockAfterRotation) Icons.Default.Lock else Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = if (lockAfterRotation) lockNowLabel else closeLabel,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }
    )
  }

  if (showWipeDialog) {
    AlertDialog(
      onDismissRequest = { showWipeDialog = false },
      containerColor = palette.surface,
      shape = RoundedCornerShape(16.dp),
      title = {
        Text(
          text = stringResource(R.string.dialog_wipe_title),
          color = palette.foreground,
          fontWeight = FontWeight.ExtraBold
        )
      },
      text = {
        Text(
          text = stringResource(R.string.dialog_wipe_body),
          color = palette.mutedForeground,
          fontSize = 13.5.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showWipeDialog = false
            onWipeSession()
            showWipeDone = true
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = palette.destructive,
            contentColor = Color.White
          ),
          modifier = Modifier.testTag("confirm_wipe_button")
        ) {
          Text(
            text = stringResource(R.string.dialog_wipe_confirm),
            fontWeight = FontWeight.ExtraBold
          )
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { showWipeDialog = false }) {
          Text(
            text = stringResource(R.string.action_cancel),
            color = palette.mutedForeground
          )
        }
      }
    )
  }
}

/** Two-step code rotation form, isolated so the parent stays readable. */
@Composable
private fun CodeRotationForm(
  currentCodeInput: String,
  onCurrentCodeChange: (String) -> Unit,
  newCodeInput: String,
  onNewCodeChange: (String) -> Unit,
  showPlainDigits: Boolean,
  onTogglePlainDigits: () -> Unit,
  error: String?,
  isVerifying: Boolean,
  onCancel: () -> Unit,
  onValidate: () -> Unit
) {
  val palette = LocalCyberPalette.current
  val transformation =
    if (showPlainDigits) VisualTransformation.None else PasswordVisualTransformation()

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = palette.terminalBg),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.2.dp, palette.primary.copy(alpha = 0.8f))
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Key,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.settings_change_code_title),
            color = palette.foreground,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = TerminalFontFamily
          )
        }
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = palette.primaryDim,
          border = BorderStroke(0.8.dp, palette.primary)
        ) {
          Text(
            text = stringResource(R.string.settings_change_code_badge),
            color = palette.primary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }

      Text(
        text = stringResource(R.string.settings_change_code_help),
        color = palette.mutedForeground,
        fontSize = 12.sp,
        lineHeight = 16.sp
      )

      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DigitStep(
          label = stringResource(R.string.settings_step_current),
          counter = currentCodeInput.length,
          value = currentCodeInput,
          onValueChange = { onCurrentCodeChange(it.filter { ch -> ch.isDigit() }) },
          placeholder = stringResource(R.string.settings_step_placeholder_current),
          transformation = transformation,
          testTag = "current_code_input",
          leadingIcon = { tint ->
            Icon(Icons.Default.Lock, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
          }
        )
      }

      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DigitStep(
          label = stringResource(R.string.settings_step_new),
          counter = newCodeInput.length,
          value = newCodeInput,
          onValueChange = { onNewCodeChange(it.filter { ch -> ch.isDigit() }) },
          placeholder = stringResource(R.string.settings_step_placeholder_new),
          transformation = transformation,
          testTag = "new_code_input",
          leadingIcon = { tint ->
            Icon(Icons.Default.Key, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
          }
        )
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onTogglePlainDigits)
          .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = if (showPlainDigits) Icons.Default.VisibilityOff else Icons.Default.Visibility,
          contentDescription = null,
          tint = palette.primary,
          modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = if (showPlainDigits) {
            stringResource(R.string.settings_hide_digits)
          } else {
            stringResource(R.string.settings_show_digits)
          },
          color = palette.primary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium
        )
      }

      error?.let { message ->
        CyberMessage(message = message, isError = true)
      }

      Text(
        text = stringResource(R.string.settings_change_code_warning),
        color = palette.foreground.copy(alpha = 0.85f),
        fontSize = 11.5.sp,
        lineHeight = 15.sp
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = onCancel,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp),
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, palette.border),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.mutedForeground)
        ) {
          Text(
            text = stringResource(R.string.action_cancel).uppercase(),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Button(
          onClick = onValidate,
          enabled = !isVerifying,
          modifier = Modifier
            .weight(1.6f)
            .heightIn(min = 48.dp)
            .testTag("validate_new_code_button"),
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = palette.primary,
            contentColor = palette.terminalBg,
            disabledContainerColor = palette.surfaceRaised,
            disabledContentColor = palette.mutedForeground
          )
        ) {
          Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.settings_validate_and_lock),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }
    }
  }
}

/** One labelled 5-digit step: counter, field, and cell preview. */
@Composable
private fun DigitStep(
  label: String,
  counter: Int,
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  transformation: VisualTransformation,
  testTag: String,
  leadingIcon: @Composable (Color) -> Unit
) {
  val palette = LocalCyberPalette.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      color = palette.foreground,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold
    )
    Text(
      text = stringResource(R.string.settings_digits_counter, counter),
      color = if (counter == 5) palette.primary else palette.mutedForeground,
      fontSize = 11.5.sp,
      fontFamily = TerminalFontFamily
    )
  }

  CyberTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = placeholder,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    visualTransformation = transformation,
    leadingIcon = { leadingIcon(palette.primary) },
    testTag = testTag
  )

  DigitCellsPreview(
    digits = value,
    showPlain = transformation == VisualTransformation.None
  )
}

@Composable
private fun DigitCellsPreview(digits: String, showPlain: Boolean) {
  val palette = LocalCyberPalette.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    for (i in 0 until 5) {
      val char = if (i < digits.length) digits[i] else null
      val isFilled = char != null
      Box(
        modifier = Modifier
          .weight(1f)
          .height(38.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(if (isFilled) palette.primaryDim else palette.surface)
          .border(
            width = if (isFilled) 1.2.dp else 0.8.dp,
            color = if (isFilled) palette.primary else palette.border,
            shape = RoundedCornerShape(6.dp)
          ),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = if (char != null) {
            if (showPlain) char.toString() else "•"
          } else {
            "-"
          },
          color = if (isFilled) palette.primary else palette.mutedForeground.copy(alpha = 0.5f),
          fontSize = if (char != null && !showPlain) 18.sp else 15.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = TerminalFontFamily
        )
      }
    }
  }
}

/**
 * A setting the operator cannot change, presented as an enforced fact rather
 * than as a switch.
 *
 * This exists because [SettingSwitchRow] renders a disabled row in the muted
 * colour, which reads as "off" or "unavailable". For a property that is always
 * on and cannot be turned off - here, the app declaring no INTERNET permission -
 * that is the wrong signal: the strongest guarantee in the app looked like a dead
 * control. There is no `Switch` at all here, so there is nothing to tempt the
 * operator into tapping, and the tint stays at full strength to mark the
 * guarantee as live.
 */
@Composable
private fun SettingStatusRow(
  icon: ImageVector,
  title: String,
  body: String,
  badge: String
) {
  val palette = LocalCyberPalette.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = palette.primary,
        modifier = Modifier.size(20.dp)
      )
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          text = title,
          color = palette.foreground,
          fontSize = 14.5.sp,
          fontWeight = FontWeight.Bold
        )
        if (body.isNotEmpty()) {
          Text(
            text = body,
            color = palette.mutedForeground,
            fontSize = 12.sp
          )
        }
      }
    }
    Spacer(Modifier.width(8.dp))
    CyberBadge(label = badge, tint = palette.primary)
  }
}

@Composable
private fun SettingSwitchRow(
  icon: ImageVector,
  title: String,
  body: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean = true
) {
  val palette = LocalCyberPalette.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) palette.primary else palette.mutedForeground,
        modifier = Modifier.size(20.dp)
      )
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          text = title,
          color = if (enabled) palette.foreground else palette.mutedForeground,
          fontSize = 14.5.sp,
          fontWeight = FontWeight.Bold
        )
        if (body.isNotEmpty()) {
          Text(
            text = body,
            color = palette.mutedForeground,
            fontSize = 12.sp
          )
        }
      }
    }
    Spacer(Modifier.width(8.dp))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      colors = SwitchDefaults.colors(
        checkedThumbColor = palette.primary,
        checkedTrackColor = palette.primaryDim,
        uncheckedThumbColor = palette.mutedForeground,
        uncheckedTrackColor = palette.surface
      )
    )
  }
}

@Composable
private fun themeLabels(): List<Pair<CyberTheme, String>> = listOf(
  CyberTheme.CYBER_GREEN to stringResource(R.string.settings_theme_cyber_green),
  CyberTheme.TACTICAL_BLUE to stringResource(R.string.settings_theme_tactical_blue),
  CyberTheme.STEALTH to stringResource(R.string.settings_theme_stealth)
)

/** Renders a millisecond duration using the localised minute/second strings. */
@Composable
private fun formatDuration(ms: Long): String {
  val totalSeconds = (ms + 999L) / 1000L
  val minutes = (totalSeconds / 60L).toInt()
  val seconds = (totalSeconds % 60L).toInt()
  return when {
    minutes == 0 -> stringResource(R.string.duration_seconds, seconds)
    seconds == 0 -> stringResource(R.string.duration_minutes, minutes)
    else -> stringResource(R.string.duration_minutes, minutes)
  }
}

/** `"24680"` -> `[2, 4, 6, 8, 0]`, or null when the length is not 5. */
private fun String.toIntListOrNull(): List<Int>? {
  if (length != 5) return null
  if (!all { it.isDigit() }) return null
  return map { it.digitToInt() }
}
