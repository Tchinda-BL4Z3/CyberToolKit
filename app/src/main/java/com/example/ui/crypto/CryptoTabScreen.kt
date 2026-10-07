package com.example.ui.crypto

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.crypto.CryptoCore
import com.example.crypto.PassphraseRating
import com.example.ui.components.CyberBadge
import com.example.ui.components.CyberCopyButton
import com.example.ui.components.CyberMessage
import com.example.ui.components.CyberSectionLabel
import com.example.ui.components.CyberTerminalBox
import com.example.ui.components.CyberTextField
import com.example.ui.components.DigestRow
import com.example.ui.theme.LocalCyberPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which direction the AES card is currently operating in. */
private enum class AesDirection { ENCRYPT, DECRYPT }

/**
 * Tab 2 - digests, HMAC, PBKDF2 and AES-256-GCM.
 *
 * ### The bug this tab was shipping
 * The old "AES-256 Encryption" card did not encrypt anything. It computed
 * `SHA-256(secret)`, took the first 8 hex characters, and returned
 * `"9f8a2b4c1d7e:" + Base64(plaintext + keyFragment)`. That is reversible with
 * no key at all, the "IV" was a hard-coded literal, and the derived key was
 * truncated to 32 bits. The UI labelled it "AES-GCM".
 *
 * It is now a real authenticated encryption: PBKDF2-HMAC-SHA256 (210k rounds,
 * random salt) -> AES-256-GCM (random 96-bit IV, 128-bit tag), serialised as
 * `CYB1$...`. Decryption is provided too, and GCM's tag check is what tells the
 * user their passphrase is wrong instead of returning garbage.
 */
