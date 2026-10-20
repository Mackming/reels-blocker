package com.reelbreaker.overlay

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.reelbreaker.R

/**
 * V4-5 bandit arm: Physiological Sigh pacer (30 s guided double-inhale /
 * long-exhale). The cyclic sighing pattern is the fastest voluntary
 * downregulation of sympathetic arousal available without equipment
 * (Balban et al. 2023, Cell Reports Medicine): short inhale, top-up
 * second inhale, then a long passive exhale that offloads CO2 and slows
 * heart rate via vagal brake engagement.
 *
 * Rendered as TYPE_ACCESSIBILITY_OVERLAY like every other Tier surface —
 * zero activity launches, lands in-place over the feed.
 */
class SighPacerController(
    private val context: Context,
    private val wm: WindowManager,
    private val host: FaceDownUrgeSurfController.Host,
) {

    interface ExtraHost { fun onSighFinished(outcome: String) }

    private var view: View? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        const val SESSION_MS = 30_000L
        // one cycle: inhale 2s -> top-up sigh 1s -> long exhale 4s
        private val FRAMES = listOf("😤  Inhale…" to 2000L, "😤  …small second sip" to 1000L, "😮‍💨  Long slow exhale…" to 4000L)
    }

    val isShowing get() = view != null

    fun show(onDone: () -> Unit) {
        if (view != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF60B0B12.toInt())
            setPadding(56, 72, 56, 56)
        }
        root.setOnTouchListener { _, _: MotionEvent -> true }

        val title = TextView(context).apply {
            text = context.getString(R.string.sigh_title)
            textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
        }
        val frame = TextView(context).apply {
            textSize = 26f; setTextColor(0xFF8FD3FF.toInt()); gravity = Gravity.CENTER
            setPadding(0, 40, 0, 16)
        }
        val count = TextView(context).apply {
            textSize = 15f; setTextColor(0xFF9A97AD.toInt()); gravity = Gravity.CENTER
        }
        val btn = Button(context).apply {
            text = context.getString(R.string.sigh_done_early); isAllCaps = false
            visibility = View.GONE
        }
        root.addView(title); root.addView(frame); root.addView(count); root.addView(btn)

        // declared before the closures below so they can call it directly
        fun finish() {
            detach()
            host.vibrate(60)
        }

        val endAt = System.currentTimeMillis() + SESSION_MS
        var fi = 0
        lateinit var cycle: Runnable
        cycle = Runnable {
            if (view == null) return@Runnable
            frame.text = FRAMES[fi % FRAMES.size].first
            handler.postDelayed(cycle, FRAMES[fi % FRAMES.size].second)
            fi++
        }
        lateinit var countdown: Runnable
        countdown = Runnable {
            if (view == null) return@Runnable
            val left = (endAt - System.currentTimeMillis()) / 1000
            if (left > 0) {
                count.text = context.getString(R.string.sigh_remaining, left.toInt())
                handler.postDelayed(countdown, 500)
            } else {
                finish(); onDone()
            }
        }
        btn.setOnClickListener { finish(); onDone() }

        handler.post(cycle); handler.post(countdown)

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
            if (android.os.Build.VERSION.SDK_INT >= 31) setBlurBehindRadius(8)
        }
        try { wm.addView(root, p); view = root } catch (_: Exception) {}
    }

    fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        handler.removeCallbacksAndMessages(null)
    }
}
