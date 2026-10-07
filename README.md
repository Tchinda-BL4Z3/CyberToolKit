# CyberToolkit SECOPS

Offline Android toolbox for security and tactical training. French-first UI with a
full English translation, built with Jetpack Compose and Material 3.

The app makes **no network requests**. There is no `INTERNET` permission in the
manifest, no networking dependency, and no analytics. Everything runs on device.

## The five tabs

| Tab | What it does |
| --- | --- |
| **Encodeur** | Base64 / Hex / URL encode and decode, with typed errors instead of a sentinel error string |
| **Crypto** | MD5, SHA-1/256/384/512, HMAC-SHA-256, PBKDF2 strength meter, AES-256-GCM encrypt/decrypt |
| **Cheat Sheets** | Offline catalogue of common commands, searchable and filterable |
| **Payloads** | Reverse-shell one-liners for Bash, Python, PowerShell, Netcat and ICMP, with host/port validation |
| **Paramètres** | Theme, lock code, auto-lock, screen capture, data wipe |

## Security model

Read `docu/THREAT_MODEL.md` for the full write-up. The short version:

- **Lock code** — 5 digits, stored as PBKDF2-HMAC-SHA256 (210 000 iterations,
  random 128-bit salt) rather than clear text. Legacy `secret_combination` values
  are migrated in place on first read and then erased.
- **Brute-force** — 3 attempts, then a 10-minute lockout that doubles on each
  subsequent lockout, capped at 24 h. The ledger lives in `SharedPreferences`,
  not `rememberSaveable`, so force-stopping the app grants no extra guesses.
  Both a monotonic clock and a tamper-resistant wall-clock high-water mark are
  checked, so rewinding the clock or rebooting does not clear a lockout.
- **Encryption** — AES-256-GCM with a random IV per operation and a versioned
  `CYB1$…` envelope, so ciphertext is authenticated and self-describing.
- **Screen capture** — `FLAG_SECURE`, applied on start and re-applied whenever
  the setting changes.
- **Backups** — `android:allowBackup="false"`, `fullBackupContent` rules that
  exclude the security preferences, and cleartext traffic refused.
- **No silent reset** — there is no "unlock now" escape hatch and no way to
  clear the lockout from the UI. `LockoutGuard.resetForTests()` exists for tests
  only and is not referenced by any screen.

## A note on intent

This is a training and reference tool. The payloads tab emits the same
one-liners you will find in any public pentest reference, because those are the
thing being studied. It contains no scanner, no exploit, and no automation —
it produces text for a human to read and understand. Only run anything against
systems you own or are authorised to test.

## Build

Requires JDK 17+ and the Android SDK. No API key, no `.env` file, no login.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # unit tests (Robolectric + JVM)
./gradlew :app:lintDebug              # Android Lint
```

The debug APK lands in `app/build/outputs/apk/debug/`.

### Release builds

Signing material is read from the environment and is never committed:

```bash
export KEYSTORE_PATH=/path/to/upload.jks
export STORE_PASSWORD=...
export KEY_PASSWORD=...
export KEY_ALIAS=upload

./gradlew :app:assembleRelease
```

A missing value fails the build with an actionable message via the
`verifyReleaseSigningMaterial` task rather than silently producing an unsigned
or debug-signed release APK.

## Project layout

```
app/src/main/java/com/example/
├── MainActivity.kt          single-activity host, lifecycle, FLAG_SECURE
├── crypto/                  CryptoCore (primitives), Codecs (text transforms)
├── data/                    CyberSettingsStore (persisted preferences)
├── payloads/                PayloadGenerator + target validation
├── security/                CyberSecurityManager, LockoutGuard
└── ui/
    ├── CyberToolkitViewModel.kt
    ├── components/          CyberTextField, CyberTerminalBox, SecureClipboard, …
    ├── crypto/ encoder/ payloads/ sheets/ settings/
    ├── lock/                HomeScreenLock
    └── theme/               CyberToolkitTheme, palettes
```

`MyApplicationTheme` is kept as an alias of `CyberToolkitTheme` for the
`GreetingScreenshotTest` contract.

## Tests

73 unit tests covering the security-critical behaviour, not just the happy path:

- `CryptoCoreTest` — published digests, RFC 4231 HMAC vectors, AES-GCM
  round-trip, wrong-passphrase rejection, tamper detection, random IV.
- `CodecsTest` — round-trips, and that every failure is a typed error that never
  leaks into the copyable output box.
- `CyberSecurityManagerTest` — asserts on the *raw* preferences file that the
  code is never written in clear text, plus legacy migration.
- `LockoutGuardTest` — budget, persistence across process death, clock rollback,
  reboot, escalation and the 24 h cap, all driven by injected timestamps.
- `PayloadGeneratorTest` — host/port validation and the `LHOST`/`LPORT`
  template fallback.
