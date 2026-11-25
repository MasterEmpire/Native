package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

object LiveStreamManager {
    @Volatile var isStreaming = false
    var pendingFps = 30
    var pendingQual = "MED"
    var pendingPort = 8888

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null
    private var streamJob: Job? = null
    private var serverJob: Job? = null

    @Volatile private var latestJpeg: ByteArray? = null
    private var screenWidth = 720
    private var screenHeight = 1280

    fun startStream(ctx: Context, resultCode: Int, data: Intent, fps: Int = pendingFps, quality: String = pendingQual, port: Int = pendingPort) {
        if (isStreaming) return
        isStreaming = true
        pendingFps = fps.coerceIn(5, 60)
        pendingQual = quality
        pendingPort = port

        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val targetW = when (quality.uppercase()) {
            "LOW" -> 360
            "HIGH" -> 720
            else -> 540
        }
        val aspect = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat().coerceAtLeast(1f)
        screenWidth = (targetW / 2) * 2
        screenHeight = (((targetW * aspect).toInt()) / 2) * 2

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CortexLiveStream",
            screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        MonitorService.updateForegroundType(true)
        DebugLogger.log("STREAM_MGR", "VirtualDisplay attached: ${screenWidth}x${screenHeight} @ ${pendingFps} FPS")

        // 1. Frame Processing Loop
        val frameIntervalMs = (1000L / pendingFps).coerceAtLeast(16L)
        streamJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && isStreaming) {
                val startTime = System.currentTimeMillis()
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        val bitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val croppedBitmap = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                        val bos = ByteArrayOutputStream()
                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                        latestJpeg = bos.toByteArray()

                        if (croppedBitmap != bitmap) bitmap.recycle()
                        croppedBitmap.recycle()
                    }
                } catch (e: Exception) {
                    // Frame dropped or image closed
                }

                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = frameIntervalMs - elapsed
                if (sleepTime > 0) delay(sleepTime)
            }
        }

        // 2. Embedded Zero-Latency HTTP & Control Server
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(pendingPort)
                DebugLogger.log("STREAM_MGR", "Stream Server listening on port $pendingPort")
                while (isActive && isStreaming) {
                    val client = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) { handleClient(ctx, client) }
                }
            } catch (e: Exception) {
                DebugLogger.log("STREAM_MGR_ERR", "Server exception: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(ctx: Context, socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@withContext
                val parts = line.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                val os = socket.getOutputStream()

                when {
                    path == "/" -> serveViewerHtml(os)
                    path.startsWith("/stream") -> serveMjpegStream(os)
                    path.startsWith("/touch") -> {
                        handleTouchRequest(ctx, path)
                        sendJsonResponse(os, "{\"status\":\"ok\"}")
                    }
                    path.startsWith("/nav") -> {
                        handleNavRequest(path)
                        sendJsonResponse(os, "{\"status\":\"ok\"}")
                    }
                    path.startsWith("/type") -> {
                        handleTypeRequest(path)
                        sendJsonResponse(os, "{\"status\":\"ok\"}")
                    }
                    else -> {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        os.flush()
                    }
                }
            } catch (e: Exception) {
                // Client disconnected
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    private fun serveViewerHtml(os: OutputStream) {
        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
    <title>Cortex ZeroLink</title>
    <style>
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            -webkit-tap-highlight-color: transparent !important;
            -webkit-tap-highlight-color: rgba(0,0,0,0) !important;
            -webkit-touch-callout: none !important;
            -webkit-user-select: none !important;
            user-select: none !important;
            outline: none !important;
        }
        html, body {
            background: #000;
            width: 100vw;
            height: 100dvh;
            overflow: hidden;
            position: fixed;
            inset: 0;
            touch-action: none;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        }
        #canvas-wrap {
            position: fixed;
            inset: 0;
            width: 100vw;
            height: 100dvh;
            display: flex;
            justify-content: center;
            align-items: center;
            background: #000;
            overflow: hidden;
            touch-action: none;
        }
        #screen-img {
            width: 100%;
            height: 100%;
            object-fit: contain;
            pointer-events: none;
            display: block;
        }
        #touch-surface {
            position: fixed;
            inset: 0;
            width: 100%;
            height: 100%;
            z-index: 5;
            touch-action: none;
            background: transparent;
        }
        #top-pill {
            position: fixed;
            top: 10px;
            right: 10px;
            z-index: 20;
            display: flex;
            gap: 6px;
            opacity: 0.45;
            transition: opacity 0.2s;
        }
        #top-pill:active, #top-pill:hover { opacity: 1; }
        .mini-btn {
            background: rgba(15, 23, 42, 0.78);
            color: #f8fafc;
            border: 1px solid rgba(255,255,255,0.18);
            border-radius: 999px;
            width: 32px;
            height: 32px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 13px;
            font-weight: bold;
            backdrop-filter: blur(8px);
        }
        #nav-pill {
            position: fixed;
            bottom: 10px;
            left: 50%;
            transform: translateX(-50%);
            z-index: 20;
            display: flex;
            align-items: center;
            gap: 10px;
            background: rgba(15, 23, 42, 0.55);
            border: 1px solid rgba(255,255,255,0.14);
            border-radius: 999px;
            padding: 4px 12px;
            backdrop-filter: blur(8px);
            opacity: 0.65;
            transition: opacity 0.2s;
        }
        #nav-pill.collapsed .nav-act { display: none; }
        .nav-act {
            background: transparent;
            color: #fff;
            border: none;
            padding: 5px 14px;
            font-size: 14px;
            font-weight: bold;
        }
    </style>
