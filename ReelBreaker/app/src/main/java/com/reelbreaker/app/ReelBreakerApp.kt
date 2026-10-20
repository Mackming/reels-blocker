package com.reelbreaker.app

import android.app.Application
import android.content.Context
import com.reelbreaker.data.Store
import com.reelbreaker.engine.AddictionEngine
import com.reelbreaker.engine.EngineConfig
import com.reelbreaker.engine.InterventionBandit

/**
 * Process-wide singletons. The accessibility service and the UI run in the
 * same process, so a shared engine instance keeps live session state (swipe
 * cadence, breach ladder) consistent across overlay + dashboard.
 */
class ReelBreakerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        val ctx = this
        val cfg = EngineConfig(
            mode = Store.get(ctx).mode,
            dailyBudget = Store.get(ctx).dailyBudget,
            sessionSoftCap = Store.get(ctx).sessionSoftCap,
            commitmentStopAt = Store.get(ctx).commitmentStopAt,
        )
        engine = AddictionEngine(cfg)
        engine.setTodayTotal(Store.get(ctx).todayTotal())
        // V4-5: process-wide Thompson-sampling bandit; the engine asks it for
        // an arm at every Tier-2 trigger. Persisted trials are replayed by the
        // accessibility service on connect (TelemetryBus.loadBanditTrials).
        bandit = InterventionBandit()
        engine.armSelector = { bandit.nextArm() }
    }

    fun syncEngineFromStore() {
        val ctx = this
        engine.updateConfig(
            EngineConfig(
                mode = Store.get(ctx).mode,
                dailyBudget = Store.get(ctx).dailyBudget,
                sessionSoftCap = Store.get(ctx).sessionSoftCap,
                commitmentStopAt = Store.get(ctx).commitmentStopAt,
            )
        )
    }

    companion object {
        lateinit var INSTANCE: ReelBreakerApp
            private set
        lateinit var engine: AddictionEngine
            private set
        lateinit var bandit: InterventionBandit
            private set
    }
}
