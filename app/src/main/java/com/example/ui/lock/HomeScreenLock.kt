@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.lock

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.security.LockoutGuard
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

enum class RouletteStatus { IDLE, SUCCESS, ERROR }

/**
 * Five-dial combination lock.
 *
 * ### What this screen no longer does
 *  - it does **not** receive or hold the secret code. Verification is delegated
 *    to [onAttempt], which lives in the security layer, so the code never enters
 *    the composition state (and therefore never reaches a saved-state `Bundle`);
 *  - it does **not** keep the attempt counter in `rememberSaveable`. That was
 *    the actual bypass: the counter was written into the instance-state bundle,
 *    so force-stopping the app restored a full 3-attempt budget. The ledger now
 *    lives in [LockoutGuard];
 *  - it does **not** ship an "unlock now" button. The previous build rendered
 *    one right inside the 10-minute lockout banner, which voided the whole
 *    anti-bruteforce rule.
 *
 * The countdown is driven by [LockoutGuard]'s monotonic deadline instead of
 * polling `System.currentTimeMillis()` in an unbounded `while (true)`.
 */
@Composable
fun HomeScreenLock(
  onAttempt: (List<Int>) -> Boolean,
  onUnlocked: () -> Unit,
  hapticsEnabled: Boolean,
  modifier: Modifier = Modifier,
  lockoutGuard: LockoutGuard = rememberLockoutGuard()
) {
  val palette = LocalCyberPalette.current
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()

  val dials = remember { mutableStateListOf(0, 0, 0, 0, 0) }
  var validationStatus by remember { mutableStateOf(RouletteStatus.IDLE) }
  val shakeOffset = remember { Animatable(0f) }
  /** Guards against a double tap queueing two PBKDF2 verifications. */
  var isVerifying by remember { mutableStateOf(false) }

  var lockout by remember { mutableStateOf(lockoutGuard.state()) }

  LaunchedEffect(lockout.lockedOut) {
    while (lockout.lockedOut) {
      delay(POLL_INTERVAL_MS)
      lockout = lockoutGuard.state()
    }
  }

  val isLocked = lockout.lockedOut || isVerifying
  val statusMessage = when {
    lockout.lockedOut -> stringResource(R.string.lock_status_blocked)
    validationStatus == RouletteStatus.SUCCESS -> stringResource(R.string.lock_status_correct)
    validationStatus == RouletteStatus.ERROR ->
      pluralStringResource(
        R.plurals.lock_status_wrong_remaining,
        lockout.attemptsLeft,
        lockout.attemptsLeft
      )
    else -> stringResource(R.string.lock_status_auth_required)
  }

  val statusColors = when {
    validationStatus == RouletteStatus.SUCCESS ->
      palette.primary to palette.primaryDim
    validationStatus == RouletteStatus.ERROR || lockout.lockedOut ->
      palette.destructive to palette.destructive.copy(alpha = 0.22f)
    else -> palette.foreground to palette.surface
  }
  val statusBorder = when {
    validationStatus == RouletteStatus.SUCCESS -> palette.primary
    validationStatus == RouletteStatus.ERROR || lockout.lockedOut -> palette.destructive
    else -> palette.border
  }

  fun tap(kind: HapticFeedbackType) {
    if (hapticsEnabled) haptics.performHapticFeedback(kind)
  }

  fun step(index: Int, delta: Int) {
    if (lockout.lockedOut) return
    dials[index] = (dials[index] + delta + 10) % 10
    if (validationStatus != RouletteStatus.IDLE) validationStatus = RouletteStatus.IDLE
    tap(HapticFeedbackType.TextHandleMove)
  }

  fun triggerUnlock() {
    if (lockout.lockedOut || isVerifying) return
    val candidate = dials.toList()
    isVerifying = true

    scope.launch {
      // PBKDF2 is deliberately slow (210k iterations): keep it off the main thread.
      val accepted = withContext(Dispatchers.Default) { onAttempt(candidate) }
      isVerifying = false

      if (accepted) {
        validationStatus = RouletteStatus.SUCCESS
        lockoutGuard.registerSuccess()
        tap(HapticFeedbackType.LongPress)
        delay(SUCCESS_DELAY_MS)
        onUnlocked()
      } else {
        lockout = lockoutGuard.registerFailure()
        validationStatus = RouletteStatus.ERROR
        tap(HapticFeedbackType.LongPress)
        shakeOffset.animateTo(18f, tween(45, easing = LinearEasing))
        shakeOffset.animateTo(-18f, tween(45, easing = LinearEasing))
        shakeOffset.animateTo(12f, tween(45, easing = LinearEasing))
        shakeOffset.animateTo(-12f, tween(45, easing = LinearEasing))
        shakeOffset.animateTo(0f, tween(45, easing = LinearEasing))
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(palette.bg)
  ) {
    TransparentPadlockBackground(
      isUnlocked = validationStatus == RouletteStatus.SUCCESS,
      status = validationStatus,
      modifier = Modifier
        .align(Alignment.Center)
        .size(420.dp)
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      // ---- Header -------------------------------------------------------
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 8.dp)
      ) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surfaceRaised)
            .border(
              1.5.dp,
              when (validationStatus) {
                RouletteStatus.SUCCESS -> palette.primary
                RouletteStatus.ERROR -> palette.destructive
                RouletteStatus.IDLE -> if (lockout.lockedOut) palette.destructive else palette.primary.copy(alpha = 0.6f)
              },
              RoundedCornerShape(18.dp)
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = when {
              validationStatus == RouletteStatus.SUCCESS -> Icons.Default.LockOpen
              lockout.lockedOut -> Icons.Default.Block
              else -> Icons.Default.Security
            },
            contentDescription = stringResource(R.string.cd_lock_status),
            tint = when {
              validationStatus == RouletteStatus.SUCCESS -> palette.primary
              validationStatus == RouletteStatus.ERROR || lockout.lockedOut -> palette.destructive
              else -> palette.primary
            },
            modifier = Modifier.size(32.dp)
          )
        }

        Spacer(Modifier.height(14.dp))

        Text(
          text = stringResource(R.string.lock_app_title),
          color = palette.foreground,
          fontSize = 30.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 2.5.sp,
          fontFamily = TerminalFontFamily
        )

        Spacer(Modifier.height(6.dp))

        Text(
          text = stringResource(R.string.lock_app_subtitle),
          color = if (lockout.lockedOut) palette.destructive else palette.primary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 2.sp
        )

        Spacer(Modifier.height(14.dp))

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = statusColors.second,
          border = BorderStroke(1.2.dp, statusBorder)
        ) {
          Row(
            modifier = Modifier
              .testTag("lock_status")
              .padding(horizontal = 14.dp, vertical = 7.dp)
              .semantics { contentDescription = statusMessage },
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(statusColors.first)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = statusMessage,
              color = statusColors.first,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = TerminalFontFamily,
              letterSpacing = 0.5.sp
            )
          }
        }
      }

      Spacer(Modifier.height(20.dp))

      // ---- Tumbler ------------------------------------------------------
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
        colors = CardDefaults.cardColors(containerColor = palette.surface.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
          1.5.dp,
          when (validationStatus) {
            RouletteStatus.SUCCESS -> palette.primary
            RouletteStatus.ERROR -> palette.destructive
            RouletteStatus.IDLE -> if (lockout.lockedOut) palette.destructive.copy(alpha = 0.6f) else palette.border
          }
        )
      ) {
        Column(
          modifier = Modifier.padding(18.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Default.VpnKey,
                contentDescription = null,
                tint = if (validationStatus == RouletteStatus.IDLE) palette.primary else statusColors.first,
                modifier = Modifier.size(18.dp)
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.lock_roulette_label),
                color = palette.foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
              )
            }

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (lockout.lockedOut) palette.destructive.copy(alpha = 0.2f) else palette.terminalBg,
              border = BorderStroke(
                1.dp,
                if (lockout.lockedOut) palette.destructive else palette.border
              )
            ) {
              Text(
                text = if (lockout.lockedOut) {
                  stringResource(R.string.lock_badge_blocked)
                } else {
                  pluralStringResource(
                    R.plurals.lock_attempts_badge,
                    lockout.attemptsLeft,
                    lockout.attemptsLeft,
                    lockout.maxAttempts
                  )
                },
                color = if (lockout.lockedOut || lockout.attemptsLeft == 1) {
                  palette.destructive
                } else {
                  palette.foreground
                },
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = TerminalFontFamily,
                modifier = Modifier
                  .testTag("lock_attempts")
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          Spacer(Modifier.height(16.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
          ) {
            dials.forEachIndexed { index, digit ->
              TumblerDial(
                digit = digit,
                status = validationStatus,
                isEnabled = !isLocked,
                onIncrement = { step(index, +1) },
                onDecrement = { step(index, -1) },
                modifier = Modifier.testTag("dial_$index"),
                valueTag = "dial_${index}_value",
                incrementTag = "dial_${index}_inc",
                decrementTag = "dial_${index}_dec"
              )
            }
          }
        }
      }

      Spacer(Modifier.height(20.dp))

      // ---- Controls -----------------------------------------------------
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        if (lockout.lockedOut) {
          LockoutBanner(state = lockout)
          Spacer(Modifier.height(16.dp))
        }

        Button(
          onClick = { triggerUnlock() },
          enabled = !isLocked,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("unlock_button"),
          colors = ButtonDefaults.buttonColors(
            containerColor = when (validationStatus) {
              RouletteStatus.SUCCESS -> palette.primary
              RouletteStatus.ERROR -> palette.destructive
              RouletteStatus.IDLE -> if (isLocked) palette.surfaceRaised else palette.primary
            },
            contentColor = when (validationStatus) {
              RouletteStatus.SUCCESS -> Color.Black
              RouletteStatus.ERROR -> Color.White
              RouletteStatus.IDLE -> if (isLocked) palette.mutedForeground else Color.Black
            },
            disabledContainerColor = palette.surfaceRaised,
            disabledContentColor = palette.mutedForeground
          ),
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(
            1.2.dp,
            if (lockout.lockedOut) palette.border else palette.primary
          )
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = when {
                validationStatus == RouletteStatus.SUCCESS -> Icons.Default.LockOpen
                lockout.lockedOut -> Icons.Default.Block
                else -> Icons.Default.Lock
              },
              contentDescription = null,
              modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
              text = when {
                isVerifying -> stringResource(R.string.lock_action_verifying)
                lockout.lockedOut -> stringResource(R.string.lock_action_locked)
                validationStatus == RouletteStatus.SUCCESS -> stringResource(R.string.lock_action_granted)
                validationStatus == RouletteStatus.ERROR -> stringResource(R.string.lock_action_wrong)
                else -> stringResource(R.string.lock_action_validate)
              },
              fontSize = 15.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 1.sp
            )
          }
        }
      }
    }
  }
}

