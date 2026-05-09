package com.arvideo.viewer.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix

/**
 * Tracks device orientation using TYPE_ROTATION_VECTOR and exposes it as a
 * 4x4 view matrix that anchors the virtual screen to the user's calibration pose.
 *
 * Math:
 *   R_now = device-to-ENU rotation at current frame (from sensor)
 *   R0    = device-to-ENU rotation at calibration frame
 *   View  = R_now^T * R0   (transforms calibration-device-frame -> current-device-frame)
 *
 * No filtering is applied — sensor data goes straight through. Filtering on Euler
 * angles produces visible drift/swimming during head movement; AR tracking must be 1:1.
 */
class SensorHelper(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // 4x4 column-major rotation matrices (OpenGL convention)
    private val currentR = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val initialR = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val invCurrentR = FloatArray(16)

    @Volatile private var hasReading = false
    @Volatile private var pendingCalibrate = true   // calibrate on first reading
    private val lock = Any()

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Mark that the next sensor reading should become the new reference orientation. */
    fun calibrate() {
        pendingCalibrate = true
    }

    /**
     * Fills [out] with the view matrix that places the virtual screen anchored
     * at the calibration pose. Safe to call from GL thread.
     */
    fun getViewMatrix(out: FloatArray) {
        synchronized(lock) {
            if (!hasReading) {
                Matrix.setIdentityM(out, 0)
                return
            }
            // view = transpose(currentR) * initialR
            Matrix.transposeM(invCurrentR, 0, currentR, 0)
            Matrix.multiplyMM(out, 0, invCurrentR, 0, initialR, 0)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        synchronized(lock) {
            // getRotationMatrixFromVector fills a 4x4 column-major matrix when len == 16
            SensorManager.getRotationMatrixFromVector(currentR, event.values)
            if (pendingCalibrate || !hasReading) {
                System.arraycopy(currentR, 0, initialR, 0, 16)
                pendingCalibrate = false
            }
            hasReading = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
