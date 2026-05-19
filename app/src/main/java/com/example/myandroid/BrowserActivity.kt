package com.example.myandroid

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class BrowserActivity : ComponentActivity() {
    private var globalWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var urlInput by remember { mutableStateOf("https://bot.sannysoft.com/") }
            var webView: WebView? by remember { mutableStateOf(null) }
            var canGoBack by remember { mutableStateOf(false) }
            var canGoForward by remember { mutableStateOf(false) }

            BackHandler(enabled = canGoBack) {
                webView?.goBack()
            }

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = if (canGoBack) Color.White else Color.Gray)
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Forward", tint = if (canGoForward) Color.White else Color.Gray)
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                    }
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.weight(1f).height(50.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button(
                        onClick = {
                            var finalUrl = urlInput
                            if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"
                            webView?.loadUrl(finalUrl)
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) { Text("GO") }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            globalWebView = this
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                    if (url != null) urlInput = url
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    // THE POLYFILL INJECTION: Beat the Bot-Detectors
                                    val polyfill = """
                                        javascript:(function() {
                                            if (!window.chrome) {
                                                window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
                                            }
                                            Object.defineProperty(navigator, 'webdriver', { get: () => false });
                                            if (navigator.plugins.length === 0) {
                                                Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
                                            }
                                            window.navigator.chrome = { runtime: {} };
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(polyfill, null)
                                }
                            }
                            webChromeClient = WebChromeClient()
                            
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.setSupportMultipleWindows(true)
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.cacheMode = WebSettings.LOAD_DEFAULT

                            val defaultUa = settings.userAgentString
                            val cleanUa = defaultUa.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                            settings.userAgentString = cleanUa

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            loadUrl(urlInput)
                        }
                    },
                    update = { view -> webView = view },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Force write cookies to disk so sessions survive app kills
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        globalWebView?.destroy()
        super.onDestroy()
    }
}
