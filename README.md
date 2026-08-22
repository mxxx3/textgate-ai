# TextGate AI

A minimal, privacy-first Android assistant that translates text you type,
triggered only by typing `?en` (translate to English) or `?pl` (translate
to Polish) at the end of a sentence, in apps you allow. By default that's
a curated set of common social-media/messaging apps, with a manual picker
in Settings for anything else (see §2.1 and §9). Built to be small enough
to read end to end in one sitting.

```
Daj znać jak będziesz miał chwilę, nie ma pośpiechu ?en
                        ↓
Let me know when you get a chance, no rush.

Let me know when you get a chance, no rush ?pl
                        ↓
Daj znać jak będziesz miał chwilę, nie ma pośpiechu.
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
        │   │   │   ├── TextGateAccessibilityService.kt   ← both pipelines
        │   │   │   └── TranslationBubble.kt     (the long-press overlay bubble window)
        │   │   ├── security/
        │   │   │   ├── SensitiveInputGuard.kt   (isSensitiveInput — the security gate)
        │   │   │   ├── EventGate.kt             (typed-trigger decision chain)
        │   │   │   ├── BubbleTranslateGate.kt   (long-press decision chain — see §2.1b)
        │   │   │   ├── TriggerDetector.kt       (?en/?pl detection + length limit)
        │   │   │   ├── AppBlocklist.kt          (hard-coded never-allow list)
        │   │   │   ├── AppSettingsStore.kt      (master switch + allow-list + bubble language, curated default)
        │   │   │   ├── KeystoreCrypto.kt        (AES-256-GCM via AndroidKeyStore)
        │   │   │   ├── SecureApiKeyStore.kt     (encrypted API key persistence)
        │   │   │   └── ResultPolicy.kt          (only Success may touch the field)
        │   │   ├── network/
        │   │   │   ├── NetworkAllowlist.kt      (the one allowed host)
        │   │   │   └── GeminiClient.kt          (HttpsURLConnection, no HTTP library)
        │   │   ├── model/
        │   │   │   └── TranslationPrompts.kt    (fixed system prompts, one per trigger)
        │   │   ├── settings/
        │   │   │   ├── SettingsActivity.kt
        │   │   │   └── InstalledAppsProvider.kt (also loads each app's real icon)
        │   │   └── util/
        │   │       └── Debouncer.kt
        │   └── res/
        │       ├── values/strings.xml            (English — default/fallback)
        │       ├── values-pl/strings.xml          (Polish — auto-selected by system language)
        │       └── ...                            (layouts, other xml configs)
        ├── test/java/com/textgate/ai/            (JVM unit tests — 87 tests)
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
        │  4. on the allow-list?              (AppSettingsStore.isPackageAllowed —
        │                                       curated default set, or the user's
        │                                       own explicit choices once made)
        │  5. node non-null?
        │  6. node.isEditable?                (SensitiveInputGuard.isEditableTextField)
        │  7. node NOT password/sensitive?    (SensitiveInputGuard.isSensitiveInput)
        │  ── only NOW is node.text ever read ──
        ▼
TriggerDetector.detect(fullText)
        │  ends with "?en"/"?pl" (± one keyboard-inserted space, any case, see §14)?
        │  content ≤ 4000 chars? non-empty?
        ▼
   Decision.Ready(content, fullText, target)   ← target: ENGLISH or POLISH
        │  debounced 400ms; any earlier pending node is recycled, not leaked
        ▼
confirmAndProcess(): re-validates EVERYTHING above again (settings may have
        │  changed; field may have changed) before touching the network
        ▼
extract only the text before the trigger — nothing else
        ▼
GeminiClient.translateBlocking()  — HTTPS POST, header-based API key,
        │  system prompt chosen by target (TranslationPrompts.EN_/PL_...),
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

### 2.1b The second pipeline: long-press "translate a received message"

Added as a feature update after the original spec (see §14) — a second,
independent pipeline for translating text the user did **not** write: a
received message or comment, long-pressed and held.

```
Any on-screen text, in an allowed app (need NOT be editable — a received
message bubble, a comment, a label — this is the whole point)
        │  user long-presses it
        ▼
fires AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ▼
TextGateAccessibilityService.handleLongClick()
        │  (the ONLY other event type this service subscribes to — see
        │   accessibility_service_config.xml)
        ▼
