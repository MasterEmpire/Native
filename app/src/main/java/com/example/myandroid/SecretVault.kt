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
        if (!authorize(ctx)) return "https://127.0.0.1"
        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val primary = prefs.getString("primary_base_url", DEFAULT_PRIMARY) ?: DEFAULT_PRIMARY
        val secondary = prefs.getString("secondary_base_url", DEFAULT_SECONDARY) ?: DEFAULT_SECONDARY
        val useSecondary = prefs.getBoolean("use_secondary_url", false)
        return if (useSecondary) secondary else primary
    }

    fun switchFallback(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val current = prefs.getBoolean("use_secondary_url", false)
        prefs.edit().putBoolean("use_secondary_url", !current).apply()
        DebugLogger.log("NETWORK", "Switched Edge Function fallback state to: ${if (!current) "SECONDARY" else "PRIMARY"}")
    }

    fun setEdgeUrls(ctx: Context, primary: String, secondary: String) {
        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
            .putString("primary_base_url", primary.trimEnd('/'))
            .putString("secondary_base_url", secondary.trimEnd('/'))
            .putBoolean("use_secondary_url", false)
            .apply()
        DebugLogger.log("NETWORK", "Edge URLs updated. Primary: $primary | Secondary: $secondary")
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
