package com.reelbreaker.blocker

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.reelbreaker.R

/**
 * Full-screen friction surface. It is NOT a translucent TYPE_APPLICATION_OVERLAY
 * window (those can be tapped through and are visually clipped by secure
 * windows); it's a singleInstance activity launched over the feed, which:
 *   - dims/obscures the reel behind it,
 *   - cannot be dismissed by accident (back button is consumed),
 *   - shows the urge-surfing breathing pacer + countdown during cooldowns.
 *
 * Design choices from the science:
 *   - The countdown has no "skip" until it completes => predictable pause
 *     flattens dopamine prediction error (engine rule #1).
 *   - Checkpoint asks an *intention question* ("What did you come here to
 *     do?") — prospective-memory cue that re-engages goal-directed control
 *     over habitual control (the basal-ganglia vs. PFC competition).
 *   - Block screen offers a replacement reward (walk, water, call someone):
 *     habit change replaces the routine, never just deletes it (Duhigg).
 */
class BlockOverlayActivity : Activity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_REASON = "reason"
        const val EXTRA_FORCE_EXIT = "force_exit"
    }

    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show even over secure windows & while locked; no dim animations from the feed
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_block_overlay)

        val kind = intent.getIntExtra(EXTRA_KIND, 0)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 5)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
        val forceExit = intent.getBooleanExtra(EXTRA_FORCE_EXIT, false)

        val title = findViewById<TextView>(R.id.overlay_title)
        val message = findViewById<TextView>(R.id.overlay_message)
        val counter = findViewById<TextView>(R.id.overlay_counter)
        val breathe = findViewById<TextView>(R.id.overlay_breathe)
        val primary = findViewById<Button>(R.id.overlay_primary)

        when (kind) {
            ReelAccessibilityService.OverlaySpec.KIND_COOLDOWN -> {
                title.text = getString(R.string.cooldown_title)
                message.text = reason
                startBreathingPacer(breathe)
                counter.visibility = TextView.VISIBLE
                timer = object : CountDownTimer(seconds * 1000L, 200) {
                    override fun onTick(ms: Long) {
                        counter.text = getString(R.string.cooldown_remaining, (ms / 1000 + 1).toInt())
                        primary.isEnabled = false
                    }
                    override fun onFinish() {
                        counter.text = ""
                        primary.isEnabled = true
                        primary.text = getString(R.string.i_am_done_continue)
                    }
                }.start()
                primary.isEnabled = false
                primary.text = getString(R.string.waiting_for_wave)
                primary.setOnClickListener { finish() }
            }
            ReelAccessibilityService.OverlaySpec.KIND_CHECKPOINT -> {
                title.text = getString(R.string.checkpoint_title)
                message.text = reason
                breathe.text = getString(R.string.checkpoint_prompt)
                primary.text = getString(R.string.stop_here_is_good)
                primary.isEnabled = true
                val secondary = findViewById<Button>(R.id.overlay_secondary)
                secondary.text = getString(R.string.just_one_more_no)
                secondary.visibility = Button.VISIBLE
                // Reflective choice: making users *articulate* the exception
                // restores agency while attaching a cost (self-awareness effect).
                secondary.setOnClickListener {
                    message.append("\n\n• Noted. Next breach will cost you twice as long.")
                    secondary.isEnabled = false
                }
                primary.setOnClickListener { goHome() }
            }
            else -> { // BLOCK
                title.text = getString(R.string.block_title)
                message.text = reason
                breathe.text = getString(R.string.block_replacement_menu)
                primary.text = getString(R.string.close_app)
                primary.isEnabled = true
                if (forceExit) goHome() // kill switch: exit first, reflect after
                primary.setOnClickListener { goHome() }
            }
        }
        findViewById<Button>(R.id.overlay_secondary).setOnClickListener { goHome() }
    }

    /** Simple 4-7-8 style pacer: expand text cues drive parasympathetic braking. */
    private fun startBreathingPacer(tv: android.widget.TextView) {
        val frames = listOf(
            "🫁  Breathe in…", "hold…", "😮‍💨  Breathe out…", "hold…"
        )
        var i = 0
        tv.tag = i
        tv.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                i = (i + 1) % frames.size
                tv.text = frames[i]
                tv.postDelayed(this, 1900)
            }
        }, 1900)
    }

    private fun goHome() {
        val svc = ReelAccessibilityService.instance
        if (svc != null) {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
        finish()
    }

    @Deprecated("Consumed intentionally: leaving requires the buttons.")
    override fun onBackPressed() {
        // Friction: back does nothing; user must ride out or choose deliberately.
        findViewById<TextView>(R.id.overlay_title).text = getString(R.string.no_shortcut_back)
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
