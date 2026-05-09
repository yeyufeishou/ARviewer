package com.arvideo.viewer.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix

/**
 * Tracks device orientation using TYPE_ROTATION_VECTOR.
 * Extracts yaw (azimuth) and pitch for AR view panning.
 * Roll is ignored so the virtual screen stays upright.
 */
class SensorHelper(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // 3×3 rotation matrix from sensor
    private val rotMat3 = FloatArray(9)
    // Orientation angles: [azimuth, pitch, roll]
    private val orientation = FloatArray(3)

    // Calibration reference angles (set on start or on user reset)
    private var refAzimuth = 0f
    private var refPitch = 0f
    private var calibrated = false

    // Smoothed relative angles published to renderer
    @Volatile var relativeYaw = 0f   // positive = rotated right
    @Volatile var relativePitch = 0f // positive = tilted up

    // Low-pass filter coefficient (0 = no filtering, 1 = frozen)
    private val alpha = 0.15f

    fun start() {
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Call to treat current orientation as the "center" reference. */
    fun calibrate() {
        calibrated = false // will re-calibrate on next event
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotMat3, event.values)
        SensorManager.getOrientation(rotMat3, orientation)

        val azimuth = orientation[0]
        val pitch = orientation[1]

        if (!calibrated) {
            refAzimuth = azimuth
            refPitch = pitch
            calibrated = true
        }

        // Compute wrapped delta angles
        var deltaYaw = azimuth - refAzimuth
        if (deltaYaw > Math.PI) deltaYaw -= 2 * Math.PI.toFloat()
        if (deltaYaw < -Math.PI) deltaYaw += 2 * Math.PI.toFloat()

        val deltaPitch = pitch - refPitch

        // Low-pass smoothing
        relativeYaw = alpha * Math.toDegrees(deltaYaw.toDouble()).toFloat() +
                (1f - alpha) * relativeYaw
        relativePitch = alpha * Math.toDegrees(deltaPitch.toDouble()).toFloat() +
                (1f - alpha) * relativePitch
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
