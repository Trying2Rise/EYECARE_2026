package com.example.eye_care_2026

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object OverlayManager {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var timerTextView: TextView? = null
    private var onDismissCallback: (() -> Unit)? = null

    fun showOverlay(
        context: Context,
        durationSeconds: Int = 20,
        onDismiss: (() -> Unit)? = null
    ) {
        if (!Settings.canDrawOverlays(context)) {
            return
        }

        Handler(Looper.getMainLooper()).post {
            // Dismiss existing overlay if active
            dismissOverlayInternal()

            this.onDismissCallback = onDismiss
            val appContext = context.applicationContext
            windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val density = appContext.resources.displayMetrics.density
            val widthPixels = appContext.resources.displayMetrics.widthPixels

            // Clean dark translucent container (#CC000000)
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val paddingPx = (20 * density).toInt()
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC000000"))
                    cornerRadius = 20 * density
                }
            }

            // Title: "Look 20 feet away!"
            val titleTextView = TextView(appContext).apply {
                text = "Look 20 feet away!"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }

            // Subtitle description
            val subtitleTextView = TextView(appContext).apply {
                text = "Rest your eyes by focusing on a distant object"
                setTextColor(Color.parseColor("#B0BEC5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                val marginPx = (6 * density).toInt()
                setPadding(0, marginPx, 0, marginPx)
            }

            // Live countdown timer text
            timerTextView = TextView(appContext).apply {
                text = "${durationSeconds}s"
                setTextColor(Color.parseColor("#00E676"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val marginPx = (8 * density).toInt()
                setPadding(0, marginPx, 0, marginPx)
            }

            // Manual "Dismiss" button
            val dismissButton = Button(appContext).apply {
                text = "Dismiss"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33FFFFFF"))
                    cornerRadius = 12 * density
                }
                val btnPaddingPx = (8 * density).toInt()
                setPadding(btnPaddingPx * 2, btnPaddingPx, btnPaddingPx * 2, btnPaddingPx)
                setOnClickListener {
                    dismissOverlay()
                }
            }

            container.addView(titleTextView)
            container.addView(subtitleTextView)
            container.addView(timerTextView)
            container.addView(dismissButton)

            val layoutParams = WindowManager.LayoutParams(
                (widthPixels * 0.85).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                windowManager?.addView(container, layoutParams)
                overlayView = container
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateTimer(secondsRemaining: Int) {
        Handler(Looper.getMainLooper()).post {
            timerTextView?.text = "${secondsRemaining}s"
        }
    }

    fun dismissOverlay() {
        Handler(Looper.getMainLooper()).post {
            dismissOverlayInternal()
        }
    }

    private fun dismissOverlayInternal() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
        timerTextView = null
        windowManager = null

        val cb = onDismissCallback
        onDismissCallback = null
        cb?.invoke()
    }

    fun isShowing(): Boolean = overlayView != null
}
