package com.reelbreaker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reelbreaker.R
import com.reelbreaker.data.Store
import kotlin.math.abs
import kotlin.random.Random

/**
 * V3-1 — Tier 2 & Tier 3 rendered as TYPE_ACCESSIBILITY_OVERLAY windows.
 * ======================================================================
 * Activities launched from a background service suffer Android 10-15 BAL
 * (Background Activity Launch) restrictions, ~300 ms transition lag and
 * lifecycle disruption of the host feed app. An AccessibilityService can
 * add windows of type TYPE_ACCESSIBILITY_OVERLAY directly through its own
 * WindowManager — they appear INSTANTANEOUSLY in-place over the target app,
 * are touch-modal (the feed behind cannot be scrolled during friction),
 * sit above even secure surfaces we own, and never touch the host's
 * activity stack.
 *
 * This controller builds the same two System-2 screens the activities had:
 *   SpeedbumpView  — affective labeling / 4-node WM sequence, no dismiss.
 *   CooldownBlockView — breathing pacer + escalating cooldown, hard-block
 *                       variant with implementation-intention card and the
 *                       V3-5 "Open [Replacement App]" intent teleport CTA.
 *
 * API 31+: FLAG_BLUR_BEHIND + progressive blurBehindRadius (2..12 px) so
 * the reel behind literally loses definition while the task is active —
 * another devaluation of the supernormal stimulus.
 */
