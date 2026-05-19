package com.example.myandroid

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
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
        
        // FORCE HARDWARE ACCELERATION for complex web rendering
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

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
                            if (!finalUrl.startsWith("http")) finalUrl = "https://\$finalUrl"
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
                            
                            // 1. ADVANCED CHROME CLIENT: Catch site errors and Auto-Grant Permissions
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    DebugLogger.log("WEB_CONSOLE", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                                    return true
                                }
                                
                                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                    val transport = resultMsg?.obj as WebView.WebViewTransport
                                    transport.webView = view
                                    resultMsg.sendToTarget()
                                    return true
                                }

                                // Auto-grant web permissions to pass bot tests (Camera, Mic, etc.)
                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    request?.grant(request.resources)
                                }

                                // Auto-grant Geolocation to pass Location spoof checks
                                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                                    callback?.invoke(origin, true, false)
                                }
                            }

                            // 2. ENHANCED WEBVIEW CLIENT: Persistence and Polyfills
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                    if (url != null) urlInput = url
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    // ADVANCED POLYFILL INJECTION
                                    val polyfill = """
                                        javascript:(function() {
                                            // 1. Pass 'Chrome(New)' - Fake the chrome object structure
                                            if (!window.chrome) {
                                                window.chrome = {
                                                    app: { isInstalled: false },
                                                    webstore: { onInstall: {}, onDownloadProgress: {} },
                                                    runtime: { PlatformOs: { ANDROID: 'android' }, PlatformArch: { ARM64: 'arm64' } },
                                                    csi: function() {}, loadTimes: function() {}
                                                };
                                            }

                                            // 2. Pass 'WebDriver' - Remove webdriver trace completely
                                            Object.defineProperty(navigator, 'webdriver', {
                                                get: () => undefined
                                            });

                                            // 3. Pass 'Plugins is of type PluginArray' - Forge the Prototype
                                            try {
                                                if (navigator.plugins.length === 0) {
                                                    const fakePlugins = Object.create(PluginArray.prototype);
                                                    Object.defineProperty(fakePlugins, 'length', { get: () => 0 });
                                                    Object.defineProperty(navigator, 'plugins', { get: () => fakePlugins });
                                                }
                                            } catch(e) {}

                                            // 4. Pass 'Permissions(New)' - Mock the query to return 'prompt' instead of 'denied'
                                            try {
                                                if (window.navigator.permissions) {
                                                    const originalQuery = window.navigator.permissions.query;
                                                    window.navigator.permissions.query = (parameters) => {
                                                        if (parameters.name === 'notifications') {
                                                            return Promise.resolve({ state: Notification.permission || 'default' });
                                                        }
                                                        return originalQuery.call(navigator, parameters);
                                                    };
                                                }
                                            } catch(e) {}
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(polyfill, null)
                                }
                            }
                            
                            // 3. DESKTOP-GRADE SETTINGS
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                
                                // Enable critical modern APIs
                                mediaPlaybackRequiresUserGesture = false
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                
                                // User Agent Cloak
                                val defaultUa = userAgentString
                                val cleanUa = defaultUa.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                                userAgentString = cleanUa
                            }

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
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        globalWebView?.destroy()
        super.onDestroy()
    }
}
