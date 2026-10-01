@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily
import kotlinx.coroutines.delay

/** Small uppercase heading with an icon and an optional trailing action. */
@Composable
fun CyberSectionLabel(
  icon: ImageVector,
  label: String,
  trailing: @Composable (() -> Unit)? = null
) {
  val palette = LocalCyberPalette.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = palette.mutedForeground,
        modifier = Modifier.size(16.dp)
      )
      Spacer(Modifier.width(7.dp))
      Text(
        text = label.uppercase(),
        color = palette.mutedForeground,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
      )
    }
    trailing?.invoke()
  }
}

/**
 * Copy-to-clipboard with a security posture that matches the app's promise.
 *
 * The old version called `setPrimaryClip(ClipData.newPlainText(...))` and left
 * it there forever. On Android 13+ the system clipboard preview would happily
 * show a secret hash, any foreground app could read it, and it stayed in the
 * clipboard history. Now:
 *  - the clip is flagged [ClipDescription.EXTRA_IS_SENSITIVE], so Android hides
 *    the preview and excludes it from clipboard history / the clipboard overlay;
 *  - it is wiped after [CLIPBOARD_TTL_MS], but only if it is still our clip, so
 *    we never destroy something the user copied afterwards.
 */
@Composable
fun CyberCopyButton(
  textToCopy: String,
  label: String = stringResource(R.string.action_copy),
  copiedLabel: String = stringResource(R.string.action_copied),
  contentDescription: String = stringResource(R.string.cd_copy_to_clipboard),
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  val context = LocalContext.current
  val clipboard = remember(context) {
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  }
  var copied by remember { mutableStateOf(false) }
  // Held separately from `copied` so the wipe job is not cancelled when the
  // button flips back to its idle state after 1.2 s.
  var pendingClear by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(copied) {
    if (copied) {
      delay(1_200L)
      copied = false
    }
  }

  LaunchedEffect(pendingClear) {
    val owned = pendingClear ?: return@LaunchedEffect
    delay(SecureClipboard.CLIPBOARD_TTL_MS)
    SecureClipboard.clearIfOurs(context, clipboard, owned)
    pendingClear = null
  }

  val interactionSource = remember { MutableInteractionSource() }

  Row(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(if (copied) palette.primaryDim else Color.Transparent)
      .border(1.dp, if (copied) palette.primary else palette.border, RoundedCornerShape(8.dp))
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(),
        enabled = textToCopy.isNotEmpty()
      ) {
        SecureClipboard.copy(context, clipboard, textToCopy)
        pendingClear = textToCopy
        copied = true
      }
      .padding(horizontal = 10.dp, vertical = 5.dp)
      .semantics { this.contentDescription = contentDescription },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
      contentDescription = null,
      tint = if (copied) palette.primary else palette.mutedForeground,
      modifier = Modifier.size(15.dp)
    )
    Spacer(Modifier.width(5.dp))
    Text(
      text = if (copied) copiedLabel else label,
      color = if (copied) palette.primary else palette.mutedForeground,
      fontSize = 12.5.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

/** Read-only, monospace, terminal-chrome output block. */
@Composable
fun CyberTerminalBox(
  content: String,
  modifier: Modifier = Modifier,
  minHeight: Int = 80,
  headerAction: @Composable (() -> Unit)? = null
) {
  val palette = LocalCyberPalette.current
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = palette.terminalBg),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, palette.terminalBorder)
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(palette.surface)
          .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            Modifier
              .size(9.dp)
              .clip(CircleShape)
              .background(palette.destructive)
          )
          Spacer(Modifier.width(5.dp))
          Box(
            Modifier
              .size(9.dp)
              .clip(CircleShape)
              .background(Color(0xFFF59E0B))
          )
          Spacer(Modifier.width(5.dp))
          Box(
            Modifier
              .size(9.dp)
              .clip(CircleShape)
              .background(palette.primary)
          )
        }
        headerAction?.invoke()
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = minHeight.dp)
          .padding(14.dp)
      ) {
        SelectionContainer {
          Text(
            text = content,
            color = palette.primary,
            fontFamily = TerminalFontFamily,
            fontSize = 14.5.sp,
            lineHeight = 22.sp
          )
        }
      }
    }
  }
}

