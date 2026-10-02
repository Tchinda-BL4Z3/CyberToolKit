package com.example.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression suite for the Encoder tab.
 *
 * The behaviour pinned here is the one the old implementation got wrong: it
 * swallowed every exception and returned the literal sentence
 * `"Invalid input for selected format"` *as the result*, so the UI showed that
 * sentence in the copyable output box. Errors are now a distinct type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodecsTest {

  // ---- Base64 --------------------------------------------------------------

  @Test
  fun `base64 round trips utf-8`() {
    val original = "CyberToolkit — accentué ✅"
    val encoded = Codecs.encode(original, CodecFormat.BASE64)
    assertTrue(encoded is CodecResult.Success)
    assertEquals(original, Codecs.decode(encoded.textOrNull!!, CodecFormat.BASE64).textOrNull)
  }

  @Test
  fun `base64 decode tolerates whitespace and the url-safe alphabet`() {
    val encoded = Codecs.encode("subjects?_d=1", CodecFormat.BASE64).textOrNull!!
    val noisy = encoded.chunked(4).joinToString("\n")
    assertEquals(
      "subjects?_d=1",
      Codecs.decode(noisy, CodecFormat.BASE64).textOrNull
    )
    val urlSafe = encoded.replace('+', '-').replace('/', '_')
    assertEquals(
      "subjects?_d=1",
      Codecs.decode(urlSafe, CodecFormat.BASE64).textOrNull
    )
  }

  // ---- Hex -----------------------------------------------------------------

  @Test
  fun `hex round trips and keeps a space separated layout`() {
    val encoded = Codecs.encode("AZ", CodecFormat.HEX).textOrNull!!
    assertEquals("41 5a", encoded)
    assertEquals("AZ", Codecs.decode(encoded, CodecFormat.HEX).textOrNull)
  }

  @Test
  fun `hex decode accepts colons dashes and an 0x prefix`() {
    listOf("41:5a", "41-5a", "0x415a", "0X415A", " 41 5a ").forEach { input ->
      assertEquals("AZ", Codecs.decode(input, CodecFormat.HEX).textOrNull)
    }
  }

  @Test
  fun `hex decode reports an odd digit count instead of truncating`() {
    val result = Codecs.decode("41 5", CodecFormat.HEX)
    assertEquals(CodecError.ODD_HEX_LENGTH, result.errorOrNull)
  }

  @Test
  fun `hex decode reports input with no hex digit at all`() {
    assertEquals(CodecError.NO_HEX_DIGIT, Codecs.decode("zzz", CodecFormat.HEX).errorOrNull)
    assertEquals(CodecError.NO_HEX_DIGIT, Codecs.decode("", CodecFormat.HEX).errorOrNull)
  }

  // ---- URL -----------------------------------------------------------------

  @Test
  fun `url round trips a query string`() {
    val original = "https://target/?a=1&b=two words#frag"
    val encoded = Codecs.encode(original, CodecFormat.URL).textOrNull!!
    assertEquals(original, Codecs.decode(encoded, CodecFormat.URL).textOrNull)
  }

  @Test
  fun `url decode reports a broken escape sequence`() {
    assertEquals(
      CodecError.INVALID_URL_ESCAPE,
      Codecs.decode("%zz", CodecFormat.URL).errorOrNull
    )
  }

  // ---- shared contract -----------------------------------------------------

  @Test
  fun `empty input is its own error, not a silent identity transform`() {
    assertEquals(
      CodecError.EMPTY_INPUT,
      Codecs.transform("", CodecMode.ENCODE, CodecFormat.BASE64).errorOrNull
    )
  }

  @Test
  fun `every failure is typed and never returns a sentinel sentence`() {
    val failures = listOf(
      Codecs.transform("", CodecMode.DECODE, CodecFormat.HEX),
      Codecs.transform("41 5", CodecMode.DECODE, CodecFormat.HEX),
      Codecs.transform("zzz", CodecMode.DECODE, CodecFormat.HEX),
      Codecs.transform("%zz", CodecMode.DECODE, CodecFormat.URL)
    )
    failures.forEach { result ->
      assertTrue("expected a Failure", result is CodecResult.Failure)
      assertEquals(null, result.textOrNull)
    }
  }

  @Test
  fun `enum lookups fall back predictably for unknown labels`() {
    assertEquals(CodecFormat.BASE64, CodecFormat.fromLabel("nope"))
    assertEquals(CodecMode.ENCODE, CodecMode.fromLabel("nope"))
    assertEquals(CodecFormat.URL, CodecFormat.fromLabel("URL"))
    assertEquals(CodecFormat.ASCII, CodecFormat.fromLabel("ASCII"))
  }

  // ---- ASCII ---------------------------------------------------------------

  @Test
  fun `ascii encodes printable text as decimal codes`() {
    assertEquals(
      "65 66 67",
      Codecs.encode("ABC", CodecFormat.ASCII).textOrNull
    )
    assertEquals(
      "32 104 105",
      Codecs.encode(" hi", CodecFormat.ASCII).textOrNull
    )
  }

  @Test
  fun `ascii round trips`() {
    val original = "root@target:~# id -u"
    val encoded = Codecs.encode(original, CodecFormat.ASCII).textOrNull!!
    assertEquals(original, Codecs.decode(encoded, CodecFormat.ASCII).textOrNull)
  }

  @Test
  fun `ascii covers the whole 7-bit range`() {
    val all = (0..127).map { it.toChar() }.joinToString("")
    val encoded = Codecs.encode(all, CodecFormat.ASCII).textOrNull!!
    assertEquals(all, Codecs.decode(encoded, CodecFormat.ASCII).textOrNull)
  }

  @Test
  fun `ascii decode tolerates ragged whitespace`() {
    assertEquals(
      "ATTACK at dawn",
      Codecs.decode("  65 84\t84\n65 67 75 32 97 116 32 100 97 119 110  ", CodecFormat.ASCII)
        .textOrNull
    )
  }

  /**
   * A code point above 127 must fail loudly. Encoding it would produce a number
   * that means nothing in the 7-bit contexts the format targets, so the operator
   * would end up with a payload that looks fine and decodes to something else.
   */
  @Test
  fun `ascii encode refuses extended characters`() {
    // Written as escapes rather than literals so the expectation survives
    // whatever encoding this file happens to be read back with.
    val extended = listOf("\u00e9", "\u2713", "\u2014", "\u00ff", "A\u00e9B")
    extended.forEach { input ->
      val result = Codecs.encode(input, CodecFormat.ASCII)
      assertTrue("expected Failure for '$input'", result is CodecResult.Failure)
      assertEquals(CodecError.NON_ASCII_INPUT, result.errorOrNull)
      assertEquals(null, result.textOrNull)
    }
  }

  @Test
  fun `ascii transform rejects empty input before reaching the codec`() {
    // `encode` itself is happy with an empty string; only the shared entry point
    // guards it, so that guard is pinned here instead of assumed.
    assertEquals(
      CodecError.EMPTY_INPUT,
      Codecs.transform("", CodecMode.ENCODE, CodecFormat.ASCII).errorOrNull
    )
  }

  @Test
  fun `ascii decode refuses out-of-range and non-numeric codes`() {
    listOf("65 128", "256", "-1", "65 abc", "65 1.5").forEach { input ->
      val result = Codecs.decode(input, CodecFormat.ASCII)
      assertTrue("expected Failure for '$input'", result is CodecResult.Failure)
      assertEquals(CodecError.INVALID_ASCII_CODE, result.errorOrNull)
    }
  }

  /**
   * A partial decode is worse than a failed one: it hands back a
   * plausible-looking string that is not what was encoded, and the operator has
   * no way to tell.
   */
  @Test
  fun `ascii decode fails whole rather than returning a partial string`() {
    val result = Codecs.decode("65 66 999 67", CodecFormat.ASCII)
    assertTrue(result is CodecResult.Failure)
    assertEquals(null, result.textOrNull)
  }

  @Test
  fun `ascii decode of whitespace only is empty input`() {
    assertEquals(
      CodecError.EMPTY_INPUT,
      Codecs.decode("   \n ", CodecFormat.ASCII).errorOrNull
    )
  }

  @Test
  fun `ascii is reachable through the shared transform entry point`() {
    val encoded = Codecs.transform("Hi", CodecMode.ENCODE, CodecFormat.ASCII).textOrNull
    assertEquals("72 105", encoded)
    assertEquals("Hi", Codecs.transform(encoded!!, CodecMode.DECODE, CodecFormat.ASCII).textOrNull)
  }
}
