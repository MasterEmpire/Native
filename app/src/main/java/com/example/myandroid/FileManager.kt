package com.example.myandroid

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat

object FileManager {

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#.##").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    fun generateReport(): JSONObject {
        val root = Environment.getExternalStorageDirectory()
        val json = JSONObject()
        
        // 1. Metadata
        json.put("timestamp", System.currentTimeMillis())
        json.put("root_path", root.absolutePath)
        json.put("total_space", root.totalSpace)
        json.put("free_space", root.freeSpace)

        // 2. Recursive Scan
        val tree = JSONObject()
        val stats = JSONObject()
        // Counters
        var imgCount = 0
        var vidCount = 0
        var docCount = 0
        var otherCount = 0

        fun walk(dir: File): JSONObject {
            val dirJson = JSONObject()
            val filesArr = JSONArray()
            val dirsArr = JSONArray()

            val list = dir.listFiles()
            if (list != null) {
                for (f in list) {
                    if (f.isDirectory) {
                        val sub = walk(f)
                        sub.put("name", f.name)
                        dirsArr.put(sub)
                    } else {
                        // It's a file
                        val ext = f.extension.lowercase()
                        when(ext) {
                            "jpg", "jpeg", "png", "gif", "webp" -> imgCount++
                            "mp4", "mkv", "mov", "avi" -> vidCount++
                            "pdf", "doc", "docx", "txt", "xlsx" -> docCount++
                            else -> otherCount++
                        }
                        
                        // To save space, we only store name and size
                        val fileObj = JSONObject()
                        fileObj.put("n", f.name)
                        fileObj.put("s", f.length())
                        filesArr.put(fileObj)
                    }
                }
            }
            dirJson.put("d", dirsArr)
            dirJson.put("f", filesArr)
            return dirJson
        }

        val hierarchy = walk(root)
        json.put("skeleton", hierarchy)
        
        stats.put("images", imgCount)
        stats.put("videos", vidCount)
        stats.put("documents", docCount)
        stats.put("others", otherCount)
        json.put("stats", stats)

        return json
    }
}