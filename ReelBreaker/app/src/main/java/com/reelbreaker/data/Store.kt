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

    /** Implementation intention: "If I get blocked on Reels, I will [this]." */
    var replacementAction: String
        get() = p.getString("replacement_action", "")!!
        set(v) = p.edit().putString("replacement_action", v).apply()

    /**
     * V3-5 — Executable Replacement Bridge: optional installed constructive
     * app (reader / chess / notes / workout) launched by the Tier-3 CTA so
     * the if-then plan is *executable*, not just remembered.
     */
    var replacementPackage: String?
        get() = p.getString("replacement_pkg", null)
        set(v) {
            val e = p.edit()
            if (v.isNullOrBlank()) e.remove("replacement_pkg") else e.putString("replacement_pkg", v)
            e.apply()
        }

    /** V3-4 — user sleep window bounds for circadian vulnerability ("HH:mm"). */
    var sleepWindowStart: String
        get() = p.getString("sleep_start", "22:30")!!
        set(v) = p.edit().putString("sleep_start", v).apply()

    var sleepWindowEnd: String
        get() = p.getString("sleep_end", "05:00")!!
        set(v) = p.edit().putString("sleep_end", v).apply()

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

    /** V4-2 doorway intention for the current calendar day ("none" = not asked/declined). */
    var doorwayIntention: String
        get() = p.getString(dayKey("doorway"), "none")!!
        set(v) = p.edit().putString(dayKey("doorway"), v).apply()

    /** V4-7 agency counters (replaces gamified zero-reset streaks as headline metrics). */
    fun recordUnassistedStop(minutesIntoSession: Long) {
        val ke = dayKey("agency_ends"); val km = dayKey("agency_minutes")
        val prevTotal = p.getInt(ke, 0) + 1
        val prevMin = p.getLong(km, 0L) + minutesIntoSession
        p.edit().putInt(ke, prevTotal).putLong(km, prevMin).apply()
        // lifetime rollups for recovery-speed / agency-rate math
        p.edit().putInt("life_agency_ends", p.getInt("life_agency_ends", 0) + 1)
            .putLong("life_agency_minutes", p.getLong("life_agency_minutes", 0L) + minutesIntoSession)
            .apply()
    }

    fun recordAssistedEnd() {
        p.edit().putInt(dayKey("assisted_ends"), p.getInt(dayKey("assisted_ends"), 0) + 1)
            .putInt("life_assisted_ends", p.getInt("life_assisted_ends", 0) + 1)
            .apply()
    }

    /** V4-7 recovery speed: minutes from last swipe of a binge to disengagement. */
    fun recordRecovery(minutes: Long) {
        p.edit().putInt("recovery_n", p.getInt("recovery_n", 0) + 1)
            .putLong("recovery_sum", p.getLong("recovery_sum", 0L) + minutes)
            .apply()
    }

    fun recoverySpeedMinutes(): Double {
        val n = p.getInt("recovery_n", 0)
        return if (n == 0) -1.0 else p.getLong("recovery_sum", 0L).toDouble() / n
    }

    /** Agency ratio over recorded session ends (0..1; -1 when no data yet). */
    fun agencyRatio(): Double {
        val a = p.getInt("life_agency_ends", 0)
        val s = p.getInt("life_assisted_ends", 0)
        return if (a + s == 0) -1.0 else a.toDouble() / (a + s)
    }

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

    /** Successful prefrontal override / urge surf count for today. */
    fun recordOverride(outcome: String) {
        val k = dayKey(if (outcome == "OVERRIDE") "overrides" else "surfs")
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
    }

    fun todayOverrides(): Int = p.getInt(dayKey("overrides"), 0)
    fun todaySurfs(): Int = p.getInt(dayKey("surfs"), 0)

    /** V4-6: count of 10-minute compassionate overrides granted from the
     *  Trigger Repair screen (logged for the evening review). */
    fun addOverrideLog10m() {
        val k = dayKey("override_10m")
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
    }
    fun todayOverrideLog10m(): Int = p.getInt(dayKey("override_10m"), 0)

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