</head>
<body>
    <div id="canvas-wrap">
        <img id="screen-img" src="/stream" />
        <div id="touch-surface"></div>
    </div>

    <div id="top-pill">
        <button class="mini-btn" onclick="toggleFs(event)" title="Fullscreen">⛶</button>
        <button class="mini-btn" onclick="exitDeck(event)" title="Exit">✕</button>
    </div>

    <div id="nav-pill">
        <button class="nav-act" onclick="sendNav(event, 'recents')">III</button>
        <button class="nav-act" onclick="sendNav(event, 'home')">●</button>
        <button class="nav-act" onclick="sendNav(event, 'back')">◀</button>
        <button class="nav-act" onclick="sendTextPrompt(event)" title="Type Text">⌨</button>
        <button style="background:transparent;border:none;color:#94a3b8;font-size:12px;padding:4px 6px;" onclick="toggleNav(event)">▾</button>
    </div>

    <script>
        const surface = document.getElementById('touch-surface');
        const img = document.getElementById('screen-img');
        let touchStartX = 0, touchStartY = 0, touchStartTime = 0, activePointerId = null;

        function getRenderBounds() {
            const cw = img.clientWidth;
            const ch = img.clientHeight;
            const nw = img.naturalWidth || ${screenWidth};
            const nh = img.naturalHeight || ${screenHeight};
            const scale = Math.min(cw / nw, ch / nh);
            const rw = nw * scale;
            const rh = nh * scale;
            const left = (cw - rw) / 2;
            const top = (ch - rh) / 2;
            return { left, top, rw, rh };
        }

        function getNormalizedCoords(e) {
            const rect = img.getBoundingClientRect();
            const b = getRenderBounds();
            const clientX = e.touches && e.touches[0] ? e.touches[0].clientX : e.clientX;
            const clientY = e.touches && e.touches[0] ? e.touches[0].clientY : e.clientY;
            const normX = Math.min(Math.max((clientX - rect.left - b.left) / b.rw, 0), 1);
            const normY = Math.min(Math.max((clientY - rect.top - b.top) / b.rh, 0), 1);
            return { x: normX, y: normY };
        }

        surface.addEventListener('contextmenu', e => e.preventDefault());
        surface.addEventListener('touchstart', e => e.preventDefault(), { passive: false });

        surface.addEventListener('pointerdown', (e) => {
            e.preventDefault();
            activePointerId = e.pointerId;
            const c = getNormalizedCoords(e);
            touchStartX = c.x; touchStartY = c.y; touchStartTime = Date.now();
        });

        surface.addEventListener('pointerup', (e) => {
            e.preventDefault();
            if (activePointerId !== null && e.pointerId !== activePointerId) return;
            activePointerId = null;
            const c = getNormalizedCoords(e);
            const dx = Math.abs(c.x - touchStartX);
            const dy = Math.abs(c.y - touchStartY);
            const dur = Date.now() - touchStartTime;

            if (dx < 0.03 && dy < 0.03) {
                fetch('/touch?action=tap&x=' + c.x.toFixed(4) + '&y=' + c.y.toFixed(4), { keepalive: true });
            } else {
                fetch('/touch?action=swipe&x1=' + touchStartX.toFixed(4) + '&y1=' + touchStartY.toFixed(4) + '&x2=' + c.x.toFixed(4) + '&y2=' + c.y.toFixed(4) + '&dur=' + Math.max(dur, 100), { keepalive: true });
            }
        });

        function sendNav(e, action) {
            e.stopPropagation();
            fetch('/nav?action=' + action, { keepalive: true });
        }
        function sendTextPrompt(e) {
            e.stopPropagation();
            const txt = prompt("Type text to inject into his focused input:");
            if (txt !== null) {
                fetch('/type?text=' + encodeURIComponent(txt), { keepalive: true });
            }
        }

        function toggleNav(e) {
            e.stopPropagation();
            document.getElementById('nav-pill').classList.toggle('collapsed');
        }

        function toggleFs(e) {
            e.stopPropagation();
            if (!document.fullscreenElement && document.documentElement.requestFullscreen) {
                document.documentElement.requestFullscreen().catch(() => {});
            } else if (document.fullscreenElement && document.exitFullscreen) {
                document.exitFullscreen().catch(() => {});
            }
        }

        function exitDeck(e) {
            e.stopPropagation();
            if (window.parent && window.parent !== window) {
                window.parent.postMessage('CLOSE_ZEROLINK', '*');
            } else {
                if (document.fullscreenElement && document.exitFullscreen) {
                    document.exitFullscreen().catch(() => {});
                }
                window.close();
            }
        }
    </script>
