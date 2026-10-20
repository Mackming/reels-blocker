package com.reelbreaker.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.reelbreaker.R
import com.reelbreaker.core.KnownTargets
import com.reelbreaker.data.Store
import com.reelbreaker.engine.InterventionMode
import com.reelbreaker.ui.dashboard.DashboardActivity

/**
 * Onboarding = the "commitment device" moment. Behavioural science says
 * intentions formed in a *cold* state and made concrete (choose apps, choose
 * level, write the if-then rule) are dramatically more likely to survive the
 * hot state later — so we ask for all three up front, once.
 */
class OnboardingActivity : AppCompatActivity() {

    private val boxes = ArrayList<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val ctx = this
        if (Store.get(ctx).onboarded) { goDash(); return }

        val container = findViewById<android.widget.LinearLayout>(R.id.app_checks)
        val pm = packageManager
        for (t in KnownTargets.ALL) {
            val installed = try {
                pm.getLaunchIntentForPackage(t.pkg) != null
            } catch (_: Exception) { false }
            val cb = CheckBox(this).apply {
                text = "${t.label}  (${t.pkg})"
                isChecked = installed || boxes.isEmpty()
                tag = t.pkg
                setTextColor(getColor(R.color.text_hi))
            }
            boxes.add(cb)
            container.addView(cb)
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Show users exactly which entry to enable
            findViewById<TextView>(R.id.a11y_hint).text =
                "Find “ReelBreaker — Reels Blocker & Counter” → toggle ON → Allow."
        }

        findViewById<Button>(R.id.btn_finish).setOnClickListener {
            val enabled = boxes.filter { it.isChecked }.map { it.tag as String }.toSet()
            val st = Store.get(ctx)
            st.setEnabledApps(enabled)
            if (st.mode == InterventionMode.OFF) st.mode = InterventionMode.MODERATE
            st.onboarded = true
            goDash()
        }
    }

    private fun goDash() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
