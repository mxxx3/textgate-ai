package com.textgate.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.textgate.ai.R
import com.textgate.ai.model.TranslationPrompts
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.security.AppSettingsStore
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
 * The entire "read the field, notice ?en, call Gemini, replace the text"
 * pipeline lives in this one service, deliberately. Splitting it apart
 * would make it harder, not easier, to see the full flow that touches a
 * user's typed text.
 *
 * READ THIS BEFORE MODIFYING:
 *   - Every path that reaches `node.text` MUST have already passed
 *     [AppBlocklist.isBlocked], [AppSettingsStore.isPackageAllowed],
 *     [SensitiveInputGuard.isEditableTextField], and
 *     [SensitiveInputGuard.isSensitiveInput], IN THAT ORDER, with the
 *     sensitivity check happening last and always BEFORE the text read.
 *   - Only [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED] is subscribed to
 *     (see accessibility_service_config.xml) — this service never calls
 *     getWindows(), getRootInActiveWindow(), or walks a node tree. It only
 *     ever inspects `event.source` for the event actually delivered.
 *   - No AccessibilityEvent is ever stored past the return of
 *     onAccessibilityEvent(); only a single AccessibilityNodeInfo may be
 *     held briefly (bounded by [DEBOUNCE_MS] plus one network round trip)
 *     while a single trigger is being processed, and it is released the
 *     moment that processing ends, succeeds, fails, or is aborted.
 *   - Any exception, anywhere in this pipeline, results in doing nothing
 *     further (fail closed) — never in retrying, never in falling back to
 *     a broader read.
 */
class TextGateAccessibilityService : AccessibilityService() {

    private lateinit var settingsStore: AppSettingsStore
    private lateinit var apiKeyStore: SecureApiKeyStore
    private lateinit var eventGate: EventGate
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debouncer = Debouncer(DEBOUNCE_MS)

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
        networkExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onInterrupt() {
        cancelPendingDebounce()
    }

    override fun onDestroy() {
        cancelPendingDebounce()
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
        if (!::settingsStore.isInitialized || !::apiKeyStore.isInitialized || !::eventGate.isInitialized) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

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
    }
}