class InterventionOverlayController(
    private val context: Context,
    private val wm: WindowManager,
    private val host: Host,
) {
    interface Host {
        fun onSpeedbumpFinished(outcome: String, affect: String?)
        fun onCooldownSurfed(seconds: Int)
        fun onBlockDismissed()
        fun goHome()
        fun launchReplacement(pkg: String)
        fun vibrateCue()
        fun isCircadianVulnerable(): Boolean
        fun replacementPackage(): String?
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active: View? = null
    private var activeParams: WindowManager.LayoutParams? = null

    val isShowing get() = active != null

    companion object {
        private val LABELS = arrayOf("Bored", "Stressed", "Procrastinating", "Just browsing")
    }

    private fun baseParams(blurPx: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // modal: touches are consumed here, the feed cannot scroll through
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (android.os.Build.VERSION.SDK_INT >= 31)
                        WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 31 && blurPx > 0) {
                setBlurBehindRadius(blurPx.coerceIn(2, 12))
            }
        }

    private fun attach(v: View, blurPx: Int) {
        detach()
        val p = baseParams(blurPx)
        try {
            wm.addView(v, p)
            active = v; activeParams = p
        } catch (_: Exception) { /* window gone; fall back silently */ }
    }

    fun detach() {
        active?.let { v -> runCatching { wm.removeView(v) } }
        active = null; activeParams = null
    }

    /** Progressive late-stage Tier-1/2 visual degradation for an open overlay. */
    fun rampBlur(fromPx: Int = 2, toPx: Int = 12, overMs: Long = 1_200) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val p = activeParams ?: return
        val steps = 8
        var i = 0
        lateinit var r: Runnable
        r = Runnable {
            if (active == null) return@Runnable
            i++
            val t = i.toFloat() / steps
            p.setBlurBehindRadius((fromPx + (toPx - fromPx) * t).toInt().coerceIn(2, 12))
            runCatching { wm.updateViewLayout(active!!, p) }
            if (i < steps) handler.postDelayed(r, overMs / steps)
        }
        handler.postDelayed(r, overMs / steps)
    }

    // ======================= TIER 2 — SPEEDBUMP =======================

    @SuppressLint("ClickableViewAccessibility")
    fun showSpeedbump(reason: String, strictTask: Boolean) {
        val root = FrameLayout(context).apply { setBackgroundColor(0xF20B0B12.toInt()) }
        // consume all gestures: no swipe-through, no accidental dismissal
        root.setOnTouchListener { _, _: MotionEvent -> true }

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
        }
        root.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        col.addView(title(context, context.getString(R.string.sb_title)))
        col.addView(body(context, reason))

        val prompt = TextView(context).apply {
            textSize = 17f; setTextColor(Color.parseColor("#E8E6F0"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 32, 0, 16)
        }
        val status = TextView(context).apply {
            textSize = 14f; setTextColor(Color.parseColor("#9A97AD"))
            setPadding(0, 16, 0, 0)
        }
        val grid = GridLayout(context).apply {
            columnCount = if (strictTask) 3 else 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        col.addView(prompt); col.addView(grid); col.addView(status)

        var sequence: List<Int> = emptyList()
        var stepIndex = 0

        fun finishSpeedbump(outcome: String, affect: String?) {
            detach()
            host.onSpeedbumpFinished(outcome, affect)
        }

        fun buildAffective() {
            prompt.text = if (host.isCircadianVulnerable())
                context.getString(R.string.sb_circadian_prompt)
            else context.getString(R.string.sb_affect_prompt)
            grid.removeAllViews(); grid.columnCount = 2
            LABELS.forEachIndexed { i, label ->
                val b = bigButton(context, label)
                b.setOnClickListener {
                    // second step: conscious choice echo (same flow as the activity)
                    prompt.text = context.getString(R.string.sb_affect_ack, label)
                    grid.removeAllViews(); grid.columnCount = 1
                    listOf(
                        context.getString(R.string.sb_act_stop) to "OVERRIDE",
                        context.getString(R.string.sb_act_one_more) to "SURFED",
                    ).forEach { (t, outcome) ->
                        val bb = bigButton(context, t)
                        bb.setOnClickListener {
                            val aff = if (host.isCircadianVulnerable()) label + "|night" else label
                            finishSpeedbump(outcome, aff)
                        }
                        grid.addView(bb)
                    }
                }
                grid.addView(b)
            }
        }

        fun buildSequence() {
            sequence = (0 until 9).shuffled(Random(System.nanoTime())).take(4)
            stepIndex = 0
            prompt.text = context.getString(R.string.sb_seq_prompt,
                sequence.joinToString(" → ") { "${it + 1}" })
            grid.removeAllViews(); grid.columnCount = 3
            for (n in 0 until 9) {
                val b = bigButton(context, "${n + 1}")
                b.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = dp(context, 64)
                    columnSpec = GridLayout.spec(n % 3, 1f)
                    rowSpec = GridLayout.spec(n / 3)
                    setMargins(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                }
                b.setOnClickListener {
                    if (n == sequence[stepIndex]) {
                        b.isEnabled = false
                        stepIndex++
                        if (stepIndex >= sequence.size) {
                            status.text = context.getString(R.string.sb_seq_done)
                            finishSpeedbump("OVERRIDE", null)
                        } else status.text = "$stepIndex/4 ✓"
                    } else {
                        status.text = context.getString(R.string.sb_seq_reset)
                        buildSequence()   // error resets ladder — deterministic cost
                    }
                }
                grid.addView(b)
            }
        }

        if (strictTask) buildSequence() else buildAffective()
        attach(root, blurPx = 8)      // Tier 2 opens already blurred (late stage)
        rampBlur(8, 12)
        host.vibrateCue()
    }

    // =================== TIER 3 — COOLDOWN / CHECKPOINT / BLOCK ===================

    enum class Kind { COOLDOWN, CHECKPOINT, BLOCK }

    @SuppressLint("ClickableViewAccessibility")
    fun showFriction(kind: Kind, seconds: Int, reason: String) {
        val root = FrameLayout(context).apply { setBackgroundColor(0xF40B0B12.toInt()) }
        root.setOnTouchListener { _, _: MotionEvent -> true }

        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 72, 48, 48)
        }
        scroll.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        val titleTv = title(context, when (kind) {
            Kind.COOLDOWN -> context.getString(R.string.cooldown_title)
            Kind.CHECKPOINT -> context.getString(R.string.checkpoint_title)
            Kind.BLOCK -> context.getString(R.string.block_title)
        })
        val msg = body(context, reason)
        val counter = TextView(context).apply {
            textSize = 34f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 24, 0, 8)
        }
        val breathe = TextView(context).apply {
            textSize = 18f; setTextColor(Color.parseColor("#8FD3FF"))
            gravity = Gravity.CENTER; setPadding(0, 8, 0, 24)
        }
        col.addView(titleTv); col.addView(msg); col.addView(counter); col.addView(breathe)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        val primary = bigButton(context, "")
        val secondary = bigButton(context, context.getString(R.string.close_app))
        btnRow.addView(primary); btnRow.addView(secondary)
        col.addView(btnRow)

        var timerRunnable: Runnable? = null

        fun stopTimer() { timerRunnable?.let { handler.removeCallbacks(it) }; timerRunnable = null }

        when (kind) {
            Kind.COOLDOWN -> {
                startBreathingPacer(breathe)
                primary.isEnabled = false
                primary.text = context.getString(R.string.waiting_for_wave)
                val endAt = System.currentTimeMillis() + seconds * 1000L
                timerRunnable = object : Runnable {
                    override fun run() {
                        val left = endAt - System.currentTimeMillis()
                        if (left > 0) {
                            counter.text = context.getString(R.string.cooldown_remaining, (left / 1000 + 1).toInt())
                            handler.postDelayed(this, 200)
                        } else {
                            counter.text = ""
                            primary.isEnabled = true
                            primary.text = context.getString(R.string.i_am_done_continue)
                        }
                    }
                }
                handler.post(timerRunnable!!)
                primary.setOnClickListener {
                    stopTimer(); detach()
                    host.onCooldownSurfed(seconds)
                }
                secondary.visibility = View.GONE
            }
            Kind.CHECKPOINT -> {
                breathe.text = context.getString(R.string.checkpoint_prompt)
                primary.text = context.getString(R.string.stop_here_is_good)
                secondary.text = context.getString(R.string.just_one_more_no)
                primary.setOnClickListener { detach(); host.goHome() }
                secondary.setOnClickListener {
                    msg.append("\n\n• Noted. Next breach will cost you twice as long.")
                    secondary.isEnabled = false
                    // continue: close the friction, let the user proceed consciously
                    detach()
                    host.onCooldownSurfed(0)
                }
            }
            Kind.BLOCK -> {
                counter.text = "⛔"
                val action = Store.get(context).replacementAction
                val pkg = host.replacementPackage()
                breathe.text = when {
                    action.isNotBlank() && pkg != null ->
                        context.getString(R.string.block_reward_header) + "\n• " + action
                    action.isNotBlank() ->
                        context.getString(R.string.block_reward_header) + "\n• " + action
                    else -> context.getString(R.string.block_replacement_menu)
                }
                // V3-5 executable replacement bridge: after the pacer breath,
                // the PRIMARY CTA teleports into the pre-committed app.
                startBreathingPacer(breathe, frames = 1)
                handler.postDelayed({
                    if (!isShowing) return@postDelayed
                    if (pkg != null) {
                        val label = launchableName(pkg) ?: action.ifBlank { "Replacement" }
                        primary.text = context.getString(R.string.open_replacement, label)
                        primary.setOnClickListener {
                            detach()
                            host.launchReplacement(pkg)
                        }
                    } else {
                        primary.text = context.getString(R.string.close_app)
                        primary.setOnClickListener { detach(); host.goHome() }
                    }
                    secondary.text = context.getString(R.string.go_home_instead)
                    secondary.setOnClickListener { detach(); host.goHome() }
                }, 2_600)   // one full breath before any button is live
                host.vibrateCue()
            }
        }

        attach(root, blurPx = if (kind == Kind.BLOCK) 12 else 6)
        if (kind != Kind.BLOCK) rampBlur(6, 12)
        host.vibrateCue()
    }

    private fun launchableName(pkg: String): String? = runCatching {
        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai).toString()
    }.getOrNull()

    /** 4-7-8-ish pacer driven by handler; frames=1 shows one calm cue only. */
    private fun startBreathingPacer(tv: TextView, frames: Int = 4) {
        val all = listOf("🫁  Breathe in…", "hold…", "😮‍💨  Breathe out…", "hold…")
        val use = if (frames == 1) all.take(1) else all
        var i = 0
        tv.postDelayed(object : Runnable {
            override fun run() {
                if (active == null) return
                i = (i + 1) % use.size
                tv.text = use[i]
                tv.postDelayed(this, 1900)
            }
        }, 1900)
    }

    fun releaseAll() { stopAllTimers(); detach() }
    private fun stopAllTimers() { handler.removeCallbacksAndMessages(null) }

    private fun title(c: Context, t: String) = TextView(c).apply {
        text = t; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 0, 0, 16)
    }
    private fun body(c: Context, t: String) = TextView(c).apply {
        text = t; textSize = 15f; setTextColor(Color.parseColor("#C9C6D9"))
        gravity = Gravity.CENTER; setPadding(0, 0, 0, 8)
    }
    private fun bigButton(c: Context, t: String) = Button(c).apply {
        text = t; textSize = 16f
        isAllCaps = false
        setPadding(24, 28, 24, 28)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(c, 8), 0, dp(c, 8)) }
    }
    private fun dp(c: Context, v: Int) = abs((v * c.resources.displayMetrics.density).toInt())
}
