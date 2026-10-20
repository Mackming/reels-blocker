package com.reelbreaker.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.reelbreaker.R
import com.reelbreaker.app.ReelBreakerApp
import com.reelbreaker.core.KnownTargets
import com.reelbreaker.data.Store
import com.reelbreaker.engine.InterventionMode

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val ctx = this
        val spinner = findViewById<Spinner>(R.id.mode_spinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.modes)
        )
        spinner.setSelection(Store.get(ctx).mode.ordinal - 1)

        val budgetEt = findViewById<EditText>(R.id.budget_input)
        budgetEt.setText(Store.get(ctx).dailyBudget.toString())
        val capEt = findViewById<EditText>(R.id.cap_input)
        capEt.setText(Store.get(ctx).sessionSoftCap.toString())
        val commitEt = findViewById<EditText>(R.id.commit_input)
        Store.get(ctx).commitmentStopAt?.let { commitEt.setText(it.toString()) }

        val container = findViewById<android.widget.LinearLayout>(R.id.app_checks2)
        val enabled = Store.get(ctx).enabledApps()
        val boxes = ArrayList<CheckBox>()
        for (t in KnownTargets.ALL) {
            val cb = CheckBox(this).apply {
                text = t.label
                isChecked = enabled.contains(t.pkg)
                tag = t.pkg
                setTextColor(getColor(R.color.text_hi))
            }
            boxes.add(cb); container.addView(cb)
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val modeIdx = spinner.selectedItemPosition.coerceAtLeast(0) + 1
            val mode = InterventionMode.values()[modeIdx]
            val budget = budgetEt.text.toString().toIntOrNull()?.coerceIn(5, 1000) ?: 30
            val cap = capEt.text.toString().toIntOrNull()?.coerceIn(3, 200) ?: 10
            val commit = commitEt.text.toString().toIntOrNull()?.takeIf { it > 0 }
            val apps = boxes.filter { it.isChecked }.map { it.tag as String }.toSet()

            val st = Store.get(ctx)
            st.mode = mode
            st.dailyBudget = budget
            st.sessionSoftCap = cap
            st.commitmentStopAt = commit
            st.setEnabledApps(apps)
            ReelBreakerApp.INSTANCE.syncEngineFromStore()
            Toast.makeText(this, R.string.set_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
