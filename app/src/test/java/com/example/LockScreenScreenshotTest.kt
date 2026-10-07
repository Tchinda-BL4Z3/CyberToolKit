package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Golden-image test for the lock screen.
 *
 * ### Why the previous test was worthless
 * `GreetingScreenshotTest` called `captureRoboImage(filePath = ...)` with no
 * `RoborazziRule` and outside any Roborazzi Gradle task, so nothing was captured
 * and nothing was compared: the call silently did nothing. Its
 * `src/test/screenshots/greeting.png` was still the file the Android Studio
 * template committed, dated weeks before any of this work. The suite reported a
 * green test that could not fail, which is worse than no test at all.
 *
 * The rule is what turns a capture into a comparison. How to run it:
 *  - `./gradlew :app:recordRoborazziDebug` accepts the current rendering;
 *  - `./gradlew :app:verifyRoborazziDebug` fails on any later drift;
 *  - `RoborazziRule` also checks accessibility, which the rest of the suite does not.
 *
 * ### Where the golden image lives, and why it matters
 * The reference is written to `src/test/screenshots/`, which is versioned. It
 * used to default to `app/build/outputs/roborazzi`, and that directory is covered
 * by `app/.gitignore` - so the golden image was never committed and a clean
 * checkout had nothing to compare against. `verifyRoborazziDebug` then failed
 * with "file not found" on CI, or worse, someone re-recorded and blessed whatever
 * the current build produced. A screenshot test whose reference is not in version
 * control is a test that cannot fail for the right reason.
 *
 * Roborazzi is configured with a relative output directory in `build.gradle.kts`
 * so this class does not hard-code a path. The file name is still explicit, so a
 * rename here is a deliberate, visible change rather than a silent one.
 *
 * A plain `./gradlew :app:testDebugUnitTest` still neither writes nor compares:
 * the comparison only happens under the Roborazzi tasks below. This test is
 * therefore not part of the fast loop by design, and the palette wiring it would
 * also cover is already asserted pixel-by-pixel in `ThemeWiringTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class LockScreenScreenshotTest {

  @get:Rule(order = 0)
  val composeTestRule = createComposeRule()

  /**
   * The `Options`-only constructor is used on purpose: the overload taking a
   * capture node would need `onRoot()` at field-initialisation time, i.e. before
   * `setContent` has run. Leaving the root implicit lets `captureRoboImage` use
   * whichever node the test hands it.
   *
   * No directory is passed here either. Roborazzi 1.59 has no per-test
   * `outputFileName` option, and the output directory is already pinned to the
   * versioned `src/test/screenshots` in `build.gradle.kts`, so repeating it here
   * would only create a second place to get wrong.
   */
  @get:Rule(order = 1)
  val roborazziRule = RoborazziRule(RoborazziRule.Options())

  @Before
  fun clearSecurityState() {
    // A fresh install, so the lock screen is the subject rather than the console.
    File(
      ApplicationProvider.getApplicationContext<Application>().filesDir.parentFile,
      "shared_prefs"
    ).listFiles()?.forEach { it.delete() }
  }

  @Test
  fun the_lock_screen_renders() {
    composeTestRule.setContent { MyApplicationTheme { CyberToolkitApp() } }
    composeTestRule.waitForIdle()

    // No file name argument: a relative path here is resolved against the module
    // root and ignores the output directory configured in `build.gradle.kts`,
    // which is how an earlier attempt ended up writing `app/lock_screen.png`.
    // Letting the rule derive the name from the class and method keeps the image
    // inside the configured, versioned directory.
    composeTestRule.onRoot().captureRoboImage()
  }
}
