package com.textgate.ai.setup

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.textgate.ai.R

internal object SetupGuideOverlay {
    private const val TAG = "SetupGuideOverlay"
    enum class Destination(val stepsRes: Int) {
        ACCESSIBILITY(R.string.setup_guide_accessibility_steps),
        BATTERY(R.string.setup_guide_battery_steps),
        AUTOSTART(R.string.setup_guide_autostart_steps)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { dismiss("timeout") }
    private var windowManager: WindowManager? = null
    private var guideView: View? = null

    fun show(context: Context, destination: Destination): Boolean {
        dismiss("show new guide")
        val permitted = Settings.canDrawOverlays(context)
        Log.d(TAG, "show destination=$destination canDrawOverlays=$permitted")
        if (!permitted) return false

        return try {
            val app = context.applicationContext
            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(app).inflate(R.layout.overlay_setup_guide, null)
            view.findViewById<TextView>(R.id.textGuideSteps).setText(destination.stepsRes)
            view.findViewById<View>(R.id.buttonCloseGuide).setOnClickListener { dismiss("close button") }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
            }

            Log.d(TAG, "Calling addView type=${params.type} flags=${params.flags} width=${params.width} height=${params.height}")
            manager.addView(view, params)
            windowManager = manager
            guideView = view
            handler.postDelayed(timeout, 180_000L)
            Log.d(TAG, "addView succeeded attached=${view.isAttachedToWindow}")
            view.post { Log.d(TAG, "first layout attached=${view.isAttachedToWindow} shown=${view.isShown}") }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not add setup guide window", e)
            false
        }
    }

    fun dismiss(reason: String = "requested") {
        handler.removeCallbacks(timeout)
        val view = guideView
        Log.d(TAG, "dismiss reason=$reason active=${view != null}")
        guideView = null
        if (view != null) {
            try {
                windowManager?.removeViewImmediate(view)
                Log.d(TAG, "Guide removed: $reason")
            } catch (e: Exception) {
                Log.e(TAG, "Could not remove guide: $reason", e)
            }
        }
        windowManager = null
    }
}
