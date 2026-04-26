package com.example.myandroid

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import org.json.JSONObject

object EnvironmentalManager {

    suspend fun sampleSensors(ctx: Context): JSONObject {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val result = JSONObject()
        
        var lux = -1f
        var proximity = -1f
        var accel = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> lux = event.values[0]
                    Sensor.TYPE_PROXIMITY -> proximity = event.values[0]
                    Sensor.TYPE_ACCELEROMETER -> accel = event.values.clone()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val sensors = listOf(
            sm.getDefaultSensor(Sensor.TYPE_LIGHT),
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY),
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        )

        sensors.forEach { it?.let { s -> sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI) } }

        // Sample for 2 seconds to get stable readings
        delay(2000)

        sm.unregisterListener(listener)

        // 1. Process Light
        result.put("lux", lux)
        result.put("light_state", when {
            lux < 0 -> "UNKNOWN"
            lux <= 5 -> "PITCH_BLACK"
            lux <= 50 -> "DARK_ROOM"
            lux <= 500 -> "INDOOR_OFFICE"
            else -> "BRIGHT_OUTDOOR"
        })

        // 2. Process Proximity
        result.put("proximity_raw", proximity)
        result.put("is_covered", proximity == 0f)

        // 3. Process Motion
        val totalForce = Math.sqrt((accel[0]*accel[0] + accel[1]*accel[1] + accel[2]*accel[2]).toDouble())
        result.put("accel_magnitude", totalForce)
        result.put("motion_state", when {
            totalForce < 5 -> "FREEFALL/LOW_G"
            totalForce in 9.5..10.5 -> "STATIONARY"
            totalForce > 12 -> "ACTIVE_MOTION"
            else -> "VIBRATION"
        })

        // 4. Temporal Integrity & Redundancy
        val now = System.currentTimeMillis()
        result.put("captured_at", now)
        
        // Pipe to Survivor Protocol (Chronological Offline Logs)
        DumpManager.appendLog("SENSOR_AUDIT", result)

        return result
    }
}