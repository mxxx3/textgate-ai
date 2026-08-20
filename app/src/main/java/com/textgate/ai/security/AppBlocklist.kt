package com.textgate.ai.security

/**
 * Hard-coded packages/categories that can NEVER be added to the user
 * allow-list, and are rejected even before the allow-list is consulted at
 * runtime (see TextGateAccessibilityService). This list is deliberately
 * biased toward over-blocking: a legitimate app being wrongly excluded is
 * an inconvenience the user can raise; a password manager or banking app
 * being wrongly included is a security incident.
 *
 * IMPORTANT LIMITATION, stated plainly rather than implied: there is no
 * reliable, general way for an app to know "this package is a bank" —
 * there are thousands of banking apps worldwide and no public, on-device
 * category API exposes this. This list is therefore a best-effort
 * combination of:
 *   1. An exact-match list of well-known password manager, authenticator,
 *      and OS security-surface packages.
 *   2. A conservative keyword heuristic over the package name itself
 *      (e.g. containing "bank", "authenticator", "password", "wallet").
 *
 * This is defense in depth, not the primary control. The primary control
 * is that the allow-list defaults to empty and only the user can add an
 * app to it — TextGate AI never auto-allows anything. Combined with the
 * SensitiveInputGuard field-level checks (which fire regardless of which
 * app is focused), a banking app that is missed by this list is still
 * protected at the field level for its actual password/PIN entry.
 */
object AppBlocklist {

    private val EXACT_BLOCKED: Set<String> = setOf(
        // Password managers
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.agilebits.onepassword",
        "com.x8bit.bitwarden",
        "com.keepersecurity.android",
        "com.nordpass.app",
        "me.proton.android.pass",
        "com.enpass.app",
        "com.siber.roboform",
        "com.samsung.android.samsungpass",
        "com.google.android.apps.password.manager",

        // Authenticator / 2FA apps
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.microsoft.authenticator",
        "com.authy.authy",
        "com.duosecurity.duomobile",
        "com.twilio.authy",

        // OS security surfaces — never text-input targets we should touch
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.credentialmanager",
        "com.android.intentresolver",
        "com.android.packageinstaller",
        "android"
    )

    /**
     * Package-name substrings that cause a package to be blocked even if
     * it is not in [EXACT_BLOCKED]. Matched case-insensitively against the
     * full package name. Kept short and high-signal on purpose — a broad
     * word list would start rejecting unrelated apps whose package happens
     * to share a substring.
     */
    private val KEYWORD_BLOCKED: List<String> = listOf(
        "authenticator",
        "password",
        "1password",
        "keychain",
        "passmanager",
        "bank",
        "banking",
        "wallet",
        "vault",
        "seedvault"
    )

    /** Our own package is always implicitly excluded by callers; listed
     * again here defensively in case this list is ever consulted alone. */
    fun isBlocked(packageName: String, ownPackageName: String? = null): Boolean {
        if (packageName.isBlank()) return true
        if (ownPackageName != null && packageName == ownPackageName) return true
        if (EXACT_BLOCKED.contains(packageName)) return true
        val lower = packageName.lowercase()
        return KEYWORD_BLOCKED.any { lower.contains(it) }
    }
}
