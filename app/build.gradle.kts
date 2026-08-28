plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.textgate.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.textgate.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "2.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // A FIXED debug-signing key, checked into the repo as
            // app/debug.keystore (see .gitignore's `!debug.keystore`
            // exception to the general `*.keystore` rule — this file is
            // deliberately tracked). Without this override, Android Gradle
            // Plugin falls back to `~/.android/debug.keystore`, which it
            // silently auto-generates WITH A FRESH RANDOM KEY the first
            // time it's missing — true on every GitHub Actions run, since
            // CI runners start from a clean image and nothing here ever
            // persisted one. That meant every CI build produced a debug
            // APK signed with a different key, so a signing-key fingerprint
            // registered once (e.g. for Android Developer Verification's
            // limited-distribution device authorization) would silently
            // stop matching the very next build.
            //
            // This key is intentionally NOT a secret: it is a debug-only
            // signing key, never used for the release build type, holds no
            // production trust, and committing a shared debug keystore for
            // exactly this reproducibility reason is standard, widely-used
            // Android practice. `storePassword`/`keyAlias`/`keyPassword`
            // below match the values every default AGP-generated debug
            // keystore already uses — nothing here is a new secret to
            // protect, only a fixed replacement for a value that used to
            // be random.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Release ("upload key") signing config. Deliberately reads
        // everything from environment variables instead of hardcoding a
        // path or password here — this file is committed to the repo, and
        // the real keystore + passwords must never be. In CI, these four
        // variables are populated by .github/workflows/release-bundle.yml
        // from GitHub Secrets (see that file for the one-time setup this
        // requires). For a local `./gradlew bundleRelease`, export the same
        // four variables yourself (see
        // release-signing/KEYSTORE_CREDENTIALS_README.txt for the values).
        //
        // If RELEASE_KEYSTORE_PATH is unset, this config is simply never
        // assigned to the release build type below — `bundleRelease` still
        // succeeds locally without it, it just produces an UNSIGNED bundle
        // (fine for local inspection; Play Console will reject it as-is).
        val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only assigned when the environment provides real signing
            // material (see signingConfigs above) — keeps a plain local
            // `bundleRelease` working (unsigned output) when no keystore is
            // configured, while CI always has it set.
            if (System.getenv("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Debug builds are for local development only and are never
            // distributed. applicationIdSuffix keeps them installable
            // side-by-side with a release build without colliding.
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Explicit security-relevant checks kept ON (they are on by default,
        // listed here so the intent is visible on review).
        disable += setOf("GoogleAppIndexingWarning")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // --- PRODUCTION DEPENDENCIES: exactly one, deliberately scoped. ---
    // This app shipped with zero third-party runtime libraries through
    // v1.7.1. As of v2, ONE dependency is added — OkHttp — used for exactly
    // one thing: the WebSocket connection to the Gemini Live API that
    // powers the "Rozmowa" and "Na żywo" real-time voice-translation modes
    // (see com.textgate.ai.live.GeminiLiveClient). Every other capability
    // this app needs (encryption, the existing synchronous HTTPS
    // translation calls, UI widgets, background work, debouncing) remains
    // hand-rolled on the Android SDK and Kotlin standard library exactly as
    // before — this is NOT a reversal of the zero-dependency policy, it is
    // one narrow, documented exception.
    //
    // Why OkHttp specifically, here, when nothing else in this app needed
    // an exception: the Android SDK has no built-in WebSocket client at
    // all. The only zero-dependency alternative is hand-rolling the RFC
    // 6455 handshake and frame format (masking, fragmentation, ping/pong,
    // close codes) over a raw SSLSocket — for a feature that streams live
    // microphone audio bidirectionally and must reconnect cleanly under
    // real network conditions (see LiveTranslationService's reconnect
    // logic), a subtle framing bug is the kind of failure that is silent,
    // hard to reproduce, and cannot be exercised at all in this project's
    // development environment (no physical device, no live Gemini Live
    // endpoint to test against here). OkHttp's WebSocket implementation is
    // widely used, has years of hardening against exactly these edge cases,
    // and is a single, self-contained, actively audited library — a
    // meaningfully smaller supply-chain surface than an equivalent
    // hand-written implementation would be untested risk. See README.md
    // "Dependency report" for the full reasoning.
    //
    // Everything else keeps the original reasoning: every other runtime
    // capability is available directly from the Android SDK and the Kotlin
    // standard library, both of which are unavoidable and are pulled in
    // automatically by the Android Gradle Plugin — they are not listed here
    // because there is nothing to choose or version-pin.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Test-only dependencies. Gradle's testImplementation scope is
    // compile+runtime for the unit test task ONLY — none of these classes
    // are compiled into, or shipped inside, the app's APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")

    // --- Instrumented-test-only dependencies (src/androidTest). These run
    // only via `connectedAndroidTest` against a real device/emulator (for
    // the one test — KeystoreCryptoInstrumentedTest — that needs the real
    // AndroidKeyStore provider) and are never compiled into the app APK.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
