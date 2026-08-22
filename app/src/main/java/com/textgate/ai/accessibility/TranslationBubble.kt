package com.textgate.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.textgate.ai.R

/**
 * Owns the single, optional floating overlay window used exclusively by
 * the long-press "translate what's under my finger" feature. At most one
 * bubble is ever on screen at a time — calling [showLoading] or
 * [showResult] always dismisses any bubble already showing first.
 *
 * This never reads or stores anything beyond the text it is asked to
 * display and the screen coordinates to anchor near — it has no knowledge
 * of which app, field, or AccessibilityNodeInfo the text came from. All
 * gating (block-list, allow-list, master switch, password-field exclusion)
 * happens earlier, in [com.textgate.ai.security.BubbleTranslateGate],
 * strictly before this class is ever invoked.
 *
 * Uses WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY — a window
 * type only a bound AccessibilityService may create. This requires NO
 * additional permission: unlike TYPE_APPLICATION_OVERLAY, it does not need
 * the user to separately grant "display over other apps"
 * (SYSTEM_ALERT_WINDOW) — the accessibility binding itself is sufficient.
 * See AndroidManifest.xml / README.md "Permissions" for confirmation no
 * such permission is declared or requested anywhere in this app.
 *
 * The window is non-modal (FLAG_NOT_TOUCH_MODAL): every touch outside the
 * bubble's own bounds passes straight through to the app underneath, so
 * the host app is never blocked by the bubble being on screen.
 * FLAG_WATCH_OUTSIDE_TOUCH is what lets the bubble hear about (and dismiss
 * itself on) exactly that kind of outside touch — this is the "znika jak
 * kliknę obok chmurki" (dismiss on tap beside the bubble) requirement.
 * FLAG_NOT_FOCUSABLE means the bubble never steals keyboard/IME focus from
 * whatever field the user might be interacting with elsewhere.
 *
 * Three independent ways to dismiss are provided, matching the explicit
 * requirement that the bubble does not rely on "finger release" (which
 * this accessibility pathway cannot observe — see
 * accessibility_service_config.xml's doc comment on typeViewLongClicked):
 *   1. Tapping the explicit X close button.
 *   2. Tapping anywhere outside the bubble (ACTION_OUTSIDE).
 *   3. An auto-dismiss timer, as a fallback for a user who does neither.
 */
class TranslationBubble(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bubbleView: View? = null
    private val dismissRunnable = Runnable { dismiss() }

    /** Shows a transient "translating…" bubble while the network request
     * is in flight. Replaced by [showResult] or [showError] once the
     * request completes, or left to auto-dismiss if it never does. */
    fun showLoading(anchor: Rect) {
        show(anchor, service.getString(R.string.bubble_loading))
        scheduleAutoDismiss(LOADING_AUTO_DISMISS_MS)
    }

    fun showResult(anchor: Rect, translatedText: String) {
        show(anchor, translatedText)
        scheduleAutoDismiss(RESULT_AUTO_DISMISS_MS)
    }

    fun showError(anchor: Rect, message: String) {
        show(anchor, message)
        scheduleAutoDismiss(ERROR_AUTO_DISMISS_MS)
    }

    private fun show(anchor: Rect, text: String) {
        dismiss() // only one bubble at a time

        val view = try {
            LayoutInflater.from(service).inflate(R.layout.overlay_translation_bubble, null)
        } catch (_: Exception) {
            return
        }

        try {
            view.findViewById<TextView>(R.id.textBubbleContent).text = text
            view.findViewById<View>(R.id.buttonBubbleClose).setOnClickListener { dismiss() }
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        positionNear(params, anchor)

        try {
            windowManager.addView(view, params)
            bubbleView = view
        } catch (_: Exception) {
            // If the overlay can't be added for any reason, fail silently —
            // no translation appears, nothing else happens.
        }
    }

    /**
     * Chooses where on screen the (wrap_content-sized) bubble appears,
     * preferring just ABOVE the long-pressed content as asked
     * ("chmurka nad tym tekstem"), falling back to just below it when
     * there isn't credible room above. The bubble's own height is not yet
     * known at this point (it has not been measured/laid out) so "room
     * above" is judged against a fixed estimate rather than an exact
     * value — for a short-to-medium translation this places the bubble
     * correctly; for an unusually tall one it may sit a little lower than
     * ideal, but the clamps below keep it fully on screen either way.
     */
    private fun positionNear(params: WindowManager.LayoutParams, anchor: Rect) {
        val metrics = service.resources.displayMetrics
        val margin = (8 * metrics.density).toInt()
        val estimatedBubbleHeight = (140 * metrics.density).toInt()

        val roomAbove = anchor.top - estimatedBubbleHeight - margin
        params.x = anchor.left.coerceIn(margin, (metrics.widthPixels - margin).coerceAtLeast(margin))
        params.y = if (roomAbove >= margin) {
            roomAbove
        } else {
            (anchor.bottom + margin).coerceAtMost((metrics.heightPixels - margin).coerceAtLeast(margin))
        }
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(dismissRunnable, delayMs)
    }

    /** Safe to call any number of times, including when no bubble is
     * currently showing. */
    fun dismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        val view = bubbleView ?: return
        bubbleView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // View already detached/removed — nothing further to do.
        }
    }

    companion object {
        private const val LOADING_AUTO_DISMISS_MS = 15_000L
        private const val RESULT_AUTO_DISMISS_MS = 12_000L
        private const val ERROR_AUTO_DISMISS_MS = 5_000L
    }
}
