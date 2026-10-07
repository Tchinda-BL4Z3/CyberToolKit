package com.example.ui.encoder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.crypto.CodecError
import com.example.crypto.CodecFormat
import com.example.crypto.CodecMode
import com.example.crypto.CodecResult
import com.example.crypto.Codecs
import com.example.ui.components.CyberChoiceChip
import com.example.ui.components.CyberCopyButton
import com.example.ui.components.CyberMessage
import com.example.ui.components.CyberSectionLabel
import com.example.ui.components.CyberSegmentedControl
import com.example.ui.components.CyberTerminalBox
import com.example.ui.components.CyberTextField
import com.example.ui.theme.LocalCyberPalette

/**
 * Tab 1 - Base64 / Hex / URL codec.
 *
 * Behaviour preserved from the previous build, with two real fixes:
 *  - errors are now surfaced as messages *beside* the field instead of being
 *    injected into the output box, where the old code put the literal sentence
 *    "Invalid input for selected format" and happily let the user copy it as if
 *    it were a real result;
 *  - the screen is driven by the typed [CodecMode] / [CodecFormat] enums rather
 *    than raw strings, so a renamed label can no longer silently fall through to
 *    the identity transform.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EncoderTabScreen(
  input: String,
  onInputChange: (String) -> Unit,
  mode: CodecMode,
  onModeChange: (CodecMode) -> Unit,
  format: CodecFormat,
  onFormatChange: (CodecFormat) -> Unit
) {
  val palette = LocalCyberPalette.current
  val context = LocalContext.current
  var formatMenuExpanded by remember { mutableStateOf(false) }

  val encodeLabel = stringResource(R.string.encoder_mode_encode)
  val decodeLabel = stringResource(R.string.encoder_mode_decode)
  val placeholder = stringResource(R.string.encoder_result_placeholder)

  val result: CodecResult = remember(input, mode, format) {
    if (input.isEmpty()) CodecResult.Failure(CodecError.EMPTY_INPUT)
    else Codecs.transform(input, mode, format)
  }

  val isSuccess = result is CodecResult.Success
  val outputText = result.textOrNull ?: ""

  fun pasteFromClipboard() {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip: ClipData? = clipboard?.primaryClip
    if (clip != null && clip.itemCount > 0) {
      onInputChange(clip.getItemAt(0).text?.toString() ?: "")
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .testTag("encoder_tab"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    CyberSegmentedControl(
      options = listOf(
        CodecMode.ENCODE to encodeLabel,
        CodecMode.DECODE to decodeLabel
      ),
      selected = mode,
      onSelect = onModeChange
    )

    Column {
      Text(
        text = stringResource(R.string.encoder_target_format).uppercase(),
        color = palette.mutedForeground,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
      )
      Spacer(Modifier.height(6.dp))
      OutlinedFormatDropdown(
        format = format,
        expanded = formatMenuExpanded,
        onExpandedChange = { formatMenuExpanded = it },
        onSelect = {
          onFormatChange(it)
          formatMenuExpanded = false
        }
      )
    }

    Column {
      CyberSectionLabel(
        icon = Icons.AutoMirrored.Filled.Input,
        label = stringResource(R.string.encoder_input_label)
      )
      CyberTextField(
        value = input,
        onValueChange = onInputChange,
        singleLine = false,
        minLines = 4,
        maxLines = 6,
        placeholder = stringResource(R.string.encoder_input_placeholder),
        testTag = "encoder_input"
      )

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 6.dp),
        horizontalArrangement = Arrangement.End
      ) {
        OutlinedButton(
          onClick = { pasteFromClipboard() },
          border = BorderStroke(1.dp, palette.border),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.heightIn(min = 44.dp)
        ) {
          Icon(
            Icons.Default.ContentPaste,
            contentDescription = null,
            tint = palette.mutedForeground,
            modifier = Modifier.size(15.dp)
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.action_paste),
            color = palette.mutedForeground,
            fontSize = 12.5.sp
          )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
          onClick = { onInputChange("") },
          enabled = input.isNotEmpty(),
          border = BorderStroke(1.dp, palette.border),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.heightIn(min = 44.dp)
        ) {
          Icon(
            Icons.Default.Clear,
            contentDescription = null,
            tint = palette.mutedForeground,
            modifier = Modifier.size(15.dp)
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.action_clear),
            color = palette.mutedForeground,
            fontSize = 12.5.sp
          )
        }
      }
    }

    Column {
      CyberSectionLabel(
        icon = Icons.Default.Terminal,
        label = stringResource(R.string.encoder_result_label),
        trailing = {
          CyberCopyButton(
            textToCopy = if (isSuccess) outputText else "",
            label = stringResource(R.string.action_copy)
          )
        }
      )

      if (isSuccess) {
        CyberTerminalBox(content = "$ $outputText")
      } else {
        val error = (result as CodecResult.Failure).error
        // "Nothing typed yet" is a neutral state, not a failure: show the
        // placeholder and stay silent instead of printing an error box.
        if (error == CodecError.EMPTY_INPUT) {
          CyberTerminalBox(content = placeholder, minHeight = 60)
        } else {
          CyberTerminalBox(content = placeholder, minHeight = 60)
          Spacer(Modifier.height(10.dp))
          CyberMessage(message = stringResource(error.messageRes()), isError = true)
        }
      }
    }

    Column {
      CyberSectionLabel(
        icon = Icons.Default.AutoAwesome,
        label = stringResource(R.string.encoder_presets_label)
      )
      // Wraps rather than scrolls, for the same reason as the category chips:
      // a scrollable row clipped the presets with no visual hint that more existed.
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        encoderPresets().forEach { (label, value) ->
          CyberChoiceChip(
            label = label,
            isSelected = input == value,
            onClick = { onInputChange(value) }
          )
        }
      }
    }
  }
}

@Composable
private fun OutlinedFormatDropdown(
  format: CodecFormat,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onSelect: (CodecFormat) -> Unit
) {
  val palette = LocalCyberPalette.current
  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 50.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(palette.surface)
        .border(1.dp, palette.border, RoundedCornerShape(12.dp))
        .clickable { onExpandedChange(true) }
        .padding(horizontal = 14.dp)
        .testTag("encoder_format"),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = format.label,
        color = palette.foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp
      )
      Icon(
        Icons.Default.ArrowDropDown,
        contentDescription = null,
        tint = palette.primary,
        modifier = Modifier.size(24.dp)
      )
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { onExpandedChange(false) }
    ) {
      CodecFormat.entries.forEach { candidate ->
        DropdownMenuItem(
          text = {
            Text(
              text = candidate.label,
              color = if (candidate == format) palette.primary else palette.foreground,
              fontFamily = FontFamily.Monospace,
              fontSize = 14.5.sp
            )
          },
          onClick = { onSelect(candidate) }
        )
      }
    }
  }
}

/** Maps a [CodecError] onto its localised, user-facing explanation. */
private fun CodecError.messageRes(): Int = when (this) {
  CodecError.EMPTY_INPUT -> R.string.encoder_input_placeholder
  CodecError.ODD_HEX_LENGTH -> R.string.encoder_error_odd_hex
  CodecError.NO_HEX_DIGIT -> R.string.encoder_error_no_hex
  CodecError.INVALID_BASE64 -> R.string.encoder_error_base64
  CodecError.INVALID_URL_ESCAPE -> R.string.encoder_error_url
  CodecError.NON_ASCII_INPUT -> R.string.encoder_error_non_ascii
  CodecError.INVALID_ASCII_CODE -> R.string.encoder_error_ascii_code
}

@Composable
private fun encoderPresets(): List<Pair<String, String>> = listOf(
  "admin:password" to "admin:password",
  stringResource(R.string.encoder_preset_jwt) to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
  stringResource(R.string.encoder_preset_sqli) to "' OR 1=1 --",
  stringResource(R.string.encoder_preset_xss) to "<script>alert(1)</script>"
)