@Composable
fun CryptoTabScreen(
  input: String,
  onInputChange: (String) -> Unit,
  secret: String,
  onSecretChange: (String) -> Unit,
  iterations: Int,
  onIterationsChange: (Int) -> Unit,
  aesPayload: String,
  onAesPayloadChange: (String) -> Unit,
  passwordHashResult: String,
  onPasswordHashChange: (String) -> Unit
) {
  val palette = LocalCyberPalette.current
  val scope = rememberCoroutineScope()

  // Digests are cheap (one pass over the input) so they stay on the main
  // thread; anything PBKDF2-sized is pushed to Default below.
  val digests = remember(input) {
    CryptoCore.supportedDigests.associateWith { CryptoCore.hexDigest(input, it) }
  }
  val hmac = remember(input, secret) {
    if (secret.isEmpty()) "" else CryptoCore.hmacSha256(secret, input)
  }
  val strength = remember(secret) { CryptoCore.passphraseStrength(secret) }

  var aesDirection by remember { mutableStateOf(AesDirection.ENCRYPT) }
  var aesError by remember { mutableStateOf<String?>(null) }
  var aesPlaintext by remember { mutableStateOf("") }
  var isBusy by remember { mutableStateOf(false) }

  // Captured outside the coroutine: stringResource is @Composable and cannot be
  // called from a suspend context.
  val errEmpty = stringResource(R.string.crypto_aes_error_empty)
  val errDecrypt = stringResource(R.string.crypto_aes_error_decrypt)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .testTag("crypto_tab"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Column {
      CyberSectionLabel(
        icon = Icons.Default.Tag,
        label = stringResource(R.string.crypto_input_label)
      )
      CyberTextField(
        value = input,
        onValueChange = onInputChange,
        testTag = "crypto_input"
      )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      CyberSectionLabel(
        icon = Icons.Default.Fingerprint,
        label = stringResource(R.string.crypto_digests_label)
      )
      digests.forEach { (algorithm, digest) ->
        DigestRow(algorithm = algorithm, hash = digest)
      }
    }

    if (hmac.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberSectionLabel(
          icon = Icons.Default.Fingerprint,
          label = stringResource(R.string.crypto_hmac_label),
          trailing = { CyberCopyButton(textToCopy = hmac) }
        )
        CyberTerminalBox(content = hmac, minHeight = 56)
      }
    }

    // ---- AES-256-GCM ----------------------------------------------------
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.5f))
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Default.Key,
              contentDescription = null,
              tint = palette.primary,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.crypto_aes_title),
              color = palette.foreground,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp
            )
          }
          CyberBadge(label = stringResource(R.string.crypto_aes_badge), tint = palette.primary)
        }

        Text(
          text = stringResource(R.string.crypto_aes_key_helper),
          color = palette.mutedForeground,
          fontSize = 11.5.sp,
          lineHeight = 16.sp
        )

        CyberTextField(
          value = secret,
          onValueChange = onSecretChange,
          label = stringResource(R.string.crypto_aes_key_label),
          visualTransformation = PasswordVisualTransformation(),
          testTag = "crypto_secret"
        )

        if (secret.isNotEmpty()) {
          StrengthMeter(strength.score, strength.rating)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          AesDirectionButton(
            label = stringResource(R.string.crypto_aes_encrypt),
            isSelected = aesDirection == AesDirection.ENCRYPT,
            enabled = !isBusy,
            modifier = Modifier.weight(1f),
            onClick = {
              aesDirection = AesDirection.ENCRYPT
              aesError = null
            }
          )
          AesDirectionButton(
            label = stringResource(R.string.crypto_aes_decrypt),
            isSelected = aesDirection == AesDirection.DECRYPT,
            enabled = !isBusy,
            modifier = Modifier.weight(1f),
            onClick = {
              aesDirection = AesDirection.DECRYPT
              aesError = null
              aesPlaintext = ""
            }
          )
        }

        Button(
          onClick = {
            val subject = if (aesDirection == AesDirection.ENCRYPT) input else aesPayload
            if (subject.isEmpty() || secret.isEmpty()) {
              aesError = errEmpty
              return@Button
            }
            isBusy = true
            aesError = null
            scope.launch {
              val result = withContext(Dispatchers.Default) {
                if (aesDirection == AesDirection.ENCRYPT) {
                  AesOutcome.Encrypted(CryptoCore.aesGcmEncrypt(subject, secret))
                } else {
                  val plain = CryptoCore.aesGcmDecrypt(subject, secret)
                  if (plain.isEmpty()) AesOutcome.Failed else AesOutcome.Decrypted(plain)
                }
              }
              isBusy = false
              when (result) {
                is AesOutcome.Encrypted -> {
                  onAesPayloadChange(result.payload)
                  aesPlaintext = ""
                }
                is AesOutcome.Decrypted -> aesPlaintext = result.plaintext
                AesOutcome.Failed -> aesError = errDecrypt
              }
            }
          },
          enabled = !isBusy,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = palette.primary,
            contentColor = palette.terminalBg,
            disabledContainerColor = palette.surfaceRaised,
            disabledContentColor = palette.mutedForeground
          ),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text(
            text = if (aesDirection == AesDirection.ENCRYPT) {
              stringResource(R.string.crypto_aes_encrypt)
            } else {
              stringResource(R.string.crypto_aes_decrypt)
            },
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }

        aesError?.let { message ->
          CyberMessage(message = message, isError = true)
        }

        if (aesDirection == AesDirection.DECRYPT && aesPayload.isNotEmpty()) {
          CyberTextField(
            value = aesPayload,
            onValueChange = onAesPayloadChange,
            label = stringResource(R.string.crypto_aes_ciphertext_label),
            minLines = 3,
            maxLines = 6,
            supportingText = stringResource(R.string.crypto_aes_note_format)
          )
        }

        if (aesPayload.isNotEmpty()) {
          CyberSectionLabel(
            icon = Icons.Default.Terminal,
            label = stringResource(R.string.crypto_aes_ciphertext_label),
            trailing = { CyberCopyButton(textToCopy = aesPayload) }
          )
          CyberTerminalBox(content = aesPayload)
        }

        if (aesPlaintext.isNotEmpty()) {
          CyberSectionLabel(
            icon = Icons.Default.LockOpen,
            label = stringResource(R.string.crypto_aes_plaintext_label),
            trailing = { CyberCopyButton(textToCopy = aesPlaintext) }
          )
          CyberTerminalBox(content = aesPlaintext)
        }

        Text(
          text = stringResource(R.string.crypto_aes_strong_label),
          color = palette.primary.copy(alpha = 0.85f),
          fontSize = 11.5.sp,
          lineHeight = 16.sp
        )
      }
    }

    // ---- PBKDF2 password hashing ----------------------------------------
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = palette.surface),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, palette.border)
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = stringResource(R.string.crypto_password_title),
          color = palette.foreground,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp
        )
        Text(
          text = stringResource(R.string.crypto_password_note),
          color = palette.mutedForeground,
          fontSize = 11.5.sp
        )

        CyberTextField(
          value = secret,
          onValueChange = onSecretChange,
          label = stringResource(R.string.crypto_hmac_key),
          visualTransformation = PasswordVisualTransformation()
        )

        CyberTextField(
          value = iterations.toString(),
          onValueChange = { typed ->
            val digits = typed.filter { it.isDigit() }.take(4)
            onIterationsChange((digits.toIntOrNull() ?: MIN_ITERATION_STEPS).coerceIn(50, 2000))
          },
          label = stringResource(R.string.crypto_password_iterations),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Button(
          onClick = {
            if (secret.isEmpty()) {
              aesError = errEmpty
              return@Button
            }
            isBusy = true
            scope.launch {
              val hashed = withContext(Dispatchers.Default) {
                CryptoCore.passwordHash(secret, iterations * 1000)
              }
              isBusy = false
              onPasswordHashChange(hashed)
            }
          },
          enabled = !isBusy,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = palette.surfaceRaised,
            contentColor = palette.primary,
            disabledContentColor = palette.mutedForeground
          ),
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, palette.primary)
        ) {
          Text(
            text = stringResource(R.string.crypto_password_action),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }

        if (passwordHashResult.isNotEmpty()) {
          CyberSectionLabel(
            icon = Icons.Default.Terminal,
            label = stringResource(R.string.crypto_password_result_label),
            trailing = { CyberCopyButton(textToCopy = passwordHashResult) }
          )
          CyberTerminalBox(content = passwordHashResult)
        }
      }
    }

    Spacer(Modifier.height(4.dp))
  }
}

