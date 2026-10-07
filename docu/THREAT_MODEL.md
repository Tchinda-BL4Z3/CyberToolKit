# Threat model — CyberToolkit SECOPS

Scope: what the 5-digit console lock and the local data actually protect, who
the realistic attacker is, and what is explicitly **not** defended. Written for
the v2.4.0 codebase.

## 1. Asset and attacker

**Assets worth protecting**

| Asset | Where | Why it matters |
| --- | --- | --- |
| Lock code | PBKDF2 record in `cyber_security_prefs` | Gate to the console |
| Anti-bruteforce counters | `cyber_lockout_prefs` | The thing that makes guessing expensive |
| Lab defaults (host, port) | `cyber_settings` | Operator's own environment |
| Clipboard contents | system clipboard | Secrets the operator copied |

**Attacker model.** Someone with *physical, unlocked access* to a running device
or its filesystem via adb/root. This is a local UI lock, so the honest baseline
is: an attacker who can hold the device and press buttons, or who can pull the
app's private files off it. It is explicitly **not** a defence against an
attacker with a debugger attached to a rooted device while the app is running —
that adversary can read the `ViewModel` directly.

Out of scope: remote attacks (there is no network surface), supply-chain attacks
on the build, and social engineering of the device owner.

## 2. Controls

### 2.1 Lock code at rest

Stored as `pbkdf2$hmac$iterations$b64salt$b64hash`, PBKDF2-HMAC-SHA256 at
210 000 iterations with a 128-bit random salt per write. The plaintext is never
written; `CyberSecurityManagerTest` asserts this against the *raw* preferences
XML rather than the accessors, so a future change that stores the digits
anywhere reachable fails the build.

**Why not a hardware-bound key.** `KeyStore`-wrapped storage would raise the bar
against an attacker who exfiltrates the files but cannot use the device's TEE.
It is not implemented because it complicates rotation and the wipe path for a
marginal gain against an adversary who already has the device unlocked. The
PBKDF2 choice is the deliberate trade: an exfiltrated hash is a cracking target,
not the code.

**Migration.** An existing `secret_combination` string is verified once, re-hashed
and erased. A corrupt value falls back to the factory code rather than bricking
the console.

### 2.2 Brute-force resistance

Three attempts, then a lockout. The ledger is in `SharedPreferences`, not
`rememberSaveable` — the previous implementation kept it in the instance-state
`Bundle`, which meant force-stopping the app granted a fresh three-attempt
budget indefinitely.

The deadline is enforced against **two** clocks, because each has a different
failure mode:

- `SystemClock.elapsedRealtime()` — monotonic, survives a wall-clock change
  within a boot.
- Wall clock + high-water mark — survives a reboot, which resets
  `elapsedRealtime` to near zero.

If the wall clock is found *behind* the high-water mark by more than the
tolerance, it is treated as tampering and the remaining lockout is replayed in
full. The lock lifts when the *earlier* of the two deadlines passes, so the
countdown cannot overshoot the real deadline.

Escalation doubles each lockout (10 min → 20 → 40 …), capped at 24 h. A
successful unlock **resets** the tier, on purpose: a correct code is the signal
that the operator is the owner. Escalation therefore only grows for someone who
never gets in.

**No reset path in the UI.** The previous build shipped a "DÉBLOQUER MAINTENANT"
button that nullified the whole lockout. `LockoutGuard.resetForTests()` is
referenced only by tests.

### 2.3 Screen capture

`FLAG_SECURE`, applied in `onCreate` and re-applied whenever the setting changes,
so screenshots and the recents thumbnail are blocked when the operator opts in.
Off by default so the app is not surprising on first run.

### 2.4 Data at rest and egress

`allowBackup="false"`, plus explicit exclusion of every domain in both
`backup_rules.xml` (API 23–30) and `data_extraction_rules.xml` (API 31+,
covering cloud backup *and* device transfer). Rolling the lock-code hash to a new
handset would hand an attacker the offline cracking target for free.

No `INTERNET` permission, no networking dependency, `usesCleartextTraffic="false"`
and a `network_security_config` that denies cleartext everywhere.

**Wipe is a session wipe, not a data wipe.** Settings → *Wipe session* clears the
in-memory buffers (encoder input, crypto input, AES payload, hash result, secret)
via `clearSessionData()`. It deliberately does **not** touch the stored lock code
or the brute-force counters — wiping the lock from inside the unlocked app would
be a way to defeat it. To replace the code, use the rotation form; to clear the
ledger, wait out the lockout. `CyberSecurityManager.clearCombination()` exists
and re-seeds the factory code, but is not wired to any button in the release UI.

### 2.5 Clipboard

`SecureClipboard` clears the primary clip shortly after a copy, and is the only
path the app uses to write to the clipboard.

## 3. Accepted residual risks

1. **Root + reboot + file edit defeats the ledger.** An attacker who can edit
   the app's private files and reboot can zero the counters. Defending this
   means hardware-backed monotonic state, which is out of scope for a local UI
   lock. *Mitigation:* the PBKDF2 hash still stands in the way of a file dump.
2. **A 5-digit space is 10⁵.** Even at 10 minutes per lockout that is a
   long-deny strategy, not a strong one. The lock protects casual access and
   shoulder-surfing; it is not a substitute for full-disk encryption.
3. **Weak codes are the operator's choice.** `isWeakCombination` rejects the
   obvious ones (11111, 12345, 12121) for *new* codes and never invalidates an
   existing lock, so an upgrade cannot lock anyone out. It is guidance, not a
   guarantee.
4. **Factory code is public.** A fresh install uses `11111`; the first-run
   screen shows it and Settings nags while it is still active. Anyone assuming a
   fresh install is locked out is wrong.
5. **A debugger beats it.** A local UI lock cannot resist an attacker attached to
   a rooted device.

## 4. Verification

`./gradlew :app:testDebugUnitTest` — 73 tests, all passing. The security-relevant
ones:

- `CyberSecurityManagerTest` — no clear-text code in the raw prefs; legacy
  migration erases the plaintext; malformed codes rejected.
- `LockoutGuardTest` — budget, persistence across a simulated process death,
  clock rollback replay, reboot survival, escalation, 24 h cap, all driven by
  injected timestamps so the clock-dependent branches are actually reachable.
- `CryptoCoreTest` — published digest vectors, RFC 4231 HMAC, AES-GCM
  round-trip, wrong-passphrase rejection, tamper detection, random IV.
- `CodecsTest` — every failure is a typed error that never reaches the copyable
  output box.

`./gradlew :app:lintDebug` — 0 errors.
