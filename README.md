# TextGate AI

A minimal, privacy-first Android assistant that translates text you type
into English, triggered only by typing `?en` at the end of a sentence, in
apps you explicitly allow. Built to be small enough to read end to end in
one sitting.

```
Daj znać jak będziesz miał chwilę, nie ma pośpiechu ?en
                        ↓
Let me know when you get a chance, no rush.
```

It uses **your normal keyboard** (Gboard, SwiftKey, whatever you already
use) — this is not a keyboard app, and it never becomes one. It watches
for the trigger through Android's Accessibility API, and only ever reads
the one field you are actively editing.

---

## 1. Project structure

```
TextGateAI/
├── settings.gradle.kts
├── build.gradle.kts                     (root — plugin versions only)
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
└── app/
    ├── build.gradle.kts                 (module config; zero prod. dependencies)
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/textgate/ai/
        │   │   ├── TextGateApplication.kt
        │   │   ├── accessibility/
        │   │   │   └── TextGateAccessibilityService.kt   ← the whole pipeline
        │   │   ├── security/
        │   │   │   ├── SensitiveInputGuard.kt   (isSensitiveInput — the security gate)
        │   │   │   ├── EventGate.kt             (whitelist/blacklist/trigger decision chain)
        │   │   │   ├── TriggerDetector.kt       (?en detection + length limit)
        │   │   │   ├── AppBlocklist.kt          (hard-coded never-allow list)
        │   │   │   ├── AppSettingsStore.kt      (master switch + user allow-list)
        │   │   │   ├── KeystoreCrypto.kt        (AES-256-GCM via AndroidKeyStore)
        │   │   │   ├── SecureApiKeyStore.kt     (encrypted API key persistence)
        │   │   │   └── ResultPolicy.kt          (only Success may touch the field)
        │   │   ├── network/
        │   │   │   ├── NetworkAllowlist.kt      (the one allowed host)
        │   │   │   └── GeminiClient.kt          (HttpsURLConnection, no HTTP library)
        │   │   ├── model/
        │   │   │   └── TranslationPrompts.kt    (fixed system prompt)
        │   │   ├── settings/
        │   │   │   ├── SettingsActivity.kt
        │   │   │   └── InstalledAppsProvider.kt
        │   │   └── util/
        │   │       └── Debouncer.kt
        │   └── res/                              (layouts, strings, xml configs)
        ├── test/java/com/textgate/ai/            (JVM unit tests — 33 tests)
        └── androidTest/java/com/textgate/ai/     (on-device Keystore test)
```

Every file above exists and is complete — there are no `TODO`s, stub
methods, or placeholder logic anywhere in this project.

---

## 2. Architecture

**Design principle:** every decision defaults to "do nothing." The app
never reads a field, never sends a network request, and never writes a
field unless every single one of a fixed sequence of checks has passed.
Any uncertainty — an exception, a null, an unreadable field, an app not on
the allow-list — resolves to inaction.

### 2.1 The pipeline

```
Keyboard (Gboard/SwiftKey/etc.)
        │  user types
        ▼
Android text field (EditText or equivalent, in some third-party app)
        │  fires AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ▼
TextGateAccessibilityService.onAccessibilityEvent()
        │  (only this ONE event type is subscribed to — see
        │   accessibility_service_config.xml)
        ▼
EventGate.evaluate(packageName, node)              ← SECURITY GATE
        │  1. packageName present?
        │  2. master AI switch on?           (AppSettingsStore.isAiEnabled)
        │  3. NOT hard-blocklisted?           (AppBlocklist.isBlocked)
        │  4. on the user's allow-list?       (AppSettingsStore.isPackageAllowed)
        │  5. node non-null?
        │  6. node.isEditable?                (SensitiveInputGuard.isEditableTextField)
        │  7. node NOT password/sensitive?    (SensitiveInputGuard.isSensitiveInput)
        │  ── only NOW is node.text ever read ──
        ▼
TriggerDetector.detect(fullText)
        │  ends with exact suffix "?en"? content ≤ 4000 chars? non-empty?
        ▼
   Decision.Ready(content, fullText)
        │  debounced 400ms; any earlier pending node is recycled, not leaked
        ▼
confirmAndProcess(): re-validates EVERYTHING above again (settings may have
        │  changed; field may have changed) before touching the network
        ▼
extract only the text before the trigger — nothing else
        ▼
GeminiClient.translateBlocking()  — HTTPS POST, header-based API key,
        │  connect to generativelanguage.googleapis.com ONLY
        ▼
Gemini API response
        │
        ▼
ResultPolicy.shouldReplaceText(result) — true ONLY for Result.Success
        │
        ▼
AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)  — no clipboard, ever
```

