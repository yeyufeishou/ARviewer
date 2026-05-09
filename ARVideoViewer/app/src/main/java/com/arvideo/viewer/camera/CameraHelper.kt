package com.arvideo.viewer.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView

/**
 * Manages Camera2 lifecycle: opens rear camera and previews to a TextureView.
 */
class CameraHelper(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val bgThread = HandlerThread("CameraBackground").also { it.start() }
    private val bgHandler = Handler(bgThread.looper)

    @SuppressLint("MissingPermission")
    fun openCamera(textureView: TextureView, onOpened: () -> Unit = {}) {
        val cameraId = getRearCameraId() ?: return

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(camera, textureView)
                onOpened()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }
        cameraManager.openCamera(cameraId, stateCallback, bgHandler)
    }

    private fun startPreview(camera: CameraDevice, textureView: TextureView) {
        val surfaceTexture = textureView.surfaceTexture ?: return
        // Use a reasonable preview size; camera will scale as needed
        surfaceTexture.setDefaultBufferSize(1920, 1080)
        val surface = Surface(surfaceTexture)

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            bgHandler
        )
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    fun release() {
        closeCamera()
        bgThread.quitSafely()
    }

    private fun getRearCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }
}
