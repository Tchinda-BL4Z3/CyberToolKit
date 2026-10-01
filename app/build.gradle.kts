import java.io.File

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
}

// Release signing material comes from the environment only, never from the repo.
// The values are resolved lazily so a debug build does not require them; the
// hard failure is raised by `verifyReleaseSigningMaterial`, which only runs when
// a release variant is actually being packaged.
val releaseKeystorePath: String = System.getenv("KEYSTORE_PATH").orEmpty()
val releaseStorePassword: String? = System.getenv("STORE_PASSWORD")
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD")
val releaseKeyAlias: String = System.getenv("KEY_ALIAS") ?: "upload"

val verifyReleaseSigningMaterial by tasks.registering {
  group = "verification"
  description = "Fails fast, with an actionable message, when the release keystore env vars are missing."

  // The environment is read *inside* `doLast`, through plain `System.getenv`, and
  // nothing from the script scope is referenced here.
  //
  // This is not a style preference. Referencing the top-level `val`s below made
  // the `doLast` closure capture the whole Gradle script object, which the
  // configuration cache refuses to serialise: `assembleRelease` failed with
  // "Configuration cache problems found in this build" before it ever reached
  // R8. Reading the environment at execution time keeps the closure free of
  // script references, and is also more correct - the variables are resolved when
  // the task runs rather than when the script is configured.
  doLast {
    val keystorePath = System.getenv("KEYSTORE_PATH").orEmpty()
    val storePassword = System.getenv("STORE_PASSWORD")
    val keyPassword = System.getenv("KEY_PASSWORD")

    val missing = buildList {
      if (keystorePath.isBlank()) add("KEYSTORE_PATH")
      if (storePassword.isNullOrEmpty()) add("STORE_PASSWORD")
      if (keyPassword.isNullOrEmpty()) add("KEY_PASSWORD")
    }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "Cannot package a release: missing environment variable(s) ${missing.joinToString(", ")}.\n" +
          "Export them, e.g.:\n" +
          "  export KEYSTORE_PATH=/absolute/path/upload.jks\n" +
          "  export STORE_PASSWORD=...  KEY_PASSWORD=...  KEY_ALIAS=upload"
      )
    }
    val keystore = File(keystorePath)
    if (!keystore.isFile) {
      throw GradleException("KEYSTORE_PATH does not point at a file: $keystorePath")
    }
  }
}

tasks.configureEach {
  if (name == "assembleRelease" || name == "bundleRelease" || name == "packageRelease") {
    dependsOn(verifyReleaseSigningMaterial)
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // Aligned with the source package (`com.example.cybertoolkit`). This used to
    // be `com.aistudio.cybertoolkit.encdx`, a leftover from the AI Studio
    // template, which meant the installed app claimed an identity the code knows
    // nothing about: any build already on a device under the old id installs
    // *alongside* this one instead of updating it, leaving two consoles on the
    // same phone. Changing it is a one-way door for existing installs - the old
    // app has to be uninstalled by hand - so it is done once, here, rather than
    // left to drift.
    applicationId = "com.example.cybertoolkit"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "2.4.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Purely local app: no dynamic features, no unbundled native code.
  androidResources {
    localeFilters += listOf("fr", "en")
  }

  signingConfigs {
    create("release") {
      // Placeholder when the env vars are absent: a debug build must configure
      // fine, and `verifyReleaseSigningMaterial` reports the real problem with
      // an actionable message before any release variant gets packaged.
      storeFile = file(if (releaseKeystorePath.isBlank()) "missing-release-keystore.jks" else releaseKeystorePath)
      storePassword = releaseStorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
      // The upload key must never be replaced by test keys.
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  buildTypes {
    release {
      // R8 shrinks + obfuscates the release build.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Uses the default AGP debug keystore (~/.android/debug.keystore), auto-created if absent.
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources {
      excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/*.version",
        "DebugProbesKt.bin",
        "kotlin-tooling-metadata.json",
        "**/*.kotlin_metadata",
        "META-INF/DEPENDENCIES"
      )
    }
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }

  // Roborazzi golden images go to a versioned directory, not the default
  // `build/outputs/roborazzi`. `app/.gitignore` ignores `build/`, so the default
  // location means the reference is never committed and a clean checkout has
  // nothing to compare against.
  roborazzi {
    outputDir.set(file("src/test/screenshots"))
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }

  lint {
    // Security-relevant checks must fail the build rather than just warn.
    warningsAsErrors = false
    abortOnError = true
    checkDependencies = true
    disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  // Compose / AndroidX
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Test
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
