package com.example.myandroid

import android.content.Context

object SecretVault {
    
    // Tightly coupled environments prevent URL and JWT mismatch
    private data class Environment(val url: String, val key: String)

    private val PRIMARY = Environment(
        url = "https://xvldfsmxskhemkslsbym.supabase.co",
        key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"
    )

    private val SECONDARY = Environment(
        url = "https://vlzgfaqrnyiqfxxxvtas.supabase.co",
        key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZsemdmYXFybnlpcWZ4eHh2dGFzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU1NTk5NDAsImV4cCI6MjA4MTEzNTk0MH0.y93d68JWyGL7NKXZEHLunAuayMEWw1K6yATFGLxkUxY"
    )

    @Volatile
    private var usePrimary = true
    
    @Volatile
    private var consecutiveFailures = 0

    init {
        System.setProperty("http.keepAlive", "false")
    }

    private fun getActiveEnvironment(): Environment {
        return if (usePrimary) PRIMARY else SECONDARY
    }

    private fun getBaseUrl(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("cortex_edge_prefs", Context.MODE_PRIVATE)
        val customUrl = if (usePrimary) prefs.getString("primary_url", null) else prefs.getString("secondary_url", null)
        return customUrl ?: getActiveEnvironment().url
    }

    fun reportSuccess() {
        if (consecutiveFailures > 0) consecutiveFailures = 0
    }

    fun switchFallback(ctx: Context, code: Int = -1, isTimeout: Boolean = false) {
        if (code == 402 || code == 403) {
            DebugLogger.log("NETWORK", "Terminal Project Error ($code). Instant fallback engaged.")
            usePrimary = !usePrimary
            consecutiveFailures = 0
        } else if (code >= 500 || isTimeout) {
            consecutiveFailures++
            if (consecutiveFailures >= 15) {
                DebugLogger.log("NETWORK", "15 Consecutive Failures. Fallback engaged.")
                usePrimary = !usePrimary
                consecutiveFailures = 0
            }
        }
    }

    fun setEdgeUrls(ctx: Context, primary: String, secondary: String) {
        ctx.getSharedPreferences("cortex_edge_prefs", Context.MODE_PRIVATE).edit()
            .putString("primary_url", primary)
            .putString("secondary_url", secondary)
            .apply()
        DebugLogger.log("NETWORK", "Edge URLs overridden remotely.")
    }

    fun getGatewayUrl(ctx: Context): String = "${getBaseUrl(ctx)}/functions/v1/cortex-gateway"
    fun getStorageUrl(ctx: Context, bucket: String, path: String): String = "${getBaseUrl(ctx)}/storage/v1/object/$bucket/$path"
    fun getRestUrl(ctx: Context, endpoint: String): String = "${getBaseUrl(ctx)}/rest/v1/$endpoint"
    
    fun getLock(ctx: Context): String {
        if (!authorize(ctx)) return ""
        return getActiveEnvironment().key
    }

    private fun authorize(ctx: Context): Boolean = ctx.packageName == "com.example.myandroid"
}
