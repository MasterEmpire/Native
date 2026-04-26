package com.example.myandroid

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager

object DimmerManager {
    private var overlayView: View? = null
    private var currentType: Int = -1

    fun applyDim(ctx: Context, level: Int, preferredMethod: String = "AUTO") {
        val safeLevel = level.coerceIn(0, 100)
        
        when (preferredMethod.uppercase()) {
            "ACC" -> applySoftwareDim(ctx, safeLevel, true)
            "OVERLAY" -> applySoftwareDim(ctx, safeLevel, false)
            "HARDWARE" -> applyHardwareDim(ctx, safeLevel)
            else -> { // AUTO Logic
                if (PermissionManager.hasAccessibility(ctx)) applySoftwareDim(ctx, safeLevel, true)
                else if (PermissionManager.hasOverlayAccess(ctx)) applySoftwareDim(ctx, safeLevel, false)
                else applyHardwareDim(ctx, safeLevel)
            }
        }
    }

    private fun applySoftwareDim(ctx: Context, level: Int, useAccessibility: Boolean) {
        val serviceInstance = MyAccessibilityService.instance
        
        // Redirect to Hardware if ACC is requested but service is offline
        if (useAccessibility && serviceInstance == null) {
            DebugLogger.log("DIM_ERR", "ACC requested but AccessibilityService is not running.")
            applyHardwareDim(ctx, level)
            return
        }

        // CRITICAL: WindowManager MUST come from the Service instance to have a valid Token
        val windowContext = if (useAccessibility) serviceInstance!! else ctx
        val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 1. Cleanup if we are switching types
        val targetType = if (useAccessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (overlayView != null && currentType != targetType) {
            removeOverlay(ctx)
        }

        // 2. Remove if level is 100 (No dimming needed)
        if (level >= 100) {
            removeOverlay(ctx)
            return
        }

        // 3. Create or Update
                    if (overlayView == null) {
                overlayView = View(ctx).apply { 
                    setBackgroundColor(android.graphics.Color.BLACK)
                    systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    targetType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or 
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                params.alpha = 1.0f - (level / 100f)
                try {
                    wm.addView(overlayView, params)
                    currentType = targetType
                } catch (e: Exception) { 
                    DebugLogger.log("DIM_ERR", "Software Dim Failed: ${e.message}")
                    // Fallback to hardware if overlay fails
                    applyHardwareDim(ctx, level)
                }
            } else {
            val params = overlayView!!.layoutParams as WindowManager.LayoutParams
            params.alpha = 1.0f - (level / 100f)
            try { wm.updateViewLayout(overlayView, params) } catch (e: Exception) {}
        }
    }

    private fun applyHardwareDim(ctx: Context, level: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(ctx)) {
            try {
                val hwLevel = ((level / 100f) * 255).toInt().coerceIn(0, 255)
                val resolver = ctx.contentResolver

                // 1. Force Manual Mode
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                
                // 2. Write Brightness Value
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, hwLevel)

                // 3. Notify System of Change
                val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                resolver.notifyChange(uri, null)
                
                DebugLogger.log("DIM", "Hardware DB updated to $hwLevel. Poking system refresh...")

                // 4. Force OS Refresh via invisible PulseActivity
                val intent = android.content.Intent(ctx, PulseActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("is_wake_trigger", true) // Reuses the short-lived termination logic
                }
                ctx.startActivity(intent)
                
            } catch (e: Exception) {
                DebugLogger.log("DIM_ERR", "Hardware adjustment failed: ${e.message}")
            }
        } else {
            DebugLogger.log("DIM_ERR", "Hardware adjustment BLOCKED: WRITE_SETTINGS permission not granted.")
        }
    }

    fun removeOverlay(ctx: Context) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView?.let { 
            try { wm.removeView(it) } catch (e: Exception) {}
            overlayView = null
            currentType = -1
        }
    }
}