plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.textgate.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.textgate.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug builds are for local development only and are never
            // distributed. applicationIdSuffix keeps them installable
            // side-by-side with a release build without colliding.
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
    // --- PRODUCTION DEPENDENCIES: INTENTIONALLY NONE. ---
    // This app ships with zero third-party runtime libraries. Every runtime
    // capability it needs (encryption, HTTPS, UI widgets, background work,
    // debouncing) is available directly from the Android SDK and the Kotlin
    // standard library, both of which are unavoidable and are pulled in
    // automatically by the Android Gradle Plugin — they are not listed here
    // because there is nothing to choose or version-pin.
    //
    // This is a deliberate security decision, not an oversight: every extra
    // dependency is additional code that (a) can change behavior on its own
    // release schedule, (b) pulls in its own transitive dependencies that
    // must also be audited, and (c) is one more supply-chain path into an
    // app that regularly sees passwords, PINs and private messages pass
    // through the device. See README.md "Dependency report" for the full
    // reasoning and the (empty) transitive dependency tree.

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
