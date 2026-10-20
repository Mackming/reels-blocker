package com.reelbreaker.data

import android.content.Context
import android.content.SharedPreferences
import com.reelbreaker.engine.InterventionMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight persistence. SharedPreferences is deliberate: the accessibility
 * service, overlay activity and dashboard must all read/write the same state
 * without a process-boundary DB, and writes are tiny + infrequent (batched).
 *
 * Daily aggregates are keyed by yyyy-MM-dd so midnight resets fall out of the
 * data model for free (fresh-start temporal landmark, engine rule #4).
 */
class Store private constructor(private val p: SharedPreferences) {

    companion object {
        private const val FILE = "reelbreaker_store"
        @Volatile private var instance: Store? = null

        fun get(c: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(
                    c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }

        private fun day(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        private fun dayKey(k: String, d: String = day()) = "d_${d}_$k"
    }

    // ---- settings ----
    var mode: InterventionMode
        get() = InterventionMode.valueOf(p.getString("mode", InterventionMode.MODERATE.name)!!)
        set(v) = p.edit().putString("mode", v.name).apply()

    var dailyBudget: Int
        get() = p.getInt("daily_budget", 30)
        set(v) = p.edit().putInt("daily_budget", v).apply()

    var sessionSoftCap: Int
        get() = p.getInt("session_softcap", 10)
        set(v) = p.edit().putInt("session_softcap", v).apply()

    var commitmentStopAt: Int?
        get() = if (p.contains("commitment_stop_at")) p.getInt("commitment_stop_at", -1) else null
        set(v) {
            val e = p.edit()
            if (v == null) e.remove("commitment_stop_at") else e.putInt("commitment_stop_at", v)
            e.apply()
        }

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Runtime flag: accessibility service connected & blocker armed. */
    var blockerActive: Boolean
        get() = p.getBoolean("blocker_active", false)
        set(v) = p.edit().putBoolean("blocker_active", v).apply()

    fun enabledApps(): Set<String> =
        p.getString("enabled_apps", "")!!.split(",").filter { it.isNotBlank() }.toSet()

    fun setEnabledApps(pkg: Set<String>) =
        p.edit().putString("enabled_apps", pkg.joinToString(",")).apply()

    // ---- telemetry ----
    fun addReel(appPkg: String) {
        val k = dayKey("total"); val ka = dayKey("app_$appPkg")
        p.edit()
            .putInt(k, p.getInt(k, 0) + 1)
            .putInt(ka, p.getInt(ka, 0) + 1)
            .apply()
    }

    fun todayTotal(): Int = p.getInt(dayKey("total"), 0)

    fun todayForApp(pkg: String): Int = p.getInt(dayKey("app_$pkg"), 0)

    fun addCooldown(seconds: Int) {
        val ks = dayKey("cooldown_s"); val kn = dayKey("cooldown_n")
        p.edit()
            .putInt(ks, p.getInt(ks, 0) + seconds)
            .putInt(kn, p.getInt(kn, 0) + 1)
            .apply()
    }

    fun addBlockedAttempt() {
        val k = dayKey("blocked")
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
    }

    fun logSession(swipes: Int, durationSec: Long) {
        val ks = dayKey("sessions")
        val prev = p.getString(ks, "")!!
        val merged = if (prev.isEmpty()) "$swipes/${durationSec}s" else "$prev,$swipes/${durationSec}s"
        val km = dayKey("max_session")
        p.edit()
            .putString(ks, merged)
            .putInt(km, maxOf(p.getInt(km, 0), swipes))
            .apply()
    }

    /** Returns list of "yyyy-MM-dd|total|cooldownS|blocked" for last [days]. */
    fun history(days: Int = 7): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val out = ArrayList<String>()
        for (i in 0 until days) {
            val d = fmt.format(Date(System.currentTimeMillis() - i * 86_400_000L))
            val total = p.getInt(dayKey("total", d), 0)
            val cd = p.getInt(dayKey("cooldown_s", d), 0)
            val bl = p.getInt(dayKey("blocked", d), 0)
            out.add("$d|$total|$cd|$bl")
        }
        return out
    }

    /** Streak: consecutive days (ending today or yesterday) under budget. */
    fun streak(): Int {
        val budget = dailyBudget
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var s = 0
        for (i in 0..365) {
            val d = fmt.format(Date(System.currentTimeMillis() - i * 86_400_000L))
            val total = p.getInt(dayKey("total", d), -1)
            if (total < 0) break                 // no data: before install date
            if (total <= budget) s++ else break  // broken day ends streak
        }
        return s
    }
}
