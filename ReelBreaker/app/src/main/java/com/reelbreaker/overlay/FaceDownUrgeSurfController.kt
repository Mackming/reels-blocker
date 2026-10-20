package com.reelbreaker.overlay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.reelbreaker.R

/**
 * V4-4 — Stage 2 "The Gearshift": Face-Down Urge Surfing.
 * ======================================================
 * Converts the urge wave into a physical ritual: the user flips the phone
 * face-down and lets the craving crest without feeding it (urge surfing,
 * Marlatt) while removing themselves from the stimulus entirely (stimulus
 * control). Accelerometer + proximity make the state machine honest:
 *
 *   prediction tap -> [Low/Medium/High] pull strength (craving expectancy)
 *   face-down lock : z-axis < -8.5 m/s^2 AND proximity covered
 *                    timer PAUSES if picked up early — no cheating, no shame,
 *                    just "flip it again" (the wave is still running)
 *   25 s survived  -> gentle success vibration ("the wave passed")
 *   pickup         -> expectancy-violation reflection:
 *                     "You surfed a HIGH urge in 25 seconds. Still want to
 *                      scroll, or done for now?" [I'm Done (Win)] | [2 More Minutes]
 *
 * Inhibitory-learning theory (Craske): the belief "I can't stand this urge"
 * disconfirms only when the user rides it out WITH the prediction on record.
 * Recording predicted strength then surviving makes the violation explicit.
 */
class FaceDownUrgeSurfController(
    private val context: Context,
    private val wm: WindowManager,
    private val host: Host,
) {

    interface Host {
        /** arm finished; [success]=user chose "I'm Done" */
        fun onSurfFinished(prediction: String, outcome: String)
        fun vibrate(ms: Long)
        fun goHome()
    }

    enum class Phase { PREDICT, SURFING, REFLECT }

    private val handler = Handler(Looper.getMainLooper())
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var view: View? = null
    private var phase = Phase.PREDICT
    private var prediction = ""
    private var faceDown = false
    private var nearFlag = true
    private var accumulatedMs = 0L
    private var lastTickAt = 0L
    private var tvStatus: TextView? = null
    private var tvCount: TextView? = null
    private var pendingArm = ""
    /** V4-4: set by show(); renders the expectancy-violation reflection step. */
    private var surfReflectHook: ((LinearLayout?) -> Unit)? = null
    /** The vertical button row inside the current overlay (rebuilt per phase). */
    private var btnRowRef: LinearLayout? = null

        companion object {
            const val SURF_TARGET_MS = 25_000L
            const val FACE_DOWN_Z_THRESHOLD = -8.5
        }

        // Resolved lazily (inside show()) so class-load never touches R before init.
        private fun labelRes(level: String): Int = when (level) {
            "Low" -> R.string.surf_low
            "Medium" -> R.string.surf_medium
            else -> R.string.surf_high
        }

    val isShowing get() = view != null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // device-frame z: face-down on a table => z ≈ -9.8
                    faceDown = e.values[2] < FACE_DOWN_Z_THRESHOLD
                }
                Sensor.TYPE_PROXIMITY -> {
                    val max = e.sensor.maximumRange
                    nearFlag = e.values[0] <= max.coerceAtMost(1f) || e.values[0] < 3f
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun show(armName: String = "") {
        if (view != null) return
        pendingArm = armName
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF60B0B12.toInt())
            setPadding(56, 72, 56, 56)
        }
        root.setOnTouchListener { _, _: MotionEvent -> true }

        val title = TextView(context).apply {
            textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
            text = context.getString(R.string.surf_title)
        }
        tvStatus = TextView(context).apply {
            textSize = 15f; setTextColor(0xFFC9C6D9.toInt()); gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        tvCount = TextView(context).apply {
            textSize = 40f; setTextColor(0xFF8FD3FF.toInt()); gravity = Gravity.CENTER
        }
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        btnRowRef = btnRow
        root.addView(title); root.addView(tvStatus); root.addView(tvCount); root.addView(btnRow)

        fun renderPredict() {
            phase = Phase.PREDICT
            tvStatus!!.text = context.getString(R.string.surf_predict_prompt)
            tvCount!!.text = ""
            btnRow.removeAllViews()
            listOf("Low", "Medium", "High").forEach { lvl ->
                val b = Button(context).apply {
                    text = context.getString(labelRes(lvl))
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                b.setOnClickListener { startSurf(lvl, btnRow) }
                btnRow.addView(b)
            }
        }

        fun startSurf(level: String, row: LinearLayout) {
            prediction = level
            phase = Phase.SURFING
            accumulatedMs = 0L
            lastTickAt = System.currentTimeMillis()
            tvStatus!!.text = context.getString(R.string.surf_flip_now)
            row.removeAllViews()
            registerSensors()
            handler.post(surfTicker)
        }

        fun renderReflect(row: LinearLayout) {
            phase = Phase.REFLECT
            unregisterSensors()
            tvCount!!.text = "✓"
            tvStatus!!.text = context.getString(R.string.surf_reflect, prediction)
            row.removeAllViews()
            val win = Button(context).apply {
                text = context.getString(R.string.surf_done_win); isAllCaps = false
            }
            val more = Button(context).apply {
                text = context.getString(R.string.surf_two_more); isAllCaps = false
            }
            win.setOnClickListener { finish("SURFED") ; host.goHome() }
            more.setOnClickListener { finish("OVERRIDE") }
            row.addView(win); row.addView(more)
        }

        fun finish(outcome: String) {
            detach()
            host.onSurfFinished(prediction, outcome)
            ReelBreakerAppHooks.onInterventionFinished(outcome, pendingArm.ifEmpty { "ARM_FACE_DOWN_SURF" })
        }

        surfReflectHook = { row -> renderReflect(row ?: btnRowRef ?: btnRow) }
        renderPredict()

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (android.os.Build.VERSION.SDK_INT >= 31)
                        WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 31) setBlurBehindRadius(10)
        }
        try { wm.addView(root, p); view = root } catch (_: Exception) {}
    }

    private val surfTicker = object : Runnable {
        override fun run() {
            if (view == null || phase != Phase.SURFING) return
            val now = System.currentTimeMillis()
            val dt = now - lastTickAt
            lastTickAt = now
            val locked = faceDown && nearFlag
            if (locked) {
                accumulatedMs += dt
                val left = ((SURF_TARGET_MS - accumulatedMs) / 1000).coerceAtLeast(0)
                tvCount!!.text = "$left"
                tvStatus!!.text = context.getString(R.string.surf_hold)
                if (accumulatedMs >= SURF_TARGET_MS) {
                    host.vibrate(60)   // gentle success pulse
                    handler.postDelayed({
                        host.vibrate(30)
                        surfReflectHook?.invoke(btnRowRef)
                    }, 350)
                    phase = Phase.REFLECT   // stop ticking
                    unregisterSensors()
                    return
                }
            } else {
                tvCount!!.text = "⏸"
                tvStatus!!.text = context.getString(R.string.surf_paused)
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun registerSensors() {
        accel?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        prox?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    private fun unregisterSensors() { sm.unregisterListener(sensorListener) }

    fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        unregisterSensors()
        handler.removeCallbacks(surfTicker)
    }
}
