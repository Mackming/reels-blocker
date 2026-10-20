package com.reelbreaker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.reelbreaker.R
import com.reelbreaker.app.ReelBreakerApp
import com.reelbreaker.core.KnownTargets
import com.reelbreaker.data.Store
import com.reelbreaker.engine.InterventionMode

class SettingsActivity : AppCompatActivity() {

    /** pkg -> label for installed launchable apps (null entry = "None"). */
    private data class AppItem(val pkg: String?, val label: String) {
        override fun toString() = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val ctx = this

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        val spinner = findViewById<Spinner>(R.id.mode_spinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.modes)
        )
        spinner.setSelection((Store.get(ctx).mode.ordinal - 1).coerceAtLeast(0))

        val budgetEt = findViewById<EditText>(R.id.budget_input)
        budgetEt.setText(Store.get(ctx).dailyBudget.toString())
        val capEt = findViewById<EditText>(R.id.cap_input)
        capEt.setText(Store.get(ctx).sessionSoftCap.toString())
        val commitEt = findViewById<EditText>(R.id.commit_input)
        Store.get(ctx).commitmentStopAt?.let { commitEt.setText(it.toString()) }

        // ---- V3-4: circadian sleep window ----
        val sleepStartEt = findViewById<EditText>(R.id.sleep_start_input)
        sleepStartEt.setText(Store.get(ctx).sleepWindowStart)
        val sleepEndEt = findViewById<EditText>(R.id.sleep_end_input)
        sleepEndEt.setText(Store.get(ctx).sleepWindowEnd)

        // ---- V3-5: implementation intention text + executable replacement app ----
        val replacementEt = findViewById<EditText>(R.id.replacement_input)
        replacementEt.setText(Store.get(ctx).replacementAction)

        val pm = packageManager
        val items = ArrayList<AppItem>()
        items.add(AppItem(null, getString(R.string.set_replacement_app_none)))
        val launchables = Intent().setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launchables, 0)
            .mapNotNull { ri ->
                runCatching {
                    ri.activityInfo.packageName to
                            pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString()
                }.getOrNull()
            }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
            .forEach { (p, l) -> items.add(AppItem(p, l)) }

        val appSpinner = findViewById<Spinner>(R.id.replacement_app_spinner)
        appSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        Store.get(ctx).replacementPackage?.let { sel ->
            items.indexOfFirst { it.pkg == sel }.takeIf { it >= 0 }?.let(appSpinner::setSelection)
        }

        val container = findViewById<LinearLayout>(R.id.app_checks2)
        val enabled = Store.get(ctx).enabledApps()
        val boxes = ArrayList<CompoundButton>()
        val density = resources.displayMetrics.density

        for (t in KnownTargets.ALL) {
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
                text = "Protected feed loop"
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_med))
            }

            textCol.addView(titleView)
            textCol.addView(subView)

            val sw = SwitchCompat(this).apply {
                isChecked = enabled.contains(t.pkg)
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

        val batteryBtn = findViewById<Button>(R.id.btn_battery_opt)
        val powerMgr = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        fun updateBatteryButton() {
            if (powerMgr.isIgnoringBatteryOptimizations(packageName)) {
                batteryBtn.text = "Battery Optimization Disabled ✔"
                batteryBtn.isEnabled = false
                batteryBtn.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            } else {
                batteryBtn.text = "Exempt from Battery Optimization"
                batteryBtn.isEnabled = true
                batteryBtn.setTextColor(ContextCompat.getColor(this, R.color.text_hi))
            }
        }

        updateBatteryButton()
        batteryBtn.setOnClickListener {
            runCatching {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val modeIdx = spinner.selectedItemPosition.coerceAtLeast(0) + 1
            val mode = InterventionMode.values()[modeIdx]
            val budget = budgetEt.text.toString().toIntOrNull()?.coerceIn(5, 1000) ?: 30
            val cap = capEt.text.toString().toIntOrNull()?.coerceIn(3, 200) ?: 10
            val commit = commitEt.text.toString().toIntOrNull()?.takeIf { it > 0 }
            val apps = boxes.filter { it.isChecked }.map { it.tag as String }.toSet()

            val hhmm = Regex("\\d{1,2}:\\d{2}")
            val sStart = sleepStartEt.text.toString().trim()
            val sEnd = sleepEndEt.text.toString().trim()
            val chosenApp = (appSpinner.selectedItemPosition
                .takeIf { it >= 0 && it < items.size })?.let { items[it].pkg }

            val st = Store.get(ctx)
            st.mode = mode
            st.dailyBudget = budget
            st.sessionSoftCap = cap
            st.commitmentStopAt = commit
            st.setEnabledApps(apps)
            if (hhmm.matches(sStart)) st.sleepWindowStart = padTime(sStart)
            if (hhmm.matches(sEnd)) st.sleepWindowEnd = padTime(sEnd)
            st.replacementAction = replacementEt.text.toString().trim()
            st.replacementPackage = chosenApp
            ReelBreakerApp.INSTANCE.syncEngineFromStore()
            Toast.makeText(this, R.string.set_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun padTime(t: String): String {
        val parts = t.split(":")
        return "%02d:%02d".format(parts[0].toInt(), parts[1].toInt())
    }
}