If the field's text has changed since the trigger was detected (the user
kept typing, deleted it, or switched apps), or the AI toggle was switched
off mid-request, the whole operation aborts silently — no request is sent,
or if one was already in flight, its result is discarded and the field is
left exactly as the user left it.

### 2.2 Why the code is organized this way

* **`SensitiveInputGuard.isSensitiveInput(node)`** is the single function
  named directly in the security spec — the central gate that every call
  site must pass before touching `node.text`. It is written so that
  *nothing* is read from the node until every check has already decided
  the field is safe: `node.text` is not evaluated anywhere above the
  point where `isSensitiveInput` and `isEditableTextField` have already
  returned. Any exception raised while inspecting the node's properties
  is caught and treated as "sensitive" (fail closed).

* **`EventGate`** exists so the "should this event do anything at all"
  decision is one reviewable, independently unit-tested class — not
  logic scattered across the accessibility service. The service calls
  this exact class; the unit tests exercise this exact class.

* **`ResultPolicy.shouldReplaceText`** exists so "only a successful
  Gemini response may ever change the field" is a single, named,
  exhaustively tested boolean function, rather than something you have to
  infer from reading a `when` block.

* **`GeminiClient`** is the only file in the entire project that performs
  network I/O. It re-validates the target host against
  `NetworkAllowlist` right before opening a connection — even though, in
  the current code, there is no way for that check to ever fail — as
  defense in depth against a future change accidentally altering URL
  construction.

### 2.3 Where a user's typed text exists in memory, and for how long

| Location | Lifetime |
|---|---|
| `AccessibilityNodeInfo.text` (read once, safe fields only) | Local variable inside `EventGate.evaluate`, discarded at the end of that call except for the extracted `content`/`fullText` strings passed onward |
| `content` (text before the trigger) | Held as a local variable / lambda capture from event → debounce → network call → response handling; not stored in any field, database, or file |
| `apiKey` (decrypted) | Local variable for the duration of one `confirmAndProcess` → `GeminiClient.translateBlocking` call; the underlying byte array is zeroed with `Arrays.fill` immediately after use in `SecureApiKeyStore`; the resulting Kotlin `String` cannot be forcibly zeroed (JVM limitation, documented in `SecureApiKeyStore.kt`) |
| HTTPS request/response bodies | Exist only inside `GeminiClient.translateBlocking`'s stack frame; never logged, never cached (`useCaches = false`), never written to disk |
| `pendingNode` / `requestInFlight` | A single `AccessibilityNodeInfo` reference, held only from the moment a trigger is detected until that one operation resolves (success, failure, or supersession) — never a history, never more than one at a time |

Nothing above is ever written to disk except the AES-256-GCM-encrypted
API key ciphertext (see §6) and the plain (non-secret) allow-list/model
settings, both in app-private `SharedPreferences`, both excluded from
every backup mechanism Android has.

---

## 3. Permissions — every single one, justified

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

That is the **only** `<uses-permission>` in the manifest.

* **Why it's needed:** `GeminiClient` makes one HTTPS POST per translation
  request, to the host in `NetworkAllowlist.GEMINI_HOST`. Android has no
  finer-grained, per-class or per-component INTERNET permission — it is
  necessarily process-wide — so it cannot be scoped tighter at the
  manifest level. It is scoped in *code* instead (see §5).

Permissions this app does **not** request, and does not need: contacts,
SMS, call logs, location, microphone, camera, storage, phone state,
`QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE`, or anything else. The list of installed apps shown
in Settings uses a declarative `<queries>` filter (launcher apps only),
which is not a runtime/dangerous permission at all.