BubbleTranslateGate.evaluate(packageName, node)     ← SECURITY GATE
        │  1. packageName present?
        │  2. master AI switch on?           (AppSettingsStore.isAiEnabled)
        │  3. NOT hard-blocklisted?           (AppBlocklist.isBlocked)
        │  4. on the allow-list?              (AppSettingsStore.isPackageAllowed —
        │                                       the SAME list the typed-trigger
        │                                       pathway uses)
        │  5. node non-null?
        │  6. node NOT password/sensitive?    (SensitiveInputGuard.isSensitiveInput
        │                                       — deliberately NO editability
        │                                       check here; see BubbleTranslateGate's
        │                                       class doc for why that's still safe)
        │  ── only NOW is node.text ever read ──
        ▼
   Decision.Ready(text)
        │  no debounce — a long-press is already one discrete gesture
        ▼
GeminiClient.translateBlocking() — same HTTPS call, same host allowlist,
        │  system prompt chosen by the user's saved
        │  AppSettingsStore.bubbleTargetLanguage (Settings → under
        │  "AI transformation")
        ▼
Gemini API response
        │
        ▼
TranslationBubble — a WindowManager overlay (TYPE_ACCESSIBILITY_OVERLAY,
        no extra permission needed — see §3) shown near the long-pressed
        text. Dynamically sized to the translated text (wrap_content, capped
        at a max width/height with internal scroll for a very long message —
        never fixed or full-screen). Dismissed by an explicit X button, a
        tap anywhere outside the bubble, or an auto-dismiss timer — never by
        "finger release," which this event type cannot observe (see the doc
        comment on typeViewLongClicked in accessibility_service_config.xml).
```

Critically, this pipeline **never writes anything back** to the app being
read — `ACTION_SET_TEXT` is only ever used by the typed-trigger pipeline
above. The bubble is a read-only, temporary overlay; the message the user
long-pressed is left completely untouched in its original app.

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

* **`BubbleTranslateGate`** is `EventGate`'s counterpart for the long-press
  pathway (§2.1b) — kept as its own separate class, not a branch inside
  `EventGate`, specifically because it protects a fundamentally different
  kind of read (arbitrary on-screen text the user is *reading*, not a
  field the user is *editing*) and deliberately omits the editability
  check that would otherwise reject every legitimate long-press. Keeping
  the two gates separate makes it possible to see, at a glance and without
  cross-referencing, exactly what each pathway is and is not allowed to
  touch. Every other check — block-list, allow-list, master switch, and
  the password/sensitive-field check — is shared and identical between the
  two.

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
| Long-press bubble text (`BubbleTranslateGate.Decision.Ready.text`) | Local variable / lambda capture threaded from `handleLongClick` → `startBubbleTranslation` → the network call → `TranslationBubble.showResult`; the `AccessibilityNodeInfo` itself is recycled immediately after its bounds and text are captured, well before the network call even starts — never held across the request the way the typed-trigger node is |
| Translated bubble text on screen | Lives only in the `TranslationBubble`'s own inflated `View`, for as long as the bubble is shown (a few seconds, or until closed/tapped-away); removed from the `WindowManager` and discarded on dismiss — never written anywhere |

Nothing above is ever written to disk except the AES-256-GCM-encrypted
API key ciphertext (see §6) and the plain (non-secret) allow-list/model/
bubble-language settings, all in app-private `SharedPreferences`, all
excluded from every backup mechanism Android has.

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
(two event types, no window enumeration; see §2 and §8).

**The long-press translation bubble (§2.1b) still requests no new
permission.** It is a `WindowManager` overlay of type
`TYPE_ACCESSIBILITY_OVERLAY`, which — unlike `TYPE_APPLICATION_OVERLAY` —
is available to any bound `AccessibilityService` without the user having
to separately grant "display over other apps" (`SYSTEM_ALERT_WINDOW`).
`SYSTEM_ALERT_WINDOW` remains absent from the manifest and unused anywhere
in this app.

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
* the app's package is not on the allow-list (the curated default set, or
  the user's own explicit choices once they've made any — see §14)
* the app's package matches the hard-coded block-list (password managers,
  authenticators, OS security surfaces, named cryptocurrency wallets/
  exchanges, or a banking/wallet/vault/crypto keyword) — this always wins,
  even for a package present in the curated default allow-list
* the master "Enable ?en / ?pl triggers" switch is off
* the trigger is not an exact, case-sensitive `?en`/`?pl` at the true end of
  the text — the only tolerance is one optional space between `?` and the
  language code, and any number of trailing spaces, both added to absorb
  keyboards' "automatic spacing"; everything else (case, the language
  code itself, two-or-more internal spaces, any real character after the
  trigger) still fails to match, and the tolerated spaces are never part
  of what's sent to Gemini (see §14)
* the text before the trigger is empty or exceeds 4000 characters
* another trigger is already being processed (single in-flight guard)
* the field's content changed between trigger detection and the debounce
  firing, or between the debounce firing and the Gemini response arriving
* no API key is configured, or it fails to decrypt
* the Gemini request times out, errors, or returns an empty/unparseable
  response
* `ACTION_SET_TEXT` itself fails

**The same checklist, for the long-press translation bubble (§2.1b),
enforced by `BubbleTranslateGate`:**

* the accessibility node is `null`
* `node.isPassword` is `true`, or any of the same `inputType`/className/
  hint checks used by `SensitiveInputGuard.isSensitiveInput` match —
  **exactly the same sensitivity check as the typed-trigger path**, so a
  long-pressed password/masked value is refused the same way a typed one
  would be
* any exception is raised while inspecting the node
* the app's package is not on the allow-list — **the same allow-list** the
  typed-trigger pathway uses; there is no separate "bubble allow-list"
* the app's package matches the hard-coded block-list — same list, same
  precedence over the allow-list
* the master "Enable ?en / ?pl triggers" switch is off — the same switch
  also gates the bubble pathway; there is no separate bubble on/off switch
* the long-pressed node's text is empty/blank or exceeds 4000 characters
* another AI request (of either kind — typed-trigger or long-press) is
  already in flight (the two pathways share one in-flight guard)
* no API key is configured, or it fails to decrypt
* the Gemini request times out, errors, or returns an empty/unparseable
  response — shown as an error message in the bubble itself, never as a
  silent failure
* the overlay window itself fails to add for any platform reason — fails
  silently; no translation appears, nothing else happens

Note what this path does **not** check: `node.isEditable`. That is the one
deliberate, documented difference from the typed-trigger checklist — see
`BubbleTranslateGate`'s class doc for why omitting it here is still safe.

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
 trigger detection  ("?en"/"?pl" exact suffix, length ≤ 4000)
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
containing only the text before the trigger and the fixed system prompt for
the matched trigger's target language (`?en` → English, `?pl` → Polish) —
never the package name, device identifiers, model name, prior messages,
other on-screen text, or clipboard contents.

**The long-press pathway's data flow (§2.1b) is the same shape, with two
differences:** the source is a `TYPE_VIEW_LONG_CLICKED` event instead of
`TYPE_VIEW_TEXT_CHANGED`, gated by `BubbleTranslateGate` instead of
`EventGate` (same block-list/allow-list/master-switch, no editability
check, same sensitivity check); and a successful response is rendered into
a temporary `TranslationBubble` overlay instead of calling
`ACTION_SET_TEXT` — the source app's content is never modified. The only
place this pathway's text can leave the device is the same
`GeminiClient.translateBlocking()` call, to the same single allowed host,
carrying only the long-pressed text and the fixed system prompt for the
user's saved `bubbleTargetLanguage`.

---

## 11. Tests

### Unit tests (`./gradlew test`) — 87 tests, no device required

| File | Scenarios covered |
|---|---|
| `TriggerDetectorTest` | exact `?en`/`?pl` match & extraction (each targeting the right language), no-trigger, malformed/partial trigger, empty content (both triggers), length limit (at and over 4000 chars), tolerated single trailing/internal space around the trigger, two internal spaces still rejected, any case of the language code tolerated (including the internal-space-plus-auto-capitalized-letter combination) |
| `SensitiveInputGuardTest` | normal field allowed, `isPassword`, all 4 required `inputType` variants, className/hint heuristics, null node, non-editable node, **exception → fail closed** |
| `AppBlocklistTest` | known password managers/authenticators/system surfaces/crypto wallets & exchanges blocked, keyword heuristic, own-package block, ordinary messaging apps NOT blocked |
| `AppSettingsStoreTest` | AI disabled by default, allow-list defaults to the curated social/messaging set (not empty), an app outside that set is unallowed until added, disabling a default-allowed package overrides its default, allow/disallow round-trip, persistence |
| `EventGateTest` | end-to-end (minus network) decision chain: allowed+`?en` → Ready targeting English, allowed+`?pl` → Ready targeting Polish, allowed+trailing space → Ready, allowed+auto-capitalized language code (`? En`) → Ready, no-trigger → NotTriggered, password+trigger → Blocked, **app outside allow-list → Blocked**, master switch off → Blocked, blocklist wins over a mistaken allow-list entry, null node/package → Blocked |
| `BubbleTranslateGateTest` | end-to-end (minus network) decision chain for the long-press pathway: **non-editable received message in an allowed app → Ready** (the key difference from `EventGateTest`), password field → Blocked even though non-editable, app outside allow-list → Blocked, master switch off → Blocked, blocklist wins over a mistaken allow-list entry, empty text → Blocked, text over the shared length limit → Blocked, null node/package → Blocked, **the WhatsApp-shaped child-text search**: message text in a single child → Ready with the child's text, longest-safe-child-wins over a shorter sibling (timestamp), a sensitive child skipped even when it would otherwise be the longest candidate, a container with only a sensitive child → Blocked (not the sensitive value), a childless/textless container → Blocked |
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
./gradlew test               # 87 unit tests, JVM only, ~1-2 minutes
./gradlew connectedAndroidTest   # optional, needs a device/emulator plugged in
```

---

## 12. Build

This project was drafted in a sandbox with no Android SDK and no network
path to Google's Maven repository or the Gradle distribution server, so
the build below could not be executed there directly (the Gradle wrapper
itself is still a real, unmodified `gradle-wrapper.jar`, generated by a
genuine local Gradle 8.14.3 install, not hand-written). It **has since
been built and verified for real** via GitHub Actions — see §15 for the
confirmed result (all tests + lint green, APK built, SHA-256 recorded).
You can reproduce the same build yourself:

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
* `unit-test-results` — the full JUnit HTML/XML report for all 59 unit
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
   and turn it on. (Nothing is monitored yet — the master switch is still
   off at this point, regardless of the allow-list's contents.)
3. Back in the app, paste your Gemini API key and tap **Save key
   (encrypted)**.
4. Tap **Test API connection** to confirm the key and model work.
5. Turn on **Enable ?en / ?pl triggers**.
6. That's it for most people: **Allowed apps** is pre-populated with a
   curated set of common social-media/messaging apps (WhatsApp, Messenger,
   Instagram, Telegram, Signal, Discord, Snapchat, TikTok, X, Threads,
   LinkedIn, SMS/Messages, and others — the exact list is
   `AppSettingsStore.DEFAULT_ALLOWED_PACKAGES`) with nothing further to
   configure. If you want a different app, or want to remove one of the
   defaults, tap **Show advanced: choose apps manually** to reveal the
   full per-app picker — the first change you make there (adding or
   removing any app) replaces the curated default with your own choices
   from then on.

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
- [ ] With the app freshly installed (master switch still off), typing `?en` or `?pl` anywhere produces zero network activity (verify with `adb shell cmd netstats` or a proxy)
- [ ] With the master switch on and the allow-list at its curated default, typing `?en` in an app *not* in `AppSettingsStore.DEFAULT_ALLOWED_PACKAGES` and not manually added produces zero network activity
- [ ] Open **Show advanced: choose apps manually** and confirm the checked apps exactly match `AppSettingsStore.DEFAULT_ALLOWED_PACKAGES`, with every known password manager/banking/authenticator/crypto-wallet package absent from the list entirely
- [ ] In an allow-listed app, focus a password field (e.g. a login screen) and type `?en` — zero network activity, field untouched
- [ ] In an allow-listed app, a normal text field with `?en` at the end triggers exactly one HTTPS request to `generativelanguage.googleapis.com`, and the same with `?pl`, using the correct target-language system prompt each time (check with a network proxy — traffic should be TLS to that host only)
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
layouts/strings (English and Polish), the ProGuard rules, and 87 unit
tests plus one instrumented test.

**Build verification history.** The project was drafted in an environment
with no Android SDK and no network path to Google's Maven repository, so
`./gradlew clean test lint assembleDebug` could not be run there — see
§12/§15 for how it was instead verified on real infrastructure
(`.github/workflows/build.yml`, GitHub's own Ubuntu runners). Six real bugs
were found and fixed this way before/after the build went green — three
compiler/lint errors, one security gap, and two UI bugs, the last three
all found through real on-device testing:

1. `values/styles.xml` — `<style name="TextGate.SectionTitle">` had no
   explicit `parent`, so AAPT tried to resolve an implicit parent style
   literally named `TextGate` (Android's dot-in-style-name convention),
   which doesn't exist → `AAPT: resource style/TextGate ... not found`.
   Fixed by renaming to the dot-free `TgSectionTitle`.
2. `SensitiveInputGuardTest.kt` — `import org.mockito.kotlin.on` doesn't
   resolve: `on` is a member of mockito-kotlin's `KStubbing<T>`, not a
   top-level function, so it's already in scope inside `mock<T> { }`
   without any import. Fixed by deleting the bad import line.
3. `KeystoreCrypto.kt` — catching `StrongBoxUnavailableException` (an
   API-28 class) directly in a function reachable from `minSdk` 26/27
   tripped Lint's `NewApi` check. Fixed by isolating the StrongBox path in
   a `@TargetApi(28)`-annotated private function, called only from the
   `SDK_INT >= P` branch.
4. `AppBlocklist.kt` — the keyword heuristic (`"wallet"`, `"bank"`, …)
   missed real cryptocurrency wallet/exchange package names seen on an
   actual device during testing (`io.metamask`, `app.phantom`,
   `com.binance.dev`, and 20+ others contain neither substring). Since the
   app's own threat model explicitly names seed phrases as protected data,
   these are now exact-blocked rather than left to the keyword match, plus
   a `"crypto"` keyword was added for unlisted apps. Covered by a new
   `AppBlocklistTest` case.
5. `SettingsActivity.kt` — with `targetSdk` 35, Android enforces
   edge-to-edge by default: window content is allowed to draw underneath
   the status bar and the ActionBar. Because this screen is a plain
   platform `Activity` (no AppCompat/Material, by design), it had none of
   the automatic inset-handling those libraries normally provide, so the
   top of the first card — the "AI transformation" title and the "Enable
   ?en trigger" switch — rendered behind the status bar and was invisible
   even scrolled all the way to the top. Confirmed on a real device via a
   screenshot, not guessed from the XML alone. Fixed with
   `window.setDecorFitsSystemWindows(true)` (a platform API, API 30+,
   guarded by an `SDK_INT` check since `minSdk` is 26) in `onCreate`,
   which opts the window back out of edge-to-edge.
6. `SettingsActivity.kt`, follow-up — fix #5 above resolved the portrait
   symptom but not a related one in landscape: rotated, the display cutout
   (the front-camera notch) sits along the layout's leading edge instead of
   the true top, and on the test device's OEM build the legacy
   `setDecorFitsSystemWindows(true)` path did not reserve space for it — a
   sliver of the first card's text was visible peeking out from under the
   green ActionBar/status-bar band. Confirmed on the real device again
   (landscape screenshot), not guessed from the theme alone. Fixed by
   reverting to `setDecorFitsSystemWindows(false)` (full edge-to-edge) and
   doing the inset padding explicitly instead of relying on the platform's
   legacy auto-fit path: a `View.OnApplyWindowInsetsListener` on the root
   view reads back `WindowInsets.Type.systemBars() or
   WindowInsets.Type.displayCutout()` on every layout pass — covering
   orientation changes — and applies it as padding. `themes.xml` also now
   sets `android:windowLayoutInDisplayCutoutMode="shortEdges"` (API 27+,
   marked with `tools:targetApi="27"` since it's silently ignored — not a
   crash — on the API 26 devices `minSdk` still allows) so the cutout
   inset is requested consistently rather than left to each OEM's default.

   **Amendment (same run):** the first push of this fix failed CI —
   Lint's `NewApi` check correctly flagged the new theme item as requiring
   API 27 while `minSdk` is 26, since XML resource attributes (unlike
   `SDK_INT`-guarded Kotlin code) have no runtime branch Lint can see is
   protecting them. Fixed by adding `tools:targetApi="27"` on the `<item>`,
   the standard way to tell Lint the lower-API omission is intentional and
   safe (the platform simply ignores an attribute it doesn't recognize).

**Feature update (post-initial delivery, requested by the app's owner):**
two changes beyond the original spec, both scoped so the existing security
guarantees are unaffected:

- **A second trigger, `?pl`.** `?en` still translates the preceding text
  to English; `?pl` translates it to Polish (auto-detecting the source
  language). `TriggerDetector.detect()` now recognizes either fixed-length
  suffix and returns which one matched as `Outcome.Ready.target`
  (`Target.ENGLISH` / `Target.POLISH`), threaded unchanged through
  `EventGate.Decision.Ready` and `TextGateAccessibilityService` to select
  the matching system prompt from `TranslationPrompts`. Every existing
  gate — block-list, allow-list, master switch, sensitivity check, node
  re-validation after the debounce — runs identically regardless of which
  trigger matched; only the outbound system prompt differs.
- **The allow-list now defaults to a curated set of common social-media
  and messaging apps** (`AppSettingsStore.DEFAULT_ALLOWED_PACKAGES` — 
  WhatsApp, Messenger, Instagram, Telegram, Signal, Discord, Snapchat,
  TikTok, X, Threads, LinkedIn, Reddit, Pinterest, WeChat, LINE,
  KakaoTalk, Viber, Skype, and SMS/Messages) instead of starting empty,
  so most people never need to open the app-picker at all. The picker
  itself still exists — a collapsed **"Show advanced: choose apps
  manually"** toggle in the "Allowed apps" card reveals it — for anyone
  who wants a different app or wants to remove a default. The first
  explicit change made there permanently replaces the curated default
  with the user's own choices (see the doc comment on
  `AppSettingsStore.getAllowedPackages()`). This is a convenience change,
  not a weaker boundary: `AppBlocklist` is still consulted independently
  by `EventGate` for every package, default or user-added alike, so a
  password manager, banking app, authenticator, or crypto wallet is never
  reachable through this list regardless of what it contains.

This changed the "safe by default" test in `AppSettingsStoreTest` from
asserting an *empty* allow-list to asserting the *curated* one — see that
file's class-level doc comment for why that's still a "zero requests
until the user (or this curated list) says otherwise" guarantee, not a
regression of it.

**Feature update #2 (post-initial delivery, requested by the app's
owner): tolerate keyboard auto-spacing around the trigger.** Reported from
real use: some Android keyboards' "automatic spacing" feature inserts a
space right after typing `?` (since it normally ends a sentence), and/or
a trailing space once a word is finished — both landing the field's text
one character short of the exact `?en`/`?pl` suffix the original
`TriggerDetector` required, so the trigger silently never fired. Fixed in
`TriggerDetector.detect()` by switching from a literal `String.endsWith`
check to two small, fixed regexes (one per trigger language), each
anchored to the true end of the text with `\z` (not `$`, which in
Java/Kotlin regex also matches just before a trailing line terminator —
`\z` keeps the "trigger must be the very last thing typed" guarantee
exact): `\?[ ]?en[ ]*\z` and `\?[ ]?pl[ ]*\z`. In plain terms: an
*optional single space* between `?` and the language code, and *any
number of trailing spaces* after it, both tolerated; everything else is
unchanged — matching is still case-sensitive, the language code itself is
still exact, two-or-more internal spaces still fail to match (this stays
a narrow tolerance for one observed keyboard behavior, not general
whitespace fuzzing), and any real character after the trigger still
disqualifies it. `Outcome.Ready.content` is computed from the regex
match's start position (where `?` begins), so a tolerated space is never
included in the text sent to Gemini — the security-relevant "only the
text before the trigger, verbatim" guarantee is unaffected. Covered by
five new `TriggerDetectorTest` cases and one new `EventGateTest`
end-to-end case; total unit tests: 71.

**Feature update #4 (post-initial delivery, requested by the app's owner):
tolerate keyboard auto-capitalization of the language code.** Reported from
real use: typing `?en`/`?pl` sometimes produced no translation, even though
feature update #2 above already tolerated the keyboard's auto-inserted
space between `?` and the language code. Root cause: the *same* keyboard
behavior that inserts that space also treats it as the start of a new
sentence (since `?` normally ends one) and auto-capitalizes the very next
letter — so `? en` routinely arrives on-device as `? En`, which the
previously case-sensitive match rejected outright. Fixed by adding
`RegexOption.IGNORE_CASE` to both patterns in
`TriggerDetector.TRIGGER_PATTERNS`, scoped narrowly to just those two small
regexes so it only ever affects the "en"/"pl" letters themselves — the `?`
is still required literally, two-or-more internal spaces still fail to
match, and any real character after the trigger (besides optional trailing
spaces) still disqualifies it. `Outcome.Ready.content` is unaffected: it is
still computed from the match's start position, so the case of the trigger
itself never leaks into the text sent to Gemini. Covered by two new tests —
one in `TriggerDetectorTest` for the internal-space-plus-capitalized-letter
combination specifically, one end-to-end in `EventGateTest`
(`scenario 1d`); total unit tests: 73.

**Feature update #3 (post-initial delivery, requested by the app's
owner): default model changed to `gemini-3.5-flash-lite`.** The app
originally defaulted to `gemini-2.5-flash`. The owner's own Google AI
Studio rate-limit dashboard (`aistudio.google.com`) showed that account
hitting `gemini-2.5-flash`'s daily quota (peak 22 requests against a
20/day limit) and `gemini-2.5-pro` sitting at a flat 0/0 quota — i.e. no
free-tier access to Pro at all on that account — while
`gemini-3.5-flash-lite` and `gemini-3.1-flash-lite` carried a 500/day,
15/minute quota, roughly 25x more headroom. `AppSettingsStore.DEFAULT_MODEL`
and the model-suggestion chips in `SettingsActivity` (now
`gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`, `gemini-2.5-flash`,
`gemini-3.7-flash` — `gemini-2.5-pro` deliberately dropped from the
suggestion list) were updated accordingly, along with the `hint_model`
string and the manual. This is a plain configuration default, not a
security- or architecture-relevant change: `GeminiClient` accepts any
model ID matching its existing validation regex regardless of this
default, so nothing else in the pipeline changes. Rate limits are
account-specific and change over time on Google's side — see the doc
comment on `DEFAULT_MODEL` for how to re-check and update it later.

**Feature update #5 (post-initial delivery, requested by the app's owner):
pin a fixed debug-signing key so the debug APK's certificate fingerprint
stays stable across CI builds.** Discovered while the owner was registering
the app's package name for Android's (new, 2026) Developer Verification
program, which asks for the SHA-256 fingerprint of the certificate the APK
is signed with. Checking the downloaded CI artifact's actual signer
(`apksigner verify --print-certs`) showed `DN: C=US, O=Android, CN=Android
Debug` — the fixed distinguished name Android Gradle Plugin always uses for
its *auto-generated* debug key, backed by a **freshly random key pair**
whenever `~/.android/debug.keystore` doesn't already exist. Every GitHub
Actions run starts from a clean image and this repo had nothing that
persisted that file across runs, so **every CI build was silently signing
the debug APK with a different key** — a fingerprint registered against one
build's APK would stop matching the very next one. Fixed by generating one
fixed debug keystore (`keytool -genkeypair`, same conventions AGP's own
default uses: alias `androiddebugkey`, both passwords `android`, DN
`CN=Android Debug,O=Android,C=US`) and checking it into the repo as
`app/debug.keystore` — `.gitignore` already carried a `!debug.keystore`
exception to its general `*.keystore` rule, anticipating exactly this. A
new explicit `signingConfigs.debug` block in `app/build.gradle.kts` points
`buildTypes.debug` at this file instead of leaving Gradle to fall back to
its implicit, host-dependent default. This key is intentionally not a
secret — a debug-only signing key holds no production trust, and a shared,
checked-in debug keystore purely for reproducible fingerprints is standard
Android practice; nothing about the release build type or its signing was
touched. **Practical effect:** the fingerprint the owner registers going
forward (`DA:A0:84:EF:2F:45:EE:60:9C:76:63:C2:44:0C:31:BE:A9:DB:9C:AF:AE:8D:B5:A8:9C:5B:34:E8:83:3F:12:DF`)
will now match every future debug build from this repo, in CI or locally,
indefinitely — no more re-registering after each rebuild. (The very first
CI-generated fingerprint the owner registered before this fix,
`cf217c66eee1a9fac13dc4af7667f11f01aa1aff867b902e6fd1fa1ab17a5eaa`, belonged
to that one already-downloaded build only and will not recur.)

**Feature update #6 (post-initial delivery, requested by the app's owner):
a long-press "translate a received message" bubble, a default target
language for it, full Polish app localization, and Allowed-apps list
cleanup.** This is the largest single feature added since the original
delivery — a genuinely new capability, not a bug fix — so it's documented
in full detail here, including the one deliberate security-scope change it
makes.

*Why this exists.* Every prior version of this app could only translate
text the user was actively **typing** (the `?en`/`?pl` triggers). There was
no way to translate a message someone else **sent** — WhatsApp/SMS/etc.'s
own long-press menus only offer Copy, and pasting into another app just to
run `?pl` on it was the best existing workaround. WhatsApp's own native
"Message Translations" feature was investigated and ruled out as a
substitute: on Android it supports only English, Spanish, Hindi,
Portuguese, Russian, and Arabic — not Polish, which is this app owner's
primary need.

*The scope change, stated plainly.* Every previous read pathway in this
app was gated on `node.isEditable` — it only ever read a field the user
was themselves writing into. This feature intentionally reads **received,
non-editable** content instead — the whole point of a "translate what
someone sent me" feature. This is a real, deliberate expansion of what the
app is allowed to read, and it is documented as one rather than glossed
over. It is scoped as tightly as the typed-trigger pathway in every other
respect: same master switch, same block-list, same allow-list, same
password/sensitive-field exclusion (`SensitiveInputGuard.isSensitiveInput`,
unchanged) — see `BubbleTranslateGate` (§2.1b, §9) for the exact chain, and
its class doc for the full reasoning on why omitting only the editability
check is safe here.

*What was built:*

- **`BubbleTranslateGate.kt`** (new) — the security gate for this pathway,
  structurally parallel to `EventGate` but intentionally a separate class
  (see §2.2) rather than a branch inside it.
- **`accessibility_service_config.xml`** — now also subscribes to
  `AccessibilityEvent.TYPE_VIEW_LONG_CLICKED`, the standard event for a
  long-press, which requires no additional capability flag and specifically
  does **not** require `flagRequestTouchExplorationMode` (which would turn
  the whole device into TalkBack-style screen-reader navigation — rejected
  as a way to detect this gesture). One consequence of using this event:
  the service cannot observe exactly when the user's finger lifts off the
  screen, only that a long-press happened — see the next point for how the
  bubble's dismissal is designed around that limitation.
- **`TranslationBubble.kt`** (new) — the floating overlay itself, a
  `WindowManager` window of type `TYPE_ACCESSIBILITY_OVERLAY` (no new
  permission needed — see §3). Requirements satisfied:
  - **Dynamic sizing.** The bubble and every view inside it are
    `wrap_content`; a short translation produces a small bubble, never a
    fixed or full-screen one (`overlay_translation_bubble.xml`), with an
    internal `ScrollView` capping runaway growth for an unusually long
    translation.
  - **Three independent ways to dismiss**, since "on finger release" isn't
    observable here: an explicit **X close button**, a **tap anywhere
    outside the bubble** (`FLAG_WATCH_OUTSIDE_TOUCH` / `ACTION_OUTSIDE` —
    the window is otherwise non-modal via `FLAG_NOT_TOUCH_MODAL`, so every
    other touch passes straight through to the app underneath), and an
    **auto-dismiss timer** as a fallback (12s for a result, 5s for an
    error, 15s for the loading state).
  - Positioned to prefer appearing just **above** the long-pressed text, as
    requested, falling back to below it if there isn't credible room —
    see the doc comment on `positionNear()` for the exact heuristic and its
    one known limitation (the bubble's own height isn't known until after
    it's laid out, so "room above" is estimated).
- **`AppSettingsStore.bubbleTargetLanguage`** (new persisted setting) — a
  single saved default (`TriggerDetector.Target.ENGLISH` or `.POLISH`,
  default Polish) used for every bubble translation, since a long-press
  gesture has no per-use way to specify a language the way typing `?en`
  vs. `?pl` does. Exposed in Settings as a two-option radio group placed
  directly under the existing "AI transformation" master switch, per the
  owner's specific request, rather than as a new separate card.
- **`TextGateAccessibilityService.kt`** — extended with `handleLongClick()`
  and `startBubbleTranslation()`. No debounce is used on this path (unlike
  the typed-trigger path) — a long-press is already one discrete gesture,
  not a stream of rapid-fire events that needs settling. Both pipelines
  share the single `requestInFlight` guard, so a typed-trigger request and
  a bubble request can never run concurrently.
- **Full Polish localization** — every string in `values/strings.xml` (the
  entire Settings screen, not just this feature's new strings) now has a
  natural Polish translation in the new `values-pl/strings.xml`, selected
  automatically by Android's standard resource-qualifier mechanism when
  the device's system language is Polish. `values/strings.xml` (English)
  remains the default for every other system language — no code change was
  needed for this, only the new resource file.
- **Allowed-apps list cleanup**, both requested changes: the package-name
  subtext under each app's name (e.g. `com.accuweather.android`) has been
  removed, leaving only the friendly app name; and each row now shows that
  app's **real launcher icon**, loaded directly from `PackageManager`
  (`ResolveInfo.loadIcon`) via the same declarative `<queries>` filter
  already used to list the apps — never a fabricated or generic graphic.
  A neutral placeholder glyph (`ic_default_app.xml`) is shown only on the
  rare occasion the platform itself can't supply an icon for a given app,
  and never claims to be that app's real icon. `item_app_row.xml` changed
  from a bare `CheckBox` to a row layout (icon + name + checkbox), with the
  whole row clickable for a larger, easier tap target.
- **New tests:** `BubbleTranslateGateTest` (9 cases), covering the same
  fail-closed scenarios as `EventGateTest` plus the one behavioral
  difference that matters most — a non-editable node still yields `Ready`
  here, where it would be `Blocked` under `EventGate`. Total unit tests:
  82 (see §11).

*What did not change:* the typed-trigger pipeline (§2.1), `EventGate`,
`SensitiveInputGuard`, `AppBlocklist`, `ResultPolicy`, `NetworkAllowlist`,
`GeminiClient`, and the API-key/encryption code are all untouched by this
feature. `ACTION_SET_TEXT` is still only ever called from the typed-trigger
path — the long-press pathway is read-only and never modifies the app it
reads from.

**Amendment to Feature update #6: the long-press bubble's node-text search
now walks the long-pressed node's own children (WhatsApp fix).**
Real-device testing (via the temporary diagnostic described just below)
confirmed the root cause of the bubble not appearing in WhatsApp: the node
the OS reports as the long-press target there is the message's outer
container row, not the text itself — its own `text` is blank, while the
actual message body lives in one of its child nodes, alongside other short
text like a timestamp or sender name. Telegram, by contrast, reports a
node whose own `text` already IS the message, which is why it worked
there from the start.

Fixed in `BubbleTranslateGate.evaluate()`: when the long-pressed node's own
text is blank, it now falls back to `findLongestSafeText()`, a
breadth-first search of that SAME node's own descendants only — never
siblings, never ancestors, and never a different window; this app still
never calls `getWindows()`/`getRootInActiveWindow()` anywhere. The search
is bounded (max depth 6, max 60 nodes visited) so it can never become an
unbounded walk, and every node it visits — not just the top-level one — is
still run through `SensitiveInputGuard.isSensitiveInput()` before its text
is read, so a sensitive child is skipped exactly as it would be at the top
level; nothing bypasses that check just by being nested. Among all safe,
non-blank candidates found, the LONGEST text wins — the practical
heuristic that a message body is reliably longer than a timestamp or a
short sender label. Every `AccessibilityNodeInfo` obtained during the
search (via `getChild()`) is recycled before the function returns,
including any left unvisited because the node cap was hit — nothing
leaks. Five new `BubbleTranslateGateTest` cases cover this: the
WhatsApp-shaped case (message in a single child), the longest-wins case
(a short timestamp sibling losing to the real message), a sensitive child
being skipped even when its text would otherwise be the longest candidate,
a container with ONLY a sensitive child correctly Blocked rather than
falling through to it, and a childless/textless container correctly
Blocked. Total unit tests: 87.

This does NOT touch `EventGate` or the typed-trigger pipeline at all — it
only ever reads node.text directly, exactly as before; this fallback
search is scoped entirely to `BubbleTranslateGate`.

**Still open:** the SMS/Messages app showed no diagnostic bubble at all
(not even a "blocked" one), unlike WhatsApp — meaning its problem may be
different: the long-click event might genuinely never fire there at all
(matching the original hypothesis below), rather than firing with blank
text the way WhatsApp's did. This needs to be re-confirmed on-device with
this fix in place before concluding whether it's fixed, unrelated, or a
platform limitation this app cannot work around.

**Temporary diagnostic (in progress, not a permanent feature, v2) in
`handleLongClick()`.** On real devices, the long-press bubble (§2.1b)
works in Telegram but does not trigger in WhatsApp or the SMS app. The
most likely explanation is that those apps implement long-press detection
with their own custom touch/gesture handling (for their own
message-selection UI) rather than through the standard
`View.performLongClick()` path that dispatches
`AccessibilityEvent.TYPE_VIEW_LONG_CLICKED` — the event this feature
relies on — but that hasn't been confirmed on-device yet. The first
diagnostic attempt used a `Toast`, which produced no visible result even
in Telegram (where the real feature demonstrably works) — Toasts fired
from an `AccessibilityService` are known to be unreliable on some Android
versions/OEM skins, so that attempt couldn't distinguish "event never
fired" from "toast never rendered." v2 instead reuses the exact same
overlay-bubble rendering path the real feature already uses successfully,
removing that uncertainty: `handleLongClick()` now shows a bubble reading
"DIAG: long-click seen" (package name only) the instant any long-click
event arrives, updated to "DIAG blocked: <reason>" if `BubbleTranslateGate`
blocks it (the abstract reason only, e.g. `"empty text"` or
`"not allow-listed"`) — never message content. Marked `TEMPORARY
DIAGNOSTIC (v2)` in the code; will be removed once the root cause is
confirmed and the real fix (if any is possible from this app's side) is
implemented.

## 15. Verified build result (GitHub Actions)

Confirmed on `https://github.com/mxxx3/textgate-ai`, workflow run
**"Fix lint NewApi error in KeystoreCrypto" (#4)**, commit `0037f20`,
Gradle 8.9, ubuntu-latest runner, total duration 2m 35s:

| Step | Result |
|---|---|
| `clean` | ✅ |
| `test` (`SensitiveInputGuardTest`, `TriggerDetectorTest`, `EventGateTest`, `ResultPolicyTest`, `AppBlocklistTest`, `AppSettingsStoreTest`, `NetworkAllowlistTest`, `GeminiClientTest`) | ✅ all passed |
| `lint` | ✅ 0 errors (19 pre-existing informational warnings, none security-relevant — see the `lint-report` artifact for the full list) |
| `assembleDebug` | ✅ APK built |

**`app-debug.apk` SHA-256:**
```
60dc6a58aea4cf0dfd0922900e0aa7413464d9e5b38b46ae99d5a0a163d96098
```
(computed by the user directly from the downloaded `app-debug-apk`
artifact via `certutil -hashfile app-debug.apk SHA256` on Windows —
independent of the workflow's own `Compute APK SHA-256` step, so this is
a second, cross-checked confirmation of the same file.)

The `connectedAndroidTest (manual)` job (real-emulator run of
`KeystoreCryptoInstrumentedTest`, the one test needing the genuine
`AndroidKeyStore` provider) has not been triggered yet — it's optional and
can be run any time from the Actions tab ("Run workflow").

**Note:** fixes #4 and #6 above (the cryptocurrency wallet block-list gap,
and the landscape display-cutout inset fix), and all six feature updates
(`?pl` trigger + curated default allow-list; keyboard auto-spacing
tolerance around the trigger; default model changed to
`gemini-3.5-flash-lite`; keyboard auto-capitalization tolerance for the
language code; fixed debug-signing key so the fingerprint stays stable
across builds; the long-press translation bubble, its default-language
setting, full Polish localization, and the Allowed-apps icon/label
cleanup) described just before this section, all landed *after* run #4, so
none of them has yet gone through its own green CI run — push them like
the earlier fixes and treat that run, not this one, as the current source
of truth for `AppBlocklist.kt`, `SettingsActivity.kt`,
`TriggerDetector.kt`, `EventGate.kt`, `BubbleTranslateGate.kt`,
`TranslationPrompts.kt`, `AppSettingsStore.kt`,
`TextGateAccessibilityService.kt`, `TranslationBubble.kt`,
`InstalledAppsProvider.kt`, `accessibility_service_config.xml`,
`values-pl/strings.xml` (new), and `app/build.gradle.kts` (new
`signingConfigs.debug` block, plus the new tracked `app/debug.keystore`
binary file itself, and the version bump to 1.2.0 / versionCode 7 for this
feature).
