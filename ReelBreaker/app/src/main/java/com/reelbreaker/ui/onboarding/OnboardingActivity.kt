package com.reelbreaker.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.reelbreaker.R
import com.reelbreaker.blocker.ReelAccessibilityService
import com.reelbreaker.core.KnownTargets
import com.reelbreaker.data.Store
import com.reelbreaker.engine.InterventionMode
import com.reelbreaker.ui.dashboard.DashboardActivity

/**
 * Modern Cyber Glass Onboarding & Permission Orchestrator.
 * Live-checks Accessibility, OEM Battery Optimization Exemption,
 * and System Overlay permissions with reactive checkmarks upon returning from settings.
 */
class OnboardingActivity : AppCompatActivity() {

    private val boxes = ArrayList<CompoundButton>()

    private lateinit var badgeA11y: TextView
    private lateinit var btnA11y: Button
    private lateinit var a11yHint: TextView

    private lateinit var badgeBattery: TextView
    private lateinit var btnBattery: Button

    private lateinit var badgeOverlay: TextView
    private lateinit var btnOverlay: Button

    private lateinit var etBudget: EditText
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        if (Store.get(ctx).onboarded) {
            goDash(); return
        }
        setContentView(R.layout.activity_onboarding)

        scrollView = findViewById(R.id.onboard_scroll)
        badgeA11y = findViewById(R.id.badge_a11y)
        btnA11y = findViewById(R.id.btn_accessibility)
        a11yHint = findViewById(R.id.a11y_hint)

        badgeBattery = findViewById(R.id.badge_battery)
        btnBattery = findViewById(R.id.btn_battery_opt)

        badgeOverlay = findViewById(R.id.badge_overlay)
        btnOverlay = findViewById(R.id.btn_overlay_perm)
        etBudget = findViewById(R.id.et_onboard_budget)

        // 1. Accessibility Button
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // 2. Battery Exemption Button
        btnBattery.setOnClickListener {
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        // 3. Overlay Permission Button
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        // 4. Target Feeds List
        val container = findViewById<LinearLayout>(R.id.app_checks)
        val pm = packageManager
        val density = resources.displayMetrics.density

        for (t in KnownTargets.ALL) {
            val installed = try {
                pm.getLaunchIntentForPackage(t.pkg) != null
            } catch (_: Exception) { false }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_elevated)
                val padH = (14 * density).toInt()
                val padV = (10 * density).toInt()
                setPadding(padH, padV, padH, padV)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
                layoutParams = params
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this).apply {
                text = t.label
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_hi))
            }

            val subView = TextView(this).apply {
                text = if (installed) "Detected on phone • Ready to protect" else "Not installed"
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_med))
            }

            textCol.addView(titleView)
            textCol.addView(subView)

            val sw = SwitchCompat(this).apply {
                isChecked = installed || boxes.isEmpty()
                tag = t.pkg
            }

            boxes.add(sw)
            row.addView(textCol)
            row.addView(sw)

            row.setOnClickListener {
                sw.toggle()
            }

            container.addView(row)
        }

        // 5. Finish / Arm Button
        findViewById<Button>(R.id.btn_finish).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(
                    this,
                    "Please grant Accessibility Service first — it is required to detect swipes!",
                    Toast.LENGTH_LONG
                ).show()
                scrollView.smoothScrollTo(0, 0)
                return@setOnClickListener
            }

            val enabledApps = boxes.filter { it.isChecked }.map { it.tag as String }.toSet()
            val budget = etBudget.text.toString().toIntOrNull()?.coerceIn(5, 1000) ?: 45

            val st = Store.get(ctx)
            st.setEnabledApps(enabledApps)
            st.dailyBudget = budget
            if (st.mode == InterventionMode.OFF) st.mode = InterventionMode.MODERATE
            st.blockerActive = true
            st.onboarded = true
            goDash()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun refreshPermissionStates() {
        // Check Accessibility
        val a11yOn = isAccessibilityEnabled()
        if (a11yOn) {
            badgeA11y.text = "ACTIVE ✔"
            badgeA11y.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            badgeA11y.setBackgroundResource(R.drawable.bg_pill_status_armed)

            btnA11y.text = "Accessibility Access Granted ✔"
            btnA11y.setBackgroundResource(R.drawable.bg_button_ghost)
            btnA11y.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            a11yHint.visibility = android.view.View.GONE
        } else {
            badgeA11y.text = "REQUIRED"
            badgeA11y.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            badgeA11y.setBackgroundResource(R.drawable.bg_pill_status_paused)

            btnA11y.text = "Grant Accessibility Access"
            btnA11y.setBackgroundResource(R.drawable.bg_button_primary)
            btnA11y.setTextColor(ContextCompat.getColor(this, R.color.text_hi))
            a11yHint.visibility = android.view.View.VISIBLE
        }

        // Check Battery Optimization
        val powerMgr = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = powerMgr.isIgnoringBatteryOptimizations(packageName)
        if (batteryIgnored) {
            badgeBattery.text = "UNRESTRICTED ✔"
            badgeBattery.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            badgeBattery.setBackgroundResource(R.drawable.bg_pill_status_armed)

            btnBattery.text = "Background Execution Allowed ✔"
            btnBattery.setBackgroundResource(R.drawable.bg_button_ghost)
            btnBattery.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            btnBattery.isEnabled = false
        } else {
            badgeBattery.text = "RECOMMENDED"
            badgeBattery.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            badgeBattery.setBackgroundResource(R.drawable.bg_pill_status_paused)

            btnBattery.text = "Allow Unrestricted Background Activity"
            btnBattery.setBackgroundResource(R.drawable.bg_button_ghost)
            btnBattery.setTextColor(ContextCompat.getColor(this, R.color.text_hi))
            btnBattery.isEnabled = true
        }

        // Check Overlay Permission
        val overlayOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        if (overlayOn) {
            badgeOverlay.text = "ENABLED ✔"
            badgeOverlay.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            badgeOverlay.setBackgroundResource(R.drawable.bg_pill_status_armed)

            btnOverlay.text = "Display Overlays Active ✔"
            btnOverlay.setBackgroundResource(R.drawable.bg_button_ghost)
            btnOverlay.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            btnOverlay.isEnabled = false
        } else {
            badgeOverlay.text = "OPTIONAL"
            badgeOverlay.setTextColor(ContextCompat.getColor(this, R.color.text_lo))
            badgeOverlay.setBackgroundResource(R.drawable.bg_pill_chip)

            btnOverlay.text = "Enable Display Over Other Apps"
            btnOverlay.setBackgroundResource(R.drawable.bg_button_ghost)
            btnOverlay.setTextColor(ContextCompat.getColor(this, R.color.text_hi))
            btnOverlay.isEnabled = true
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (ReelAccessibilityService.instance != null) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any {
            it.contains(packageName) && it.contains("ReelAccessibilityService")
        }
    }

    private fun goDash() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