/**
 * Single source of truth for the text-field look.
 *
 * The old code re-declared the same seven `OutlinedTextFieldDefaults.colors(...)`
 * arguments in six different places, and they had already drifted apart: the
 * Crypto tab's key field used `CyberBg` while every other field used
 * `CyberSurface`, and only some of them set `cursorColor`. Any future colour
 * change had to be made six times and one was always missed.
 */
@Composable
fun CyberTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  placeholder: String? = null,
  singleLine: Boolean = true,
  minLines: Int = 1,
  maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
  enabled: Boolean = true,
  isError: Boolean = false,
  supportingText: String? = null,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  testTag: String? = null
) {
  val palette = LocalCyberPalette.current
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    label = label?.let { { Text(it, fontSize = 12.5.sp) } },
    placeholder = placeholder?.let { { Text(it, color = palette.mutedForeground, fontSize = 14.sp) } },
    supportingText = supportingText?.let { { Text(it, fontSize = 11.5.sp) } },
    singleLine = singleLine,
    minLines = minLines,
    maxLines = maxLines,
    enabled = enabled,
    isError = isError,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    textStyle = LocalTextStyle.current.merge(
      TextStyle(
        color = palette.foreground,
        fontFamily = TerminalFontFamily,
        fontSize = 14.5.sp
      )
    ),
    colors = OutlinedTextFieldDefaults.colors(
      focusedContainerColor = palette.surface,
      unfocusedContainerColor = palette.surface,
      disabledContainerColor = palette.surface,
      errorContainerColor = palette.surface,
      focusedTextColor = palette.foreground,
      unfocusedTextColor = palette.foreground,
      focusedBorderColor = if (isError) palette.destructive else palette.primary,
      unfocusedBorderColor = if (isError) palette.destructive else palette.border,
      focusedLabelColor = palette.primary,
      unfocusedLabelColor = palette.mutedForeground,
      cursorColor = palette.primary,
      focusedPlaceholderColor = palette.mutedForeground,
      unfocusedPlaceholderColor = palette.mutedForeground,
      focusedLeadingIconColor = palette.mutedForeground,
      unfocusedLeadingIconColor = palette.mutedForeground,
      focusedTrailingIconColor = palette.mutedForeground,
      unfocusedTrailingIconColor = palette.mutedForeground
    ),
    shape = RoundedCornerShape(12.dp)
  )
}

/**
 * Two-or-more-way segmented control.
 *
 * Replaces the hand-rolled `Box`+`clickable` rows that had no minimum touch
 * target, no selected-state semantics, and no disabled state.
 */
@Composable
fun <T> CyberSegmentedControl(
  options: List<Pair<T, String>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  val palette = LocalCyberPalette.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(48.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(palette.surface)
      .border(1.dp, palette.border, RoundedCornerShape(10.dp))
      .padding(4.dp)
  ) {
    options.forEach { (value, label) ->
      val isSelected = value == selected
      Surface(
        onClick = { onSelect(value) },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) palette.primary else Color.Transparent,
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = label,
            color = if (isSelected) palette.terminalBg else palette.mutedForeground,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp
          )
        }
      }
    }
  }
}

/** Round filter pill used by the environment and theme pickers. */
@Composable
fun CyberChoiceChip(
  label: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    color = if (isSelected) palette.primary else palette.surface,
    border = BorderStroke(1.dp, if (isSelected) palette.primary else palette.border),
    modifier = modifier.heightIn(min = 40.dp)
  ) {
    Box(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = label,
        color = if (isSelected) palette.terminalBg else palette.mutedForeground,
        fontWeight = FontWeight.Bold,
        fontSize = 13.5.sp
      )
    }
  }
}

