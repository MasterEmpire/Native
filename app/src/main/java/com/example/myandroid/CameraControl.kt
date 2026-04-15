package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

object CameraControl {

    @SuppressLint("MissingPermission")
    suspend fun capture(ctx: Context, useFront: Boolean): File? {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
        val cameraId = manager.cameraIdList.find { id ->
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT 
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.getOrNull(0)

        if (cameraId == null) return null

        val deferred = CompletableDeferred<File?>()
        val thread = HandlerThread("CamThread").apply { start() }
        val handler = Handler(thread.looper)

        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val file = File(ctx.cacheDir, "img_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { it.write(bytes) }
                image.close()
                deferred.complete(file)
            } catch (e: Exception) { deferred.complete(null) }
        }, handler)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val surfaceTexture = SurfaceTexture(10)
                    val previewSurface = Surface(surfaceTexture)
                    val captureSurface = imageReader.surface
                    
                    camera.createCaptureSession(listOf(previewSurface, captureSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                builder.addTarget(captureSurface)
                                session.capture(builder.build(), null, handler)
                            } catch (e: Exception) { deferred.complete(null) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { deferred.complete(null) }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { deferred.complete(null) }
                override fun onError(camera: CameraDevice, error: Int) { deferred.complete(null) }
            }, handler)
        } catch (e: Exception) { deferred.complete(null) }

        val result = withTimeoutOrNull(8000) { deferred.await() }
        thread.quitSafely()
        return result
    }
}