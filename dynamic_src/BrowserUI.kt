package com.example.dynamic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.view.View
import android.webkit.*
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myandroid.dynamic.DynamicEntry
import com.example.myandroid.dynamic.CortexNativeAPI
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrowserUI : DynamicEntry() {
    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    BrowserScreen(context, bridge, baseDir)
                }
            }
            post {
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        params.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        wm.updateViewLayout(this, params)
                    }
                } catch(e: Exception) { }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun BrowserScreen(context: Context, bridge: Any, baseDir: String) {
        val api = remember { CortexNativeAPI(bridge) }
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        var webView: WebView? by remember { mutableStateOf(null) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }

        var isRecordingDom by remember { mutableStateOf(false) }
        val capturedDoms = remember { mutableStateListOf<Pair<String, String>>() }

        LaunchedEffect(isRecordingDom, webView) {
            if (isRecordingDom && webView != null) {
                while (isActive && isRecordingDom) {
                    captureCurrentDom(webView!!, capturedDoms)
                    delay(4000) 
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { 
                    if (canGoBack) webView?.goBack() else api.close() 
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                        saveCapturedDomsToFile(context, capturedDoms, api)
                        capturedDoms.clear()
                    } else {
                        capturedDoms.clear()
                        isRecordingDom = true
                        webView?.let { captureCurrentDom(it, capturedDoms) }
                    }
                }) {
                    Text(text = if (isRecordingDom) "🔴" else "▶️", fontSize = 20.sp)
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
                            finalUrl = if (input.contains(".") && !input.contains(" ")) {
                                "https://$input"
                            } else {
                                "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
                            }
                        }
                        urlInput = finalUrl
                        webView?.loadUrl(finalUrl)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) { Text("GO", color = Color.White) }
                
                IconButton(onClick = { api.close() }) {
                    Text("✖", color = Color.White, fontSize = 20.sp)
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        
                        addJavascriptInterface(bridge, "Cortex")
                        
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                api.log("[WEB_CONSOLE] [${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                                return true
                            }
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                val transport = resultMsg?.obj as WebView.WebViewTransport
                                transport.webView = view
                                resultMsg.sendToTarget()
                                return true
                            }
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.grant(request.resources)
                            }
                            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                                callback?.invoke(origin, true, false)
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                                if (url != null) {
                                    urlInput = url
                                    if (isRecordingDom && view != null) captureCurrentDom(view, capturedDoms)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (autoPromptB64 != null) {
                                    api.log("[AUTO_VISIBLE] Target URL loaded. Injecting Automation Javascript.")
                                    try {
                                        val clazz = Class.forName("com.example.myandroid.DynamicUIManager")
                                        val instance = clazz.getField("INSTANCE").get(null)
                                        val script = clazz.getMethod("getAutomationScript", String::class.java).invoke(instance, autoPromptB64) as String
                                        view?.evaluateJavascript(script, null)
                                    } catch (e: Exception) {
                                        api.log("[AUTO_ERR] Failed to get Automation Script via reflection: ${e.message}")
                                    }
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                val polyfill = """
                                    javascript:(function() {
                                        try { delete Object.getPrototypeOf(navigator).webdriver; } catch(e) {}
                                        try {
                                            if (!window.chrome) {
                                                window.chrome = { app: { isInstalled: false }, runtime: { connect: function(){}, sendMessage: function(){} }, csi: function(){}, loadTimes: function(){} };
                                                window.chrome.runtime.connect.toString = function() { return "function connect() { [native code] }"; };
                                                window.chrome.runtime.sendMessage.toString = function() { return "function sendMessage() { [native code] }"; };
                                            }
                                        } catch(e) {}
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
                                        try {
                                            if (navigator.plugins.length === 0) {
                                                const mockP = [{name:'Chrome PDF Viewer',filename:'internal-pdf-viewer',description:'Portable Document Format'}];
                                                const pArr = [];
                                                mockP.forEach(p => { const pl = Object.create(Plugin.prototype); Object.assign(pl, p); pArr.push(pl); });
                                                Object.setPrototypeOf(pArr, PluginArray.prototype);
                                                const pGetter = function() { return pArr; };
                                                pGetter.toString = function() { return "function get plugins() { [native code] }"; };
                                                Object.defineProperty(Object.getPrototypeOf(navigator), 'plugins', { get: pGetter, configurable: true });
                                            }
                                        } catch(e) {}
                                        try {
                                            if (!window.Notification) window.Notification = { permission: 'default', requestPermission: function() { return Promise.resolve('default'); } };
                                            if (window.navigator.permissions) {
                                                const originalQuery = window.navigator.permissions.query;
                                                window.navigator.permissions.query = function(parameters) {
                                                    if (parameters.name === 'notifications') return Promise.resolve({ state: window.Notification.permission });
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
                        
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            val defaultUa = userAgentString
                            val cleanUa = defaultUa.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                            userAgentString = cleanUa
                        }

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        loadUrl(initialUrl)
                    }
                },
                update = { view -> webView = view },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    private fun captureCurrentDom(webView: WebView, capturedList: MutableList<Pair<String, String>>) {
        webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
            if (html != null) {
                val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                    try { org.json.JSONObject("{\"html\":$html}").getString("html") } catch(e: Exception) { html }
                } else html
                
                val currentUrl = webView.url ?: "unknown_url"
                val isDuplicate = capturedList.any { it.first == currentUrl && it.second == rawHtml }
                if (!isDuplicate) {
                    capturedList.add(Pair(currentUrl, rawHtml))
                }
            }
        }
    }

    private fun saveCapturedDomsToFile(context: Context, capturedList: List<Pair<String, String>>, api: CortexNativeAPI) {
        if (capturedList.isEmpty()) {
            api.toast("No DOM snapshots captured.")
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
                    
                    zos.putNextEntry(ZipEntry(entryName))
                    val header = "<!-- ORIGINAL URL: $url -->\n<!-- CAPTURED AT: ${Date()} -->\n"
                    zos.write((header + html).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            api.log("DOM_RECORDER Saved ${capturedList.size} snapshots to: ${zipFile.absolutePath}")
            api.toast("Saved to Downloads/DOM_Dump_$timestamp.zip")
        } catch (e: Exception) {
            api.toast("Failed to save ZIP: ${e.message}")
        }
    }
}
