package com.textgate.ai.security

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Centralizes the full "should this event be acted on at all" decision
 * chain used by TextGateAccessibilityService, as its own directly
 * unit-testable class — not a parallel re-implementation the service
 * happens to also follow, but the actual code the service calls.
 *
 * Order is significant and mirrors the security spec exactly:
 *   1. Package name present at all.
 *   2. Master AI switch on.
 *   3. Package not on the hard-coded block-list.
 *   4. Package present in the user's own allow-list.
 *   5. A source node exists.
 *   6. The node is an editable text field.
 *   7. The node is NOT a sensitive/password field — checked LAST, and
 *      still strictly before any `node.text` read, which only happens
 *      inside [TriggerDetector.detect] on the next line once every guard
 *      above has passed.
 */
class EventGate(
    private val settingsStore: AppSettingsStore,
    private val ownPackageName: String
) {

    sealed class Decision {
        /** Nothing will happen; [reason] is for tests/debugging only and
         * is never shown to the user or logged in production. */
        data class Blocked(val reason: String) : Decision()
        data object NotTriggered : Decision()
        data class TooLong(val length: Int, val limit: Int) : Decision()
        /** Trigger matched and content is within limits — safe to send.
         * [fullText] is the complete field text at evaluation time
         * (content + trigger), captured here so callers don't need a
         * second, separate `node.text` read to snapshot it. [target] is
         * which language the matched trigger asks for (see
         * [TriggerDetector.Target]). */
        data class Ready(
            val content: String,
            val fullText: String,
            val target: TriggerDetector.Target
        ) : Decision()
    }

    fun evaluate(packageName: String?, node: AccessibilityNodeInfo?): Decision {
        if (packageName.isNullOrBlank()) return Decision.Blocked("no package name")
        if (!settingsStore.isAiEnabled) return Decision.Blocked("ai disabled")
        if (AppBlocklist.isBlocked(packageName, ownPackageName)) return Decision.Blocked("blocklisted app")
        if (!settingsStore.isPackageAllowed(packageName)) return Decision.Blocked("app not allow-listed")
        if (node == null) return Decision.Blocked("no source node")
        if (!SensitiveInputGuard.isEditableTextField(node)) return Decision.Blocked("not editable")
        if (SensitiveInputGuard.isSensitiveInput(node)) return Decision.Blocked("sensitive field")

        // Only now — after every gate above — is node.text read.
        val fullText = node.text
        return when (val outcome = TriggerDetector.detect(fullText)) {
            TriggerDetector.Outcome.NoTrigger,
            TriggerDetector.Outcome.EmptyContent -> Decision.NotTriggered
            is TriggerDetector.Outcome.TooLong -> Decision.TooLong(outcome.length, outcome.limit)
            is TriggerDetector.Outcome.Ready ->
                Decision.Ready(outcome.content, fullText.toString(), outcome.target)
        }
    }
}
