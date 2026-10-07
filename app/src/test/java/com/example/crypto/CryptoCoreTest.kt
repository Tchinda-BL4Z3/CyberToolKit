package com.example.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression suite for the crypto core.
 *
 * The important one here is [aes_gcm_round_trips_and_rejects_a_wrong_passphrase]:
 * the previous build shipped an "AES-256" card that was `Base64(plaintext +
 * firstBytesOfSha256(secret))` with a hard-coded IV, so it decrypted with no key
 * at all. These tests pin the real behaviour so that cannot come back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoCoreTest {

  // ---- digests -------------------------------------------------------------

  @Test
  fun `digest matches the published MD5 vector`() {
    assertEquals("900150983cd24fb0d6963f7d28e17f72", CryptoCore.hexDigest("abc", "MD5"))
  }

  @Test
  fun `digest matches the published SHA-256 vector`() {
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      CryptoCore.hexDigest("abc", "SHA-256")
    )
  }

  @Test
  fun `digest is empty for blank input or an unknown algorithm`() {
    assertEquals("", CryptoCore.hexDigest("", "SHA-256"))
    assertEquals("", CryptoCore.hexDigest("abc", "NOT-A-HASH"))
  }

  @Test
  fun `every advertised digest is available on the device`() {
    // The hex length is what proves the real algorithm ran, not a stub.
    val expectedHexLength = mapOf(
      "MD5" to 32,
      "SHA-1" to 40,
      "SHA-256" to 64,
      "SHA-384" to 96,
      "SHA-512" to 128
    )
    assertTrue(
      "an advertised digest is missing from the expectations: " +
        CryptoCore.supportedDigests.joinToString(),
      CryptoCore.supportedDigests.containsAll(expectedHexLength.keys)
    )
    expectedHexLength.forEach { (algorithm, length) ->
      val hex = CryptoCore.hexDigest("CyberToolkit", algorithm)
      assertEquals("$algorithm hex length", length, hex.length)
      assertTrue("$algorithm must be lowercase hex", hex.all { it in "0123456789abcdef" })
    }
  }

  // ---- HMAC ----------------------------------------------------------------

  @Test
  fun `hmac matches RFC 4231 test case 1`() {
    // key = 0x0b x20, data = "Hi There"
    val key = "\u000b".repeat(20)
    assertEquals(
      "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
      CryptoCore.hmacSha256(key, "Hi There")
    )
  }


  @Test
  fun `hmac changes when the key changes`() {
    assertNotEquals(
      CryptoCore.hmacSha256("key-a", "message"),
      CryptoCore.hmacSha256("key-b", "message")
    )
  }

  // ---- PBKDF2 --------------------------------------------------------------

  @Test
  fun `password hash verifies and round-trips its own parameters`() {
    val hash = CryptoCore.passwordHash("correct horse", iterations = 1_000)
    assertTrue(hash.startsWith("pbkdf2\$"))
    assertEquals(5, hash.split('$').size)
    assertTrue(CryptoCore.passwordVerify("correct horse", hash))
    assertFalse(CryptoCore.passwordVerify("wrong horse", hash))
  }

  @Test
  fun `password hash rejects malformed stored values instead of throwing`() {
    listOf("", "pbkdf2", "pbkdf2\$a\$b", "md5\$x\$1\$y\$z", "pbkdf2\$a\$0\$y\$z")
      .forEach { assertFalse(CryptoCore.passwordVerify("whatever", it)) }
  }

  @Test
  fun `two hashes of the same password differ because of the random salt`() {
    assertNotEquals(
      CryptoCore.passwordHash("same", iterations = 1_000),
      CryptoCore.passwordHash("same", iterations = 1_000)
    )
  }

  // ---- AES-256-GCM ---------------------------------------------------------

  @Test
  fun `aes gcm round trips and rejects a wrong passphrase`() {
    val passphrase = "ethical-hacker-key"
    val plaintext = "CyberToolkit // payload"
    val payload = CryptoCore.aesGcmEncrypt(plaintext, passphrase)

    assertTrue(payload.startsWith("CYB1\$"))
    assertEquals(plaintext, CryptoCore.aesGcmDecrypt(payload, passphrase))
    assertEquals("", CryptoCore.aesGcmDecrypt(payload, "not-the-passphrase"))
  }

  @Test
  fun `aes gcm output is not reproducible because the iv is random`() {
    val first = CryptoCore.aesGcmEncrypt("same input", "key")
    val second = CryptoCore.aesGcmEncrypt("same input", "key")
    assertNotEquals(first, second)
    assertEquals(
      CryptoCore.aesGcmDecrypt(first, "key"),
      CryptoCore.aesGcmDecrypt(second, "key")
    )
  }

  @Test
  fun `aes gcm detects a tampered ciphertext`() {
    val payload = CryptoCore.aesGcmEncrypt("integrity matters", "key")
    val parts = payload.split('$').toMutableList()
    val body = parts[5]
    // Flip a character in the middle of the ciphertext, leaving the framing and
    // the IV intact, so only the AEAD tag can catch it.
    val target = body.length / 2
    parts[5] = body.substring(0, target) +
      (if (body[target] == 'A') 'B' else 'A') +
      body.substring(target + 1)
    assertEquals("", CryptoCore.aesGcmDecrypt(parts.joinToString("$"), "key"))
  }

  @Test
  fun `aes gcm refuses blank inputs and malformed payloads`() {
    assertEquals("", CryptoCore.aesGcmEncrypt("", "key"))
    assertEquals("", CryptoCore.aesGcmEncrypt("text", ""))
    listOf("", "CYB0\$a\$1\$b\$c\$d", "garbage", "CYB1\$a\$1\$b\$c")
      .forEach { assertEquals("", CryptoCore.aesGcmDecrypt(it, "key")) }
  }

  @Test
  fun `aes gcm ciphertext does not contain the plaintext`() {
    val payload = CryptoCore.aesGcmEncrypt("PLAINTEXTMARKER", "key")
    assertFalse(payload.contains("PLAINTEXTMARKER"))
  }

  // ---- passphrase strength -------------------------------------------------

  @Test
  fun `passphrase strength separates weak from strong`() {
    assertEquals(PassphraseRating.EMPTY, CryptoCore.passphraseStrength("").rating)
    assertEquals(PassphraseRating.WEAK, CryptoCore.passphraseStrength("abc").rating)
    assertTrue(
      CryptoCore.passphraseStrength("Tr0ub4dor&3!xZq").rating.ordinal >=
        PassphraseRating.FAIR.ordinal
    )
  }
}
