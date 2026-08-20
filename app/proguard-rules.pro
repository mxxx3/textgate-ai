# TextGate AI — R8 / ProGuard rules
#
# This app has no reflection-based libraries (no Gson/Moshi/Retrofit/Room),
# so there is very little that needs an explicit -keep rule. The rules
# below exist for two purposes: (1) guarantee release builds cannot emit
# log output even if a future change accidentally adds a Log.* call, and
# (2) document, rather than silently rely on, the small number of
# framework-mandated keep rules the Android Gradle Plugin already applies
# automatically (manifest-declared Activity/Service classes).

# ---------------------------------------------------------------------------
# 1. Strip ALL android.util.Log calls from release builds, unconditionally.
#    This app's source does not call Log.* with any sensitive value today —
#    see the "Logging" section of README.md — but this rule makes that a
#    property of the release build itself, not just of code review, so a
#    future accidental Log.d(...) of request/response/API-key data is
#    removed by the compiler rather than shipped.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# ---------------------------------------------------------------------------
# 2. Keep line numbers for readable stack traces in local debug testing,
#    but do not keep source file names (avoids leaking exact file layout
#    in a stripped release build's stack traces).
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 3. org.json (org.json.JSONObject / JSONArray) ships as part of the
#    Android platform itself (android.jar), not as a library this app
#    bundles, so it needs no keep/shrink rule here.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 4. Kotlin metadata: keep enough for kotlin-reflect-free operation (this
#    app does not use kotlin-reflect). No explicit rule required beyond
#    AGP's Kotlin defaults.
# ---------------------------------------------------------------------------
