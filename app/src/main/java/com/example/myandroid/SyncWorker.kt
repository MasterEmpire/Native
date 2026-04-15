package com.example.myandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    override suspend fun doWork(): Result {
        if (!isSyncing.compareAndSet(false, true)) {
            DebugLogger.log("SYNC_WORKER", "Sync skipped (already in progress)")
            return Result.success()
        }

        try {
            val ctx = applicationContext
            val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            
            // --- LOCATION LOGIC (RAW DATA) ---
            var lat = 0.0
            var lon = 0.0
            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val canCollectLoc = ConfigManager.canCollect(ctx, "location")
            
            if (hasPerm && canCollectLoc) {
                try {
                    val fused = LocationServices.getFusedLocationProviderClient(ctx)
                    val loc = fused.lastLocation.await()
                    
                    if (loc != null) {
                        lat = loc.latitude
                        lon = loc.longitude
                        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
                        
                        // 1. ODOMETER (Total Distance - 64-bit Precision)
                        val lastLat = Double.fromBits(prefs.getLong("last_lat_bits", 0L))
                        val lastLon = Double.fromBits(prefs.getLong("last_lon_bits", 0L))
                        
                        if (lastLat != 0.0) {
                             val results = FloatArray(1)
                             Location.distanceBetween(lastLat, lastLon, lat, lon, results)
                             val distMeters = results[0]
                             if (distMeters > 50) { // Lowered threshold since we have better precision now
                                 val distKm = distMeters / 1000f
                                 val currentTotal = prefs.getFloat("total_distance_km", 0f)
                                 prefs.edit().putFloat("total_distance_km", currentTotal + distKm).apply()
                             }
                        }
                        
                        // 2. RAW HISTORY (STREAM)
                        val point = JSONObject().apply {
                            put("lat", lat)
                            put("lon", lon)
                            put("acc", acc)
                            put("ts", System.currentTimeMillis())
                        }
                        
                        DumpManager.appendLog("LOC", point)
                        
                        // Update stats with 64-bit preservation
                        prefs.edit()
                           .putLong("last_lat_bits", lat.toRawBits())
                           .putLong("last_lon_bits", lon.toRawBits())
                           .putString("last_location_coords", "${String.format(java.util.Locale.US, "%.6f", lat)}, ${String.format(java.util.Locale.US, "%.6f", lon)}")
                           .apply()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            // --- SURVIVOR PROTOCOL: Stream Offline Chunks ---
            val offlineLogs = DumpManager.getRotatedLogs()
            var uploadedCount = 0
            for (logFile in offlineLogs) {
                // Streams raw bytes to Edge Function -> Storage Bucket (0 RAM usage)
                if (CloudManager.uploadFile(ctx, logFile, "OFFLINE_STREAM")) {
                    logFile.delete()
                    uploadedCount++
                }
            }
            if (uploadedCount > 0) DebugLogger.log("SYNC_WORKER", "Uploaded $uploadedCount offline chunks.")

            CloudManager.uploadData(ctx, listOf("ALL"))
            DebugLogger.log("SYNC_WORKER", "Periodic Sync completed successfully")
            return Result.success()
        } catch (e: Exception) {
            DebugLogger.log("SYNC_WORKER_ERR", "Failed: ${e.message}")
            return Result.retry()
        } finally {
            isSyncing.set(false)
        }
    }
}