/** Live lockout banner. No reset affordance: that was the bypass. */
@Composable
private fun LockoutBanner(state: LockoutGuard.State) {
  val palette = LocalCyberPalette.current
  val minutes = state.remainingSeconds / 60
  val seconds = state.remainingSeconds % 60
  val formatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("lockout_banner"),
    shape = RoundedCornerShape(16.dp),
    color = palette.destructive.copy(alpha = 0.15f),
    border = BorderStroke(1.5.dp, palette.destructive)
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Default.Timer,
          contentDescription = null,
          tint = palette.destructive,
          modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.lock_blocked_countdown, formatted),
          color = palette.destructive,
          fontSize = 17.sp,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = TerminalFontFamily,
          letterSpacing = 1.sp
        )
      }
      Spacer(Modifier.height(8.dp))
      Text(
        text = pluralStringResource(
          R.plurals.lock_blocked_explain,
          state.maxAttempts,
          state.maxAttempts
        ),
        color = palette.foreground.copy(alpha = 0.9f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center
      )
      if (state.level > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.lock_blocked_escalation),
          color = palette.mutedForeground,
          fontSize = 11.5.sp,
          textAlign = TextAlign.Center
        )
      }
      if (state.clockRollbackDetected) {
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.lock_blocked_clock_warning),
          color = palette.destructive,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

