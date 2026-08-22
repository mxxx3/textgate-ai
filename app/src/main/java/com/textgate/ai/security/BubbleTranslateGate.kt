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
 *
 * ### Where the text actually lives
 *
 * Confirmed on a real device (via a temporary diagnostic, since removed):
 * in WhatsApp, the node the OS reports as the long-press target is the
 * message's outer container row, not the text itself — its own `text` is
 * blank, while the actual message body lives in one of its child nodes,
 * alongside other short text like a timestamp or sender name. Telegram, by
 * contrast, reports a node whose own `text` already IS the message.
 *
 * To handle both shapes without weakening any check, [evaluate] falls back
 * to [findLongestSafeText] — a search of the long-pressed node's own
 * DESCENDANTS ONLY (never siblings, never ancestors, never a different
 * window; still no [AccessibilityService.getWindows]/`getRootInActiveWindow`
 * call anywhere in this app) — used only when the node's own text is blank.
 * Every node visited during that search, not just the top-level one, is
 * still run through [SensitiveInputGuard.isSensitiveInput] before its text
 * is read, so a sensitive child (however unlikely inside a chat bubble) is
 * skipped exactly as it would be at the top level. The search is bounded
 * ([MAX_TRAVERSAL_DEPTH] / [MAX_TRAVERSAL_NODES]) so it can never become an
 * unbounded walk, and picks the LONGEST safe text found — a message body is
 * reliably longer than a timestamp or short sender label, which is the
 * practical heuristic this relies on.
 */
class BubbleTranslateGate(
    private val settingsStore: AppSettingsStore,
    private val ownPackageName: String
) {

    sealed class Decision {
        /** Nothing will happen; [reason] is for tests/debugging only and
         * is never shown to the user or logged in production. */
        data class Blocked(val reason: String) : Decision()

        /** Safe to translate. [text] is the resolved message text — either
         * the long-pressed node's own text, or the longest safe candidate
         * found among its descendants (see class doc). */
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
        var text = node.text?.toString()
        if (text.isNullOrBlank()) {
            // The long-pressed node itself carries no text — some apps'
            // long-press target is a container row rather than the text
            // (see class doc "Where the text actually lives"). Search its
            // own children, bounded and downward-only, before giving up.
            text = findLongestSafeText(node)
        }
        if (text.isNullOrBlank()) return Decision.Blocked("empty text")
        if (text.length > TriggerDetector.MAX_INPUT_LENGTH) return Decision.Blocked("too long")

        return Decision.Ready(text)
    }

    /**
     * Breadth-first search of [root]'s descendants (never [root] itself —
     * the caller already checked that) for the longest non-blank text that
     * also passes [SensitiveInputGuard.isSensitiveInput]. Returns `null` if
     * nothing qualifies. Every node this function obtains via
     * [AccessibilityNodeInfo.getChild] is recycled before returning,
     * including any left unvisited because [MAX_TRAVERSAL_NODES] was hit —
     * nothing obtained here is ever handed back to the caller or leaked.
     */
    private fun findLongestSafeText(root: AccessibilityNodeInfo): String? {
        var best: String? = null
        var visited = 0
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()

        try {
            val childCount = root.childCount
            for (i in 0 until childCount) {
                root.getChild(i)?.let { queue.add(it to 1) }
            }
        } catch (_: Exception) {
            return null
        }

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (visited >= MAX_TRAVERSAL_NODES) {
                recycleSafely(node)
                continue
            }
            visited++
            try {
                if (!SensitiveInputGuard.isSensitiveInput(node)) {
                    val candidate = node.text?.toString()
                    if (!candidate.isNullOrBlank() &&
                        (best == null || candidate.length > best!!.length)
                    ) {
                        best = candidate
                    }
                }
                if (depth < MAX_TRAVERSAL_DEPTH) {
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        node.getChild(i)?.let { queue.add(it to depth + 1) }
                    }
                }
            } catch (_: Exception) {
                // Fail closed for this one node: skip it as a text
                // candidate, but do not abort the rest of the search.
            } finally {
                recycleSafely(node)
            }
        }

        return best
    }

    private fun recycleSafely(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
            // recycle() is a deprecated no-op on modern API levels; ignore
            // any failure either way.
        }
    }

    companion object {
        /** How many levels below the long-pressed node this search will
         * descend. Chosen generously enough for a typical chat-bubble
         * view hierarchy (row → column → text) while still being a firm,
         * small bound. */
        private const val MAX_TRAVERSAL_DEPTH = 6

        /** Hard cap on how many descendant nodes a single search will ever
         * visit, regardless of how wide or deep the tree is — the backstop
         * that keeps this a bounded search rather than an open-ended walk. */
        private const val MAX_TRAVERSAL_NODES = 60
    }
}
