package com.example.myandroid

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.text.DecimalFormat

object SystemDeepScan {

    // --- 1. POWER PLANT (Battery) ---
    fun getBatteryDetailed(ctx: Context): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = ctx.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val pct = (level / scale.toFloat()) * 100

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tech = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
        val health = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0

        map["Level"] = "${pct.toInt()}%"
        map["Status"] = if(isCharging) "Charging" else "Discharging"
        map["Power Source"] = when {
            usbCharge -> "USB"
            acCharge -> "AC Wall"
            else -> "Battery"
        }
        map["Voltage"] = "${voltage} mV"
        map["Temperature"] = "${temp / 10.0}°C" // Temp is usually decicelsius
        map["Technology"] = tech
        map["Health Condition"] = getHealthString(health)

        // REFLECTION: Get Physical Design Capacity (mAh)
        try {
            val powerProfile = Class.forName("com.android.internal.os.PowerProfile")
                .getConstructor(Context::class.java)
                .newInstance(ctx)
            val capacity = powerProfile.javaClass
                .getMethod("getBatteryCapacity")
                .invoke(powerProfile) as Double
            map["Capacity"] = "${capacity.toInt()} mAh"
        } catch (e: Exception) {
            map["Capacity"] = "Unknown"
        }

        return map
    }

    // --- 2. SILICON (SoC & CPU) ---
    fun getCpuDetailed(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        map["SoC Board"] = Build.BOARD.uppercase()
        map["Hardware"] = Build.HARDWARE.uppercase()
        map["Bootloader"] = Build.BOOTLOADER.uppercase()
        map["Supported ABIs"] = Build.SUPPORTED_ABIS.joinToString(", ")
        map["CPU Cores"] = Runtime.getRuntime().availableProcessors().toString()
        
        // Architecture Logic
        val arch = System.getProperty("os.arch") ?: "Unknown"
        map["Architecture"] = arch
        map["Bit Mode"] = if (arch.contains("64")) "64-bit" else "32-bit"
        
        return map
    }

    // --- 3. MEMORY (RAM & Heap) ---
    fun getMemoryDetailed(ctx: Context): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val total = memInfo.totalMem / (1024 * 1024)
        val avail = memInfo.availMem / (1024 * 1024)
        val used = total - avail
        val threshold = memInfo.threshold / (1024 * 1024)

        map["Physical RAM"] = "$total MB"
        map["Available RAM"] = "$avail MB"
        map["Used RAM"] = "$used MB"
        map["Low Mem Threshold"] = "$threshold MB"
        map["Low Memory State"] = if(memInfo.lowMemory) "CRITICAL" else "STABLE"

        // Java Heap (App specific)
        val runtime = Runtime.getRuntime()
        val heapMax = runtime.maxMemory() / (1024 * 1024)
        val heapAlloc = runtime.totalMemory() / (1024 * 1024)
        val heapFree = runtime.freeMemory() / (1024 * 1024)
        map["Java Heap Max"] = "$heapMax MB"
        map["Java Heap Alloc"] = "$heapAlloc MB"

        return map
    }

    // --- 4. OPTICS (Camera) ---
    fun getCameraDetailed(ctx: Context): Map<String, String> {
        val map = linkedMapOf<String, String>()
        try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            map["Cameras Found"] = ids.size.toString()
            
            ids.forEachIndexed { index, id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = if(facing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "REAR"
                
                // Calculate Megapixels
                val size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = if (size != null) {
                    val pixels = size.width * size.height
                    DecimalFormat("#.0").format(pixels / 1_000_000.0) + " MP"
                } else "Unknown"

                map["Cam $index ($facingStr)"] = mp
            }
        } catch (e: Exception) {
            map["Status"] = "Camera Access Blocked"
        }
        return map
    }

    // --- 5. VISION (Display) ---
    fun getDisplayDetailed(ctx: Context): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        map["Resolution"] = "${metrics.widthPixels} x ${metrics.heightPixels}"
        map["Density (DPI)"] = "${metrics.densityDpi}"
        map["Refresh Rate"] = "${display.refreshRate} Hz"
        map["Orientation"] = if(ctx.resources.configuration.orientation == 1) "Portrait" else "Landscape"
        map["Scaled Density"] = "${metrics.scaledDensity}"
        
        // HDR Check (API 24+)
        val hdr = display.hdrCapabilities
        map["HDR Supported"] = if (hdr != null && hdr.supportedHdrTypes.isNotEmpty()) "YES" else "NO"
        
        return map
    }

    // --- 6. SOUL (Software) ---
    fun getSoftwareDetailed(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        map["Android Version"] = Build.VERSION.RELEASE
        map["SDK API Level"] = Build.VERSION.SDK_INT.toString()
        map["Security Patch"] = Build.VERSION.SECURITY_PATCH
        map["Build ID"] = Build.ID
        map["Incremental"] = Build.VERSION.INCREMENTAL
        map["Radio/Baseband"] = Build.getRadioVersion() ?: "Unknown"
        map["Fingerprint"] = Build.FINGERPRINT.takeLast(20) // Just the hash end
        return map
    }
    
    // --- 7. STORAGE (Partitions) ---
    fun getStorageDetailed(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        // Internal
        val internalStat = StatFs(Environment.getRootDirectory().absolutePath)
        val internalTotal = internalStat.blockCountLong * internalStat.blockSizeLong
        val internalFree = internalStat.availableBlocksLong * internalStat.blockSizeLong
        map["Root (/)"] = "${formatSize(internalTotal)} (Free: ${formatSize(internalFree)})"

        // Data
        val dataStat = StatFs(Environment.getDataDirectory().absolutePath)
        val dataTotal = dataStat.blockCountLong * dataStat.blockSizeLong
        val dataFree = dataStat.availableBlocksLong * dataStat.blockSizeLong
        map["Data (/data)"] = "${formatSize(dataTotal)} (Free: ${formatSize(dataFree)})"
        
        return map
    }

    // --- 8. PERIPHERALS (USB / OTG / INPUT) ---
    fun getPeripheralDetailed(ctx: Context): Map<String, String> {
        val map = linkedMapOf<String, String>()
        
        // 1. USB OTG
        try {
            val usbManager = ctx.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val usbDevices = usbManager.deviceList
            map["USB Host Mode"] = if (ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)) "Supported" else "Unsupported"
            map["Attached USB Devices"] = usbDevices.size.toString()
            usbDevices.values.forEachIndexed { i, dev ->
                map["USB [$i]"] = "${dev.manufacturerName ?: "Unknown"} ${dev.productName ?: "Device"} (ID: ${dev.deviceId})"
            }
        } catch (e: Exception) { map["USB Info"] = "Restricted" }

        // 2. Audio Jack / Output
        try {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val wired = devices.any { 
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET 
            }
            map["Audio Output Routing"] = if (wired) "Wired / USB Headset Connected" else "Internal Speaker / Wireless"
        } catch (e: Exception) {}

        // 3. Input Devices (Keyboards/Mice)
        try {
            val inputManager = ctx.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
            val inputIds = inputManager.inputDeviceIds
            var extCount = 0
            for (id in inputIds) {
                val dev = inputManager.getInputDevice(id)
                if (dev != null && !dev.isVirtual) {
                    extCount++
                    map["Input [${extCount}]"] = dev.name
                }
            }
            map["External Inputs"] = extCount.toString()
        } catch (e: Exception) {}

        return map
    }

    private fun getHealthString(h: Int): String {
        return when(h) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
    }
    
    private fun formatSize(size: Long): String {
        val gb = size / (1024.0 * 1024.0 * 1024.0)
        return DecimalFormat("#.##").format(gb) + " GB"
    }
}