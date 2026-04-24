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

        var cameraDevice: CameraDevice? = null
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surfaceTexture = SurfaceTexture(10)
                    val previewSurface = Surface(surfaceTexture)
                    val captureSurface = imageReader.surface
                    
                    camera.createCaptureSession(listOf(previewSurface, captureSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                // 1. START PREVIEW (WARM-UP PHASE)
                                // This allows the Auto-Exposure (AE) to calculate light levels before we snap the photo
                                val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                previewBuilder.addTarget(previewSurface)
                                session.setRepeatingRequest(previewBuilder.build(), null, handler)

                                // 2. DELAY FOR STABILIZATION
                                // Wait 1 second to let the sensor adjust ISO and Shutter Speed
                                handler.postDelayed({
                                    try {
                                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        captureBuilder.addTarget(captureSurface)
                                        // Set high quality priorities
                                        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                                                // ImageAvailableListener will now receive a well-exposed frame
                                            }
                                        }, handler)
                                    } catch (e: Exception) { deferred.complete(null) }
                                }, 1000)

                            } catch (e: Exception) { deferred.complete(null) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { deferred.complete(null) }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    deferred.complete(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    deferred.complete(null)
                }
            }, handler)
        } catch (e: Exception) { deferred.complete(null) }

        val result = withTimeoutOrNull(10000) { deferred.await() }
        
        // HARDWARE RELEASE CLEANUP
        cameraDevice?.close()
        imageReader.close()
        thread.quitSafely()
        
        return result
    }
}