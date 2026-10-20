package com.reelbreaker.ui.dashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.reelbreaker.R
import com.reelbreaker.data.Store
import com.reelbreaker.blocker.ReelAccessibilityService
import com.reelbreaker.ui.settings.SettingsActivity
import java.util.Locale

/**
 * Live Cyber Glass dashboard. Refreshes every second — visibility itself is an
 * intervention (measurement reactivity: people change behaviour when they
 * watch it; see "self-monitoring" literature, Nelson & Hayes).
 */
class DashboardActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var circularGauge: CircularGaugeView
    private lateinit var streakLine: TextView
    private lateinit var sessionLine: TextView
    private lateinit var blockedLine: TextView
    private lateinit var brainLine: TextView
    private lateinit var stateLine: TextView
    private lateinit var mindStatusBadge: TextView
    private lateinit var historyChart: HistoryView
    private lateinit var armButton: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.get(this).onboarded) {
            startActivity(Intent(this, com.reelbreaker.ui.onboarding.OnboardingActivity::class.java))
            finish(); return
        }
        setContentView(R.layout.activity_dashboard)

        circularGauge = findViewById(R.id.circular_gauge)
        streakLine = findViewById(R.id.streak_line)
        sessionLine = findViewById(R.id.session_line)
        blockedLine = findViewById(R.id.blocked_line)
        brainLine = findViewById(R.id.brain_line)
        stateLine = findViewById(R.id.state_line)
        mindStatusBadge = findViewById(R.id.mind_status_badge)
        historyChart = findViewById(R.id.history_chart)
        armButton = findViewById(R.id.arm_button)

        findViewById<ImageView>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        armButton.setOnClickListener {
            // Jump straight to the OS accessibility screen for one-tap re-arm
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private var lastChartTotal = -1
    private var lastChartBudget = -1

    private fun render() {
        val store = Store.get(this)
        val total = store.todayTotal()
        val budget = store.dailyBudget

        // Update Cyber Glass Circular Gauge
        circularGauge.setProgress(total, budget)
        streakLine.text = getString(R.string.dash_streak, store.streak())

        val s = activeSessionSnapshot()
        sessionLine.text = if (s != null && s.sessionActive)
            getString(
                R.string.dash_session_active,
                s.sessionSwipes,
                String.format(Locale.US, "%.1f", s.sessionElapsedSec / 60.0),
                s.swipesPerMinute
            )
        else getString(R.string.dash_session_idle)

        val todayRow = store.history(1)[0].split("|")
        blockedLine.text = getString(
            R.string.dash_blocked_today,
            todayRow[3].toInt(), todayRow[2].toInt()
        )

        // Phase-4 neuro metrics: trance loops broken + prefrontal overrides
        brainLine.text = getString(
            R.string.dash_brain_metrics,
            store.todaySurfs() + store.todayOverrides(),
            store.todayOverrides()
        )

        val kin = ReelAccessibilityService.instance?.liveKinematics()
        if (kin != null && kin.sampleCount >= 3) {
            stateLine.text = getString(
                R.string.dash_state_line,
                kin.state.name,
                String.format(Locale.US, "%.1fs", kin.meanIsiMs / 1000.0),
                String.format(Locale.US, "%.2f", kin.cv)
            )
            stateLine.visibility = View.VISIBLE

            when (kin.state.name) {
                "FORAGING" -> {
                    mindStatusBadge.text = "🟡 FORAGING"
                    mindStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                }
                "TRANCE" -> {
                    mindStatusBadge.text = "🟣 TRANCE DETECTED"
                    mindStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.warn))
                }
                else -> {
                    mindStatusBadge.text = "● FOCUS STEADY"
                    mindStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent2))
                }
            }
        } else {
            stateLine.visibility = View.GONE
            mindStatusBadge.text = "● FOCUS STEADY"
            mindStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent2))
        }

        if (total != lastChartTotal || budget != lastChartBudget) {
            lastChartTotal = total
            lastChartBudget = budget
            historyChart.setData(store.history(7).map { it.split("|")[1].toInt() }.reversed())
            historyChart.budget = budget
        }

        val armed = ReelAccessibilityService.instance != null || store.blockerActive
        if (armed) {
            armButton.text = "● ARMED"
            armButton.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            armButton.setBackgroundResource(R.drawable.bg_pill_status_armed)
        } else {
            armButton.text = "● PAUSED"
            armButton.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            armButton.setBackgroundResource(R.drawable.bg_pill_status_paused)
        }
    }

    /** Live session read-out: the accessibility service owns the SessionTracker. */
    private fun activeSessionSnapshot(): com.reelbreaker.core.SessionTracker.Snapshot? =
        ReelAccessibilityService.instance?.sessions?.snapshot()
}
