package com.textgate.ai.util

import android.os.Handler
import android.os.Looper

/**
 * Minimal debounce helper built on the platform's own [Handler] —
 * no external scheduling/coroutine library required.
 *
 * Each call to [schedule] cancels any previously scheduled, not-yet-fired
 * action and schedules the new one [delayMillis] in the future. This is
 * what satisfies the spec's "a single trigger must not cause multiple
 * requests" requirement: rapid-fire accessibility events for the same
 * keystroke collapse into a single scheduled action, and that action only
 * actually runs after the input has been quiet for [delayMillis].
 */
class Debouncer(private val delayMillis: Long) {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun schedule(action: () -> Unit) {
        cancel()
        val runnable = Runnable { pending = null; action() }
        pending = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    fun hasPending(): Boolean = pending != null
}
