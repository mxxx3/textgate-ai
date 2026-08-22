package com.textgate.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.textgate.ai.R
import com.textgate.ai.model.TranslationPrompts
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.security.AppBlocklist
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.BubbleTranslateGate
import com.textgate.ai.security.EventGate
import com.textgate.ai.security.ResultPolicy
import com.textgate.ai.security.SecureApiKeyStore
import com.textgate.ai.security.SensitiveInputGuard
import com.textgate.ai.security.TriggerDetector
import com.textgate.ai.util.Debouncer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two independent read-and-translate pipelines live in this one service,
 * deliberately kept together rather than split into separate services: the
 * typed "?en"/"?pl" trigger pipeline (read the field, notice the trigger,
 * call Gemini, replace the text) and the long-press "translate what's
 * under my finger" bubble pipeline (long-press a message, call Gemini,
 * show a floating translation). Splitting them apart would make it harder,
 * not easier, to see the full set of ways this service ever touches
 * on-screen text.
 *
 * READ THIS BEFORE MODIFYING:
 *   - Typed-trigger path: every path that reaches `node.text` MUST have
 *     already passed [AppBlocklist.isBlocked], [AppSettingsStore.isPackageAllowed],
 *     [SensitiveInputGuard.isEditableTextField], and
 *     [SensitiveInputGuard.isSensitiveInput], IN THAT ORDER, with the
 *     sensitivity check happening last and always BEFORE the text read —
 *     enforced by [EventGate].
 *   - Long-press bubble path: every path that reaches `node.text` MUST
 *     have already passed the equivalent chain in [BubbleTranslateGate] —
 *     the same block-list, the same allow-list, the same master switch,
 *     and the same [SensitiveInputGuard.isSensitiveInput] check (this path
 *     intentionally skips ONLY the editability check, since its entire
 *     purpose is reading non-editable received content — see
 *     BubbleTranslateGate's class doc for why that is safe).
 *   - Four event types are currently subscribed to:
 *     [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED],
 *     [AccessibilityEvent.TYPE_VIEW_LONG_CLICKED],
 *     [AccessibilityEvent.TYPE_VIEW_SELECTED], and — TEMPORARILY, as of
 *     v1.2.7, for diagnostics only —
 *     [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED] (see
 *     accessibility_service_config.xml and README.md §14 "Fourth
 *     amendment") — this service never calls getWindows() or
 *     getRootInActiveWindow(), and never enumerates any window other than
 *     the one that raised the event. It only ever inspects `event.source`
 *     for the event actually delivered, and — for the long-press bubble
 *     path ONLY, when that node's own text is blank — that SAME node's
 *     own bounded set of descendants (see [BubbleTranslateGate]'s "Where
 *     the text actually lives" doc section). The typed-trigger path never
 *     does even that much: it only ever reads `event.source` directly,
 *     nothing further. The v4 diagnostic handler for
 *     TYPE_WINDOW_CONTENT_CHANGED also only ever inspects `event.source`
 *     — never any other node — and deliberately never displays that
 *     node's actual text/contentDescription content, only whether each is
 *     present and how long it is.
 *   - No AccessibilityEvent is ever stored past the return of
 *     onAccessibilityEvent(); only a single AccessibilityNodeInfo may be
 *     held briefly (bounded by [DEBOUNCE_MS] plus one network round trip
 *     for the typed-trigger path, or just one network round trip for the
 *     long-press path) while a single request is being processed, and it
 *     is released the moment that processing ends, succeeds, fails, or is
 *     aborted.
 *   - Both pipelines share one [requestInFlight] guard — only one AI
 *     request (of either kind) is ever in flight at a time.
 *   - Any exception, anywhere in either pipeline, results in doing nothing
 *     further (fail closed) — never in retrying, never in falling back to
 *     a broader read.
 *
 * The long-press bubble pathway does not work in every app — see README.md
 * §14 "Third amendment to Feature update #6" for a full, evidence-based
 * account of the investigation into Google Messages (SMS) and X/Twitter,
 * both of which appear to be built with Jetpack Compose in a way that
 * makes a real physical long-press architecturally invisible to
 * [AccessibilityEvent.TYPE_VIEW_LONG_CLICKED] and
 * [AccessibilityEvent.TYPE_VIEW_SELECTED]. That investigation was
 * concluded and closed as an accepted limitation in v1.2.6 — then reopened
 * in v1.2.7 after a new, source-verified lead (see README.md §14 "Fourth
 * amendment") showed the earlier [AccessibilityEvent.TYPE_VIEW_SELECTED]
 * hypothesis was testing the wrong signal, and that the previously-tried
 * [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED] diagnostic (v1.2.5) may
 * not have been generic noise after all — it just wasn't inspecting the
 * right properties of `event.source`. v1.2.7 re-adds a narrower, better
 * targeted diagnostic to actually test that before drawing any final
 * conclusion. v1.2.8 then fixed a real regression discovered on-device in
 * v1.2.7: the diagnostic fired for every allow-listed app, not just the
 * two under investigation, producing a disruptive false-looking
 * "translation" bubble in TikTok. It is now hard-restricted to exactly
 * [DIAGNOSTIC_TARGET_PACKAGES] — see README.md §14 "Fifth amendment".
 */
class TextGateAccessibilityService : AccessibilityService() {

    private lateinit var settingsStore: AppSettingsStore
    private lateinit var apiKeyStore: SecureApiKeyStore
    private lateinit var eventGate: EventGate
    private lateinit var bubbleGate: BubbleTranslateGate
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debouncer = Debouncer(DEBOUNCE_MS)

    /** TEMPORARY (v4 diagnostic, v1.2.7) — collapses a burst of rapid-fire
     * TYPE_WINDOW_CONTENT_CHANGED events (there are usually several per
     * user interaction) down to just the LAST one's snapshot, shown once
     * things go quiet for [DIAGNOSTIC_DEBOUNCE_MS]. Entirely separate from
     * [debouncer] — this one never holds a node, only a plain summary
     * String, since the node is always recycled before scheduling. */
    private val diagnosticDebouncer = Debouncer(DIAGNOSTIC_DEBOUNCE_MS)

    private val bubble by lazy { TranslationBubble(this) }

    /** True while a single trigger's network round trip is in flight. Also
     * doubles as the "one operation at a time" guard: a new trigger is
     * ignored entirely while this is true, rather than queued. */
    private val requestInFlight = AtomicBoolean(false)

    /** The node currently captured by a scheduled-but-not-yet-fired debounce
     * callback, if any. Tracked explicitly so that when a newer event
     * supersedes it (see [cancelPendingDebounce]), that OLDER node is still
     * recycled rather than silently dropped when its now-cancelled Runnable
     * never runs. */
    private var pendingNode: AccessibilityNodeInfo? = null

    private var networkExecutor: ExecutorService? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = AppSettingsStore(applicationContext)
        apiKeyStore = SecureApiKeyStore(applicationContext)
        eventGate = EventGate(settingsStore, applicationContext.packageName)
        bubbleGate = BubbleTranslateGate(settingsStore, applicationContext.packageName)
        networkExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onInterrupt() {
        cancelPendingDebounce()
        diagnosticDebouncer.cancel()
        bubble.dismiss()
    }

    override fun onDestroy() {
        cancelPendingDebounce()
        diagnosticDebouncer.cancel()
        bubble.dismiss()
        networkExecutor?.shutdownNow()
        networkExecutor = null
        super.onDestroy()
    }

    /** Cancels any scheduled-but-not-fired debounce action AND recycles the
     * node it was holding, if any. Every call site that supersedes or
     * abandons a pending debounce must go through this — never call
     * `debouncer.cancel()` directly, or the superseded node leaks. */
    private fun cancelPendingDebounce() {
        debouncer.cancel()
        pendingNode?.let { recycleSafely(it) }
        pendingNode = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (_: Exception) {
            // Fail closed: swallow and do nothing further for this event.
            // No content, stack trace, or field state is logged here even
            // in debug builds — see README "Logging" section.
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::settingsStore.isInitialized || !::apiKeyStore.isInitialized ||
            !::eventGate.isInitialized || !::bubbleGate.isInitialized
        ) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            // Both routed to the same handler: TYPE_VIEW_LONG_CLICKED is the
            // standard long-press signal (works in WhatsApp/Telegram);
            // TYPE_VIEW_SELECTED is a fallback for apps whose long-press
            // enters a multi-select mode instead, without ever dispatching
            // TYPE_VIEW_LONG_CLICKED — see accessibility_service_config.xml.
            // handleLongClick() itself doesn't need to know or care which
            // of the two fired; the gating and text-resolution logic is
            // identical either way.
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> handleLongClick(event)
            // TEMPORARY (v4 diagnostic, v1.2.7) — see
            // handleComposeSelectionDiagnostic()'s doc for the full
            // rationale. Re-added to test a source-verified hypothesis
            // after the v1.2.5 attempt was found to have been looking at
            // the wrong node properties, not necessarily a dead end.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleComposeSelectionDiagnostic(event)
            else -> return
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        // Cheapest possible short-circuit, before ever touching
        // event.source: only one trigger is processed at a time.
        if (requestInFlight.get()) return

        val packageName = event.packageName?.toString()
        val node = event.source

        // EventGate performs every remaining check — block-list,
        // allow-list, master switch, editability, sensitivity — and only
        // reads node.text internally after ALL of those pass.
        when (val decision = eventGate.evaluate(packageName, node)) {
            is EventGate.Decision.Ready -> {
                // Safe: EventGate.evaluate() only returns Ready when both
                // packageName and node were non-null (see EventGate.kt) —
                // asserted here rather than re-checked, since the checks
                // already happened inside evaluate().
                val safeNode = node!!
                val snapshot = decision.fullText
                // Supersede (and properly recycle) any earlier pending
                // debounce before taking on this new one — see
                // cancelPendingDebounce() for why this must not be skipped.
                cancelPendingDebounce()
                pendingNode = safeNode
                // The node is handed off to the debounced pipeline; it is
                // recycled exactly once, either here if superseded, or at
                // the end of the pipeline (see handleResult()).
                debouncer.schedule {
                    pendingNode = null
                    confirmAndProcess(packageName!!, safeNode, snapshot, decision.content, decision.target)
                }
            }
            is EventGate.Decision.TooLong -> {
                cancelPendingDebounce()
                showToast(getString(R.string.error_text_too_long, decision.limit))
                node?.let { recycleSafely(it) }
            }
            EventGate.Decision.NotTriggered,
            is EventGate.Decision.Blocked -> {
                node?.let { recycleSafely(it) }
            }
        }
    }

    /**
     * Handles a long-press on any view, anywhere the service is receiving
     * events. No debounce is used here (unlike the typed-trigger path) —
     * a long-press is already a single, deliberate, discrete gesture, not
     * a stream of rapid-fire events that needs settling.
     */
    private fun handleLongClick(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val node = event.source

        // Same cheap short-circuit as the typed-trigger path: only one AI
        // request of either kind runs at a time.
        if (requestInFlight.get()) {
            node?.let { recycleSafely(it) }
            return
        }

        // BubbleTranslateGate performs every remaining check — block-list,
        // allow-list, master switch, sensitivity (deliberately NOT
        // editability — see its class doc) — and only reads node.text
        // internally after all of those pass.
        when (val decision = bubbleGate.evaluate(packageName, node)) {
            is BubbleTranslateGate.Decision.Ready -> {
                // Bounds must be captured before the node is recycled —
                // they anchor where the bubble appears on screen. This is
                // screen geometry, not field content, so capturing it does
                // not itself count as "reading" anything sensitive.
                val bounds = Rect()
                try {
                    node?.getBoundsInScreen(bounds)
                } catch (_: Exception) {
                    // Leave bounds as the zero-rect default; the bubble's
                    // own clamping still keeps it fully on screen.
                }
                node?.let { recycleSafely(it) }
                startBubbleTranslation(decision.text, bounds)
            }
            is BubbleTranslateGate.Decision.Blocked -> {
                node?.let { recycleSafely(it) }
            }
        }
    }

    /**
     * TEMPORARY DIAGNOSTIC (v4, v1.2.7) — see README.md §14 "Fourth
     * amendment" for the full background. Re-added after directly
     * verifying (against the actual AndroidX Compose source, not just
     * trusting an outside claim) two things that change what the v1.2.5
     * TYPE_WINDOW_CONTENT_CHANGED diagnostic's "generic-looking" output
     * actually proved:
     *
     *   1. `AndroidComposeViewAccessibilityDelegateCompat` maps a
     *      non-Tab-role selectable element's selection state to
     *      `isChecked` (via `info.isChecked = it`), NOT `isSelected` —
     *      confirmed in source as `if (role == Role.Tab) { info.isSelected
     *      = it } else { info.isChecked = it }`. A selected chat message
     *      row is not a Tab, so the v1.2.4 TYPE_VIEW_SELECTED attempt was
     *      testing a signal that this specific kind of element would
     *      never surface via isSelected in the first place — regardless
     *      of whether the right event type fires for it.
     *   2. Compose addresses a specific virtual semantics node via
     *      `event.setSource(view, virtualViewId)` — confirmed present in
     *      source. This means `event.source` CAN legitimately reference
     *      one exact message row even while `event.className` still
     *      reports the generic "android.view.View" seen in the v1.2.5
     *      diagnostic output. A generic className alone does not prove
     *      the source node itself carries no specific information — v3
     *      never actually checked the node's own properties, only its
     *      className and the raw fact that *an* event fired.
     *
     * This handler tests that directly: it inspects `event.source` — the
     * one node that raised THIS event, nothing else, no window
     * enumeration — for `isChecked`, `isSelected`, `isLongClickable`,
     * whether its action list contains ACTION_LONG_CLICK, and whether
     * `text`/`contentDescription` are present, all gated behind the same
     * block-list / allow-list / master-switch checks as every other path,
     * with [SensitiveInputGuard.isSensitiveInput] checked before even the
     * *length* of text/contentDescription is read (a sensitive field
     * reports as unavailable, not as length 0).
     *
     * What is deliberately NOT done, even temporarily: the actual text or
     * contentDescription content is never shown on screen — only whether
     * each is present, and how long it is. If this diagnostic shows a
     * non-trivial textLen or descLen lining up with a real long-press on
     * a message, that alone is strong enough evidence to justify building
     * a real (non-diagnostic) extraction path next — there is no need to
     * display the private message text itself just to confirm the
     * hypothesis.
     */
    private fun handleComposeSelectionDiagnostic(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (packageName.isNullOrBlank()) return
        // v1.2.8 fix: this diagnostic exists ONLY to investigate Google
        // Messages and X — it must never fire for any other app, even one
        // on the user's own allow-list. v1.2.7 mistakenly gated this on
        // settingsStore.isPackageAllowed(packageName) alone, which meant it
        // fired for EVERY allow-listed app (confirmed on-device: it fired
        // constantly in TikTok while scrolling comments / tapping buttons,
        // since TYPE_WINDOW_CONTENT_CHANGED is just as generic there as it
        // is everywhere else — see README.md §14 "Fifth amendment"). The
        // cheapest possible check goes first, before touching settingsStore
        // or event.source at all.
        if (packageName !in DIAGNOSTIC_TARGET_PACKAGES) return
        if (!::settingsStore.isInitialized) return
        if (!settingsStore.isAiEnabled) return
        if (AppBlocklist.isBlocked(packageName, applicationContext.packageName)) return
        if (!settingsStore.isPackageAllowed(packageName)) return

        val node = event.source ?: return
        try {
            val sensitive = try {
                SensitiveInputGuard.isSensitiveInput(node)
            } catch (_: Exception) {
                true // fail closed: if the check itself fails, treat as sensitive
            }
            val textLen = if (sensitive) -2 else (node.text?.length ?: -1)
            val descLen = if (sensitive) -2 else (node.contentDescription?.length ?: -1)
            val hasLongClickAction = try {
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
            } catch (_: Exception) {
                false
            }
            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: Exception) {
                // Leave as the zero-rect default; still shown, just
                // anchored toward the top-left instead of near the node.
            }
            val className = node.className?.toString().orEmpty()
            val viewId = try {
                node.viewIdResourceName.orEmpty()
            } catch (_: Exception) {
                ""
            }

            val summary = buildString {
                append("DIAG v4  pkg=").append(packageName).append('\n')
                append("checked=").append(node.isChecked)
                append(" selected=").append(node.isSelected)
                append(" longClk=").append(node.isLongClickable)
                append(" hasLCAction=").append(hasLongClickAction).append('\n')
                append("textLen=").append(textLen)
                append(" descLen=").append(descLen).append('\n')
                append("id=").append(viewId.ifBlank { "-" }).append('\n')
                append("cls=").append(className)
            }

            // The debouncer's own Handler already runs on the main
            // looper, so this callback can call bubble.showResult()
            // directly — no extra mainHandler.post() needed.
            diagnosticDebouncer.schedule { bubble.showResult(bounds, summary) }
        } finally {
            recycleSafely(node)
        }
    }

    /** No AccessibilityNodeInfo is held past this point — [text] and
     * [anchor] are plain values, not a live reference into another app's
     * window, so there is nothing left here to recycle or refresh. */
    private fun startBubbleTranslation(text: String, anchor: Rect) {
        if (!requestInFlight.compareAndSet(false, true)) return

        try {
            val apiKey = apiKeyStore.getApiKey()
            if (apiKey.isNullOrBlank()) {
                requestInFlight.set(false)
                showToast(getString(R.string.error_no_api_key))
                return
            }
            val model = settingsStore.selectedModel
            val target = settingsStore.bubbleTargetLanguage
            val systemPrompt = when (target) {
                TriggerDetector.Target.ENGLISH -> TranslationPrompts.EN_TRANSLATION_SYSTEM_PROMPT
                TriggerDetector.Target.POLISH -> TranslationPrompts.PL_TRANSLATION_SYSTEM_PROMPT
            }

            val executor = networkExecutor
            if (executor == null || executor.isShutdown) {
                requestInFlight.set(false)
                return
            }

            mainHandler.post { bubble.showLoading(anchor) }

            executor.execute {
                val result = try {
                    GeminiClient.translateBlocking(
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = systemPrompt,
                        userText = text
                    )
                } catch (_: Exception) {
                    GeminiClient.Result.Failure.InvalidResponse
                }
                mainHandler.post {
                    requestInFlight.set(false)
                    when (result) {
                        is GeminiClient.Result.Success -> bubble.showResult(anchor, result.translatedText)
                        is GeminiClient.Result.Failure -> bubble.showError(anchor, mapFailureMessage(result))
                    }
                }
            }
        } catch (_: Exception) {
            requestInFlight.set(false)
        }
    }

    /**
     * Runs after the debounce delay. Re-validates everything from scratch
     * (settings may have changed mid-debounce; the field may have changed
     * mid-debounce) before a single byte is sent anywhere.
     */
    private fun confirmAndProcess(
        packageName: String,
        node: AccessibilityNodeInfo,
        originalFullText: String,
        content: String,
        target: TriggerDetector.Target
    ) {
        if (!requestInFlight.compareAndSet(false, true)) {
            recycleSafely(node)
            return
        }

        val abort: () -> Unit = {
            requestInFlight.set(false)
            recycleSafely(node)
        }

        try {
            if (!settingsStore.isAiEnabled) return abort()
            if (!settingsStore.isPackageAllowed(packageName)) return abort()

            // Re-validate the node is still the same field with the same
            // content — if the user kept typing (or the trigger is gone)
            // during the debounce window, do nothing.
            val stillValid = try {
                node.refresh()
            } catch (_: Exception) {
                false
            }
            if (!stillValid) return abort()
            if (!SensitiveInputGuard.isEditableTextField(node)) return abort()
            if (SensitiveInputGuard.isSensitiveInput(node)) return abort()

            val currentText = node.text?.toString()
            if (currentText != originalFullText) return abort()

            val apiKey = apiKeyStore.getApiKey()
            if (apiKey.isNullOrBlank()) {
                showToast(getString(R.string.error_no_api_key))
                return abort()
            }
            val model = settingsStore.selectedModel

            val executor = networkExecutor
            if (executor == null || executor.isShutdown) return abort()

            showToast(getString(R.string.toast_sending_to_gemini))

            val systemPrompt = when (target) {
                TriggerDetector.Target.ENGLISH -> TranslationPrompts.EN_TRANSLATION_SYSTEM_PROMPT
                TriggerDetector.Target.POLISH -> TranslationPrompts.PL_TRANSLATION_SYSTEM_PROMPT
            }

            executor.execute {
                val result = try {
                    GeminiClient.translateBlocking(
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = systemPrompt,
                        userText = content
                    )
                } catch (_: Exception) {
                    GeminiClient.Result.Failure.InvalidResponse
                }
                mainHandler.post { handleResult(node, originalFullText, result) }
            }
        } catch (_: Exception) {
            abort()
        }
    }

    /** Always runs on the main thread. */
    private fun handleResult(
        node: AccessibilityNodeInfo,
        originalFullText: String,
        result: GeminiClient.Result
    ) {
        try {
            // ResultPolicy.shouldReplaceText is the single source of truth
            // for "is this result allowed to touch the field" — see its
            // unit tests for the exhaustive proof that every Failure
            // variant (timeout, HTTP error, empty/invalid response, ...)
            // resolves to false, i.e. the original text is left untouched.
            if (ResultPolicy.shouldReplaceText(result) && result is GeminiClient.Result.Success) {
                applyResult(node, originalFullText, result.translatedText)
            } else if (result is GeminiClient.Result.Failure) {
                showToast(mapFailureMessage(result))
            }
        } finally {
            requestInFlight.set(false)
            recycleSafely(node)
        }
    }

    private fun applyResult(node: AccessibilityNodeInfo, originalFullText: String, translatedText: String) {
        val stillValid = try {
            node.refresh()
        } catch (_: Exception) {
            false
        }
        if (!stillValid) {
            showToast(getString(R.string.toast_field_changed_aborted))
            return
        }
        val currentText = node.text?.toString()
        if (currentText != originalFullText) {
            showToast(getString(R.string.toast_field_changed_aborted))
            return
        }

        val applied = setNodeText(node, translatedText)
        if (!applied) {
            // Per the security spec: no clipboard fallback in this
            // version. If ACTION_SET_TEXT cannot replace the field, we
            // surface an error instead of silently degrading to a less
            // safe mechanism.
            showToast(getString(R.string.error_generic))
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Exception) {
            false
        }
    }

    private fun mapFailureMessage(failure: GeminiClient.Result.Failure): String = when (failure) {
        GeminiClient.Result.Failure.Timeout -> getString(R.string.error_timeout)
        GeminiClient.Result.Failure.NetworkError -> getString(R.string.error_network)
        is GeminiClient.Result.Failure.HttpError -> getString(R.string.error_http, failure.code)
        GeminiClient.Result.Failure.EmptyResponse -> getString(R.string.error_empty_response)
        GeminiClient.Result.Failure.MissingApiKey -> getString(R.string.error_no_api_key)
        GeminiClient.Result.Failure.InvalidModel,
        GeminiClient.Result.Failure.InvalidResponse,
        GeminiClient.Result.Failure.HostNotAllowed -> getString(R.string.toast_translation_failed)
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

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEBOUNCE_MS = 400L

        /** TEMPORARY (v4 diagnostic, v1.2.7) — see
         * [handleComposeSelectionDiagnostic]'s doc. Short quiet window used
         * only to collapse a burst of TYPE_WINDOW_CONTENT_CHANGED events
         * into a single displayed snapshot; not a correctness-critical
         * value the way [DEBOUNCE_MS] is for the typed-trigger path. */
        private const val DIAGNOSTIC_DEBOUNCE_MS = 250L

        /** TEMPORARY (v4 diagnostic, v1.2.8 fix) — the ONLY two packages
         * this diagnostic is allowed to ever fire for: Google Messages and
         * X (Twitter), the two apps this whole investigation is actually
         * about. Added after on-device confirmation that gating on the
         * user's general allow-list alone (v1.2.7) let this diagnostic
         * fire for every allow-listed app — including TikTok, where
         * TYPE_WINDOW_CONTENT_CHANGED fires constantly during ordinary
         * scrolling/tapping, producing a disruptive false-looking
         * "translation" bubble that has nothing to do with the real
         * feature. This set is intentionally an allow-list, not a
         * TikTok-specific block — narrower and safer than trying to
         * enumerate every "chatty" app that might exhibit the same
         * problem. */
        private val DIAGNOSTIC_TARGET_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.twitter.android"
        )
    }
}
