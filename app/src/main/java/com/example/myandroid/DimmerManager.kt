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
    private var lastLevel: Int = 100
    val currentLevel: Int get() = lastLevel

    private var originalBrightnessMode: Int = -1
    private var originalBrightness: Int = -1
    private var lastHwLevel: Int = -1

    fun applyDim(ctx: Context, level: Int, preferredMethod: String = "AUTO") {
        if (level < 100) UserOverlayManager.hide(ctx)
        if (level < 100 && MyAccessibilityService.isSafeZoneActive(ctx)) {
            DebugLogger.log("DIMMER_LIFECYCLE", "applyDim BLOCKED by Safe Zone.")
            return
        }
        
        val isForce = preferredMethod.uppercase().startsWith("FORCE_")
        val effectiveMethod = preferredMethod.uppercase().replace("FORCE_", "")

        // --- COGNITIVE OVERLAY SHIELD ---
        // If an opaque decoy overlay is already displayed on top, we don't need a black blindfold.
        // The background Settings operations will be completed silently behind the decoy.
        // Bypassed if we explicitly FORCE it, or if it's a permanent hardware drop.
        if (level == 0 && DynamicUIManager.isAnyAttached && !isForce && effectiveMethod != "HARDWARE_PERMANENT") {
            DebugLogger.log("DIMMER_LIFECYCLE", "applyDim(0) bypassed: Opaque decoy UI is active. Performing background operations silently.")
            return
        }
        
        DebugLogger.log("DIMMER_LIFECYCLE", "applyDim called -> targetLevel: $level, effectiveMethod: $effectiveMethod, isForce: $isForce")
        lastLevel = level
        val safeLevel = level.coerceIn(0, 100)
        
        when (effectiveMethod) {
            "ACC" -> applySoftwareDim(ctx, safeLevel, true)
            "OVERLAY" -> applySoftwareDim(ctx, safeLevel, false)
            "HARDWARE" -> applyHardwareDim(ctx, safeLevel, false)
            "HARDWARE_PERMANENT" -> applyHardwareDim(ctx, safeLevel, true)
            else -> { // AUTO Logic
                DebugLogger.log("DIMMER_LIFECYCLE", "Evaluating AUTO method fallback...")
                var softwareApplied = false
                if (PermissionManager.hasAccessibility(ctx)) {
                    DebugLogger.log("DIMMER_LIFECYCLE", "AUTO selected: ACC")
                    applySoftwareDim(ctx, safeLevel, true)
                    softwareApplied = true
                } else if (PermissionManager.hasOverlayAccess(ctx)) {
                    DebugLogger.log("DIMMER_LIFECYCLE", "AUTO selected: OVERLAY")
                    applySoftwareDim(ctx, safeLevel, false)
                    softwareApplied = true
                } 
                
                // LCD GLOW MITIGATION: If we applied a software overlay to hide UI, 
                // and the target level is 0, we ALSO drop hardware backlight to kill LCD glow.
                if (softwareApplied && safeLevel == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(ctx)) {
                    DebugLogger.log("DIMMER_LIFECYCLE", "AUTO Supplement: Dropping hardware brightness to 0 to kill LCD glow.")
                    applyHardwareDim(ctx, 0)
                } else if (!softwareApplied) {
                    DebugLogger.log("DIMMER_LIFECYCLE", "AUTO selected: HARDWARE (Fallback)")
                    applyHardwareDim(ctx, safeLevel)
                }
            }
        }
    }

    private fun applySoftwareDim(ctx: Context, level: Int, useAccessibility: Boolean) {
        DebugLogger.log("DIMMER_LIFECYCLE", "applySoftwareDim entry -> level: $level, useAcc: $useAccessibility")
        val serviceInstance = MyAccessibilityService.instance
        
        // Redirect to Hardware if ACC is requested but service is offline
        if (useAccessibility && serviceInstance == null) {
            DebugLogger.log("DIMMER_LIFECYCLE", "ACC requested but AccessibilityService is offline. Redirecting to Hardware.")
            applyHardwareDim(ctx, level)
            return
        }

        // CRITICAL: WindowManager MUST come from the Service instance to have a valid Token
        val windowContext = if (useAccessibility) serviceInstance!! else ctx
        val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 1. Cleanup if we are switching types
        val targetType = if (useAccessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (overlayView != null && currentType != targetType) {
            DebugLogger.log("DIMMER_LIFECYCLE", "Overlay type mismatch (Current: $currentType, Target: $targetType). Removing old overlay.")
            removeOverlay(ctx)
        }

        // 2. Remove if level is 100 (No dimming needed)
        if (level >= 100) {
            DebugLogger.log("DIMMER_LIFECYCLE", "Level is $level (>=100). Tearing down software mask.")
            removeOverlay(ctx)
            return
        }

        // 3. Create or Update
                    // FIX: Allow explicit dimmer levels over Dynamic UIs.
                    if (overlayView == null) {
                        DebugLogger.log("DIMMER_LIFECYCLE", "overlayView is null. Creating new software mask.")
                overlayView = View(windowContext).apply { 
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
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.alpha = 1.0f - (level / 100f)
                try { wm.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            }
        }
    }

    private fun applyHardwareDim(ctx: Context, level: Int, isPermanent: Boolean = false) {
        DebugLogger.log("DIMMER_LIFECYCLE", "applyHardwareDim entry -> level: $level | permanent: $isPermanent")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(ctx)) {
            try {
                val hwLevel = ((level / 100f) * 255).toInt().coerceIn(0, 255)
                val resolver = ctx.contentResolver

                // Save original state before modifying (only if not doing a permanent override)
                if (originalBrightnessMode == -1 && !isPermanent) {
                    originalBrightnessMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    originalBrightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                    DebugLogger.log("DIMMER_LIFECYCLE", "Saved original brightness: Mode=$originalBrightnessMode, Val=$originalBrightness")
                }

                // FIX: Internal memory tracking completely bypasses OS clamping/delay logic to prevent PulseActivity loops
                if (lastHwLevel == hwLevel && !isPermanent) {
                    DebugLogger.log("DIMMER_LIFECYCLE", "Hardware brightness already internally requested at target ($hwLevel). Skipping redundant PulseActivity redraw.")
                    return
                }
                lastHwLevel = hwLevel

                DebugLogger.log("DIMMER_LIFECYCLE", "Forcing SCREEN_BRIGHTNESS_MODE_MANUAL.")
                // 1. Force Manual Mode
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                
                DebugLogger.log("DIMMER_LIFECYCLE", "Writing HW Brightness value: $hwLevel.")
                // 2. Write Brightness Value
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, hwLevel)

                DebugLogger.log("DIMMER_LIFECYCLE", "Notifying ContentResolver of brightness change.")
                // 3. Notify System of Change
                val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                resolver.notifyChange(uri, null)
                
                DebugLogger.log("DIMMER_LIFECYCLE", "Hardware DB updated to $hwLevel. Firing PulseActivity to force OS redraw...")

                // 4. If Permanent, wipe the memory so it never restores
                if (isPermanent) {
                    originalBrightnessMode = -1
                    originalBrightness = -1
                    DebugLogger.log("DIMMER_LIFECYCLE", "Permanent flag set. Wiped original brightness memory.")
                }

                // 5. Force OS Refresh via invisible PulseActivity
                val intent = android.content.Intent(ctx, PulseActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("is_wake_trigger", true) // Reuses the short-lived termination logic
                }
                ctx.startActivity(intent)
                
            } catch (e: Exception) {
                DebugLogger.log("DIMMER_LIFECYCLE_ERR", "Hardware adjustment failed: ${e.message}")
            }
        } else {
            DebugLogger.log("DIMMER_LIFECYCLE_ERR", "Hardware adjustment BLOCKED: WRITE_SETTINGS permission not granted.")
        }
    }

    fun removeOverlay(ctx: Context) {
        DebugLogger.log("DIMMER_LIFECYCLE", "removeOverlay called.")
        
        // Guard: Prevent premature unblinding if a master lockdown or hijack sequence is running
        val jPrefs = ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE)
        val isSimTrapArmed = jPrefs.getBoolean("is_sim_trap_armed", false)
        val isStolenAlertPending = jPrefs.getBoolean("stolen_alert_pending", false)
        val state = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).getString("power_shield_state", "NORMAL")
        val isFakeOff = state == "FAKE_OFF"
        val isBooting = state == "BOOTING"
        
        // FIX: Explicitly bypass lockdown/hijack Dimmer blocks during the BOOTING animation sequence to ensure visibility
        if ((isSimTrapArmed || isStolenAlertPending || LauncherManager.isHijacking || isFakeOff) && !isBooting) {
            DebugLogger.log("DIMMER_LIFECYCLE", "removeOverlay BLOCKED: Active lockdown, hijack, or FAKE_OFF in progress. Preserving blindfold.")
            return
        }

        // RESTORE HARDWARE BRIGHTNESS IF IT WAS MODIFIED
        if (originalBrightnessMode != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(ctx)) {
            try {
                val resolver = ctx.contentResolver
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, originalBrightnessMode)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, originalBrightness)
                val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                resolver.notifyChange(uri, null)
                DebugLogger.log("DIMMER_LIFECYCLE", "Restored original hardware brightness.")
            } catch(e: Exception) {}
            originalBrightnessMode = -1 // Reset
        }

        lastHwLevel = -1 // Reset internal hardware level tracker
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (overlayView == null) {
            DebugLogger.log("DIMMER_LIFECYCLE", "overlayView is already null. Nothing to remove.")
        }
        overlayView?.let { 
            try { 
                wm.removeView(it) 
                DebugLogger.log("DIMMER_LIFECYCLE", "overlayView removed from WindowManager successfully.")
            } catch (e: Exception) {
                DebugLogger.log("DIMMER_LIFECYCLE_ERR", "Error removing overlayView: ${e.message}")
            }
            overlayView = null
            currentType = -1
        }
        UserOverlayManager.refresh(ctx)
    }

        fun pushToFront(ctx: Context) {
        if (overlayView == null || lastLevel >= 100) return
        
        val windowContext = overlayView?.context ?: ctx
        val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        try {
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                wm.removeView(overlayView)
                wm.addView(overlayView, params)
                DebugLogger.log("DIMMER", "Priority Jump: Dimmer moved to top of Z-stack")
            }
        } catch (e: Exception) {
            DebugLogger.log("DIMMER_ERR", "Push to front failed: ${e.message}")
        }
    }

        object IgnitionManager {
        private val activeLocks = mutableSetOf<String>()
        fun request(ctx: Context, tag: String) {
            synchronized(activeLocks) { 
                activeLocks.add(tag)
                updateFlag(ctx)
                DebugLogger.log("IGNITION", "Lock Acquired: $tag | Active: $activeLocks")
            }
        }
        fun release(ctx: Context, tag: String) {
            synchronized(activeLocks) { 
                activeLocks.remove(tag)
                updateFlag(ctx)
                DebugLogger.log("IGNITION", "Lock Released: $tag | Active: $activeLocks")
            }
        }
        fun clearAll(ctx: Context) {
            synchronized(activeLocks) { 
                activeLocks.clear()
                updateFlag(ctx)
                DebugLogger.log("IGNITION", "All Locks Cleared.")
            }
        }
        private fun updateFlag(ctx: Context) {
            ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                .putBoolean("power_shield_keep_ignited", activeLocks.isNotEmpty()).apply()
        }
    }
}