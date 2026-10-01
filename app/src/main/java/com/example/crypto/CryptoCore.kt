package com.example.crypto

import android.os.Build
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every cryptographic primitive used by CyberToolkit.
 *
 * Design rules enforced here:
 *  - digests are constant-shape lowercase hex, never truncated;
 *  - key derivation is always PBKDF2 with a random 128-bit salt and a recorded
 *    iteration count, so stored material stays verifiable when the cost is raised;
 *  - the AES payload is authenticated (GCM) and fully self-describing, and the
 *    IV is drawn from [SecureRandom] every time - never hard-coded, never reused.
 *
 * All operations are pure functions on immutable inputs: safe to call from any
 * thread, safe to unit-test.
 */
object CryptoCore {

  private const val SALT_BYTES = 16
  private const val GCM_IV_BYTES = 12
  private const val GCM_TAG_BITS = 128
  private const val AES_KEY_BITS = 256

  /** PBKDF2 cost used for the lock code and for the Crypto tab. */
  const val DEFAULT_ITERATIONS = 210_000

  /** HMAC used by PBKDF2, chosen once and then stored alongside the hash. */
  val defaultPbkdf2Algorithm: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      PBKDF2_SHA256
    } else {
      // PBKDF2WithHmacSHA256 only exists from API 26. The algorithm name is
      // persisted with the hash, so a material created on O+ stays verifiable
      // on 24/25 and vice-versa.
      PBKDF2_SHA1
    }

  const val PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
  const val PBKDF2_SHA1 = "PBKDF2WithHmacSHA1"

  private val secureRandom = SecureRandom()

  /** Digest algorithms offered by the Crypto tab. */
  val supportedDigests: List<String> = listOf("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512")

  // ---------------------------------------------------------------------------
  // Digests
  // ---------------------------------------------------------------------------

  /**
   * Hex-encoded digest of [input] (UTF-8), or an empty string when [input] is
   * blank or [algorithm] is unavailable on this device.
   */
  fun hexDigest(input: String, algorithm: String): String {
    if (input.isEmpty()) return ""
    return try {
      MessageDigest.getInstance(algorithm)
        .digest(input.toByteArray(Charsets.UTF_8))
        .toHex()
    } catch (e: NoSuchAlgorithmException) {
      ""
    } catch (e: IllegalArgumentException) {
      ""
    }
  }

  /** HMAC-SHA256 of [message] under [key], hex-encoded. */
  fun hmacSha256(key: String, message: String): String = try {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
  } catch (e: GeneralSecurityException) {
    ""
  }

  // ---------------------------------------------------------------------------
  // PBKDF2
  // ---------------------------------------------------------------------------

  /**
   * PBKDF2 key derivation.
   *
   * The returned array is freshly allocated; callers own it and should wipe it.
   */
  fun pbkdf2(
    password: CharArray,
    salt: ByteArray,
    iterations: Int = DEFAULT_ITERATIONS,
    keyLengthBits: Int = AES_KEY_BITS,
    algorithm: String = defaultPbkdf2Algorithm
  ): ByteArray {
    val chars = if (algorithm == PBKDF2_SHA1) {
      // PBKDF2WithHmacSHA1 uses PKCS#5, i.e. the low byte of each char. Doing the
      // folding here keeps the derivation identical to what the provider does.
      CharArray(password.size) { (password[it].code and 0xFF).toChar() }
    } else {
      password
    }
    return try {
      val factory = secretKeyFactoryOf(algorithm)
      val spec = PBEKeySpec(chars, salt, iterations, keyLengthBits)
      try {
        factory.generateSecret(spec).encoded
      } finally {
        spec.clearPassword()
        if (chars !== password) chars.fill('\u0000')
      }
    } catch (e: GeneralSecurityException) {
      ByteArray(0)
    }
  }

  private fun secretKeyFactoryOf(algorithm: String): SecretKeyFactory =
    try {
      SecretKeyFactory.getInstance(algorithm)
    } catch (e: NoSuchAlgorithmException) {
      // The BouncyCastle copy bundled with Android exposes the SHA-256 variants.
      SecretKeyFactory.getInstance(algorithm, "BC")
    }

  // ---------------------------------------------------------------------------
  // Password hashing (for the Crypto tab and for the lock code)
  // ---------------------------------------------------------------------------

  /**
   * Hashes [password] into a self-describing, storable string:
   * `pbkdf2$<hmac algorithm>$<iterations>$<base64 salt>$<base64 hash>`.
   *
   * The format records its own parameters so the cost can be raised later
   * without invalidating existing material.
   */
  fun passwordHash(
    password: String,
    iterations: Int = DEFAULT_ITERATIONS,
    algorithm: String = defaultPbkdf2Algorithm
  ): String {
    val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
    val derived = pbkdf2(password.toCharArray(), salt, iterations, AES_KEY_BITS, algorithm)
    if (derived.isEmpty()) return ""
    return listOf(
      "pbkdf2",
      algorithm,
      iterations.toString(),
      salt.b64(),
      derived.b64()
    ).joinToString("$")
  }

  /**
   * Verifies [password] against a [stored] hash produced by [passwordHash].
   *
   * The comparison is constant-time ([MessageDigest.isEqual]) so it leaks nothing
   * about how many leading bytes matched. Returns false for any malformed input.
   */
  fun passwordVerify(password: String, stored: String): Boolean {
    val parts = stored.split('$')
    if (parts.size != 5 || parts[0] != "pbkdf2") return false
    val algorithm = parts[1]
    val iterations = parts[2].toIntOrNull() ?: return false
    if (iterations <= 0) return false
    val salt = parts[3].unB64() ?: return false
    val expected = parts[4].unB64() ?: return false
    if (expected.isEmpty()) return false

    val actual = pbkdf2(password.toCharArray(), salt, iterations, expected.size * 8, algorithm)
    if (actual.isEmpty()) return false
    return MessageDigest.isEqual(expected, actual)
  }

  // ---------------------------------------------------------------------------
  // AES-256-GCM
  // ---------------------------------------------------------------------------

  /**
   * Encrypts [plaintext] with AES-256-GCM under a PBKDF2 key derived from
   * [passphrase].
   *
   * Output layout (self-describing, so it survives a cost change):
   * `CYB1$<hmac>$<iterations>$<b64 salt>$<b64 iv>$<b64 ciphertext||tag>`
   *
   * A fresh 96-bit IV is drawn for every call; GCM guarantees safety as long as
   * an (key, IV) pair is never reused, and the salt makes each key unique anyway.
   *
   * @return the payload string, or an empty string if [plaintext] or [passphrase]
   *         is blank or a provider is unavailable.
   */
  fun aesGcmEncrypt(plaintext: String, passphrase: String): String {
    if (plaintext.isEmpty() || passphrase.isEmpty()) return ""
    return try {
      val algorithm = defaultPbkdf2Algorithm
      val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
      val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
      val key = pbkdf2(passphrase.toCharArray(), salt, DEFAULT_ITERATIONS, AES_KEY_BITS, algorithm)
      if (key.isEmpty()) return ""

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
      val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
      key.wipe()

      listOf("CYB1", algorithm, DEFAULT_ITERATIONS.toString(), salt.b64(), iv.b64(), ciphertext.b64())
        .joinToString("$")
    } catch (e: GeneralSecurityException) {
      ""
    }
  }

  /**
   * Reverses [aesGcmEncrypt].
   *
   * @return the plaintext, or an empty string when the payload is malformed or
   *         the authentication tag does not verify (wrong passphrase or tampering).
   */
  fun aesGcmDecrypt(payload: String, passphrase: String): String {
    if (payload.isEmpty() || passphrase.isEmpty()) return ""
    val parts = payload.trim().split('$')
    if (parts.size != 6 || parts[0] != "CYB1") return ""
    val algorithm = parts[1]
    val iterations = parts[2].toIntOrNull() ?: return ""
    if (iterations <= 0) return ""
    val salt = parts[3].unB64() ?: return ""
    val iv = parts[4].unB64() ?: return ""
    val ciphertext = parts[5].unB64() ?: return ""
    if (iv.size != GCM_IV_BYTES || ciphertext.isEmpty()) return ""

    return try {
      val key = pbkdf2(passphrase.toCharArray(), salt, iterations, AES_KEY_BITS, algorithm)
      if (key.isEmpty()) return ""
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
      val plain = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
      key.wipe()
      plain
    } catch (e: AEADBadTagException) {
      // Wrong passphrase or altered ciphertext: GCM refuses to decrypt.
      ""
    } catch (e: GeneralSecurityException) {
      ""
    } catch (e: IllegalArgumentException) {
      ""
    }
  }

  /** Cheap indicator of how much work a passphrase would cost to attack. */
  fun passphraseStrength(passphrase: String): PassphraseStrength {
    if (passphrase.isEmpty()) return PassphraseStrength(0, PassphraseRating.EMPTY)
    val classes = listOf(
      passphrase.any { it.isLowerCase() },
      passphrase.any { it.isUpperCase() },
      passphrase.any { it.isDigit() },
      passphrase.any { !it.isLetterOrDigit() },
      passphrase.any { it.code > 127 }
    ).count { it }
    val score = ((passphrase.length * classes) / 6).coerceIn(0, 100)
    val rating = when {
      passphrase.length < 8 -> PassphraseRating.WEAK
      score < 40 -> PassphraseRating.FAIR
      score < 70 -> PassphraseRating.STRONG
      else -> PassphraseRating.EXCELLENT
    }
    return PassphraseStrength(score, rating)
  }
}

data class PassphraseStrength(val score: Int, val rating: PassphraseRating)

enum class PassphraseRating { EMPTY, WEAK, FAIR, STRONG, EXCELLENT }

// ---------------------------------------------------------------------------
// Byte helpers
// ---------------------------------------------------------------------------

internal fun ByteArray.toHex(): String {
  val out = StringBuilder(size * 2)
  for (b in this) {
    val v = b.toInt() and 0xFF
    out.append(HEX[v ushr 4])
    out.append(HEX[v and 0x0F])
  }
  return out.toString()
}

internal fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

internal fun String.unB64(): ByteArray? = try {
  Base64.decode(this, Base64.NO_WRAP)
} catch (e: IllegalArgumentException) {
  null
}

internal fun ByteArray.wipe() {
  for (i in indices) this[i] = 0
}

private val HEX = "0123456789abcdef".toCharArray()
