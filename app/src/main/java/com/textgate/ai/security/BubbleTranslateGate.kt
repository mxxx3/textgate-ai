package com.textgate.ai.security

import android.view.accessibility.AccessibilityNodeInfo

/**
 * The full "should this long-press be acted on at all" decision chain for
 * the floating-bubble translation feature — the counterpart to [EventGate],
 * but for a DIFFERENT reading pathway with a different shape.
 *
 * [EventGate] governs text the user is actively TYPING into an editable
 * field, triggered by an explicit `?en`/`?pl` suffix the user types on
 * purpose. This gate governs text the user did NOT write — a received
 * message or comment the user long-presses to have translated — triggered
 * by a gesture (long-press) rather than typed characters. Because the two
 * pathways read fundamentally different kinds of content under
 * fundamentally different triggers, they are kept as two separate classes
 * rather than one merged/branching one: mixing "editable field the user is
 * writing" and "arbitrary on-screen text the user is reading" logic into a
 * single gate would make it much harder to see, at a glance, exactly what
 * each pathway is and is not allowed to read.
 *
 * What both gates DO share, deliberately and identically:
 *   - the master AI on/off switch ([AppSettingsStore.isAiEnabled])
 *   - the hard-coded block-list ([AppBlocklist.isBlocked]) — password
 *     managers, authenticators, OS security surfaces, crypto wallets and
 *     exchanges are excluded here exactly as they are from the typed-
 *     trigger pathway
 *   - the user's own per-app allow-list ([AppSettingsStore.isPackageAllowed])
 *   - the password/sensitive-field checks in [SensitiveInputGuard]
 *
 * The one check this gate deliberately does NOT perform, unlike
 * [EventGate], is [SensitiveInputGuard.isEditableTextField] — requiring
 * editability would reject every legitimate use of this feature, since a
 * received message bubble is by definition NOT an editable field. This is
 * the sole intentional difference in the two gates' order of checks; every
 * other check below mirrors EventGate's order exactly, and
 * [SensitiveInputGuard.isSensitiveInput] is still called — and still
 * checked strictly before any `node.text` read — because a long-pressed
 * node CAN in principle be a password field (e.g. a masked value shown in
 * a list), and that must still never be read or sent anywhere.
 */
class BubbleTranslateGate(
    private val settingsStore: AppSettingsStore,
    private val ownPackageName: String
) {

    sealed class Decision {
        /** Nothing will happen; [reason] is for tests/debugging only and
         * is never shown to the user or logged in production. */
        data class Blocked(val reason: String) : Decision()

        /** Safe to translate. [text] is the long-pressed node's full text,
         * captured here so callers don't need a second, separate
         * `node.text` read after this decision is returned. */
        data class Ready(val text: String) : Decision()
    }

    fun evaluate(packageName: String?, node: AccessibilityNodeInfo?): Decision {
        if (packageName.isNullOrBlank()) return Decision.Blocked("no package name")
        if (!settingsStore.isAiEnabled) return Decision.Blocked("ai disabled")
        if (AppBlocklist.isBlocked(packageName, ownPackageName)) return Decision.Blocked("blocklisted app")
        if (!settingsStore.isPackageAllowed(packageName)) return Decision.Blocked("app not allow-listed")
        if (node == null) return Decision.Blocked("no source node")
        // Deliberately NO isEditableTextField check here — see class doc.
        if (SensitiveInputGuard.isSensitiveInput(node)) return Decision.Blocked("sensitive field")

        // Only now — after every gate above — is node.text read.
        val text = node.text?.toString()
        if (text.isNullOrBlank()) return Decision.Blocked("empty text")
        if (text.length > TriggerDetector.MAX_INPUT_LENGTH) return Decision.Blocked("too long")

        return Decision.Ready(text)
    }
}