**Accessibility** is not a manifest permission — it's a system-mediated
capability the user grants manually in Settings → Accessibility, and its
scope is further restricted by `accessibility_service_config.xml`
(one event type, no window enumeration; see §2 and §8).

---

## 4. Dependency report

**Production dependencies: zero.** The `dependencies {}` block in
`app/build.gradle.kts` contains no `implementation(...)` lines. Every
capability the shipped app needs — AES-256-GCM encryption, HTTPS,
JSON parsing, UI widgets, background execution, debouncing — comes from
the Android SDK and the Kotlin standard library, which are unavoidable
(pulled in automatically by the Android Gradle Plugin) and therefore
aren't "dependencies" in the sense of something chosen, version-pinned,
or auditable as a separate supply-chain input.

Concretely, capabilities and their source:

| Capability | Source | Why not a library |
|---|---|---|
| AES-256-GCM + hardware-backed key | `android.security.keystore.*`, `javax.crypto.*` | Platform API |
| HTTPS client | `javax.net.ssl.HttpsURLConnection` | Platform API |
| JSON build/parse | `org.json.*` | Platform API (bundled in `android.jar`, real implementation, not a stub) |
| UI widgets | `android.widget.*`, platform `Theme.Material` | Platform API — no AndroidX/Material Components library |
| View binding | AGP `buildFeatures.viewBinding` | AGP build-time codegen feature, not a chosen library |
| Background execution | `java.util.concurrent.Executors` | JDK |
| Debounce | `android.os.Handler` | Platform API |

This means the transitive-dependency tree for the **shipped app** is
empty — there is nothing to run `./gradlew app:dependencies` against
beyond the Kotlin stdlib and AGP's own implicit AndroidX annotations
artifact, neither of which executes any code path in this app's request
pipeline.

**Test-only dependencies** (compiled only for `./gradlew test`, never
packaged into the APK):

| Dependency | Why |
|---|---|
| `junit:junit:4.13.2` | Test runner/assertions |
| `org.robolectric:robolectric:4.13` | Runs `AccessibilityNodeInfo`, `SharedPreferences`, `org.json` against real Android framework behavior on the JVM, without an emulator |
| `androidx.test:core:1.6.1` | `ApplicationProvider` for Robolectric-backed `Context` |
| `org.mockito:mockito-core:5.12.0` + `org.mockito.kotlin:mockito-kotlin:5.4.0` | Used exactly once, to prove `isSensitiveInput` fails closed when node inspection throws |

**Instrumented-test-only dependencies** (compiled only for
`./gradlew connectedAndroidTest`, requires a real device/emulator, never
packaged into the APK):

| Dependency | Why |
|---|---|
| `androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2` | Needed to run `KeystoreCryptoInstrumentedTest`, which exercises the real `AndroidKeyStore` provider — not available under Robolectric or in a plain JVM unit test |

To reproduce the (empty) production dependency report yourself:

```
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```

---

## 5. Network audit

Every location in the codebase that could conceivably open a network
connection, found by grepping the entire project for
`http(s)://|URL|URI|Socket|WebSocket|HttpURLConnection|OkHttp|Retrofit|
DNS|Firebase|analytics|telemetry|crash|upload|POST|PUT`:

| File : line | What it is | Reaches the network? |
|---|---|---|
| `network/GeminiClient.kt` | `HttpsURLConnection` POST to `NetworkAllowlist.GEMINI_HOST` | **Yes — the only site in the entire project that does.** Guarded by: host allow-list re-check on the URL actually built, HTTPS-only, model-id regex validation, connect/read timeouts. |
| `network/NetworkAllowlist.kt` | Defines the single allowed hostname string | No — a constant and a comparison function, no I/O |
| `res/xml/network_security_config.xml` | Declares `cleartextTrafficPermitted="false"` everywhere, and a `domain-config` for the Gemini host | Not code — a platform policy file. It blocks all plaintext HTTP app-wide and pins trust-anchors to system CAs for the Gemini domain. It does **not**, by itself, prevent HTTPS to some other host — that guarantee comes from the code-level checks in `GeminiClient`, described honestly here rather than implied. |
| `AndroidManifest.xml` | `android:usesCleartextTraffic="false"`, `<uses-permission INTERNET>` | Not code — permission grant + manifest-level cleartext policy |
| `util/Debouncer.kt` | `Handler.postDelayed` | No — this is a documentation-comment false-positive (the word "runs" matched near "after ... input") |
| `TextGateApplication.kt` | Doc comment mentioning "no analytics/crash-reporting SDK" | No — comment only |
| Everywhere else the grep matched | Comments, KDoc referencing security properties, `mainHandler.post { }` (an in-process `Handler.post`, nothing to do with HTTP POST), string resources describing privacy behavior, XML namespace URLs (`http://schemas.android.com/...`, required boilerplate on every Android XML file, not a runtime network call) | No |

