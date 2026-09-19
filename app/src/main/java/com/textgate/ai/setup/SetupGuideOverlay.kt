package com.textgate.ai.setup

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.textgate.ai.R

internal object SetupGuideOverlay {
    enum class Destination(val stepsRes: Int) {
        ACCESSIBILITY(R.string.setup_guide_accessibility_steps),
        BATTERY(R.string.setup_guide_battery_steps),
        AUTOSTART(R.string.setup_guide_autostart_steps)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { dismiss() }
    private var windowManager: WindowManager? = null
    private var guideView: View? = null

    fun show(context: Context, destination: Destination): Boolean {
        dismiss()
        if (!Settings.canDrawOverlays(context)) return false

        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(app).inflate(R.layout.overlay_setup_guide, null)
        view.findViewById<TextView>(R.id.textGuideSteps).setText(destination.stepsRes)
        view.findViewById<View>(R.id.buttonCloseGuide).setOnClickListener { dismiss() }

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

        return try {
            manager.addView(view, params)
            windowManager = manager
            guideView = view
            handler.postDelayed(timeout, 180_000L)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun dismiss() {
        handler.removeCallbacks(timeout)
        val view = guideView
        guideView = null
        if (view != null) {
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
                // The system may already have removed the overlay.
            }
        }
        windowManager = null
    }
}
