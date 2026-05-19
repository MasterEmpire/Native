package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

                // DOM RECORDER STATE
                var isRecordingDom by remember { mutableStateOf(false) }
                val capturedDoms = remember { mutableStateListOf<Pair<String, String>>() }
                val context = androidx.compose.ui.platform.LocalContext.current

                // Periodic DOM Polling while recording is active (Captures dynamically-rendered elements)
                LaunchedEffect(isRecordingDom, webView) {
                    if (isRecordingDom && webView != null) {
                        while (isActive && isRecordingDom) {
                            captureCurrentDom(webView!!, capturedDoms)
                            delay(4000) 
                        }
                    }
                }

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
                    IconButton(onClick = {
                        if (isRecordingDom) {
                            isRecordingDom = false
                            saveCapturedDomsToFile(context, capturedDoms)
                            capturedDoms.clear()
                        } else {
                            capturedDoms.clear()
                            isRecordingDom = true
                            webView?.let { captureCurrentDom(it, capturedDoms) }
                        }
                    }) {
                        Text(
                            text = if (isRecordingDom) "🔴" else "▶️",
                            fontSize = 20.sp
                        )
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
                            val input = urlInput.trim()
                            var finalUrl = input
                            if (!input.startsWith("http://") && !input.startsWith("https://")) {
                                // If it contains a dot and no spaces, it's likely a URL. Otherwise, treat as a search query.
                                finalUrl = if (input.contains(".") && !input.contains(" ")) {
                                    "https://$input"
                                } else {
                                    "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
                                }
                            }
                            urlInput = finalUrl // Update the address bar to show where we are going
                            webView?.loadUrl(finalUrl)
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) { Text("GO") }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
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
                            // 2. ENHANCED WEBVIEW CLIENT: Persistence and Polyfills
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                    if (url != null) {
                                        urlInput = url
                                        if (isRecordingDom && view != null) {
                                            captureCurrentDom(view, capturedDoms)
                                        }
                                    }
                                }
                            }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    // ADVANCED POLYFILL INJECTION (HARDENED)
                                    val polyfill = """
                                        javascript:(function() {
                                            // 1. Destroy WebDriver totally (Passes creepjs/intoli)
                                            try { delete Object.getPrototypeOf(navigator).webdriver; } catch(e) {}
                                            
                                            // 2. Forge Chrome Object with Native Strings
                                            try {
                                                if (!window.chrome) {
                                                    window.chrome = {
                                                        app: { isInstalled: false },
                                                        runtime: { connect: function(){}, sendMessage: function(){} },
                                                        csi: function(){}, loadTimes: function(){}
                                                    };
                                                    window.chrome.runtime.connect.toString = function() { return "function connect() { [native code] }"; };
                                                    window.chrome.runtime.sendMessage.toString = function() { return "function sendMessage() { [native code] }"; };
                                                }
                                            } catch(e) {}
                                            
                                            // 3. IFrame Sandboxing Escape Hatch
                                            try {
                                                const originalCreateElement = document.createElement;
                                                document.createElement = function(tagName) {
                                                    const el = originalCreateElement.call(document, tagName);
                                                    if (tagName.toLowerCase() === 'iframe') {
                                                        el.addEventListener('load', function() {
                                                            try { delete Object.getPrototypeOf(this.contentWindow.navigator).webdriver; } catch(e) {}
                                                            try { this.contentWindow.chrome = window.chrome; } catch(e) {}
                                                        });
                                                    }
                                                    return el;
                                                };
                                                document.createElement.toString = function() { return "function createElement() { [native code] }"; };
                                            } catch(e) {}
                                            
                                            // 4. Properly Structured PluginArray
                                            try {
                                                if (navigator.plugins.length === 0) {
                                                    const mockP = [{name:'Chrome PDF Viewer',filename:'internal-pdf-viewer',description:'Portable Document Format'}];
                                                    const pArr = [];
                                                    mockP.forEach(p => { 
                                                        const pl = Object.create(Plugin.prototype); 
                                                        Object.assign(pl, p); 
                                                        pArr.push(pl); 
                                                    });
                                                    Object.setPrototypeOf(pArr, PluginArray.prototype);
                                                    const pGetter = function() { return pArr; };
                                                    pGetter.toString = function() { return "function get plugins() { [native code] }"; };
                                                    Object.defineProperty(Object.getPrototypeOf(navigator), 'plugins', { get: pGetter, configurable: true });
                                                }
                                            } catch(e) {}
                                            
                                            // 5. Notification & Permissions Forge
                                            try {
                                                if (!window.Notification) {
                                                    window.Notification = { permission: 'default', requestPermission: function() { return Promise.resolve('default'); } };
                                                }
                                                if (window.navigator.permissions) {
                                                    const originalQuery = window.navigator.permissions.query;
                                                    window.navigator.permissions.query = function(parameters) {
                                                        if (parameters.name === 'notifications') {
                                                            return Promise.resolve({ state: window.Notification.permission });
                                                        }
                                                        return originalQuery.call(navigator, parameters);
                                                    };
                                                    window.navigator.permissions.query.toString = function() { return "function query() { [native code] }"; };
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

        // --- DOM RECORDER UTILITIES ---
        private fun captureCurrentDom(webView: WebView, capturedList: MutableList<Pair<String, String>>) {
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                if (html != null) {
                    val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                        try {
                            org.json.JSONObject("{\"html\":$html}").getString("html")
                        } catch(e: Exception) {
                            html
                        }
                    } else {
                        html
                    }
                    
                    val currentUrl = webView.url ?: "unknown_url"
                    val isDuplicate = capturedList.any { it.first == currentUrl && it.second == rawHtml }
                    if (!isDuplicate) {
                        capturedList.add(Pair(currentUrl, rawHtml))
                        DebugLogger.log("DOM_RECORDER", "Captured snapshot for: $currentUrl (Total: ${capturedList.size})")
                    }
                }
            }
        }

        private fun saveCapturedDomsToFile(context: Context, capturedList: List<Pair<String, String>>) {
            if (capturedList.isEmpty()) {
                Toast.makeText(context, "No DOM snapshots captured.", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val zipFile = File(downloadDir, "DOM_Dump_$timestamp.zip")

                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    capturedList.forEachIndexed { index, pair ->
                        val url = pair.first
                        val html = pair.second
                        
                        val cleanUrl = url.replace(Regex("[^a-zA-Z0-9]"), "_").take(50)
                        val entryName = "snapshot_${index + 1}_$cleanUrl.html"
                        
                        val entry = ZipEntry(entryName)
                        zos.putNextEntry(entry)
                        
                        val header = "<!-- ORIGINAL URL: $url -->\n<!-- CAPTURED AT: ${Date()} -->\n"
                        val fullContent = header + html
                        
                        zos.write(fullContent.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }

                DebugLogger.log("DOM_RECORDER", "Saved ${capturedList.size} snapshots to: ${zipFile.absolutePath}")
                Toast.makeText(context, "Saved to Downloads/DOM_Dump_$timestamp.zip", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                DebugLogger.log("DOM_RECORDER_ERR", "Save failed: ${e.message}")
                Toast.makeText(context, "Failed to save ZIP: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
