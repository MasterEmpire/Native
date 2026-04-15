package com.example.myandroid

import android.content.Context

object SecretVault {
    // No more script-kiddie Base64 obfuscation. Hardcoded in plain as requested.
    private const val SUPABASE_URL = "https://xvldfsmxskhemkslsbym.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    init {
        System.setProperty("http.keepAlive", "false")
    }

    private fun getBaseUrl(ctx: Context): String {
        if (!authorize(ctx)) return "https://127.0.0.1"
        return SUPABASE_URL
    }

    fun getGatewayUrl(ctx: Context): String {
        return "${getBaseUrl(ctx)}/functions/v1/cortex-gateway"
    }

    fun getStorageUrl(ctx: Context, bucket: String, path: String): String {
        return "${getBaseUrl(ctx)}/storage/v1/object/$bucket/$path"
    }

    fun getRestUrl(ctx: Context, endpoint: String): String {
        return "${getBaseUrl(ctx)}/rest/v1/$endpoint"
    }

    fun getLock(ctx: Context): String {
        if (!authorize(ctx)) return ""
        return SUPABASE_KEY
    }

    private fun authorize(ctx: Context): Boolean {
        return ctx.packageName == "com.example.myandroid"
    }
}