/**
 * Mechanical tumbler dial: tap the barrel or the arrows to step, or drag
 * vertically across the barrel for fast adjustment.
 */
@Composable
fun TumblerDial(
  digit: Int,
  status: RouletteStatus,
  isEnabled: Boolean,
  onIncrement: () -> Unit,
  onDecrement: () -> Unit,
  modifier: Modifier = Modifier,
  valueTag: String,
  incrementTag: String,
  decrementTag: String
) {
  val palette = LocalCyberPalette.current

  val borderColor by animateColorAsState(
    targetValue = when (status) {
      RouletteStatus.SUCCESS -> palette.primary
      RouletteStatus.ERROR -> palette.destructive
      RouletteStatus.IDLE -> palette.border
    },
    animationSpec = tween(durationMillis = 200),
    label = "tumbler_border"
  )

  val containerColor by animateColorAsState(
    targetValue = when (status) {
      RouletteStatus.SUCCESS -> palette.primaryDim.copy(alpha = 0.5f)
      RouletteStatus.ERROR -> palette.destructive.copy(alpha = 0.18f)
      RouletteStatus.IDLE -> palette.terminalBg
    },
    animationSpec = tween(durationMillis = 200),
    label = "tumbler_container"
  )

  // Accumulated drag distance, in px, not yet converted into digit steps.
  var dragAccumulator by remember { mutableFloatStateOf(0f) }
  val dialLabel = stringResource(R.string.cd_dial_value, digit)

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    modifier = modifier
  ) {
    DialButton(
      icon = Icons.Default.KeyboardArrowUp,
      contentDescription = stringResource(R.string.cd_increment_dial),
      enabled = isEnabled,
      onClick = onIncrement,
      modifier = Modifier.testTag(incrementTag)
    )

    Surface(
      onClick = onIncrement,
      enabled = isEnabled,
      shape = RoundedCornerShape(12.dp),
      color = containerColor,
      border = BorderStroke(1.5.dp, borderColor),
      modifier = Modifier
        .testTag(valueTag)
        .width(56.dp)
        .height(92.dp)
        .pointerInput(isEnabled) {
          if (!isEnabled) return@pointerInput
          detectVerticalDragGestures(
            onDragEnd = { dragAccumulator = 0f },
            onVerticalDrag = { change, delta ->
              change.consume()
              dragAccumulator += delta
              while (dragAccumulator >= DRAG_STEP_PX) {
                dragAccumulator -= DRAG_STEP_PX
                onDecrement()
              }
              while (dragAccumulator <= -DRAG_STEP_PX) {
                dragAccumulator += DRAG_STEP_PX
                onIncrement()
              }
            }
          )
        }
        .semantics { contentDescription = dialLabel }
    ) {
      Box(contentAlignment = Alignment.Center) {
        Box(
          modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.border.copy(alpha = 0.5f))
        )

        Box(
          modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .height(40.dp)
            .border(
              1.dp,
              when (status) {
                RouletteStatus.SUCCESS -> palette.primary.copy(alpha = 0.7f)
                RouletteStatus.ERROR -> palette.destructive.copy(alpha = 0.7f)
                RouletteStatus.IDLE -> Color.Transparent
              },
              RoundedCornerShape(6.dp)
            )
        )

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Text(
            text = "${(digit - 1 + 10) % 10}",
            color = palette.mutedForeground.copy(alpha = 0.35f),
            fontSize = 13.sp,
            fontFamily = TerminalFontFamily
          )

          AnimatedContent(
            targetState = digit,
            transitionSpec = {
              if (targetState > initialState || (initialState == 9 && targetState == 0)) {
                (slideInVertically(animationSpec = spring()) { height -> height } + fadeIn())
                  .togetherWith(slideOutVertically(animationSpec = spring()) { height -> -height } + fadeOut())
              } else {
                (slideInVertically(animationSpec = spring()) { height -> -height } + fadeIn())
                  .togetherWith(slideOutVertically(animationSpec = spring()) { height -> height } + fadeOut())
              }
            },
            label = "digit_animation"
          ) { targetDigit ->
            Text(
              text = "$targetDigit",
              color = when (status) {
                RouletteStatus.SUCCESS -> palette.primary
                RouletteStatus.ERROR -> palette.destructive
                RouletteStatus.IDLE -> palette.foreground
              },
              fontSize = 30.sp,
              fontWeight = FontWeight.ExtraBold,
              fontFamily = TerminalFontFamily,
              textAlign = TextAlign.Center
            )
          }

          Text(
            text = "${(digit + 1) % 10}",
            color = palette.mutedForeground.copy(alpha = 0.35f),
            fontSize = 13.sp,
            fontFamily = TerminalFontFamily
          )
        }

        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.border.copy(alpha = 0.5f))
        )
      }
    }

    DialButton(
      icon = Icons.Default.KeyboardArrowDown,
      contentDescription = stringResource(R.string.cd_decrement_dial),
      enabled = isEnabled,
      onClick = onDecrement,
      modifier = Modifier.testTag(decrementTag)
    )
  }
}

