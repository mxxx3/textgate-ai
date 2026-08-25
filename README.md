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
fires AccessibilityEvent.TYPE_VIEW_LONG_CLICKED (the standard long-press
        signal — WhatsApp, Telegram; the ONLY other event type this
        service subscribes to besides typeViewTextChanged — see
        accessibility_service_config.xml). A second event type,
        TYPE_VIEW_SELECTED, was tried as a fallback from v1.2.4 through
        v1.2.9 and permanently removed in v1.2.10 after it was confirmed
        to cause real, unwanted translations on ordinary taps in other
        apps without ever once helping the app it was added for — see the
        "Known, accepted limitation" note below and README.md §14
        "Seventh amendment" for the full story.
        ▼
TextGateAccessibilityService.handleLongClick()
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

**Known, accepted limitation (as of 1.2.10): Google Messages (SMS) and
X (Twitter) do not support the long-press bubble.** Real-device testing
showed the bubble works in Telegram, and (after a fix — see §14
"Amendment to Feature update #6") in WhatsApp, but
`TYPE_VIEW_LONG_CLICKED` — the only long-press signal this service still
subscribes to as of 1.2.10 — is never dispatched for a long-press in
Google Messages or X. A second signal, `TYPE_VIEW_SELECTED`, was tried as
a fallback from 1.2.4 through 1.2.9, never once worked for this purpose,
and was permanently removed in 1.2.10 after being confirmed to cause
unwanted real translations in an unrelated app (TikTok) — see §14
"Seventh amendment". A third signal, `TYPE_WINDOW_CONTENT_CHANGED`, was
tried twice as a diagnostic (1.2.5, then again more narrowly in
1.2.7/1.2.8 after two related claims were confirmed directly against the
AndroidX Compose source — see §14 "Fourth" and "Fifth amendment") and
both times confirmed to fire, but never conclusively shown to carry
anything a real feature could use — the second attempt was closed by the
app's owner (§14 "Sixth amendment") before it reached a verdict either
way. The most likely explanation for why `TYPE_VIEW_LONG_CLICKED` never
fires at all, backed by Android's own documentation and researched in
full in §14 ("Second amendment to Feature update #6" and after): both
apps' relevant UI is
very likely built with Jetpack Compose, whose `combinedClickable`
long-press handling never goes through the classic `View.performLongClick()`
path that signal depends on. Two further alternatives —
`Intent.ACTION_PROCESS_TEXT` and a clipboard-reading floating button —
were researched and either ruled out or left unbuilt (see §14 for why).
Consistent with the reasoning throughout this section, raw touch/motion
tracking (`flagRequestTouchExplorationMode`) and screenshot-based OCR
remain rejected: raw touch tracking would turn the whole device into
TalkBack-style navigation, and OCR would require a new third-party
dependency (breaking this project's zero-production-dependency principle
— see §4), read the entire screen instead of one scoped node, and cannot
reliably exclude password/sensitive on-screen content — breaking this
app's core security guarantee. This is a deliberate, evidence-based
stopping point, not an oversight — the typed `?en`/`?pl` trigger pathway
(§2.1) is completely unaffected and works identically in every app,
including Google Messages and X.

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
scope is further restricted by `accessibility_service_config.xml` (two
event types as of 1.2.10, no window enumeration; see §2 and §8).

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
* As of v1.3.0, Settings also has a **"Get a free API key"** button right
  above the key field, which opens Google AI Studio's API-keys page
  (`aistudio.google.com/apikey`) directly in the browser, plus a
  collapsible step-by-step guide for a first-time user — see "Getting a
  Gemini API key" in §12.

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

