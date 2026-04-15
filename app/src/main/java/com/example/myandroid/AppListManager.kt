package com.example.myandroid

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppListManager {

    fun getInstalledApps(ctx: Context): JSONArray {
        val list = JSONArray()
        try {
            val pm = ctx.packageManager
            val packages = pm.getInstalledPackages(0)

            for (pkg in packages) {
                // Filter out system apps to save space, unless you want them
                val isSystem = (pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                val obj = JSONObject()
                obj.put("name", pkg.applicationInfo.loadLabel(pm).toString())
                obj.put("pkg", pkg.packageName)
                obj.put("ver", pkg.versionName)
                obj.put("type", if(isSystem) "SYSTEM" else "USER")
                obj.put("install_date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(pkg.firstInstallTime)))
                
                list.put(obj)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }
}
