package com.example.crypto

import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The three wire formats handled by the Encoder tab. */
enum class CodecFormat(val label: String) {
  BASE64("Base64"),
  HEX("Hex"),
  URL("URL");

  companion object {
    fun fromLabel(label: String): CodecFormat =
      entries.firstOrNull { it.label == label } ?: BASE64
  }
}

enum class CodecMode(val label: String) {
  ENCODE("encode"),
  DECODE("decode");

  companion object {
    fun fromLabel(label: String): CodecMode =
      entries.firstOrNull { it.label == label } ?: ENCODE
  }
}

/** Why a transform failed, so the UI can show a localised message. */
enum class CodecError {
  EMPTY_INPUT,
  ODD_HEX_LENGTH,
  NO_HEX_DIGIT,
  INVALID_BASE64,
  INVALID_URL_ESCAPE
}

sealed interface CodecResult {
  data class Success(val text: String) : CodecResult
  data class Failure(val error: CodecError) : CodecResult

  val textOrNull: String? get() = (this as? Success)?.text
  val errorOrNull: CodecError? get() = (this as? Failure)?.error
}

/**
 * Pure text codecs: Base64, Hex and URL percent-encoding, in both directions.
 *
 * Every failure mode is reported as a [CodecError] instead of throwing or
 * silently returning a half-decoded string. The previous implementation returned
 * the literal sentence "Invalid input for selected format" *as if it were the
 * result*, so that text ended up in the copyable output box.
 */
object Codecs {

  /** Hex output keeps the original space-separated layout for terminal readability. */
  private const val HEX_SEPARATOR = " "
  private const val HEX_DIGITS = "0123456789abcdef"

  fun transform(input: String, mode: CodecMode, format: CodecFormat): CodecResult {
    if (input.isEmpty()) return CodecResult.Failure(CodecError.EMPTY_INPUT)
    return when (mode) {
      CodecMode.ENCODE -> encode(input, format)
      CodecMode.DECODE -> decode(input, format)
    }
  }

  fun encode(input: String, format: CodecFormat): CodecResult = when (format) {
    CodecFormat.BASE64 -> CodecResult.Success(
      Base64.encodeToString(input.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    )

    CodecFormat.HEX -> CodecResult.Success(
      buildString {
        for (byte in input.toByteArray(StandardCharsets.UTF_8)) {
          val v = byte.toInt() and 0xFF
          if (isNotEmpty()) append(HEX_SEPARATOR)
          append(HEX_DIGITS[v ushr 4])
          append(HEX_DIGITS[v and 0x0F])
        }
      }
    )

    CodecFormat.URL -> try {
      CodecResult.Success(URLEncoder.encode(input, StandardCharsets.UTF_8.name()))
    } catch (e: Exception) {
      CodecResult.Failure(CodecError.INVALID_URL_ESCAPE)
    }
  }

  fun decode(input: String, format: CodecFormat): CodecResult {
    return when (format) {
      CodecFormat.BASE64 -> decodeBase64(input)
      CodecFormat.HEX -> decodeHex(input)
      CodecFormat.URL -> try {
        CodecResult.Success(URLDecoder.decode(input, StandardCharsets.UTF_8.name()))
      } catch (e: Exception) {
        CodecResult.Failure(CodecError.INVALID_URL_ESCAPE)
      }
    }
  }

  private fun decodeBase64(input: String): CodecResult {
    // Accept the URL-safe alphabet and any whitespace the user pasted.
    val compact = input.filterNot { it.isWhitespace() }
      .replace('-', '+')
      .replace('_', '/')
    if (compact.isEmpty()) return CodecResult.Failure(CodecError.INVALID_BASE64)
    val bytes = try {
      Base64.decode(compact, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
      return CodecResult.Failure(CodecError.INVALID_BASE64)
    }
    return CodecResult.Success(String(bytes, StandardCharsets.UTF_8))
  }

  private fun decodeHex(input: String): CodecResult {
    // Separators a human may have typed: spaces, colons, dashes, an 0x prefix.
    val separators = setOf(' ', '\t', ':', '-')
    val stripped = input
      .removePrefix("0x")
      .removePrefix("0X")
    if (stripped.any { it !in separators && it.digitToIntOrNull(16) == null }) {
      return CodecResult.Failure(CodecError.NO_HEX_DIGIT)
    }
    val clean = stripped.filter { it !in separators }
    if (clean.isEmpty()) return CodecResult.Failure(CodecError.NO_HEX_DIGIT)
    if (clean.length % 2 != 0) return CodecResult.Failure(CodecError.ODD_HEX_LENGTH)

    val bytes = ByteArray(clean.length / 2)
    for (i in bytes.indices) {
      bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return CodecResult.Success(String(bytes, StandardCharsets.UTF_8))
  }
}
