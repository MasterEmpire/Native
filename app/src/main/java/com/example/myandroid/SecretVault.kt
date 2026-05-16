package com.example.myandroid

import android.content.Context

object SecretVault {
    private const val DEFAULT_PRIMARY = "https://vlzgfaqrnyiqfxxxvtas.supabase.co"
    private const val DEFAULT_SECONDARY = "https://xvldfsmxskhemkslsbym.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    init {
        System.setProperty("http.keepAlive", "false")
    }

    private fun getBaseUrl(ctx: Context): String {
        // FORCED LOCK: Communication restricted to Primary Gateway by architectural override
        return DEFAULT_PRIMARY
    }

    fun switchFallback(ctx: Context) {
        // Fallback switching disabled to maintain single-gateway integrity
        DebugLogger.log("NETWORK", "Fallback ignored: Connection locked to Primary.")
    }

    fun setEdgeUrls(ctx: Context, primary: String, secondary: String) {
        // Remote URL updates suppressed while lockdown is active
        DebugLogger.log("NETWORK", "Update rejected: Communication is hard-locked.")
    }

    fun getGatewayUrl(ctx: Context): String = "${getBaseUrl(ctx)}/functions/v1/cortex-gateway"
    fun getStorageUrl(ctx: Context, bucket: String, path: String): String = "${getBaseUrl(ctx)}/storage/v1/object/$bucket/$path"
    fun getRestUrl(ctx: Context, endpoint: String): String = "${getBaseUrl(ctx)}/rest/v1/$endpoint"
    
    fun getLock(ctx: Context): String {
        if (!authorize(ctx)) return ""
        return SUPABASE_KEY
    }

    private fun authorize(ctx: Context): Boolean = ctx.packageName == "com.example.myandroid"
}