**Result matches the expected outcome:** the only external host this app
can ever reach is `generativelanguage.googleapis.com` — the official
Gemini API host — and only when `GeminiClient.translateBlocking()` is
invoked, which only happens from `TextGateAccessibilityService` after
every gate in §2.1 has passed, or from the explicit, user-initiated
"Test API connection" button in Settings.

No analytics, telemetry, crash reporting, ad, or update-check SDK exists
anywhere in this codebase. No `Firebase`, `Retrofit`, `OkHttp`, `Sentry`,
`Mixpanel`, `Amplitude`, `AppsFlyer`, or `Adjust` import exists anywhere
in this codebase (verified by the same grep).

---

## 6. API key handling

* Entered once in Settings, in a masked (`inputType="textPassword"`,
  `importantForAutofill="no"`) field.
* On "Save," it is encrypted with **AES-256-GCM**, using a key generated
  inside the **Android Keystore** (StrongBox-backed hardware module when
  the device has one, falling back to the Keystore's TEE-backed storage
  otherwise — see `KeystoreCrypto.kt`). The app itself never has access to
  the raw wrapping key; only the Keystore-mediated `Cipher` object.
* Only the ciphertext + IV (Base64-encoded) is written to
  `SharedPreferences`. The on-screen field is cleared immediately after
  the key is handed off for encryption.
* That `SharedPreferences` file is covered by
  `android:allowBackup="false"`, `backup_rules.xml`, and
  `data_extraction_rules.xml` — excluded from cloud backup, `adb backup`,
  and Android's device-to-device transfer flow, on every Android version
  this app supports.
* Sent to Gemini via the `x-goog-api-key` HTTPS **header**, never as a URL
  query parameter — so it never appears in a URL string, server access
  log entry that includes the query string, or this app's own `URL`
  object.
* Never logged: there is no `android.util.Log` call anywhere in
  `app/src/main` (verified by grep; see §5-style audit). `proguard-rules.pro`
  additionally strips all `Log.*` calls from release builds as a second,
  independent safety net in case a future change adds one.
* Never touches `ClipboardManager` — there is no clipboard code anywhere
  in this app (by design; see §7).
* Never exported — no `ContentProvider`, no explicit share/export
  feature, and both exported components (see §8) accept no
  externally-supplied data that could be used to exfiltrate it.
* Removing the key (Settings → "Remove saved key") deletes the
  ciphertext **and** destroys the Keystore wrapping key itself, so any
  ciphertext that somehow survived is permanently undecryptable.

**Known, documented limitation:** once decrypted into a Kotlin `String`
for the outgoing HTTPS request, that String cannot be forcibly zeroed —
this is a JVM/Kotlin language limitation (Strings are immutable and
interned), not something this app can work around. Exposure is minimized
by decrypting only immediately before use, in a local variable, and by
never storing the decrypted value in any field, cache, or log.

---

## 7. Clipboard

