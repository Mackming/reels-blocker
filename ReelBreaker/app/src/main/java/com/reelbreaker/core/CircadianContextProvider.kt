package com.reelbreaker.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V3-4 — Circadian & Dark-Room Vulnerability Scaling.
 * ===================================================
 * Late-night scrolling in a dark room is the highest-risk episode class:
 * blue-rich screen light in darkness suppresses melatonin, the prefrontal
 * cortex is at its lowest glucose/arousal (ego-depletion window), and the
 * behaviour is usually "revenge bedtime procrastination" — reclaiming
 * autonomy at the cost of sleep, which deepens next-day impulsivity
 * (a self-reinforcing loop documented in bedtime-procrastination research).
 *
 * Detection is battery-frugal: ONE-SHOT ambient-light sampling (listener
 * registered for 500 ms then unregistered immediately) combined with the
 * wall clock. If lux < 5 AND time within the user's sleep window
 * (default 22:30 -> 05:00) the session is flagged CIRCADIAN_VULNERABLE and
 * the engine tightens caps by 50%, Tier 1 starts tinted warm-red from
 * swipe #1, and Tier 2 asks a circadian-specific affective question.
 */
data class CircadianState(
    val vulnerable: Boolean,
    val ambientLux: Float,
    val hour: Int,
)

class CircadianContextProvider(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val light: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** Last sampled lux; NaN until first sample. */
    @Volatile var lastLux: Float = Float.NaN
        private set

    var sleepStart: LocalTime = LocalTime.of(22, 30)
    var sleepEnd: LocalTime = LocalTime.of(5, 0)

    private val sampling = AtomicBoolean(false)

    /**
     * Fire-and-forget one-shot sample. Safe to call on every session start;
     * concurrent calls are ignored. Unregisters after 500 ms or first event.
     */
    fun sampleOnce() {
        val s = sm ?: return
        val sensor = light ?: return          // device without ALS: stay silent
        if (!sampling.compareAndSet(false, true)) return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                lastLux = e.values[0]
                stop()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            fun stop() {
                if (sampling.compareAndSet(true, false)) {
                    s.unregisterListener(this)
                }
            }
        }
        s.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, 1)
        // hard stop after 500 ms even if no event arrives
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ l.stop() }, 500L)
    }

    fun evaluate(now: LocalTime = LocalTime.now()): CircadianState {
        val lux = if (lastLux.isNaN()) 999f else lastLux
        val inWindow = if (sleepStart <= sleepEnd) {
            !now.isBefore(sleepStart) && !now.isAfter(sleepEnd)
        } else { // wraps midnight, e.g. 22:30..05:00
            !now.isBefore(sleepStart) || !now.isAfter(sleepEnd)
        }
        return CircadianState(
            vulnerable = lux < DARK_LUX && inWindow,
            ambientLux = lux,
            hour = now.hour,
        )
    }

    companion object {
        const val DARK_LUX = 5f
    }
}
