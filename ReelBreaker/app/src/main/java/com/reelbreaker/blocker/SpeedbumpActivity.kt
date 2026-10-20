package com.reelbreaker.blocker

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import com.reelbreaker.R
import com.reelbreaker.data.Store
import com.reelbreaker.telemetry.InterventionEvent
import com.reelbreaker.telemetry.TelemetryBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Tier 2 — Variable-Ratio Flattening & System-2 Cognitive Speedbump.
 * ===================================================================
 * Launched stochastically (N ± rand(1,3) swipes) instead of on a fixed
 * schedule, so the user can never predict or habituate to the pause.
 *
 * There is deliberately NO passive dismiss button. To continue the user must
 * perform one of two prefrontal-recruitment tasks:
 *
 *  A) AFFECTIVE LABELING ("name it to tame it", Lieberman et al. 2007):
 *     putting felt state into words measurably down-regulates amygdala and
 *     interrupts the impulsive loop. The chosen label also becomes telemetry
 *     that reveals their personal scroll trigger (Bored / Stressed /
 *     Procrastinating / Just Browsing).
 *
 *  B) MICRO-WORKING-MEMORY SEQUENCE (Strict mode): tap a randomized 4-node
 *     order. This breaks thumb muscle-memory (the habitual motor program
 *     cannot execute an unfamiliar sequence), forcing a System-2 online
 *     control switch for ~2-4 seconds — enough for the urge wave to drop.
 */
class SpeedbumpActivity : Activity() {

    companion object {
        const val EXTRA_REASON = "reason"
        private val LABELS = arrayOf("Bored", "Stressed", "Procrastinating", "Just browsing")
    }

    private lateinit var prompt: TextView
    private lateinit var status: TextView
    private var strictTask = false
    private var sequence: List<Int> = emptyList()
    private var stepIndex = 0
    private var dayKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        setContentView(R.layout.activity_speedbump)

        dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
        findViewById<TextView>(R.id.sb_reason).text = reason

        prompt = findViewById(R.id.sb_prompt)
        status = findViewById(R.id.sb_status)
        val grid = findViewById<GridLayout>(R.id.sb_grid)

        strictTask = Store.get(this).mode == com.reelbreaker.engine.InterventionMode.STRICT ||
                Store.get(this).mode == com.reelbreaker.engine.InterventionMode.BLOCK

        buildGrid(grid)
    }

    private fun buildGrid(grid: GridLayout) {
        grid.removeAllViews()
        if (!strictTask) return buildAffectiveGrid(grid)
        return buildSequenceGrid(grid)
    }

    // ---- Task A: affective labeling ----
    private fun buildAffectiveGrid(grid: GridLayout) {
        prompt.text = getString(R.string.sb_affect_prompt)
        for ((i, label) in LABELS.withIndex()) {
            val b = Button(this).apply {
                text = label
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(i % 2, 1f)
                    rowSpec = GridLayout.spec(i / 2)
                    setMargins(12, 12, 12, 12)
                }
            }
            b.setOnClickListener { onAffectChosen(label) }
            grid.addView(b)
        }
    }

    private fun onAffectChosen(label: String) {
        // Second step: implementation intention echo — convert the label into
        // a concrete alternative action before releasing the friction.
        prompt.text = getString(R.string.sb_affect_ack, label)
        val grid2 = findViewById<GridLayout>(R.id.sb_grid)
        grid2.removeAllViews()
        val actions = listOf(
            getString(R.string.sb_act_stop),
            getString(R.string.sb_act_one_more),
        )
        actions.forEachIndexed { i, t ->
            val b = Button(this).apply {
                text = t
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(0, 1f)
                    rowSpec = GridLayout.spec(i)
                    setMargins(12, 12, 12, 12)
                }
            }
            b.setOnClickListener { finishWith(outcome = if (i == 0) "OVERRIDE" else "SURFED", affect = label) }
            grid2.addView(b)
        }
    }

    // ---- Task B: 4-node random sequence (Strict/BLOCK modes) ----
    private fun buildSequenceGrid(grid: GridLayout) {
        sequence = (0 until 9).shuffled(Random(System.nanoTime())).take(4)
        stepIndex = 0
        prompt.text = getString(R.string.sb_seq_prompt, sequence.joinToString(" → ") { "${it + 1}" })
        for (n in 0 until 9) {
            val b = Button(this).apply {
                text = "${n + 1}"
                textSize = 20f
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = resources.getDimensionPixelSize(R.dimen.sb_node)
                    columnSpec = GridLayout.spec(n % 3, 1f)
                    rowSpec = GridLayout.spec(n / 3)
                    setMargins(8, 8, 8, 8)
                }
            }
            b.setOnClickListener { onNodeTapped(n, b) }
            grid.addView(b)
        }
    }

    private fun onNodeTapped(n: Int, b: Button) {
        if (n == sequence[stepIndex]) {
            b.isEnabled = false
            stepIndex++
            if (stepIndex >= sequence.size) {
                status.text = getString(R.string.sb_seq_done)
                finishWith(outcome = "OVERRIDE", affect = null)
            } else {
                status.text = "$stepIndex/4 ✓"
            }
        } else {
            // error resets the ladder — mild, deterministic cost, not punishment
            status.text = getString(R.string.sb_seq_reset)
            buildGrid(findViewById(R.id.sb_grid))
        }
    }

    private fun finishWith(outcome: String, affect: String?) {
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey,
                kind = "SPEEDBUMP",
                outcome = outcome,
                affectLabel = affect,
                cooldownSeconds = 0,
            )
        )
        Store.get(this).recordOverride(outcome)
        finish()
    }

    @Deprecated("No passive dismissal by design.")
    override fun onBackPressed() {
        status.text = getString(R.string.no_shortcut_back)
    }
}