As of v1.3.0, Settings has a **"Get a free API key"** button that opens
`aistudio.google.com/apikey` directly in the browser, plus a collapsible
**"How do I do this? (step-by-step guide)"** section with plain-language
instructions for a first-time user — no need to know in advance which of
Google's developer sites is the right one. Paste the resulting key into
TextGate AI's Settings screen — it is encrypted on-device immediately
(see §6) and never leaves the device except inside the HTTPS requests you
trigger yourself.

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
layouts and strings (as of v1.4.0: 40 languages — see §14 "Multi-language
rebuild"), the ProGuard rules, and 87 unit tests plus one instrumented
test.

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

**Second amendment to Feature update #6: `TYPE_VIEW_SELECTED` added as an
experimental fallback for SMS (v1.2.4, unconfirmed as of this writing).**
Confirmed the diagnostic never appearing for the stock/Google Messages SMS
app (the phone this is being tested on identified itself as a "Pixel"-class
stock-Android device, i.e. Google's own Messages app) meant
`handleLongClick()` was never even being invoked there — the OS was not
dispatching `TYPE_VIEW_LONG_CLICKED` for that app's message list at all, a
different and deeper problem than WhatsApp's blank-text issue above.

Before reaching for anything invasive, OCR-based screenshot text
extraction was considered and explicitly rejected, for four reasons: (1)
it would not, by itself, solve "no event fires at all" — it would still
need raw touch/motion tracking on top of it to know *when* to run; (2) it
would require a new third-party dependency (e.g. ML Kit), breaking this
project's zero-production-dependency principle (§4); (3) it would have to
read the entire screen rather than one scoped accessibility node; and (4),
most importantly, it cannot reliably exclude password/sensitive on-screen
content the way `SensitiveInputGuard` does today, which would break this
app's core security guarantee. Raw touch/motion-event tracking via
`flagRequestTouchExplorationMode` was rejected earlier in this project for
a related reason: it would turn the whole device into TalkBack-style
screen-reader navigation just to detect one gesture.

The fallback actually implemented instead: subscribing to the standard,
purpose-built `AccessibilityEvent.TYPE_VIEW_SELECTED` event alongside
`TYPE_VIEW_LONG_CLICKED` (see `accessibility_service_config.xml`), on the
hypothesis that Google Messages — like other apps with a custom
long-press-to-multi-select UI — marks the long-pressed row `isSelected`
via its own touch handling without ever calling
`View.performLongClick()`, but that entering that selection state is still
expected to be announced to accessibility services in the standard way.
`TextGateAccessibilityService.handleEvent()` now routes both event types
to the same `handleLongClick()`, which does not need to know or care which
of the two fired, since `BubbleTranslateGate`'s gating and text-resolution
logic is identical either way. This is still a standard accessibility
signal, not raw touch tracking, and still requires no additional
capability flag or permission.

**This is a hypothesis, not a confirmed fix** — exactly like the WhatsApp
diagnostic-first approach above, it needs the same evidence-based
verification: deploy, long-press a message in the Google Messages SMS app
with the temporary diagnostic (below) still active, and see whether "DIAG:
long-click seen" or "DIAG blocked: ..." now appears at all. If it does not,
the honest conclusion will likely be that SMS support is a platform
limitation this app cannot address without the invasive approaches already
rejected above, and that will be documented here as an accepted limitation
rather than pursued further.

**Result (1.2.4, confirmed on-device): the hypothesis was wrong.**
`TYPE_VIEW_SELECTED` does not fire for Google Messages either — neither
"DIAG: long-click seen" nor "DIAG blocked: ..." appeared. This ruled out
"the row just isn't marked `isSelected` the standard way" as the
explanation, and prompted the deeper architectural research below.

**Third amendment to Feature update #6: researching WHY, not just guessing
again (v1.2.5).** Rather than trying a third event type blind, this round
started from Android's own documentation. Two searches were done: (1)
whether Compose apps' long-press/selection semantics reach a classic
`AccessibilityService.onAccessibilityEvent()` callback the way View-based
apps' do, and (2) whether Google Messages' message list is known to be
built with Jetpack Compose.

Android's official Compose accessibility documentation
([developer.android.com/develop/ui/compose/accessibility/api-defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults))
shows that `Modifier.combinedClickable(onLongClick = ...)` exposes the
long-click as an **action** on the node (what TalkBack announces as
"Double tap and hold to <label>"), not as a legacy `AccessibilityEvent`.
That matters because of how Compose actually handles a real, physical
long-press: `combinedClickable`'s pointer-input handling runs entirely
inside Compose's own gesture-detection system and invokes the
`onLongClick` lambda directly — it never calls `View.performLongClick()`,
which is the one and only code path that dispatches
`TYPE_VIEW_LONG_CLICKED`. TalkBack instead triggers the SAME long-click
action by calling `AccessibilityNodeInfo.performAction(ACTION_LONG_CLICK)`
on the node itself — a service-*initiated* action, fundamentally different
from observing a user's real gesture, and not something this app does or
should do (this app only ever reads events, never performs actions on
other apps' nodes — see the fail-closed checklist in §9).

This is a plausible, documented explanation for BOTH failed attempts at
once: if Google Messages' message list is Compose-based (a redesign Google
has been rolling out across the app), a real long-press there would be
architecturally invisible to `TYPE_VIEW_LONG_CLICKED` — and if the
resulting "row selected" state is represented only as a Compose semantics
property rather than a legacy `View.isSelected` flip, it would be
architecturally invisible to `TYPE_VIEW_SELECTED` too, exactly matching
what was observed.

**What might still work, and the new temporary diagnostic (v3):** if
Compose's semantics for the *resulting* UI change (a toolbar label
changing to "1 selected", a checkmark appearing) are wired up correctly on
Google's side, Compose's accessibility bridge should still emit
`AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` for that change, even
though it emits nothing for the long-press gesture itself. This is a
standard, purpose-built accessibility event — not raw touch tracking — so
it does not conflict with the reasoning that ruled out
`flagRequestTouchExplorationMode` and OCR earlier in this investigation.
`accessibility_service_config.xml` now also subscribes to
`typeWindowContentChanged`, routed to the new
`handleContentChangedDiagnostic()` in
`TextGateAccessibilityService.kt`. This handler is diagnostic-only: it
never reads `node.text`, never starts a translation, and shows only
abstract, non-sensitive data (the app's package name, the source node's
class name — useful for confirming whether it looks like a Compose node —
and the raw `contentChangeTypes` bitmask). Because this event type fires
very frequently during ordinary use (scrolling, incoming messages, cursor
blink), individual events are never shown directly; they're counted and
batched behind a dedicated 700&nbsp;ms debounce (`diagnosticDebouncer`,
deliberately separate from the typed-trigger pipeline's own debouncer so
the two can never interfere with each other), and only the most recent
event's details are shown once the stream goes quiet, as "DIAG
content-changed x&lt;count&gt;\npackage=...\nclass=...\ntypes=...".

**Result (1.2.5, confirmed on-device): the signal fires, but is unusably
generic.** Long-pressing a message in Google Messages did produce a "DIAG
content-changed" bubble — but so did nearly every other tap, scroll, and
interaction anywhere in the app, with `contentChangeTypes` reported as `1`
(`CONTENT_CHANGE_TYPE_SUBTREE`, the most generic possible value — "some
subtree of the screen changed," which Android sends for almost any UI
update) and the source node's class name reported as the generic
`android.view.View`, not anything Compose-identifiable. The same test was
then run against X (Twitter) — chosen because Twitter's own engineering
team has publicly described going "all in on Jetpack Compose"
([source](https://android-developers.googleblog.com/2022/04/twitter-going-all-in-on-jetpack-compose.html)) —
and produced the exact same pattern: content-changed fires constantly,
long-click and selected never do. This is consistent with, though not
absolute proof of, the Compose-architecture explanation above, and — more
practically — confirms this is not an SMS-specific quirk but a pattern
likely to recur in any app whose UI has migrated to Compose.

Two further alternatives were researched and ruled out before concluding
the investigation:

* **`Intent.ACTION_PROCESS_TEXT`** — the standard Android mechanism that
  makes an app appear as a "Translate"-style entry in the system text-
  selection toolbar (Cut/Copy/Paste/...) wherever text can be selected by
  dragging. This is simple, safe, and requires no accessibility-service
  involvement at all for the apps where it applies. It was ruled out for
  this specific problem because Google Messages does not support
  fine-grained text selection inside a received message at all — long-
  pressing only ever enters its own whole-message "select/Copy/Delete/
  Star" mode (confirmed on-device), never the standard Android text-
  selection handles `ACTION_PROCESS_TEXT` depends on. Separately,
  Compose's own `TextToolbar` API exposes only a fixed set of actions
  (copy/paste/cut/select-all) with no documented support for third-party
  `ACTION_PROCESS_TEXT` entries, so even apps that DO support Compose text
  selection may not surface this option either. This remains a
  potentially valuable, independent, lower-risk feature for a future
  version — it would work everywhere classic Android text selection
  exists (browsers, most `EditText`/`TextView` content, likely Telegram
  and WhatsApp) — but it does not solve the Messages/X problem and was
  not built in this investigation.
* **A persistent floating "translate what I copied" button** — since
  Google Messages' own "Copy" action (visible in its whole-message
  selection toolbar) does work, one remaining idea was a small always-
  visible overlay button, shown only in allow-listed foreground apps, that
  the user taps after using the app's own Copy action; tapping it would
  launch a brief, real (focused) Activity to read the clipboard, since
  Android 10+ restricts `ClipboardManager` reads to the default IME or the
  app that currently holds focus
  ([source](https://developer.android.com/about/versions/10/privacy/changes)) —
  something neither the accessibility service nor its overlay bubble can
  claim to be. This was judged workable in principle but was not pursued:
  it requires a persistent on-screen button (a real, ongoing UX cost) for
  an uncertain payoff, and the app's own owner chose to stop the
  investigation before it was built.

**Conclusion as of 1.2.6: SMS (Google Messages) and X (Twitter) were
documented as an accepted limitation of the long-press bubble feature
(§2.1b), not a bug being actively chased.** Three independent, standard,
non-invasive accessibility signals were tried (`TYPE_VIEW_LONG_CLICKED`,
`TYPE_VIEW_SELECTED`, `TYPE_WINDOW_CONTENT_CHANGED`); raw touch/motion
tracking and OCR were ruled out earlier for solid security/architecture
reasons (see above); `ACTION_PROCESS_TEXT` and a clipboard-reading
floating button were researched and either ruled out or left as
un-pursued future options. All temporary diagnostic code from this
investigation (`v2`'s "DIAG: long-click seen"/"DIAG blocked" bubbles and
`v3`'s `TYPE_WINDOW_CONTENT_CHANGED` handler) was removed as of 1.2.6 —
`accessibility_service_config.xml` went back to exactly the three
permanent event types, and `TextGateAccessibilityService.kt` no longer
showed anything on screen beyond the real feature's own loading/result/
error bubble. This was the closing state of the investigation until it
was reopened in 1.2.7 — see below.

**Fourth amendment to Feature update #6 (v1.2.7): reopened after new,
partially source-verified information, with a narrower diagnostic (v4).**
The app's owner asked ChatGPT to look for alternative approaches to this
exact problem and shared its response back. That response made several
specific technical claims about AndroidX Compose's accessibility source
code. Rather than act on an AI-generated technical claim about internal
framework behavior at face value, each load-bearing claim was checked
directly against the real source
([`AndroidComposeViewAccessibilityDelegateCompat.android.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt))
before any code changed. Results, stated plainly:

* **Confirmed in source:** a selectable element whose Compose `Role` is
  NOT `Role.Tab` — e.g. a selected chat message row — has its selection
  state mapped to `AccessibilityNodeInfo.isChecked`, not `isSelected`:
  `if (role == Role.Tab) { info.isSelected = it } else { info.isChecked =
  it }`. This means the 1.2.4 `TYPE_VIEW_SELECTED` attempt (see the
  "Second amendment" above) was checking a property this kind of element
  would never populate in the first place, independent of whether any
  event fires for it at all.
* **Confirmed in source:** Compose addresses a specific virtual semantics
  node via `event.setSource(view, virtualViewId)`. This means
  `event.source` can legitimately reference one exact, specific message
  row even while `event.className` still reports the generic
  `"android.view.View"` seen in the 1.2.5 diagnostic output. In other
  words, the 1.2.5 diagnostic's "generic-looking" output did not actually
  prove the source node itself carries nothing useful — that diagnostic
  never inspected the node's own properties (`isChecked`, whether `text`
  is present, `isLongClickable`, etc.), only its class name and the bare
  fact that some event fired.
* **NOT verified (fetched source was truncated before the relevant code):**
  whether Compose's long-click handling (`combinedClickable(onLongClick =
  ...)`) really never dispatches `TYPE_VIEW_LONG_CLICKED` even when
  `ACTION_LONG_CLICK` is invoked. This claim is still treated as
  unconfirmed from source — though it remains consistent with every
  on-device test result in this investigation, where that event type has
  never once been observed to fire in Google Messages or X.
* **NOT verified (not visible in the fetched excerpt):** which exact
  `AccessibilityEvent` type is dispatched when a non-Tab node's
  `isChecked` state changes — the ChatGPT response assumed
  `TYPE_WINDOW_CONTENT_CHANGED`, which is plausible (Compose's
  accessibility bridge does route most semantics changes through that
  event type) but not something the fetched source excerpt actually
  showed.

The clipboard-reading floating-button fallback the same ChatGPT response
described in full implementation detail (a `TYPE_ACCESSIBILITY_OVERLAY`
that briefly drops `FLAG_NOT_FOCUSABLE` on a button tap to gain
`isUidFocused` status and legitimately read a freshly-copied clipboard
item, with staleness and sensitivity checks) was **not** re-evaluated
against source and was **not** built — it remains exactly what it was in
the "Third amendment" above: judged workable in principle, not pursued,
now for the same reason plus the fact that it's a bigger, more invasive
feature (a new persistent on-screen button, active window-focus
manipulation) that only makes sense to build if the smaller diagnostic
below turns out to be a dead end too.

Given the two confirmed claims materially change what the 1.2.5 result
actually proved (an unhelpful diagnostic, not proof the node is useless),
a new temporary diagnostic was built rather than immediately writing a
production feature on top of unverified assumptions. `v1.2.7` re-adds
`typeWindowContentChanged` to `accessibility_service_config.xml`,
routed to `handleComposeSelectionDiagnostic()` in
`TextGateAccessibilityService.kt`. Unlike `v3`, this handler inspects the
source node's own `isChecked`, `isSelected`, `isLongClickable`, whether
its action list contains `ACTION_LONG_CLICK`, its `viewIdResourceName`,
and its class name — and whether `text`/`contentDescription` are present
and how long they are (gated behind `SensitiveInputGuard.isSensitiveInput`
exactly like every other read in this app). It deliberately still never
displays the actual text/contentDescription content on screen, even
temporarily — presence and length are enough to confirm or refute the
hypothesis without ever showing private message content in a diagnostic
overlay. Like `v3`, rapid-fire events are collapsed via a dedicated
debounce (`diagnosticDebouncer`, 250&nbsp;ms, entirely separate from the
typed-trigger pipeline's own debouncer) so only the latest snapshot is
shown once the stream goes quiet. This is still evidence-gathering only:
no production behavior changes in 1.2.7, and the long-press bubble's real
pathways are untouched.

**Status as of 1.2.7 (superseded — see "Sixth amendment" below): open
again, pending on-device test results.** The plan at the time: if the
diagnostic showed `checked=true` and/or a non-trivial `textLen` lining up
with a real long-press/selection on a message in Google Messages or X,
that would be strong evidence a real (non-diagnostic) extraction path is
buildable, and the next step would be a `v5` that reads `node.text` (or
`node.contentDescription`) through the same gates the rest of this app
already uses. If it instead showed the node carrying nothing useful
(`checked=false`, `textLen=-1`, no long-click action) even on a confirmed
long-press, that would be stronger, more specific evidence for the
"accepted limitation" conclusion than `v3` provided.

**Fifth amendment (v1.2.8): a real regression, found and fixed the same
day, from on-device use rather than deliberate testing.** After
installing 1.2.7, the app's owner reported that a translation-looking
bubble kept appearing in **TikTok** — every time they scrolled the
comment list, and every time they tapped any button — while nothing
similar happened anywhere else while scrolling. This was not a new,
separate bug: it was the v4 diagnostic bubble from the paragraph above,
firing in an app that has nothing to do with this investigation.

The cause was a gating mistake in `handleComposeSelectionDiagnostic()`:
it was gated on `settingsStore.isPackageAllowed(packageName)` — the
user's general allow-list — exactly like the real translation pathways
are. But the user has TikTok on that allow-list (for the typed `?en`/`?pl`
trigger feature, §2.1), so the *diagnostic*, which was only ever meant to
run against Google Messages and X, fired there too. TikTok's comment list
and video UI change constantly (playback progress, like counters, newly
loaded comments), so `TYPE_WINDOW_CONTENT_CHANGED` fires there just as
generically and constantly as it does everywhere else — this in itself
is a small additional confirmation of the 1.2.5 finding that this event
type is inherently noisy, not something specific to Compose or to
Messages/X. The visible symptom — a bubble that looked like a translation
result appearing on every scroll/tap — was purely a side effect of this
diagnostic reusing the same `TranslationBubble.showResult()` UI as the
real feature; no actual translation, network call, or text read to
Gemini ever happened for TikTok content, since the diagnostic's own text
read is separately gated behind `SensitiveInputGuard.isSensitiveInput`
and never sends anything anywhere — but it was still a real, disruptive
usability regression in an app the user actively uses.

**Fix:** `handleComposeSelectionDiagnostic()` now checks
`event.packageName` against a new, hard-coded
`DIAGNOSTIC_TARGET_PACKAGES` set — exactly `com.google.android.apps.messaging`
and `com.twitter.android` — as the very first, cheapest check, before even
touching `settingsStore` or `event.source`. This is deliberately an
allow-list of the two investigation targets, not a TikTok-specific
block-list entry, since the same noisy-content-changed pattern could
plausibly show up in any other chatty, frequently-updating app on the
user's allow-list, not just TikTok. `accessibility_service_config.xml`'s
doc comment and the class-level KDoc in
`TextGateAccessibilityService.kt` were both updated to document this
restriction. No other behavior changed: the diagnostic's internal logic,
the real translation pathways, and every other gate are exactly as
described in the "Fourth amendment" above.

**Sixth amendment to Feature update #6 (v1.2.9): investigation closed a
second time, at the app owner's explicit request, before the diagnostic
produced a verdict.** After installing the fixed 1.2.8 build, the app's
owner tested the v4 diagnostic on Google Messages and X and reported that
long-press translation still did not work in either app — and asked to
drop the investigation ("nie wysłałem zrzutu bo nic to nie pomogło bo
nadal nie ma tłumaczeń w Google Messages/X więc to odpuszczam"). No
diagnostic screenshot was ever captured or shared, so the specific
question the v4 diagnostic was built to answer — whether `event.source`
for a long-pressed/selected message in these apps actually carries
`isChecked=true` and/or non-trivial `textLen`/`descLen` — was never
actually resolved either way. This is stated plainly rather than implied:
the investigation is closed because the app's owner chose to stop
pursuing it, not because the evidence pointed to a firm dead end. The
`v3`/`v4` diagnostic code (`typeWindowContentChanged` subscription,
`handleComposeSelectionDiagnostic()`, `diagnosticDebouncer`,
`DIAGNOSTIC_TARGET_PACKAGES`) has been fully removed as of 1.2.9,
including the TikTok-specific restriction added in 1.2.8 — with the
diagnostic itself gone, that restriction has nothing left to guard.
`accessibility_service_config.xml` is back to exactly the three permanent
event types, and `TextGateAccessibilityService.kt` no longer shows
anything on screen beyond the real feature's own loading/result/error
bubble — the same end state as after the first closure in 1.2.6.

**What this round of the investigation actually added, net of the
back-and-forth:** two specific claims about AndroidX Compose's
accessibility source code were checked against the real source rather
than taken on faith from an AI-generated summary, and one — that
non-Tab-role elements map selection state to `isChecked` rather than
`isSelected` — retroactively explains why the 1.2.4 `TYPE_VIEW_SELECTED`
attempt could never have worked, independent of whatever caused the
final `v4` result. The `v4` diagnostic itself, and whether `event.source`
in these two specific apps carries anything a real feature could use,
remains untested to a conclusion. If this is ever revisited, re-running
the fixed (1.2.8-style) diagnostic — scoped to Messages/X only from the
start this time — and actually capturing what it shows on a real
long-press is the concrete next step; re-verifying the two still-open
AndroidX claims from the "Fourth amendment" (long-click's event
dispatch, and which event type carries an `isChecked` change) would be
useful but secondary, since the diagnostic approach tests the practical
outcome directly regardless of which specific event type turns out to be
responsible.

**Conclusion as of 1.2.9 (superseded — see "Seventh amendment" below): SMS
(Google Messages) and X (Twitter) do not support the long-press bubble,
and the investigation into why is closed at the app owner's request —
not because every avenue was exhausted, but because the owner decided the
remaining avenues (a fuller `v5` diagnostic pass, or the
clipboard/floating-button fallback) were not worth pursuing further right
now.** The long-press bubble continues to work normally in Telegram,
WhatsApp, and any other app whose UI dispatches standard `View`-based
accessibility events; the typed `?en`/`?pl` trigger pathway is entirely
unaffected by any of this, in every app, since it never depended on
long-press detection at all.

**Seventh amendment to Feature update #6 (v1.2.10): `TYPE_VIEW_SELECTED`
itself — the other event type this whole investigation had been chasing
since v1.2.4 — turned out to be a real, active bug in a completely
different app, and was permanently removed.**

Right after the second closure (1.2.9), the app's owner reported that a
translation bubble — a **real** one, "wygląda realnie jak tłumaczenie"
("looks like a real translation"), not diagnostic text — kept appearing
in TikTok whenever they tapped a button, e.g. on the home screen. This
was confirmed to still happen on 1.2.9, i.e. **after** every diagnostic
code path from the "Fourth" through "Sixth amendment" had already been
fully removed — proving this was not diagnostic leftovers, but the real,
production `BubbleTranslateGate` translation pathway firing for real.

**Root cause:** `TYPE_VIEW_SELECTED` was added in v1.2.4 as a long-press
fallback specifically for Google Messages, on the hypothesis that a
long-press there enters a multi-select mode that marks the row
`isSelected` even without dispatching `TYPE_VIEW_LONG_CLICKED`. That
hypothesis was disproven on-device back in v1.2.4 (§14 "Second amendment")
— the event never fires for Google Messages either — but the event type
was left subscribed anyway, on the reasoning that it was "a standard,
zero-cost signal that might help other apps." `handleEvent()` routed
`TYPE_VIEW_SELECTED` to the exact same `handleLongClick()` as a genuine
long-press, with no way to tell them apart.

That reasoning turned out to be wrong on both counts. `TYPE_VIEW_SELECTED`
is not zero-cost: Jetpack Compose legitimately dispatches it for its own
built-in `Role.Tab` elements whenever their selected state changes —
which is exactly what happens on an ordinary, single tap of a bottom
navigation tab, a "For You"/"Following" feed toggle, or any similar
selectable UI element, all common, unremarkable patterns in a modern app
like TikTok. This is Compose's accessibility bridge working exactly as
designed — it is a real, on-purpose signal for real Tab UI, just entirely
unrelated to the "long-press to multi-select" scenario it was added here
to detect. Since `handleLongClick()` could not distinguish "a message row
just entered multi-select mode via long-press" from "a completely
ordinary single tap just changed which tab is selected," every such tap
on TikTok (an app already on the user's own allow-list, for the unrelated
`?en`/`?pl` typed-trigger feature) triggered a real `BubbleTranslateGate`
evaluation, and — whenever the tapped element's label/content passed the
usual checks — a real network call to Gemini and a real, on-screen
translation bubble for a button label, out of nowhere.

**Fix:** `typeViewSelected` is removed from
`accessibility_service_config.xml`'s `accessibilityEventTypes`, and
`TYPE_VIEW_SELECTED` is no longer routed to `handleLongClick()` (or
handled at all) in `TextGateAccessibilityService.kt`. This is a
permanent removal, not another temporary experiment: across the entire
investigation (v1.2.4 through v1.2.9), this event type produced zero
working translations in the app it was added for, and it is now confirmed
to actively cause unwanted, real translation attempts in at least one
other, unrelated app. There is no remaining justification to keep
subscribing to it. `TYPE_VIEW_LONG_CLICKED` — the pathway that actually
works, in Telegram and WhatsApp — is completely unaffected; only the
never-successful fallback is gone.

**Conclusion, final (as of 1.2.10): the long-press bubble now subscribes
to exactly one event type, `TYPE_VIEW_LONG_CLICKED`, plus
`TYPE_VIEW_TEXT_CHANGED` for the unrelated typed-trigger pathway.** SMS
(Google Messages) and X (Twitter) still do not support the long-press
bubble, and that investigation remains closed per the "Sixth amendment"
above. What changed in 1.2.10 is unrelated to that investigation's
outcome: it is the removal of a signal that never contributed a single
working translation anywhere, once it was shown to actively cause
unwanted ones elsewhere. The long-press bubble continues to work normally
in Telegram, WhatsApp, and any other app whose UI dispatches
`TYPE_VIEW_LONG_CLICKED` for a real physical long-press; the typed
`?en`/`?pl` trigger pathway is entirely unaffected by any of this, in
every app, since it never depended on long-press detection at all.

**Onboarding and privacy-text fixes (v1.3.0), unrelated to the SMS/X
investigation above.** Two separate, smaller issues were fixed together
in this release, both about making Settings clearer for a first-time
user rather than about the accessibility-event architecture discussed
throughout this section:

* **"Get a free API key" button + step-by-step guide.** Previously,
  getting a Gemini API key required already knowing to go to
  `aistudio.google.com` — now Settings has a button that opens the
  correct page (`aistudio.google.com/apikey`, confirmed against Google's
  own `ai.google.dev/gemini-api/docs/api-key` documentation) directly in
  the browser, plus a collapsible plain-language guide covering every
  step from "sign in with your Google account" to "paste the key back
  into this app." See §6 and §12 "Getting a Gemini API key."
* **Privacy section no longer points to a file the app doesn't ship.**
  `privacy_notice_body` used to end with "see the README for the exact
  rules this app follows" — but `README.md` is a repo-only developer
  document, never packaged into the APK, so a real end user had nowhere
  to actually go. It's replaced with a "Full privacy policy" button that
  opens an in-app dialog with the complete, plain-language explanation
  (what's read, what's never read, what's sent and where, what's stored,
  what TextGate AI deliberately does not do) — no external reference, no
  new dependency (a platform `AlertDialog`, consistent with §4's
  zero-production-dependency principle).

**Multi-language rebuild (v1.4.0), unrelated to the SMS/X investigation
above.** Before publishing to Google Play, the app's owner asked for a
much larger set of translation languages, for the typed-trigger picker,
the long-press bubble's target language, and the app's own interface to
all be rebuilt around one shared list rather than the original hardcoded
English/Polish pair. Requested and delivered as three explicit design
answers: the typed triggers AND the bubble's language list both expand
together ("Dymek + nowe triggery"); picking a language changes BOTH the
default translation target AND the app's own interface language ("Jedno
i drugie" — one list, one choice, the whole app follows); and the exact
40-language list was supplied by the app's owner directly, using
Android's own legacy resource-qualifier code spelling in a few places
(`in` for Indonesian, `iw` for Hebrew, `nb` for Norwegian Bokmål, `pt-rBR`
/ `zh-rCN` for the region-qualified Brazilian Portuguese / Simplified
Chinese variants) rather than the more modern ISO codes those languages
are otherwise known by, since those codes now serve triple duty as the
typed-trigger suffix, the persisted Settings value, and the Android
`values-<code>` resource folder suffix, all at once.

* **`Languages.kt` (new) — single source of truth.** A `SupportedLanguage`
  data class (`code`, `nativeName`, `englishName`, `localeLanguageTag`)
  plus `Languages.ALL` (the full 40-entry list), `Languages.byCode()`, and
  `Languages.DEFAULT` (Polish, matching the app's original default).
  Every other language-aware piece of the app — the trigger patterns, the
  translation prompt, the Settings picker, the locale override — reads
  from this one list rather than maintaining its own copy.
* **`TriggerDetector.Target` redesigned.** Went from a closed, 2-value
  `enum class` to a `@JvmInline value class Target(val code: String)`,
  so any of the 40 `Languages.ALL` codes can be a typed-trigger target
  (`?en`, `?de`, `?pt-rBR`, and so on for every code) without the type
  itself needing to change again for a future language addition.
  `Target.ENGLISH` and `Target.POLISH` are kept as companion-object
  constants purely for source compatibility with code and tests written
  before this rebuild — they are ordinary `Target("en")`/`Target("pl")`
  values, nothing more special than any other language's `Target`, and
  every existing unit test (`TriggerDetectorTest.kt`, `EventGateTest.kt`)
  continues to compile and pass unchanged, since value-class equality
  compares by the wrapped `code` string. `TRIGGER_PATTERNS` is now
  generated from `Languages.ALL` (one regex per language) instead of two
  hand-written patterns, preserving every existing tolerance rule
  (optional single space after `?`, trailing spaces, case-insensitivity,
  the `\z` absolute-end anchor) for all 40 languages — including
  hyphenated codes like `pt-rBR`, which are literal-escaped so they can
  never be ambiguous with the plain `pt` trigger.
* **`TranslationPrompts.kt` made generic.** The two hardcoded
  `EN_TRANSLATION_SYSTEM_PROMPT`/`PL_TRANSLATION_SYSTEM_PROMPT` constants
  (kept only as compatibility wrappers, e.g. for the "Test API connection"
  button) are replaced by `systemPromptFor(target)`, which resolves the
  target's English display name via `Languages.byCode(target.code)` and
  builds the same auto-detect-source / preserve-tone / idiomatic /
  no-commentary prompt template for any of the 40 languages.
* **`LocaleHelper.kt` (new) — the "whole app" language override.** This
  app has zero third-party dependencies (§4), so the usual AndroidX
  shortcut (`AppCompatDelegate.setApplicationLocales`) isn't available;
  this is the same effect hand-rolled with only platform APIs, using the
  standard `attachBaseContext` + `createConfigurationContext` pattern.
  Applied in three places, each independently, since a Service or an
  Activity can be given its own fresh Configuration by the system without
  necessarily inheriting an override applied only once at the
  `Application` level: `TextGateApplication.attachBaseContext`,
  `SettingsActivity.attachBaseContext`, and
  `TextGateAccessibilityService.attachBaseContext` (covering that
  service's own toast/notification text). A `null`
  `appInterfaceLanguage` setting (the default, until the user picks a
  language) means "follow the device's own system language," identical
  to this app's original, pre-1.4.0 behavior.
* **Settings screen: one collapsible list, not two radio buttons.** The
  old two-`RadioButton` English/Polish picker is replaced by a single
  `Spinner` (a zero-dependency platform widget — the "zwinięta lista,"
  collapsed list, that was asked for) listing all 40 `Languages.ALL`
  entries by `nativeName` (each language's own name, in its own script,
  never translated — exactly like every native OS language picker, so a
  Polish speaker and a Japanese speaker both see "日本語" for Japanese,
  not a translation of the word). Picking a language sets BOTH
  `AppSettingsStore.bubbleTargetLanguage` and
  `AppSettingsStore.appInterfaceLanguage` in one action and immediately
  calls `recreate()`, per the "Jedno i drugie" answer above.
* **`AppSettingsStore.kt`: `bubbleTargetLanguage` now stores the raw
  language code directly** (validated against `Languages.byCode()`,
  falling back to `Languages.DEFAULT` if unrecognized) instead of a
  closed `"EN"`/`"PL"` string pair — and since `Languages.byCode()` does
  a case-insensitive fallback lookup, an existing install upgrading from
  1.3.0 with `"EN"` or `"PL"` already stored keeps working with no
  migration code needed. A new, independent `appInterfaceLanguage: String?`
  setting was added alongside it.
* **38 new translated `values-<code>/strings.xml` locale files**, one per
  `Languages.ALL` entry other than `en` (the existing default `values/`
  folder) and `pl` (the existing `values-pl/`). Generated by having every
  one of this app's 61 Settings-screen strings translated per language,
  with the app name, the "Gemini"/"Google AI Studio"/"Android Keystore"
  product names, the literal trigger examples, the `%1$s`/`%1$d`
  placeholders, and third-party app/brand names (WhatsApp, Instagram,
  etc.) deliberately left untranslated in every locale, and verified
  afterward — every one of the 38 files was checked for well-formed XML,
  for containing exactly the same 61 string keys in the same order as the
  English source, and for retaining every untranslatable token intact.
  **Known, accepted limitation:** this app does not declare
  `android:supportsRtl`, so the three right-to-left languages on the list
  (Arabic, Persian, Hebrew) get correctly translated text but the
  Settings screen's own layout does not mirror to a right-to-left flow —
  a cosmetic gap, not a functional one, left for a future release rather
  than blocking this one.
* **Two remaining user-facing strings were also generalized while this
  work was in progress**, since they still hardcoded "?en"/"?pl" and
  would have read as inaccurate once 40 triggers existed:
  `accessibility_service_description` (the system-level Accessibility
  Settings description Android shows the user) and the master-switch /
  privacy-notice copy — all now describe "a translation trigger like ?en
  or ?de" generically instead of naming only the original two.

**Post-1.4.0 CI regression fix (v1.4.1): `AppSettingsStore` NPE during
Application bootstrap, caught by the GitHub Actions run right after 1.4.0
was delivered, before it was ever installed on a device.** The CI logs
showed 55 of 87 unit tests failing with `NullPointerException at
AppSettingsStore.kt:31` — including tests (`SensitiveInputGuardTest`,
`GeminiClientTest`) that never touch `AppSettingsStore` at all, which was
the tell that this wasn't really 55 separate failures but one shared root
cause poisoning every Robolectric-backed test class.

**Root cause:** `TextGateApplication.attachBaseContext` (added in 1.4.0
to apply the new "App interface language" override via `LocaleHelper`,
see above) calls `LocaleHelper.applyOverride(base)`, which constructs
`AppSettingsStore(base)` to read `appInterfaceLanguage`. `AppSettingsStore`
in turn called `context.applicationContext.getSharedPreferences(...)` —
but `Context.getApplicationContext()` has a well-known Android
chicken-and-egg gap specifically *inside* `Application.attachBaseContext`:
the `Application` object is itself what becomes the application context,
but at the moment `attachBaseContext` runs it has not finished attaching
to itself yet, so `applicationContext` is still `null` there. Calling
`.getSharedPreferences(...)` on that `null` threw the `NullPointerException`.
Robolectric constructs `TextGateApplication` (as declared in the
manifest) once per test via `ApplicationProvider`, which runs
`attachBaseContext` for every single Robolectric-based test regardless of
what that test itself exercises — hence all 5 Robolectric test classes
failing uniformly (`AppSettingsStoreTest`, `BubbleTranslateGateTest`,
`EventGateTest`, `GeminiClientTest`, `SensitiveInputGuardTest`), while the
4 plain-JUnit test classes that need no `Context` at all
(`NetworkAllowlistTest`, `AppBlocklistTest`, `ResultPolicyTest`,
`TriggerDetectorTest`) were unaffected — 87 total, 55 failed, 32 passed,
matching exactly.

**Fix:** `AppSettingsStore`'s `SharedPreferences` initializer now falls
back to the raw `context` itself when `context.applicationContext` is
`null`: `(context.applicationContext ?: context).getSharedPreferences(...)`.
This is safe specifically because `getSharedPreferences` does not retain
a reference to whichever `Context` it was called through — unlike storing
a `Context` in a field for later use (the actual leak-prone pattern this
app avoids elsewhere, e.g. in `SecureApiKeyStore`, which is never
constructed during `attachBaseContext` and so keeps the plain
`context.applicationContext` form unchanged). Every other call site of
`AppSettingsStore` (Activity `onCreate`, Service `onServiceConnected`) is
outside the risky window and behaves exactly as before.

**Prominent disclosure screen for the Accessibility Service (v1.5.0).**
Google Play's policy for apps that use `AccessibilityService` as a
"non-accessibility tool" (see
`docs/publikacja_google_play.md`, section 0) requires a dedicated in-app
screen — not the Android system Settings screen — that is shown *before*
the user is ever sent to enable the service, clearly discloses what is
read and why, and requires an explicit affirmative action (a button that
reads like real consent, e.g. "I agree", never a dismissive "OK" or
"Understood") before continuing.

**What changed:** a new `AccessibilityDisclosureActivity`
(`app/src/main/java/com/textgate/ai/accessibility/`) with its own layout
(`activity_accessibility_disclosure.xml`). `SettingsActivity`'s "Open
Accessibility Settings" button no longer jumps straight to
`Settings.ACTION_ACCESSIBILITY_SETTINGS`: if the service is not yet
enabled, it now launches this disclosure screen first, and only that
screen's own "I agree and continue to settings" button opens the system
screen — tapping "Cancel", or the system back gesture, simply returns to
Settings with nothing changed. If the service is already enabled, the
button still goes straight to the system screen as before (to review or
turn it back off), since Google's requirement is specifically about the
moment before the permission is first requested, not every subsequent
visit.

The disclosure text (`accessibility_disclosure_title`,
`accessibility_disclosure_body`, `btn_accessibility_agree`,
`btn_accessibility_cancel`) was added to all 40 language files this app
ships (`values/` through the 39 `values-<code>/` locale directories),
each translated natively rather than machine-translated, matching the
existing tone/register and terminology already used in that same file —
consistent with how the v1.4.0 multi-language rebuild's strings were
localized. The new Activity applies the same
`LocaleHelper.applyOverride`/`attachBaseContext` pattern as every other
screen, so it always renders in the user's chosen app language, not just
the system language.

`AndroidManifest.xml` declares the new Activity with `exported="false"` —
it is only ever started from `SettingsActivity` via an explicit internal
`Intent`, never a launcher entry point or a target for any external
`Intent`, so no new attack surface is introduced.

**App name change for Play Store discoverability (v1.5.1).** The
in-app launcher name (`app_name`, shown under the home-screen icon, in
the Recents/app-switcher, and as the Accessibility Service's label in
Android's own Settings) was "TextGate AI" with no translation-related
keyword in it. Google Play's own search ranking weighs the app's title
field heavily, and a purely branded name with no descriptive keyword
tends to under-perform against apps that pair a keyword with their brand
(a "Brand: Keyword" or "Keyword Brand" title, one keyword only — not
several stuffed in). It was changed to **"Tłumacz TextGate AI"**
("Tłumacz" = Polish for "Translator") to match the same title now used
for the Google Play Store listing itself (see
`docs/publikacja_google_play.md`), so a search for "tłumacz" is more
likely to surface the app both in Play search and, secondarily, in the
device's own app-drawer/system search.

This string is deliberately identical (untranslated) across all 40
`values*/strings.xml` locale files — same treatment as the "TextGate AI"
brand name before it, which was likewise never translated per-locale.
The trade-off is worth noting: a Polish word will show under the icon
regardless of the phone's system language. That was a deliberate,
explicit choice (not an oversight) made when this was decided, on the
basis that the Play Store listing itself is Polish-first (see the short/
long descriptions in `store-assets/opisy_sklepowe.md`, which already
lead with "Tłumacz"/"Translator" respectively) — if the app is ever
positioned for non-Polish markets as a primary audience, this is the
string to revisit, and per-locale translation of `app_name` (e.g.
"Translator TextGate AI" for English) is a straightforward follow-up if
wanted.

**Multiple Gemini API keys with automatic rotation (v1.6.0).** A single
Gemini API key's free tier is capped at a modest number of requests per
month, which a regular user of the long-press "translate a received
message" bubble can exhaust well before the month is over. Rather than
build any kind of paid-tier billing integration, `SecureApiKeyStore` was
rewritten to hold an ORDERED LIST of independently encrypted keys instead
of a single one, and a new `KeyRotationTranslator` object transparently
tries the user's next saved key whenever the currently active one comes
back HTTP 429 (Gemini's quota-exceeded response) — so translation keeps
working for as long as *any* of the user's saved free-tier keys still has
headroom, without the user ever having to notice a failure or manually
switch keys themselves.

**What changed, file by file:**

- `SecureApiKeyStore.kt` — completely rewritten. Each key is still
  encrypted with the same single Keystore-resident AES-256-GCM wrapping
  key as before (`KeystoreCrypto`) — one wrapping key safely seals many
  independent (iv, ciphertext) pairs, since GCM's security only requires a
  fresh random IV per encryption, which `KeystoreCrypto.encrypt` already
  guarantees on every call. Keys are addressed by an internally generated
  random id (never derived from the key itself) and kept in an ordered
  JSON id-list (`org.json.JSONArray`, already part of the Android SDK —
  no new dependency). New public API: `listKeys()`, `keyCount()`,
  `hasAnyKey()`, `addKey(CharArray): Boolean` (appends; only the very
  first key ever added becomes active automatically), `removeKey(id)`,
  `clearAllKeys()`, `activeKeyId()`, `getActiveKeyPlaintext()`, and
  `advanceActiveKey()` (moves the active pointer to the next key in
  add-order, wrapping back to the first after the last). The old
  single-key methods (`saveApiKey`/`getApiKey`/`hasApiKey`/`clearApiKey`)
  no longer exist. As with the previous version, no plaintext key is ever
  written to disk, nothing here calls `android.util.Log` with a key or
  ciphertext, and the only plaintext ever persisted per key is its last 4
  characters — purely so the Settings screen can show which saved key is
  which ("•••• aB12"), the same convention Stripe, AWS, and Google Cloud's
  own consoles use for exactly this reason.

- `KeyRotationTranslator.kt` — new file. A thin, stateless orchestration
  layer on top of `GeminiClient.translateBlocking`: calls it once with the
  active key; on any failure other than an HTTP 429, returns that result
  immediately unrotated (a timeout, network error, or malformed response
  is not fixed by trying a different key, and silently retrying it would
  only delay the user seeing the real error); on a 429, advances to the
  next stored key and retries, at most once per stored key, then returns
  `AllKeysExhausted` if every key was tried and every one came back
  quota-exceeded. `GeminiClient` itself is deliberately left untouched and
  stateless — it still has no concept of "more than one key" — with a new
  `GeminiClient.Result.Failure.AllKeysExhausted` sealed subtype added
  purely so callers can distinguish "every saved key is out of quota"
  from an ordinary single-key `HttpError(429)`.

- `TextGateAccessibilityService.kt` — both translation entry points (the
  typed `?en`/`?de`-style trigger in `confirmAndProcess`, and the
  long-press "translate a received message" bubble in
  `startBubbleTranslation`) now check `apiKeyStore.hasAnyKey()` instead of
  reading a single key, and both route the actual network call through
  `KeyRotationTranslator.translateWithRotation` instead of calling
  `GeminiClient.translateBlocking` directly. `mapFailureMessage`'s
  exhaustive `when` gained the new `AllKeysExhausted` branch
  (`error_all_keys_exhausted`).

- `SettingsActivity.kt` / `activity_settings.xml` / the new
  `item_api_key_row.xml` — the single "paste one key, save" field was
  replaced with a real list UI: a "Saved API keys" section
  (`layoutApiKeyList`) rendering one row per stored key (its last 4
  characters, plus a " · active" marker on whichever key is currently in
  use), each with its own "Remove" button; an "Add another key" field
  below it (`buttonAddApiKey`, appends rather than replaces); and a
  "Remove all" button that clears the whole list at once
  (`apiKeyStore.clearAllKeys()`). The status line now reads e.g. "3 keys
  saved. Active: •••• aB12" (`api_key_status_format`) instead of a plain
  present/absent line. "Test API" (`runApiTest`) now runs through
  `KeyRotationTranslator.translateWithRotation` too, rather than testing
  only the active key directly — so it reports whether translation will
  actually work right now, including a transparent rotation past an
  exhausted key — and refreshes the key list afterward in case rotation
  moved the active pointer during the test.

- Ten new string keys (`label_saved_api_keys`, `api_keys_multi_description`,
  `label_add_api_key`, `btn_add_api_key`, `btn_remove_api_key`,
  `api_keys_empty`, `api_key_status_format`, `api_key_row_label`,
  `api_key_row_label_active`, `error_all_keys_exhausted`) were added to
  all 40 language files this app ships, each translated natively rather
  than machine-translated, matching the existing tone/register already
  used in that same file — same process as the v1.4.0 multi-language
  rebuild and the v1.5.0 disclosure-screen strings. Verified afterward
  that every one of the 40 `strings.xml` files parses as valid XML and
  carries exactly the same 75 string keys as the English base file, with
  no duplicates.

- `KeystoreCryptoInstrumentedTest.kt` (`app/src/androidTest/`) — rewritten
  against the new multi-key API. Covers: add-then-read-back, clearing
  every key, a blank key being rejected and nothing stored, a second
  added key appending rather than replacing (and the first key staying
  active), `advanceActiveKey` cycling through stored keys and wrapping
  back to the first after the last, `advanceActiveKey` being a no-op with
  only one stored key, and `removeKey`'s active-pointer reassignment in
  three cases (removing the active key from the middle of the order,
  removing it when it was the last key — the specific case the
  `removedIndex % newOrder.size` wraparound formula exists for — and
  removing the last remaining key, which clears the active pointer
  entirely).

**Known limitation, not yet addressed:** a user who saved a single key
under the old (pre-v1.6.0) `SecureApiKeyStore` schema will find that key
silently unreadable after updating — the old and new schemas use
different SharedPreferences entries under the same file name. Since this
app has not yet been published to real users, this is treated as an
accepted breaking change for now rather than a migration path that was
built; if the app already has real users by the time this ships, writing
a one-time migration (read the old `api_key_iv`/`api_key_ciphertext`
entries if present, `addKey()` them into the new schema, then delete the
old entries) would be a small, contained follow-up.

**User gender preference and a rewritten, precision-focused translation
prompt (v1.7.0).** Two related changes, requested together: a new Settings
option for the app owner's own grammatical gender, and a stricter system
prompt built specifically to stop the model from silently "improving"
things it should have left alone (changing a plural to a singular,
resolving an intentionally vague pronoun, dropping formality) — a
real-world complaint the app owner raised about occasional mistranslation.

**New setting — "Your gender" (Automatic/unspecified, Male, Female).**
Added `UserGender` (`app/src/main/java/com/textgate/ai/model/UserGender.kt`),
a 3-value enum, plus `AppSettingsStore.userGender` (defaults to `AUTO`,
persisted in the existing plain — not Keystore-encrypted — settings
prefs file, same as `selectedModel`). A new Spinner
(`spinnerUserGender`) was added to `activity_settings.xml`, right under
the language picker in the "AI transformation" card, wired up by
`SettingsActivity.setupUserGenderSection()` — same deferred-listener
pattern as the existing language Spinner, so the initial programmatic
selection doesn't immediately re-save itself. Five new string keys
(`label_user_gender`, `user_gender_description`, `label_gender_auto`,
`label_gender_male`, `label_gender_female`) were added to all 40 language
files, each translated natively — for languages with grammatical gender,
the Male/Female labels use a short, natural self-descriptive noun a
person would actually pick to describe themselves (e.g. German
"Männlich"/"Weiblich", Russian "Мужчина"/"Женщина"), not a clinical term.

**Scope is deliberately asymmetric between the two translation
pathways — this was the actual point of the request.** This preference is
read in exactly ONE place: `TextGateAccessibilityService.confirmAndProcess`,
the typed `?xx`-trigger path, which translates text the phone owner
themselves wrote. `startBubbleTranslation` — the long-press "translate a
received message" bubble — explicitly passes `UserGender.AUTO` regardless
of the stored setting, with a comment at that exact call site explaining
why: that text was written by someone else, so the phone owner's own
gender has no bearing on it and must never be sent for it. Both
`AppSettingsStore.userGender`'s and `UserGender`'s own doc comments state
this constraint too, so it stays visible from three different places in
the code, not just one.

**The rewritten system prompt** (`TranslationPrompts.systemPromptFor`,
`app/src/main/java/com/textgate/ai/model/TranslationPrompts.kt`) now
explicitly instructs Gemini to preserve — never invent, smooth over, or
"improve" — exact meaning, grammatical number (singular vs. plural),
person, tense, any gender the source text itself expresses, tone, and
formality; to never flip a singular to a plural or vice versa; to keep
source ambiguity as ambiguity when the target language allows it, rather
than resolving it by guessing; and to leave proper nouns, usernames,
`@mentions`, URLs, numbers, emoji, and existing formatting untouched
unless translating literally requires a change. Text already in the
target language is now explicitly scoped to a grammar-only pass ("only
correct grammar, spelling, and phrasing... without changing its
meaning") rather than the vaguer "lightly polish" wording it replaced.
The "return only the finished text, nothing else" constraint from the
original prompt is unchanged.

`systemPromptFor` gained a second, optional parameter —
`speakerGender: UserGender = UserGender.AUTO` — so every pre-existing call
site (the long-press bubble, and `SettingsActivity`'s fixed "Test API"
string) keeps compiling and behaving exactly as before with no change at
their call sites' meaning. When a non-`AUTO` gender is passed, one further
paragraph is appended identifying the message's author (first-person
"I"/"me"/"my"/"myself" only) as male or female, but — deliberately —
worded as *conditional*: "Apply this only where \[language\]
grammatically requires marking the speaker's own gender... otherwise
ignore it entirely." This app supports 40 target languages, and most of
them (English, Chinese, Turkish, Finnish, ...) do not grammatically mark
a first-person speaker's gender at all; hand-maintaining a 40-language
"does this language need it" lookup table would have reproduced exactly
the kind of unmaintainable, error-prone per-language list
`TranslationPrompts`' own class doc already warns against for the
language-name templating it already does. That judgment is left to
Gemini instead, which already knows which languages inflect for speaker
gender and which don't. The one unconditional sentence in that paragraph
is the actual security/correctness-relevant part: "Never use this to
infer, assume, or change the gender of anyone else mentioned in the
text" — without it, a model could plausibly "help" by assuming every
other person named in the message shares the user's declared gender,
which is exactly the kind of invented detail the rest of the prompt
forbids.

**Lowest available "thinking" budget for the default model.**
`GeminiClient.buildRequestBody` now attaches
`generationConfig.thinkingConfig.thinkingBudget = 0` whenever the
resolved model id is exactly `gemini-3.5-flash-lite` (this app's default,
see `AppSettingsStore.DEFAULT_MODEL`) — a plain translation is not a
reasoning task, so there is no reason to pay for or wait on "thinking"
tokens on the one model this app specifically knows supports disabling
them. Every other model is left exactly as before (no `thinkingConfig`
key at all in the request body), since different Gemini model families
expose different thinking-budget ranges, defaults, and requirements, and
guessing a value for a model this app doesn't specifically know about
risks a rejected request rather than a faster one. No other part of the
request shape, the endpoint, the retry/rotation behavior
(`KeyRotationTranslator`), or conversation-history handling (still none —
every request remains a single, stateless, non-streaming `generateContent`
call with no prior messages attached) changed.

**Tests added:** `TranslationPromptsTest.kt`
(`app/src/test/java/com/textgate/ai/model/`, new file/new test package)
covers: `AUTO` produces no speaker-gender clause; the default parameter
value behaves identically to passing `AUTO` explicitly; `MALE`/`FEMALE`
each add a clause that says so; that clause always includes the
"never apply to anyone else" sentence and the "only where the language
requires it... otherwise ignore it" conditioning; and, separately, that
the base prompt (regardless of gender) still asserts every
preserve-don't-invent constraint described above. `AppSettingsStoreTest.kt`
gained two cases: `userGender` defaults to `AUTO`, and persists correctly
across separate `AppSettingsStore` instances backed by the same
`Context` (the same persistence-proof pattern already used for
`isAiEnabled`/allow-listed packages in that file).

**Correction: gender preference no longer assumes the trigger pipeline's
text is the user's own writing (v1.7.1).** v1.7.0's gendered clause
applied the declared gender to "the author" unconditionally whenever a
non-`AUTO` gender was set — wrong, because the typed-trigger pipeline
(`?en`, `?pl`, ...) translates whatever text sits before the trigger,
which the user may well have pasted in from someone else before typing
the trigger themselves (e.g. "I was tired yesterday ?pl" — the English
sentence could be a quote from another person, not the user's own words).

`TranslationPrompts.systemPromptFor` gained a third parameter,
`userPreferredLanguage: SupportedLanguage? = null` — the user's own
primary language, resolved from `AppSettingsStore.appInterfaceLanguage`
(a stored code used directly; `null`, "follow the device's system
language", resolved via a new `LocaleHelper.resolvePreferredLanguage`
using the `Context`'s own current `Configuration` locale — no network
call, no extra AI request). The appended gender clause is now
conditional: it tells Gemini to treat the declared gender as the
author's gender ONLY when the request's own already-instructed
source-language auto-detection lands on the user's preferred language
AND the translation target is a different language (i.e. the shape of
"I wrote this myself, translate it out") — scoped to first-person
grammatical forms only, never applied when translating text that is
already in another language or when translating INTO the user's own
preferred language, and, as before, never allowed to change any other
person's gender mentioned in the text. `TextGateAccessibilityService.
confirmAndProcess` now resolves and passes this preferred language
alongside the existing gender preference;
`startBubbleTranslation` is unchanged — it still always passes
`UserGender.AUTO` for the long-press "translate a received message"
bubble. `TranslationPromptsTest.kt` was rewritten against the new
three-argument shape and the new conditional wording.

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

## 16. v2.0.0 — new interface, voice translation, Gemini Live, model
fallback, and background Live operation

The single biggest change since this app existed: a new bottom-navigation
UI (Tłumacz / Rozmowa / Na żywo / Ustawienia) replacing the old
single-screen layout, two new real-time voice-translation modes built on
the Gemini Live API, an automatic text-model fallback with
timezone-correct daily-quota handling, and a Foreground Service that keeps
an ambient Live session alive through screen-off and backgrounding. None
of the pre-v2 functionality listed in every earlier section of this
changelog was rebuilt or altered beyond what is described below — the
`?xx` triggers, the long-press bubble, key rotation, encrypted key storage,
the allow-list, and every existing setting all still work exactly as
before.

**New launcher & navigation.** `MainActivity` (new) replaces
`SettingsActivity` as the app's launcher Activity and hosts a hand-rolled
bottom navigation bar — four plain `LinearLayout` tab items, each tab a
separate inflated layout kept alive for the Activity's lifetime with only
one visible at a time (`MainActivity.showTab`). No Jetpack Navigation, no
Fragments, no `BottomNavigationView` — consistent with this app's
"no UI library" precedent in `SettingsActivity`. `SettingsActivity` is
still the exact same Activity it always was; the Ustawienia tab simply
starts it, unchanged, via `startActivity`.

**One new production dependency, narrowly scoped.** Every version through
1.7.1 shipped with zero third-party runtime libraries — a deliberate,
documented security stance. v2 adds exactly one: OkHttp
(`com.squareup.okhttp3:okhttp:4.12.0`), used for exactly one thing — the
WebSocket connection to the Gemini Live API
(`com.textgate.ai.live.GeminiLiveClient`). The Android SDK has no built-in
WebSocket client; the only zero-dependency alternative was hand-rolling
RFC 6455 framing over a raw `SSLSocket` for a feature that streams live
microphone audio bidirectionally and must reconnect cleanly — judged too
high-risk to hand-roll correctly with no device or live endpoint available
to test against in this project's development environment (see the note
on unverifiable pieces at the end of this section). Every other v2 addition
— the new UI, speech-to-text/text-to-speech, audio capture/playback, audio
focus, the Foreground Service — uses only platform APIs, exactly like
everything before it. See `app/build.gradle.kts`'s `dependencies {}` block
for the full reasoning, kept in place as a code comment.

**Tłumacz tab** (`content_translate.xml` /
`com.textgate.ai.translate.TranslateTabController`) is a
Google-Translate-style screen — source language (with "Automatycznie") +
swap + target language, a large text field with one-shot mic dictation
(platform `SpeechRecognizer`, no library), a debounced auto-translate (700ms,
reusing the existing `Debouncer` class), a result field with copy
(`ClipboardManager`) and text-to-speech playback (platform
`TextToSpeech`), and a clear button. Translation itself goes through the
*exact same* call the typed-trigger pipeline uses —
`TranslationOrchestrator.translateText` with
`TranslationPrompts.systemPromptFor` conditioned on the user's gender and
preferred language, precisely as `TextGateAccessibilityService.
confirmAndProcess` already does — no parallel translation logic was
written for this screen. The `?xx` trigger mechanism and the long-press
bubble are completely untouched by this tab.

**Rozmowa tab** (`content_conversation.xml` /
`com.textgate.ai.conversation.ConversationTabController`) is a two-person,
in-person conversation mode using `GeminiLiveClient` directly for
`gemini-3.5-live-translate-preview`'s native audio-to-audio translation —
never a text model. Deliberately foreground-only (no Foreground Service):
leaving the tab or backgrounding the app always ends the session; this was
judged the correct fit for a mode both participants are actively looking
at the screen for, unlike Na żywo's ambient use case. One simplification
worth flagging explicitly: the Live API's `translationConfig` carries one
`targetLanguageCode` per session, with no documented way found to change
it on an already-open connection — so a two-person conversation is modeled
as one active translation DIRECTION at a time (Language A → B, or the
reverse), with a swap button that closes and reopens the session with the
other target language. This is the most defensible reading available
without being able to test against the real API from this environment;
revisit once a real device confirms whether direction can be changed
in-session instead.

**Na żywo tab** (`content_live.xml` /
`com.textgate.ai.live.LiveTabController`, backed by
`com.textgate.ai.live.LiveTranslationService`) is the ambient
"phone-in-your-pocket" mode. The Foreground Service — never the Activity —
owns the entire session: mic capture (`AudioRecord`, 16kHz mono PCM16),
the `GeminiLiveClient` WebSocket, translated-audio playback (`AudioTrack`,
24kHz mono PCM16), `AudioFocusRequest`, a bounded/backed-off reconnect
(2s/4s/8s/16s/30s, max 5 attempts before transitioning to BŁĄD), audio-route
monitoring (`AudioRouteMonitor`, platform `AudioDeviceCallback` — no
Bluetooth library needed), and a conditional `PARTIAL_WAKE_LOCK` (acquired
right after START, always released on STOP/BŁĄD/`onDestroy`, never a
`FLAG_KEEP_SCREEN_ON` — the screen is allowed to turn off normally). All
seven required states (ZATRZYMANO, ŁĄCZENIE, SŁUCHAM, TŁUMACZĘ, PONOWNE
ŁĄCZENIE, WSTRZYMANO, BŁĄD — `LiveSessionState`) are implemented exactly as
specified. The service is started only via `LiveTranslationService.start()`
from an explicit user tap on the Na żywo tab's START button while the app
is in the foreground — never on boot, never on app launch, never silently;
`START_NOT_STICKY` is used deliberately so a process kill under memory
pressure requires a fresh, deliberate START rather than silently
resurrecting mic capture. Reopening the app binds to whatever the service's
real current state is (`LiveTabController.onStart`/`onStop` only
attach/detach the UI listener — they never start or stop the session
itself), and a second `ACTION_START` while already connecting/active is a
no-op, so two parallel sessions can never run.

**Headset-disconnect behavior** (`HeadsetDisconnectBehavior`, new
`AppSettingsStore.headsetDisconnectBehavior` setting, "Audio i Live"
section of Settings) defaults to "Wstrzymaj tłumaczenie": if the headset
disconnects mid-session, playback stops immediately, output never falls
back to the speaker, the Gemini session is left open underneath for an
instant resume, and the screen/notification show WSTRZYMANO —
reconnecting a headset resumes automatically. The alternative,
"Przełącz na głośnik," is an explicit opt-in the user must choose
themselves. The persistent Live notification
(`live_notification_channel_name`/`live_notification_title`) always shows
current status and a ZATRZYMAJ action that stops the mic, closes the
Gemini session, releases audio focus and the WakeLock, and stops the
Foreground Service.

**Text-model fallback (3.5 → 3.1) with real RPD/RPM distinction.**
`GeminiClient.Result.Failure` gained two new cases:
`QuotaExceeded(scope: QuotaScope, retryAfterSeconds: Long?)` (replacing
what used to surface as a plain `HttpError(429)`) and `AllKeysExhausted`
became a `data class` carrying the last observed `QuotaExceeded` detail
instead of a bare `data object`. `GeminiClient.parseQuotaFailure` reads the
429 response body's `error.details[].violations[]` (Google's standard
QuotaFailure error-detail shape) to classify DAILY vs. SHORT_TERM vs.
UNKNOWN — and deliberately treats UNKNOWN exactly like SHORT_TERM, never
like DAILY, so an ambiguous 429 can never block a model for a whole day on
a guess. New `ModelAvailabilityStore` persists a per-model
"unavailable-until" instant — for DAILY, always the next
`America/Los_Angeles` local midnight (DST-aware via `ZonedDateTime`, not a
fixed UTC offset — see `ModelAvailabilityStoreTest`'s spring-forward/
fall-back test cases); for SHORT_TERM/UNKNOWN, the server's own
`Retry-After`/`retryDelay` hint or a 60s default, capped at 30 minutes. New
`TranslationOrchestrator` is now the single entry point for every TEXT
translation (`TextGateAccessibilityService`'s both pipelines, the Tłumacz
tab, and the "Test API" button all route through it): it checks
`ModelAvailabilityStore` before choosing `gemini-3.5-flash-lite` or
`gemini-3.1-flash-lite`, and on a fresh exhaustion, marks the store and
immediately retries the *current* request against the fallback model once,
so the user is never kept waiting on a request that has already failed. No
separate probe request is ever made to check recovery — the very next real
translation after a stored cooldown/reset naturally tries the primary model
again. `KeyRotationTranslator`'s existing per-key rotation is completely
unchanged and untouched by any of this — the two mechanisms are layered,
not merged, exactly per the app owner's own note that Gemini quotas are
project-level, not per-key, so blind key rotation stays exactly as
conservative as before.

**Android 16 / manifest.** New permissions: `RECORD_AUDIO`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (the Android 14+
mandatory service-type permission for a microphone-capturing foreground
service), `POST_NOTIFICATIONS`. `LiveTranslationService` declares
`android:foregroundServiceType="microphone"`. `RECORD_AUDIO`/
`POST_NOTIFICATIONS` are requested at runtime only the first time the user
actually taps into Rozmowa or Na żywo — never at launch.

**Settings reorganization.** `activity_settings.xml` was regrouped under
six labeled headers (Tłumaczenie / Audio i Live / AI / Integracje /
Prywatność / Zaawansowane, new `TgGroupHeader` style) — a labeling and
grouping change only; every existing card, id, and Kotlin handler inside
`SettingsActivity.kt` is unchanged, plus one new card (headset-disconnect
behavior) under "Audio i Live".

**Everything the spec asked not to touch, confirmed untouched:** the `?xx`
trigger mechanism (`TriggerDetector`), the long-press bubble
(`BubbleTranslateGate`, `TranslationBubble`), `TextGateAccessibilityService`'s
event-gating chain, `SecureApiKeyStore`/`KeystoreCrypto` encryption,
`AppBlocklist`, the allow-list, `TranslationPrompts`' v1.7.1 gender logic,
and every pre-existing Settings string/behavior.

**What could not be verified in this environment, and must be checked on a
real device before release:** this sandbox has no Android SDK, no
compiler, no emulator, and no network path to a real Gemini Live endpoint
— exactly the same constraint noted throughout this file for every prior
change, but materially more consequential here. Verified by careful manual
review, exhaustive `when`-branch coverage (the Kotlin compiler's own
guarantee once compiled), and — for the pure, non-Android logic —real unit
tests (`GeminiClientTest`'s new `parseQuotaFailure` cases,
`ModelAvailabilityStoreTest`'s DST-crossing reset math independently
cross-checked against a Python `zoneinfo` simulation using epoch-timestamp
arithmetic, `GeminiLiveClientTest`'s message-parsing cases). NOT verified
by compilation or execution, and needing real-device confirmation: every
Gemini Live WebSocket message shape in `GeminiLiveClient` (built from the
field names specified plus this project's best understanding of the
BidiGenerateContent protocol — flagged in that file's own class doc as the
first thing to check against Google's current reference); `AudioRecord`/
`AudioTrack`/`AudioFocusRequest` behavior on a real device; Bluetooth/wired/
USB route detection and the headset-disconnect pause/resume flow; the
Foreground Service's behavior across real screen-off/lock/backgrounding;
the persistent notification's STOP action; and the Rozmowa "swap direction
by reconnecting" simplification noted above. No Gradle build, lint pass, or
test run was executed for this release — unlike section 15's verified CI
run, there is no green-build confirmation yet for versionCode 26 /
versionName "2.0.0".
