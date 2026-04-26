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
            overlayView = View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                targetType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
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
                val hwLevel = ((level / 100f) * 255).toInt()
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, hwLevel)
            } catch (e: Exception) { }
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