package com.reelbreaker.overlay

import com.reelbreaker.app.ReelBreakerApp
import com.reelbreaker.data.Store

/**
 * V4-5 — Thin indirection so the sensor-driven overlay controllers can report
 * intervention outcomes (bandit scoring + telemetry) without depending on the
 * AccessibilityService class (avoids circular imports). The service wires
 * [sink] in onServiceConnected; if unset, outcomes fall back to Store-only logs.
 */
object ReelBreakerAppHooks {
    var sink: ((outcome: String, armName: String) -> Unit)? = null

    fun onInterventionFinished(outcome: String, armName: String) {
        val s = sink
        if (s != null) {
            s(outcome, armName)
        } else {
            runCatching { ReelBreakerApp.bandit.recordOutcome(success = outcome == "SURFED") }
            runCatching { Store.get(ReelBreakerApp.INSTANCE).recordOverride(outcome) }
        }
    }
}
