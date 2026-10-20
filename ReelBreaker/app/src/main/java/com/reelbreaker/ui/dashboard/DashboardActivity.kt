package com.reelbreaker.ui.dashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.reelbreaker.R
import com.reelbreaker.data.Store
import com.reelbreaker.blocker.ReelAccessibilityService
import com.reelbreaker.ui.settings.SettingsActivity
import java.util.Locale

/**
 * Live dashboard. Refreshes every second — visibility itself is an
 * intervention (measurement reactivity: people change behaviour when they
 * watch it; see "self-monitoring" literature, Nelson & Hayes).
 */
class DashboardActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bigCount: TextView
    private lateinit var budgetLine: TextView
    private lateinit var streakLine: TextView
    private lateinit var sessionLine: TextView
    private lateinit var blockedLine: TextView
    private lateinit var historyChart: HistoryView
    private lateinit var armButton: Button

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

        bigCount = findViewById(R.id.big_count)
        budgetLine = findViewById(R.id.budget_line)
        streakLine = findViewById(R.id.streak_line)
        sessionLine = findViewById(R.id.session_line)
        blockedLine = findViewById(R.id.blocked_line)
        historyChart = findViewById(R.id.history_chart)
        armButton = findViewById(R.id.arm_button)
        findViewById<Button>(R.id.settings_button).setOnClickListener {
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

    private fun render() {
        {
            val store = Store.get(ctx)
            val total = store.todayTotal()
            val budget = store.dailyBudget
            bigCount.text = total.toString()
            bigCount.setTextColor(
                getColor(if (total <= budget) R.color.accent2 else R.color.warn)
            )
            budgetLine.text = getString(R.string.dash_budget_of, budget)
            streakLine.text = getString(R.string.dash_streak, store.streak())

            val s = activeSessionSnapshot()
            sessionLine.text = if (s != null && s.sessionActive)
                getString(R.string.dash_session_active,
                    s.sessionSwipes,
                    String.format(Locale.US, "%.1f", s.sessionElapsedSec / 60.0),
                    s.swipesPerMinute)
            else getString(R.string.dash_session_idle)

            val todayRow = store.history(1)[0].split("|")
            blockedLine.text = getString(
                R.string.dash_blocked_today,
                todayRow[3].toInt(), todayRow[2].toInt()
            )

            historyChart.setData(store.history(7).map { it.split("|")[1].toInt() }.reversed())
            historyChart.budget = budget

            val armed = ReelAccessibilityService.instance != null || store.blockerActive
            armButton.text = getString(
                if (armed) R.string.dash_protection_on else R.string.dash_protection_off
            )
            armButton.isEnabled = !armed
        }
    }

    /** Live session read-out: the accessibility service owns the SessionTracker. */
    private fun activeSessionSnapshot(): com.reelbreaker.core.SessionTracker.Snapshot? =
        ReelAccessibilityService.instance?.sessions?.snapshot()
}