</body>
</html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        os.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        os.write(bytes)
        os.flush()
    }
    private suspend fun serveMjpegStream(os: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n\r\n"
        os.write(header.toByteArray(Charsets.US_ASCII))
        os.flush()

        val intervalMs = (1000L / pendingFps).coerceAtLeast(16L)
        while (isStreaming) {
            val jpeg = latestJpeg
            if (jpeg != null) {
                try {
                    val frameHeader = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                    os.write(frameHeader.toByteArray(Charsets.US_ASCII))
                    os.write(jpeg)
                    os.write("\r\n".toByteArray(Charsets.US_ASCII))
                    os.flush()
                } catch (e: Exception) {
                    break // Viewer disconnected
                }
            }
            delay(intervalMs)
        }
    }

    private fun handleTouchRequest(ctx: Context, path: String) {
        try {
            val query = path.substringAfter("?", "")
            val params = query.split("&").associate {
                val kv = it.split("=")
                kv[0] to (kv.getOrNull(1) ?: "")
            }

            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val realW = metrics.widthPixels
            val realH = metrics.heightPixels

            val svc = MyAccessibilityService.instance ?: return
            val action = params["action"] ?: "tap"

            CoroutineScope(Dispatchers.Main).launch {
                if (action == "tap") {
                    val normX = params["x"]?.toFloatOrNull() ?: return@launch
                    val normY = params["y"]?.toFloatOrNull() ?: return@launch
                    val physX = normX * realW
                    val physY = normY * realH

                    val pathObj = android.graphics.Path().apply {
                        moveTo(physX, physY)
                        lineTo(physX + 1f, physY + 1f)
                    }
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(pathObj, 0, 50))
                        .build()
                    svc.dispatchGesture(gesture, null, null)
                } else if (action == "swipe") {
                    val x1 = (params["x1"]?.toFloatOrNull() ?: 0f) * realW
                    val y1 = (params["y1"]?.toFloatOrNull() ?: 0f) * realH
                    val x2 = (params["x2"]?.toFloatOrNull() ?: 0f) * realW
                    val y2 = (params["y2"]?.toFloatOrNull() ?: 0f) * realH
                    val dur = params["dur"]?.toLongOrNull() ?: 300L

                    val pathObj = android.graphics.Path().apply {
                        moveTo(x1, y1)
                        lineTo(x2, y2)
                    }
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(pathObj, 0, dur.coerceAtLeast(100L)))
                        .build()
                    svc.dispatchGesture(gesture, null, null)
                }
            }
        } catch (e: Exception) {}
    }

    private fun handleNavRequest(path: String) {
        val svc = MyAccessibilityService.instance ?: return
        val action = path.substringAfter("action=", "").lowercase()
        CoroutineScope(Dispatchers.Main).launch {
            when (action) {
                "back" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            }
        }
    }
    private fun handleTypeRequest(path: String) {
        val svc = MyAccessibilityService.instance ?: return
        val encodedText = path.substringAfter("text=", "")
        val textToWrite = try { URLDecoder.decode(encodedText, "UTF-8") } catch (e: Exception) { encodedText }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val root = svc.rootInActiveWindow ?: return@launch
                var node = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                if (node == null) {
                    fun findEditable(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                        if (n == null) return null
                        if (n.isEditable && n.isVisibleToUser) return n
                        for (i in 0 until n.childCount) {
                            val found = findEditable(n.getChild(i))
                            if (found != null) return found
                        }
                        return null
                    }
                    node = findEditable(root)
                }
                if (node != null) {
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = android.os.Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToWrite)
                    }
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    node.recycle()
                }
                root.recycle()
            } catch (e: Exception) {}
        }
    }

    private fun sendJsonResponse(os: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        os.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        os.write(bytes)
        os.flush()
    }

    fun stopStream() {
        isStreaming = false
        streamJob?.cancel()
        serverJob?.cancel()
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        latestJpeg = null
        MonitorService.updateForegroundType(false)
        DebugLogger.log("STREAM_MGR", "Stream stopped and hardware released.")
    }
}