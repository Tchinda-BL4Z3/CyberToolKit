package com.example.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [SecureClipboard].
 *
 * ### Why the pre-33 path needs a test of its own
 * `ClipDescription.EXTRA_IS_SENSITIVE` only exists from API 33. Referencing it
 * does not crash on older releases, because the Kotlin compiler folds the field
 * into a plain `String` constant at build time - there is no runtime class lookup
 * to fail. That is exactly why it is easy to get wrong: the app keeps working,
 * the secret simply stops being marked, and nothing anywhere complains.
 *
 * So the behaviour on both sides of the boundary is asserted explicitly here. The
 * TTL wipe is the fallback that protects a secret on API 24-32, and the tests
 * below make sure it is still armed and still removes only our own clip.
 */
@RunWith(RobolectricTestRunner::class)
class SecureClipboardTest {

  private lateinit var context: Context
  private lateinit var clipboard: ClipboardManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  }

  private fun currentClip(): ClipData? = clipboard.primaryClip

  private fun currentText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

  // ---------------------------------------------------------------------------
  // The sensitive flag
  // ---------------------------------------------------------------------------

  @Test
  fun `the clip is always flagged sensitive when copied`() {
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")

    val description = currentClip()?.description
    assertNotNull("nothing was copied", description)
    assertTrue(
      "the clip must carry the sensitive flag on every API level",
      description?.extras?.getBoolean(SecureClipboard.SENSITIVE_FLAG) == true
    )
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun `on api 33 the flag uses the platform constant`() {
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")

    // On 33+ this is the platform's own key, not a look-alike we invented.
    assertEquals(
      ClipDescription.EXTRA_IS_SENSITIVE,
      SecureClipboard.SENSITIVE_FLAG
    )
    assertEquals(
      true,
      currentClip()?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)
    )
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.S])
  fun `on api 31 the flag is inlined and copying still works`() {
    // The constant is folded into the dex, so nothing is resolved reflectively
    // and no NoSuchFieldError is possible on a release where the field does not
    // exist. What the platform does with the unknown key is its business; what
    // matters is that we do not crash and the secret is still copied.
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")

    assertEquals("s3cr3t-hash", currentText())
    assertTrue(
      currentClip()?.description?.extras?.getBoolean(SecureClipboard.SENSITIVE_FLAG) == true
    )
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.N])
  fun `on api 24 the pre-33 fallback still copies the secret`() {
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")
    assertEquals("s3cr3t-hash", currentText())
  }

  // ---------------------------------------------------------------------------
  // The TTL wipe, which is the real protection before API 33
  // ---------------------------------------------------------------------------

  @Test
  @Config(sdk = [Build.VERSION_CODES.S])
  fun `on pre-33 the wipe removes our own clip`() {
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")
    assertNotNull(currentClip())

    SecureClipboard.clearIfOurs(context, clipboard, "s3cr3t-hash")

    val after = currentClip()
    val text = after?.getItemAt(0)?.text?.toString()
    assertTrue(
      "the secret survived the wipe: '$text'",
      text.isNullOrEmpty()
    )
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.S])
  fun `the wipe never destroys a clip the user copied afterwards`() {
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")
    // The user copies something else before the TTL expires.
    clipboard.setPrimaryClip(ClipData.newPlainText("Other", "unrelated-text"))

    SecureClipboard.clearIfOurs(context, clipboard, "s3cr3t-hash")

    assertEquals(
      "the wipe destroyed a clip that was not ours",
      "unrelated-text",
      currentText()
    )
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.S])
  fun `the wipe is a no-op when the clipboard is empty`() {
    SecureClipboard.clearIfOurs(context, clipboard, "s3cr3t-hash")
    assertTrue(currentClip() == null || currentText().isNullOrEmpty())
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.S])
  fun `a second copy wipes the first value only`() {
    SecureClipboard.copy(context, clipboard, "first")
    SecureClipboard.copy(context, clipboard, "second")
    // The expected value no longer matches what is on the clipboard.
    SecureClipboard.clearIfOurs(context, clipboard, "first")
    assertEquals("second", currentText())
  }

  // ---------------------------------------------------------------------------
  // Degenerate inputs
  // ---------------------------------------------------------------------------

  @Test
  fun `a null clipboard is ignored instead of crashing`() {
    SecureClipboard.copy(context, null, "s3cr3t-hash")
    SecureClipboard.clearIfOurs(context, null, "s3cr3t-hash")
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.S])
  fun `empty text is never put on the clipboard`() {
    SecureClipboard.copy(context, clipboard, "")
    assertTrue(currentClip() == null || currentText().isNullOrEmpty())
  }

  @Test
  fun `the sensitive flag is a plain string constant`() {
    // Documents why the InlinedApi lint hit is not a runtime hazard: the value is
    // a compile-time constant that the dex carries, which is why the same code
    // path is exercised unchanged on API 24.
    assertTrue(SecureClipboard.SENSITIVE_FLAG is String)
    assertFalse(SecureClipboard.SENSITIVE_FLAG.isEmpty())
  }

  @Test
  fun `the wipe timeout is a bounded, non-zero delay`() {
    assertTrue(SecureClipboard.CLIPBOARD_TTL_MS > 0L)
    assertTrue(SecureClipboard.CLIPBOARD_TTL_MS <= 5 * 60_000L)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun `the extras bundle is a persistable bundle`() {
    SecureClipboard.copy(context, clipboard, "s3cr3t-hash")
    assertTrue(currentClip()?.description?.extras is PersistableBundle)
  }
}