This app contains **no clipboard code whatsoever.** Text replacement uses
`AccessibilityNodeInfo.ACTION_SET_TEXT` exclusively. If that action fails
for any reason (the target app doesn't support it), the user is shown a
plain error and the field is left as-is — there is intentionally no
fallback to `ClipboardManager.setPrimaryClip`, per the security
requirement that a safer failure is preferable to a working-but-riskier
mechanism.

---

## 8. Exported components

| Component | `exported` | Why |
|---|---|---|
| `settings.SettingsActivity` | `true` | Mandatory — it's the launcher entry point; the system launcher must be able to start it. It reads no data from the launching `Intent` (no `getIntent().getExtras()`/data/scheme anywhere in `SettingsActivity.kt`), so there is no intent-injection surface even though it is exported. |
| `accessibility.TextGateAccessibilityService` | `true` | Mandatory — the OS Accessibility framework will not bind a service it cannot see as exported. Locked down by `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, which only the system process holds; no third-party app, however privileged, can bind or start it. It declares no other `<intent-filter>`. |

No `<provider>`, no `<receiver>` — this app has neither. No deep links,
no custom URI schemes, no `android:exported="true"` anywhere else.

---

## 9. Fail-closed checklist

Every one of these, if true, results in **doing nothing** (no read, no
network request, no field write):

* the accessibility node is `null`
* the node's type/editability can't be determined, or `isEditable` is `false`
* `node.isPassword` is `true`
* `inputType` variation is `TYPE_TEXT_VARIATION_PASSWORD`,
  `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`,
  or `TYPE_NUMBER_VARIATION_PASSWORD`
* the node's class name or hint text suggests a password/PIN/seed-phrase/
  CVV/OTP/card-number field
* any exception is raised while inspecting the node
* the app's package is not on the user's allow-list
* the app's package matches the hard-coded block-list (password managers,
  authenticators, OS security surfaces, or a banking/wallet/vault keyword)
* the master "Enable ?en trigger" switch is off
* the trigger is not an exact, case-sensitive `?en` suffix
* the text before the trigger is empty or exceeds 4000 characters
* another trigger is already being processed (single in-flight guard)
* the field's content changed between trigger detection and the debounce
  firing, or between the debounce firing and the Gemini response arriving
* no API key is configured, or it fails to decrypt
* the Gemini request times out, errors, or returns an empty/unparseable
  response
* `ACTION_SET_TEXT` itself fails

---

## 10. Data-flow diagram

```
 Keyboard (Gboard / SwiftKey / any IME)
        │
        ▼
 Android text field in a monitored app
        │
        ▼
 AccessibilityService  (TYPE_VIEW_TEXT_CHANGED only, single active node)
        │
        ▼
 SECURITY GATE  (EventGate → SensitiveInputGuard, before any text read)
        │  fail ──────────────────────────────► stop, nothing read/sent
        ▼ pass
 trigger detection  ("?en" exact suffix, length ≤ 4000)
        │  no match ───────────────────────────► stop, nothing sent
        ▼ match
 extract ONLY the text before the trigger
        │
        ▼
 Gemini API  (HTTPS, generativelanguage.googleapis.com, header-based key)
        │
        ▼
 response
        │  failure/timeout ─────────────────────► original text untouched
        ▼ success
 ACTION_SET_TEXT   (field updated; no clipboard, ever)
```

**Everywhere the user's typed text exists in memory:** the
`AccessibilityNodeInfo` read inside `EventGate`; the `content`/`fullText`
local variables threaded through the debounce → network call → result
handler; the outgoing HTTPS request body inside `GeminiClient`; the
incoming HTTPS response body inside `GeminiClient`. Nowhere else — no
database, no file, no static/companion-object cache, no log line.

**The only place text can leave the device:** the single HTTPS POST in
`GeminiClient.translateBlocking()`, to `generativelanguage.googleapis.com`,
containing only the text before the trigger and the fixed system prompt —
never the package name, device identifiers, model name, prior messages,
other on-screen text, or clipboard contents.

---

## 11. Tests

### Unit tests (`./gradlew test`) — 33 tests, no device required

| File | Scenarios covered |
|---|---|
| `TriggerDetectorTest` | exact trigger match & extraction, no-trigger, malformed/partial trigger, empty content, length limit (at and over 4000 chars) |
| `SensitiveInputGuardTest` | normal field allowed, `isPassword`, all 4 required `inputType` variants, className/hint heuristics, null node, non-editable node, **exception → fail closed** |
| `AppBlocklistTest` | known password managers/authenticators/system surfaces blocked, keyword heuristic, own-package block, ordinary messaging apps NOT blocked |
| `AppSettingsStoreTest` | AI disabled by default, allow-list empty by default, allow/disallow round-trip, persistence |
| `EventGateTest` | end-to-end (minus network) decision chain: allowed+trigger → Ready, no-trigger → NotTriggered, password+trigger → Blocked, **app outside allow-list → Blocked**, master switch off → Blocked, blocklist wins over a mistaken allow-list entry, null node/package → Blocked |
| `GeminiClientTest` | model-id validation, blank-key rejection, invalid-model rejection, response parsing (success/empty/malformed/blank), **timeout and I/O exception classification** |
| `ResultPolicyTest` | Success → may replace text; **every single Failure variant → may NOT replace text** |
| `NetworkAllowlistTest` | official host allowed; look-alike/subdomain/other hosts rejected |

Mapping to the 13 scenarios requested in the spec: #1–#11 are covered
directly by `EventGateTest`, `SensitiveInputGuardTest`, `AppBlocklistTest`,
and `TriggerDetectorTest`. #12 (timeout) and #13 (API error) are covered
by `GeminiClientTest`'s exception-classification tests combined with
`ResultPolicyTest`'s exhaustive proof that no `Failure` variant is ever
allowed to trigger a field write — which is the precise mechanism by
which "the original text stays untouched" is enforced in
`TextGateAccessibilityService`.

**Why network I/O itself isn't re-tested end-to-end against a live
socket:** `GeminiClient` hard-codes its target host and re-validates the
constructed URL against `NetworkAllowlist` before connecting. Weakening
that check to accept a local test server's address would mean testing a
version of the security control that isn't the one that ships. Instead,
the two behaviors that check composes — "is this exception a timeout, a
network error, or something else" and "does this response body parse
into a translation or a failure" — are each tested directly and
deterministically against the real `GeminiClient.classifyException` /
`GeminiClient.parseResponse` functions.

### Instrumented test (`./gradlew connectedAndroidTest`) — requires a device/emulator

`KeystoreCryptoInstrumentedTest` round-trips real ciphertext through the
real `AndroidKeyStore` provider (unavailable under Robolectric or a plain
JVM unit test) and confirms `SecureApiKeyStore` persists and restores a
key correctly, and that clearing it removes it.

### Running the tests

```
./gradlew test               # 33 unit tests, JVM only, ~1-2 minutes
./gradlew connectedAndroidTest   # optional, needs a device/emulator plugged in
```

---

## 12. Build

This sandbox has no Android SDK and no network path to Google's Maven
repository or the Gradle distribution server, so the build below was
**not** executed here — see the accompanying delivery notes for exactly
what *was* independently verified (the Gradle wrapper itself is a real,
unmodified `gradle-wrapper.jar` generated by a genuine local Gradle
8.14.3 install, not hand-written). Build this on your own machine:

```
./gradlew clean test lint assembleDebug
```

Toolchain pinned in this project: **AGP 8.7.3**, **Gradle 8.9**,
**Kotlin 2.0.21**, `compileSdk`/`targetSdk` **35**, `minSdk` **26**
(Android 8.0+). These are a known-consistent, stable combination. If
Android Studio offers to upgrade the Android Gradle Plugin (AGP 9.x
became current at some point in 2026), accepting that upgrade should be
safe, but wasn't something this delivery could verify against directly.

### Building via GitHub Actions instead

`.github/workflows/build.yml` runs `./gradlew clean test lint
assembleDebug` on GitHub's own Ubuntu runners (real Android SDK, real
network access) on every push, on every pull request, and on demand from
the Actions tab. It uploads three artifacts from each run:

* `app-debug-apk` — the built APK plus a `app-debug-sha256.txt` file with
  its SHA-256
* `unit-test-results` — the full JUnit HTML/XML report for all 33 unit
  tests
* `lint-report` — the Android Lint HTML report

A second job, `connectedAndroidTest (manual)`, only runs when you trigger
the workflow manually ("Run workflow" in the Actions tab) — it boots a
real emulator and runs `KeystoreCryptoInstrumentedTest`, the one test that
needs the real `AndroidKeyStore` provider. It's excluded from the
automatic push/PR trigger because emulator boot makes it noticeably
slower than the plain unit-test job.

To use it: push this project to a new GitHub repository (from inside the
unzipped `TextGateAI` folder):

```
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<your-username>/<repo-name>.git
git push -u origin main
```

Then open the **Actions** tab on the repository page — the workflow
starts automatically on that push. Click the run, then each job, to read
the log or download the artifacts.

### Installation

1. Open the project folder in Android Studio (`File → Open`).
2. Let Gradle sync (first sync downloads the pinned Gradle/AGP/Kotlin
   versions — needs internet access once).
3. `Run ▶` on a device or emulator running Android 8.0+, or
   `./gradlew assembleDebug` and install
   `app/build/outputs/apk/debug/app-debug.apk` manually
   (`adb install app-debug.apk`).

### Enabling the app

1. Open **TextGate AI**.
2. Tap **Open Accessibility Settings**, find "TextGate AI" in the list,
   and turn it on. (Nothing is monitored yet — the master switch and the
   allow-list are both still off/empty at this point.)
3. Back in the app, paste your Gemini API key and tap **Save key
   (encrypted)**.
4. Tap **Test API connection** to confirm the key and model work.
5. Turn on **Enable ?en trigger**.
6. In **Allowed apps**, switch on exactly the apps you want this to work
   in (e.g. Telegram, WhatsApp, Signal, Discord — nothing is preselected).

### Getting a Gemini API key

Create one at Google AI Studio (`aistudio.google.com`) → "Get API key."
Paste it into TextGate AI's Settings screen — it is encrypted on-device
immediately (see §6) and never leaves the device except inside the
HTTPS requests you trigger yourself.

---

## 13. Manual security audit checklist

Use this to verify the built APK yourself:

- [ ] `aapt dump permissions app-debug.apk` shows exactly one permission: `android.permission.INTERNET`
- [ ] `aapt dump badging app-debug.apk` shows no `<uses-feature>` for camera/microphone/location
- [ ] With the app freshly installed and the allow-list empty, typing `?en` anywhere produces zero network activity (verify with `adb shell cmd netstats` or a proxy)
- [ ] Add one app to the allow-list; typing `?en` in a *different, non-allow-listed* app produces zero network activity
- [ ] In an allow-listed app, focus a password field (e.g. a login screen) and type `?en` — zero network activity, field untouched
- [ ] In an allow-listed app, a normal text field with `?en` at the end triggers exactly one HTTPS request to `generativelanguage.googleapis.com` (check with a network proxy — traffic should be TLS to that host only)
- [ ] Turn the master switch off; repeat the above — zero network activity
- [ ] `adb backup` (or Settings → "Back up my data") does not include this app's data — confirm via `android:allowBackup="false"` and by inspecting the backup archive
- [ ] `adb logcat` during a full translation cycle contains no request/response body, no API key, no field content, in a `-tag TextGate` or unfiltered search of the app's own log lines
- [ ] Removing the saved API key in Settings, then attempting a translation, produces zero network activity and a clear "no API key" message
- [ ] Killing network mid-request (airplane mode) results in a timeout error and the original field text unchanged
- [ ] `apksigner verify --print-certs app-debug.apk` / equivalent for a signed release build

---

## 14. What this delivery does and does not include

**Included, complete, and ready to open in Android Studio:** every Gradle
file, the manifest, all XML security configs, all Kotlin source, all
layouts/strings, the ProGuard rules, and 33 passing-by-inspection unit
tests plus one instrumented test.

**Not executed in this delivery, and why:** `./gradlew clean test lint
assembleDebug` was not run against this exact project in the environment
that produced it, because that environment has no Android SDK and no
network path to Google's Maven repository. The Gradle wrapper included
here is genuine (generated by a real local Gradle 8.14.3 install), and
every file was written and cross-checked by hand against the actual
Android/Kotlin APIs it uses — but "should compile" is not the same
verification as "did compile." `.github/workflows/build.yml` (see §12) runs
that exact command on GitHub's own infrastructure, with a real SDK and
real network access, the first time this project is pushed to a GitHub
repository — treat its result, or a local build's, as the real
verification, and treat any failure in either as a genuine bug report.
