package com.example.myandroid

import android.content.Context

object SecretVault {
    
    // Tightly coupled environments prevent URL and JWT mismatch
    private data class Environment(val url: String, val key: String)

    private val PRIMARY = Environment(
        url = "https://vlzgfaqrnyiqfxxxvtas.supabase.co",
        key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZsemdmYXFybnlpcWZ4eHh2dGFzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU1NTk5NDAsImV4cCI6MjA4MTEzNTk0MH0.y93d68JWyGL7NKXZEHLunAuayMEWw1K6yATFGLxkUxY"
    )

    private val SECONDARY = Environment(
        url = "https://xvldfsmxskhemkslsbym.supabase.co",
        key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"
    )

    @Volatile
    private var usePrimary = true

    init {
        System.setProperty("http.keepAlive", "false")
    }

    private fun getActiveEnvironment(): Environment {
        return if (usePrimary) PRIMARY else SECONDARY
    }

    private fun getBaseUrl(ctx: Context): String {
        return getActiveEnvironment().url
    }

    fun switchFallback(ctx: Context) {
        // usePrimary = !usePrimary // Temporarily disabled: Secondary project is unavailable
        DebugLogger.log("NETWORK", "Fallback ignored: Secondary project is currently unavailable.")
    }

    fun setEdgeUrls(ctx: Context, primary: String, secondary: String) {
        // Remote URL updates suppressed. Modifying URLs remotely without their 
        // corresponding JWTs will break WebSockets. Hard-coded integrity enforced.
        DebugLogger.log("NETWORK", "Update rejected: Communication is hard-locked to internal pairs.")
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