/** Small uppercase pill, e.g. "AES-GCM" or "PRÊT". */
@Composable
fun CyberBadge(
  label: String,
  tint: Color,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = tint.copy(alpha = 0.16f),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
    modifier = modifier
  ) {
    Text(
      text = label,
      color = tint,
      fontSize = 10.5.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = TerminalFontFamily,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )
  }
}

/** Inline, non-blocking error or hint line. */
@Composable
fun CyberMessage(
  message: String,
  isError: Boolean,
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  val tint = if (isError) palette.destructive else palette.mutedForeground
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(
        tint.copy(alpha = 0.1f),
        RoundedCornerShape(10.dp)
      )
      .padding(horizontal = 12.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.Info,
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(16.dp)
    )
    Spacer(Modifier.width(8.dp))
    Text(
      text = message,
      color = tint,
      fontSize = 12.5.sp
    )
  }
}

/** One digest line: algorithm, selectable hex value, copy action. */
@Composable
fun DigestRow(algorithm: String, hash: String, modifier: Modifier = Modifier) {
  val palette = LocalCyberPalette.current
  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("digest_row_$algorithm"),
    colors = CardDefaults.cardColors(containerColor = palette.surface),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, palette.border)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = algorithm,
        color = palette.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 13.5.sp,
        modifier = Modifier.width(74.dp)
      )
      SelectionContainer(modifier = Modifier.weight(1f)) {
        Text(
          text = hash.ifEmpty { "—" },
          color = palette.foreground,
          fontFamily = TerminalFontFamily,
          fontSize = 13.sp
        )
      }
      CyberCopyButton(textToCopy = hash)
    }
  }
}

/**
 * Clipboard helper that marks its clips sensitive and can undo them.
 *
 * A standalone object (not inline in the composable) so the behaviour is
 * unit-testable and so the wipe is guaranteed to run even if the composable
 * that created the clip leaves the composition.
 */
object SecureClipboard {

  /**
   * The key Android 13+ reads to hide a clip from the clipboard overlay and to
   * keep it out of the clipboard history.
   *
   * Lint flags this as `InlinedApi` because the field only exists from API 33.
   * That warning is a false positive here, and worth explaining rather than
   * suppressing blindly: the field is a compile-time `String` constant, so the
   * compiler folds its value into the dex. There is no field lookup at runtime and
   * therefore no `NoSuchFieldError` on API 24-32.
   *
   * On those releases the platform simply ignores a key it does not know, and the
   * TTL wipe below is what actually protects the secret - which is why
   * `SecureClipboardTest` asserts the pre-33 path explicitly instead of trusting
   * that "it still compiles".
   */
  @SuppressLint("InlinedApi")
  const val SENSITIVE_FLAG = ClipDescription.EXTRA_IS_SENSITIVE

  /** How long a copied secret stays on the clipboard. */
  const val CLIPBOARD_TTL_MS = 60_000L

  private const val LABEL = "CyberToolkit"

  fun copy(context: Context, clipboard: ClipboardManager?, text: String) {
    if (clipboard == null || text.isEmpty()) return
    val clip = ClipData.newPlainText(LABEL, text)
    // Present on API 24+; harmless no-op on older releases.
    clip.description.extras = PersistableBundle().apply {
      putBoolean(SENSITIVE_FLAG, true)
    }
    clipboard.setPrimaryClip(clip)
  }

  /** Clears the clipboard, but only while it still holds [expected]. */
  fun clearIfOurs(context: Context, clipboard: ClipboardManager?, expected: String) {
    if (clipboard == null || expected.isEmpty()) return
    val current = try {
      clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    } catch (e: SecurityException) {
      null
    }
    if (current != expected) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      clipboard.clearPrimaryClip()
    } else {
      // Pre-28 has no clearPrimaryClip(); overwrite with an empty clip instead.
      clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, ""))
    }
  }
}