private sealed interface AesOutcome {
  data class Encrypted(val payload: String) : AesOutcome
  data class Decrypted(val plaintext: String) : AesOutcome
  data object Failed : AesOutcome
}

@Composable
private fun AesDirectionButton(
  label: String,
  isSelected: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  val palette = LocalCyberPalette.current
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.heightIn(min = 44.dp),
    border = BorderStroke(1.dp, if (isSelected) palette.primary else palette.border),
    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
      containerColor = if (isSelected) palette.primaryDim else Color.Transparent,
      contentColor = if (isSelected) palette.primary else palette.mutedForeground
    ),
    shape = RoundedCornerShape(10.dp)
  ) {
    Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
  }
}

/** 5-segment strength gauge driven by [CryptoCore.passphraseStrength]. */
@Composable
private fun StrengthMeter(score: Int, rating: PassphraseRating) {
  val palette = LocalCyberPalette.current
  val labelRes = when (rating) {
    PassphraseRating.EMPTY -> R.string.crypto_strength_empty
    PassphraseRating.WEAK -> R.string.crypto_strength_weak
    PassphraseRating.FAIR -> R.string.crypto_strength_fair
    PassphraseRating.STRONG -> R.string.crypto_strength_strong
    PassphraseRating.EXCELLENT -> R.string.crypto_strength_excellent
  }
  val tint = when (rating) {
    PassphraseRating.EMPTY, PassphraseRating.WEAK -> palette.destructive
    PassphraseRating.FAIR -> Color(0xFFF59E0B)
    PassphraseRating.STRONG, PassphraseRating.EXCELLENT -> palette.primary
  }
  val label = stringResource(R.string.crypto_strength_label, stringResource(labelRes), score)

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = label,
      color = tint,
      fontSize = 11.5.sp,
      fontWeight = FontWeight.Bold
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      repeat(5) { index ->
        val filled = score >= (index + 1) * 20
        Box(
          modifier = Modifier
            .weight(1f)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (filled) tint else palette.border)
        )
      }
    }
  }
}

private const val MIN_ITERATION_STEPS = 50
