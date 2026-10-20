package com.reelbreaker.app

import android.app.Application
import android.content.Context
import com.reelbreaker.data.Store
import com.reelbreaker.engine.AddictionEngine
import com.reelbreaker.engine.EngineConfig

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
    }
}
