package com.example.ui.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ssh.SshCredentials
import com.example.ssh.SshResult
import com.example.ssh.SshSession
import com.example.ui.components.CyberBackHeader
import com.example.ui.components.CyberMessage
import com.example.ui.components.CyberSectionLabel
import com.example.ui.components.CyberTerminalBox
import com.example.ui.components.CyberTextField
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.TerminalFontFamily
import kotlinx.coroutines.launch

/**
 * Runs one command on a machine the operator owns, over SSH.
 *
 * ### The consent model
 *
 * Android grants `INTERNET` at install time with no runtime prompt, so there is no
 * system dialog to hang the decision on. [sshEnabled] is that decision instead:
 * while it is off, this composable does not render a connect button at all and
 * [SshSession] is never reached. That is why the panel explains itself rather than
 * failing with "permission denied".
 *
 * ### Credentials
 *
 * [key] and [passphrase] live in composition state only. Nothing here writes them
 * to preferences, to a file, or to a log, and they are dropped when the tab leaves
 * composition. [rememberSaveable] is deliberately not used for either.
 */
/**
 * The SSH form on its own route, with a way back.
 *
 * Own screen for the same reason as the lab shell: the form is taller than the
 * space left under the cheat list once the keyboard opens.
 */
@Composable
fun SshScreen(
  sshEnabled: Boolean,
  defaultHost: String,
  defaultPort: String,
  onBack: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .imePadding()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    CyberBackHeader(title = stringResource(R.string.ssh_title), onBack = onBack)
    SshPanel(
      sshEnabled = sshEnabled,
      defaultHost = defaultHost,
      defaultPort = defaultPort
    )
  }
}

@Composable
private fun SshPanel(
  sshEnabled: Boolean,
  defaultHost: String,
  defaultPort: String,
  modifier: Modifier = Modifier
) {
  val palette = LocalCyberPalette.current
  val scope = rememberCoroutineScope()
  val session = remember(sshEnabled) { SshSession(consentGiven = sshEnabled) }

  var host by remember { mutableStateOf(defaultHost) }
  var port by remember { mutableStateOf(defaultPort) }
  var username by remember { mutableStateOf("") }
  var key by remember { mutableStateOf("") }
  var passphrase by remember { mutableStateOf("") }
  var command by remember { mutableStateOf("id") }
  var output by remember { mutableStateOf("") }
  var running by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // No `verticalScroll` here. This panel is a child of the tab's Column, and a
  // scroll modifier on an unweighted child makes the parent measure it as
  // infinite-tall, which collapses the sibling `weight(1f)` cheat list. The tab
  // owns the single scroll; children only lay out.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .testTag("ssh_panel"),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    CyberSectionLabel(
      icon = Icons.Default.Terminal,
      label = stringResource(R.string.ssh_title)
    )

    if (!sshEnabled) {
      // The gate, stated plainly. No button to press, nothing to misconfigure.
      CyberMessage(message = stringResource(R.string.ssh_optin_blocked), isError = false)
      return@Column
    }

    CyberMessage(message = stringResource(R.string.ssh_legal_note), isError = false)

    CyberTextField(
      value = host,
      onValueChange = { host = it },
      label = stringResource(R.string.ssh_host_label),
      singleLine = true,
      testTag = "ssh_host"
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      CyberTextField(
        value = username,
        onValueChange = { username = it },
        label = stringResource(R.string.ssh_user_label),
        modifier = Modifier.weight(1.6f),
        singleLine = true,
        testTag = "ssh_user"
      )
      CyberTextField(
        value = port,
        onValueChange = { typed -> port = typed.filter { it.isDigit() }.take(5) },
        label = stringResource(R.string.ssh_port_label),
        modifier = Modifier.weight(1f),
        singleLine = true,
        testTag = "ssh_port"
      )
    }

    CyberTextField(
      value = key,
      onValueChange = { key = it },
      label = stringResource(R.string.ssh_key_label),
      placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
      singleLine = false,
      minLines = 2,
      maxLines = 6,
      testTag = "ssh_key"
    )

    CyberTextField(
      value = passphrase,
      onValueChange = { passphrase = it },
      label = stringResource(R.string.ssh_passphrase_label),
      singleLine = true,
      visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
      testTag = "ssh_passphrase"
    )

    CyberTextField(
      value = command,
      onValueChange = { command = it },
      label = stringResource(R.string.ssh_command_label),
      supportingText = stringResource(R.string.ssh_command_hint),
      singleLine = true,
      testTag = "ssh_command"
    )

    Button(
      onClick = {
        val portNumber = port.toIntOrNull() ?: 22
        error = null
        running = true
        output = ""
        scope.launch {
          val result = session.run(
            SshCredentials(
              host = host,
              port = portNumber,
              username = username,
              secret = passphrase,
              useKeyAuth = true,
              privateKey = key,
              passphrase = passphrase
            ),
            command
          )
          running = false
          when (result) {
            is SshResult.Success -> output = result.output.ifEmpty { "(sortie vide)" }
            is SshResult.Failure -> {
              error = result.message
              output = ""
            }
          }
        }
      },
      enabled = !running && host.isNotBlank() && username.isNotBlank() && key.isNotBlank(),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("ssh_run_button"),
      colors = ButtonDefaults.buttonColors(
        containerColor = palette.primary,
        contentColor = palette.bg
      ),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    ) {
      if (running) {
        CircularProgressIndicator(
          modifier = Modifier.width(16.dp).height(16.dp),
          strokeWidth = 2.dp,
          color = palette.bg
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.ssh_running),
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = TerminalFontFamily
        )
      } else {
        Icon(
          Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.width(18.dp).height(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.ssh_connect),
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = TerminalFontFamily
        )
      }
    }

    error?.let { message ->
      CyberMessage(message = message, isError = true)
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
      Column(modifier = Modifier.padding(12.dp)) {
        CyberSectionLabel(
          icon = Icons.Default.Terminal,
          label = stringResource(R.string.ssh_output)
        )
        Spacer(Modifier.height(8.dp))
        CyberTerminalBox(
          content = output.ifEmpty { stringResource(R.string.ssh_idle) },
          minHeight = 110
        )
      }
    }
  }
}
