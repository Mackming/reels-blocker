package com.reelbreaker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Tier 1 — Sub-Threshold Sensory Dampening (non-blocking).
 * ========================================================
 * A full-screen TYPE_APPLICATION_OVERLAY window that is explicitly
 * FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE: the user never notices it as a
 * "block", their swipes pass straight through. What changes is the visual
 * *reward* of the feed itself.
 *
 * Mechanism (sensation / hedonic adaptation research; "cooling" limbic
 * response by degrading stimulus salience): short-form video hooks the
 * ventral striatum with high saturation + contrast. As the session
 * approaches the friction threshold we progressively wash the screen out —
 * lowering effective contrast and warmth — so each additional reel delivers
 * a smaller sensory hit while the intervention counter quietly climbs.
 * The feed becomes boring *before* any rule fires, which is exactly how
 * extinction schedules work in the Skinner literature.
 *
 * Alpha model:
 *   progress = clamp((swipes - preWarnOffset) / (threshold - preWarnOffset))
 *   dimAlpha = MAX_ALPHA * easeInOut(progress)      (0 -> ~0.42)
 * plus a warm/amber tint whose strength scales with progress (contrast &
 * colour-saturation reduction reads as "tiring", reducing cue reactivity).
 */
class SensoryDampener(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: DimView? = null
    private var params: WindowManager.LayoutParams? = null

    private var currentAlpha = 0f
    private var targetAlpha = 0f
    private val anim = object : Runnable {
        override fun run() {
            currentAlpha += (targetAlpha - currentAlpha) * 0.12f
            if (kotlin.math.abs(targetAlpha - currentAlpha) < 0.005f) currentAlpha = targetAlpha
            view?.let { v -> v.alphaValue = currentAlpha; v.invalidate() }
            if (currentAlpha != targetAlpha) handler.postDelayed(this, 60)
        }
    }

    companion object {
        const val MAX_ALPHA = 0.42f          // still readable, clearly less vivid
        const val PRE_WARN_OFFSET = 4        // start dampening N swipes before threshold
        const val FULL_DWELL_MS = 8_000L     // hold max dimming this long, then fade out
    }

    fun ensureWindow() {
        if (view != null) return
        val v = DimView(context)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // THE critical flags: invisible to touch, never takes focus.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(v, p)
            view = v; params = p
        } catch (_: SecurityException) {
            // SYSTEM_ALERT_WINDOW not granted — Tier 1 silently degrades to no-op.
        }
    }

    /**
     * Called on every committed swipe + on mode/threshold changes.
     * @param swipesInSession reels counted in the current session
     * @param threshold       session soft cap where Tier 2 would fire
     */
    fun updateProgress(swipesInSession: Int, threshold: Int, enabled: Boolean) {
        if (!enabled) { fadeTo(0f); return }
        ensureWindow()
        val span = max(4, threshold - PRE_WARN_OFFSET)
        // V3-4: in the circadian-vulnerable window the veil starts from
        // swipe #1 — baseline warm red-shift is applied immediately.
        val offset = if (circadianBaseline) 0 else PRE_WARN_OFFSET
        val raw = (swipesInSession - offset).toFloat() / max(2, threshold - offset)
        val eased = easeInOut(raw.coerceIn(0f, 1f))
        targetAlpha = MAX_ALPHA * (if (circadianBaseline) 0.55f + 0.45f * eased else eased)
        handler.removeCallbacks(anim)
        handler.post(anim)
        if (raw >= 1f) handler.postDelayed({ fadeTo(0f) }, FULL_DWELL_MS)
    }

    /** V3-4 — dark-room sessions get a stronger, warmer veil from swipe #1. */
    var circadianBaseline: Boolean = false

    fun setWarmRedShift(on: Boolean) { warmBoost = if (on) 1f else 0f }
    private var warmBoost = 0f

    fun fadeTo(a: Float) { targetAlpha = a; handler.removeCallbacks(anim); handler.post(anim) }

    fun release() {
        handler.removeCallbacks(anim)
        view?.let { runCatching { wm.removeView(it) } }
        view = null; params = null
    }

    private fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2

    /** Custom view: dark desaturating veil + amber warmth overlay. */
    private inner class DimView(ctx: Context) : View(ctx) {
        var alphaValue = 0f
        private val veil = Paint().apply { color = Color.rgb(8, 8, 14) }
        private val warm = Paint().apply { color = Color.rgb(255, 150, 40) }
        private val redShift = Paint().apply { color = Color.rgb(255, 60, 20) }
        override fun onDraw(c: Canvas) {
            val a = alphaValue.coerceIn(0f, 1f)
            // V3-4: even at zero session progress the circadian baseline paints
            // a gentle warm-red shift — blue light is exactly what suppresses
            // melatonin in the dark-room window.
            if (warmBoost > 0f) {
                redShift.alpha = (30 + 40 * warmBoost).toInt().coerceIn(0, 255)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), redShift)
            }
            if (a <= 0.003f) return
            veil.alpha = (a * 255 * 0.75f).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
            // vignette-ish warmth: strongest at edges via second lighter pass
            warm.alpha = ((a * 0.22f + 0.06f * warmBoost) * 255f).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), warm)
        }
    }
}