/** 48dp-minimum arrow button, previously 46dp. */
@Composable
private fun DialButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(10.dp),
    color = palette.surfaceRaised,
    border = BorderStroke(1.dp, if (enabled) palette.border else palette.border.copy(alpha = 0.3f)),
    modifier = modifier
      .size(48.dp)
      .semantics { this.contentDescription = contentDescription }
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) palette.foreground else palette.mutedForeground.copy(alpha = 0.4f),
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

/** Transparent high-tech padlock: radar rings, laser scan, lifting shackle. */
@Composable
fun TransparentPadlockBackground(
  isUnlocked: Boolean,
  status: RouletteStatus,
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  val infiniteTransition = rememberInfiniteTransition(label = "padlock_infinite")

  val radarSweep by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 5000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "radar_sweep"
  )

  val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.15f,
    targetValue = 0.35f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "glow_pulse"
  )

  val laserPosition by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2800, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "laser_sweep"
  )

  val shackleLift by animateFloatAsState(
    targetValue = if (isUnlocked) -44f else 0f,
    animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f),
    label = "shackle_lift"
  )

  val primaryAccent = when (status) {
    RouletteStatus.SUCCESS -> palette.primary
    RouletteStatus.ERROR -> palette.destructive
    RouletteStatus.IDLE -> palette.primary.copy(alpha = 0.7f)
  }

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height

    drawCircle(
      color = primaryAccent.copy(alpha = glowAlpha * 0.45f),
      radius = w * 0.48f,
      center = Offset(w * 0.5f, h * 0.54f),
      style = Stroke(width = 1.8.dp.toPx())
    )
    drawCircle(
      color = primaryAccent.copy(alpha = glowAlpha * 0.25f),
      radius = w * 0.42f,
      center = Offset(w * 0.5f, h * 0.54f),
      style = Stroke(width = 1.2.dp.toPx())
    )

    drawArc(
      color = primaryAccent.copy(alpha = glowAlpha * 0.9f),
      startAngle = radarSweep,
      sweepAngle = 75f,
      useCenter = false,
      topLeft = Offset(w * 0.04f, h * 0.08f),
      size = Size(w * 0.92f, h * 0.92f),
      style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )
    drawArc(
      color = primaryAccent.copy(alpha = glowAlpha * 0.6f),
      startAngle = radarSweep + 180f,
      sweepAngle = 55f,
      useCenter = false,
      topLeft = Offset(w * 0.08f, h * 0.12f),
      size = Size(w * 0.84f, h * 0.84f),
      style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    )

    val shackleLeft = w * 0.30f
    val shackleRight = w * 0.70f
    val shackleTop = h * 0.08f + shackleLift
    val shackleBottom = h * 0.42f + (if (isUnlocked) shackleLift else 0f)

    val shacklePath = Path().apply {
      moveTo(shackleLeft, shackleBottom)
      lineTo(shackleLeft, shackleTop + (w * 0.20f))
      arcTo(
        rect = androidx.compose.ui.geometry.Rect(
          left = shackleLeft,
          top = shackleTop,
          right = shackleRight,
          bottom = shackleTop + (shackleRight - shackleLeft)
        ),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false
      )
      lineTo(shackleRight, if (isUnlocked) shackleTop + (w * 0.12f) else shackleBottom)
    }

    drawPath(
      path = shacklePath,
      color = primaryAccent.copy(alpha = if (isUnlocked) 0.85f else glowAlpha),
      style = Stroke(width = 13.dp.toPx(), cap = StrokeCap.Round)
    )
    drawPath(
      path = shacklePath,
      color = if (isUnlocked) palette.primary.copy(alpha = 0.95f) else Color(0xFFE2E8F0).copy(alpha = 0.25f),
      style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
    )

    val bodyLeft = w * 0.16f
    val bodyTop = h * 0.38f
    val bodyWidth = w * 0.68f
    val bodyHeight = h * 0.50f
    val bodyRadius = 28.dp.toPx()

    drawRoundRect(
      brush = Brush.verticalGradient(
        colors = listOf(
          palette.surfaceRaised.copy(alpha = 0.45f),
          palette.terminalBg.copy(alpha = 0.68f)
        )
      ),
      topLeft = Offset(bodyLeft, bodyTop),
      size = Size(bodyWidth, bodyHeight),
      cornerRadius = CornerRadius(bodyRadius, bodyRadius)
    )
    drawRoundRect(
      color = primaryAccent.copy(alpha = if (isUnlocked) 0.85f else 0.40f),
      topLeft = Offset(bodyLeft, bodyTop),
      size = Size(bodyWidth, bodyHeight),
      cornerRadius = CornerRadius(bodyRadius, bodyRadius),
      style = Stroke(width = 2.dp.toPx())
    )

    val currentLaserY = bodyTop + (bodyHeight * laserPosition)
    drawLine(
      brush = Brush.horizontalGradient(
        colors = listOf(
          Color.Transparent,
          primaryAccent.copy(alpha = 0.5f),
          primaryAccent.copy(alpha = 0.90f),
          primaryAccent.copy(alpha = 0.5f),
          Color.Transparent
        )
      ),
      start = Offset(bodyLeft + 12f, currentLaserY),
      end = Offset(bodyLeft + bodyWidth - 12f, currentLaserY),
      strokeWidth = 2.5.dp.toPx()
    )

    val keyholeCenter = Offset(w * 0.50f, h * 0.57f)
    drawCircle(
      color = primaryAccent.copy(alpha = if (isUnlocked) 0.95f else glowAlpha * 1.6f),
      radius = 15.dp.toPx(),
      center = keyholeCenter
    )
    val slotPath = Path().apply {
      moveTo(keyholeCenter.x - 6.dp.toPx(), keyholeCenter.y + 4.dp.toPx())
      lineTo(keyholeCenter.x + 6.dp.toPx(), keyholeCenter.y + 4.dp.toPx())
      lineTo(keyholeCenter.x + 11.dp.toPx(), keyholeCenter.y + 28.dp.toPx())
      lineTo(keyholeCenter.x - 11.dp.toPx(), keyholeCenter.y + 28.dp.toPx())
      close()
    }
    drawPath(
      path = slotPath,
      color = primaryAccent.copy(alpha = if (isUnlocked) 0.95f else glowAlpha * 1.6f)
    )

    val cornerLen = 18.dp.toPx()
    drawLine(
      color = primaryAccent.copy(alpha = 0.85f),
      start = Offset(bodyLeft, bodyTop + cornerLen),
      end = Offset(bodyLeft, bodyTop),
      strokeWidth = 3.dp.toPx()
    )
    drawLine(
      color = primaryAccent.copy(alpha = 0.85f),
      start = Offset(bodyLeft, bodyTop),
      end = Offset(bodyLeft + cornerLen, bodyTop),
      strokeWidth = 3.dp.toPx()
    )
    drawLine(
      color = primaryAccent.copy(alpha = 0.85f),
      start = Offset(bodyLeft + bodyWidth, bodyTop + bodyHeight - cornerLen),
      end = Offset(bodyLeft + bodyWidth, bodyTop + bodyHeight),
      strokeWidth = 3.dp.toPx()
    )
    drawLine(
      color = primaryAccent.copy(alpha = 0.85f),
      start = Offset(bodyLeft + bodyWidth, bodyTop + bodyHeight),
      end = Offset(bodyLeft + bodyWidth - cornerLen, bodyTop + bodyHeight),
      strokeWidth = 3.dp.toPx()
    )
  }
}

@Composable
private fun rememberLockoutGuard(): LockoutGuard {
  val context = LocalContext.current
  return remember(context) { LockoutGuard(context.applicationContext) }
}

private const val POLL_INTERVAL_MS = 500L
private const val SUCCESS_DELAY_MS = 850L
private const val DRAG_STEP_PX = 28f
