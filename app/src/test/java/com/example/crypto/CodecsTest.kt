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
  }
}
