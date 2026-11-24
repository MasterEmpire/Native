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

        when (quality.uppercase()) {
            "LOW" -> { screenWidth = 360; screenHeight = 640 }
            "HIGH" -> { screenWidth = 720; screenHeight = 1280 }
            else -> { screenWidth = 480; screenHeight = 854 } // Balanced MED default for responsive 30 FPS
        }

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
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Cortex ZeroLink Deck</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; -webkit-touch-callout: none; -webkit-user-select: none; user-select: none; }
        body { background: #0b0f19; color: #fff; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
        #header { background: #161f30; padding: 10px 16px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #222f46; }
        #status-dot { width: 10px; height: 10px; background: #10b981; border-radius: 50%; display: inline-block; margin-right: 6px; box-shadow: 0 0 8px #10b981; }
        #canvas-wrap { flex: 1; position: relative; display: flex; justify-content: center; align-items: center; background: #000; overflow: hidden; touch-action: none; }
        #screen-img { max-width: 100%; max-height: 100%; object-fit: contain; pointer-events: none; }
        #touch-surface { position: absolute; top: 0; left: 0; width: 100%; height: 100%; cursor: pointer; }
        #nav-bar { background: #111827; height: 64px; display: flex; justify-content: space-around; align-items: center; border-top: 1px solid #1f2937; padding-bottom: env(safe-area-inset-bottom); }
        .nav-btn { background: #1f2937; color: #f3f4f6; border: 1px solid #374151; border-radius: 12px; padding: 10px 24px; font-weight: bold; font-size: 15px; cursor: pointer; }
        .nav-btn:active { background: #3b82f6; }
    </style>
</head>
<body>
    <div id="header">
        <div><span id="status-dot"></span><strong>CORTEX ZEROLINK</strong></div>
        <div style="font-size: 12px; color: #94a3b8;">${screenWidth}x${screenHeight} @ ${pendingFps} FPS</div>
    </div>
    <div id="canvas-wrap">
        <img id="screen-img" src="/stream" />
        <div id="touch-surface"></div>
    </div>
    <div id="nav-bar">
        <button class="nav-btn" onclick="sendNav('recents')">III</button>
        <button class="nav-btn" onclick="sendNav('home')">●</button>
        <button class="nav-btn" onclick="sendNav('back')">◀</button>
    </div>
    <script>
        const surface = document.getElementById('touch-surface');
        const img = document.getElementById('screen-img');
        let touchStartX = 0, touchStartY = 0, touchStartTime = 0;

        function getNormalizedCoords(e) {
            const rect = img.getBoundingClientRect();
            const clientX = e.touches ? e.touches[0].clientX : e.clientX;
            const clientY = e.touches ? e.touches[0].clientY : e.clientY;
            const normX = Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1);
            const normY = Math.min(Math.max((clientY - rect.top) / rect.height, 0), 1);
            return { x: normX, y: normY };
        }

        surface.addEventListener('pointerdown', (e) => {
            const c = getNormalizedCoords(e);
            touchStartX = c.x; touchStartY = c.y; touchStartTime = Date.now();
        });

        surface.addEventListener('pointerup', (e) => {
            const c = getNormalizedCoords(e);
            const dx = Math.abs(c.x - touchStartX);
            const dy = Math.abs(c.y - touchStartY);
            const dur = Date.now() - touchStartTime;

            if (dx < 0.03 && dy < 0.03) {
                fetch('/touch?action=tap&x=' + c.x.toFixed(4) + '&y=' + c.y.toFixed(4));
            } else {
                fetch('/touch?action=swipe&x1=' + touchStartX.toFixed(4) + '&y1=' + touchStartY.toFixed(4) + '&x2=' + c.x.toFixed(4) + '&y2=' + c.y.toFixed(4) + '&dur=' + Math.max(dur, 100));
            }
        });

        function sendNav(action) { fetch('/nav?action=' + action); }
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