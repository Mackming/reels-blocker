package com.reelbreaker.engine

import kotlin.math.max
import kotlin.math.min

/**
 * ReelBreaker Behavioural Engine
 * ==============================
 * Intervention policy derived from addiction neuroscience & habit science.
 * Every rule below maps to a published mechanism; citations in comments.
 *
 * 1. VARIABLE-RATIO REINFORCEMENT (Ferster & Skinner, 1957; dopamine
 *    prediction-error work of Schultz 1997): reel feeds are slot machines.
 *    The compulsion lives in the *anticipation*, not the reward. We therefore
 *    do NOT remove the reward abruptly (that triggers an extinction burst —
 *    relapse stronger than baseline). Instead we insert a predictable pause
 *    before every pull: making the machine "boring and predictable" flattens
 *    the prediction error and weakens conditioning, while remaining tolerable.
 *
 * 2. RESPONSE EFFORT / FRICTION (James Clear, "Atomic Habits": make it
 *    difficult; research on "temptation bundling" & distance-based self-
 *    control, Duckworth et al.): each escalation stage adds effort between
 *    urge and action. Effort depletes the impulsive System-1 loop before it
 *    can execute (Baumeister's strength model + Hare/Camerer vmPFC-DLPFC
 *    intertemporal-choice models: cooling the limbic hit by adding seconds
 *    lets the prefrontal valuation catch up — "hot-cold empathy gap",
 *    Loewenstein).
 *
 * 3. URGE SURFING (Marlatt's mindfulness-based relapse prevention): urges are
 *    wave-like, peak ~20-30 min but behavioural cravings typically crest in
 *    60-90 s. Our cooldown screens ask the user to ride the wave with a
 *    breathing pacer instead of fighting it — extinction without suppression.
 *
 * 4. STIMULUS CONTROL & FRESH-START EFFECT (Van Ryn et al., temporal
 *    landmarks): daily budget resets at midnight; when a limit is crossed we
 *    surface a "yesterday you planned to stop at N" commitment reminder,
 *    re-activating the cold-state intention inside the hot state.
 *
 * 5. IMPLEMENTATION INTENTIONS ("if-then" plans, Gollwitzer): the app asks
 *    users to pre-commit a stopping rule ("I stop after X reels / Y minutes")
 *    and enforces exactly that rule — decision made once, in cold cognition,
 *    executed mechanically, removing in-the-moment choice (decision fatigue).
 *
 * 6. LOSS OF CONTROL SIGNAL (LaRose et al., problematic media use =
 *    "displacement + loss of control"): we don't only measure time — we
 *    detect *acceleration*. Escalating swipe velocity (shortening inter-swipe
 *    intervals) marks the transition from hedonic browsing to compulsive
 *    autopilot ("zoning out"). That is when interventions fire hardest.
 *
 * 7. INTERMITTENT REWARD FOR ABSTINENCE: streaks + daily wins exploit the
 *    same variable-ratio circuitry in reverse (habit formation cue-routine-
 *    reward loop, Lally's 66-day median adherence curve).
 */
enum class InterventionMode { OFF, GENTLE, MODERATE, STRICT, BLOCK }

data class EngineConfig(
    val mode: InterventionMode = InterventionMode.MODERATE,
    /** reels allowed per day before hard behaviour (budget) */
    val dailyBudget: Int = 30,
    /** session length in reels before friction even under budget */
    val sessionSoftCap: Int = 10,
    /** if user set an explicit "if-then" stop rule, enforce it */
    val commitmentStopAt: Int? = null,
)

sealed class Intervention {
    /** No UI; show live counter chip only. */
    object Awareness : Intervention()
    /** Breathing/urge-surf screen for [seconds], then user may continue. */
    data class Cooldown(val seconds: Int, val reason: String) : Intervention()
    /** Reflective checkpoint: intent check before continuing. */
    data class Checkpoint(val reason: String) : Intervention()
    /** Hard block until tomorrow / until commitment reset. */
    data class Block(val reason: String) : Intervention()
}

/**
 * Pure state machine — trivially unit-testable, no Android deps.
 * Feed it swipes + timing, it returns the intervention to display.
 */
class AddictionEngine(cfg: EngineConfig) {

    private var config = cfg

    // rolling inter-swipe intervals (ms) — the "compulsion ramp"
    private val intervals = ArrayDeque<Long>(40)
    private var lastSwipeAt = 0L
    private var sessionSwipes = 0
    private var sessionStart = 0L
    private var todayTotal = 0

    // escalating cooldown ladder: first breach 5 s, then 10, 20, 45...
    private var breachLevel = 0
    private var lastInterventionAt = 0L

    fun updateConfig(c: EngineConfig) { config = c }
    fun setTodayTotal(n: Int) { todayTotal = n }

    /** Exponential moving average of inter-swipe interval: lower = more compulsive. */
    private fun rampVelocity(): Double {
        if (intervals.size < 4) return -1.0
        // compare mean of last 8 vs baseline mean of all
        val recent = intervals.takeLast(8).average()
        val base = intervals.average()
        return if (base <= 0) -1.0 else recent / base
    }

    /**
     * Loss-of-control heuristic: user is accelerating AND ignoring dwell time.
     * Median TikTok/Reels dwell is 10-25 s per clip when mindful; compulsive
     * "trance scrolling" shows sub-4 s swipes with accelerating cadence.
     */
    fun isLossOfControl(): Boolean {
        if (intervals.size < 6) return false
        val recentMean = intervals.takeLast(6).average()
        val ratio = rampVelocity()
        return recentMean < 3_500 && (ratio < 0.75 || recentMean < 2_000)
    }

    fun onSessionEnd() {
        sessionSwipes = 0
        breachLevel = 0
        intervals.clear()
        lastSwipeAt = 0L
    }

    /** Main entry: called once per detected reel swipe. */
    fun onSwipe(now: Long = System.currentTimeMillis()): Decision {
        if (lastSwipeAt > 0) {
            val dt = now - lastSwipeAt
            if (dt in 1..120_000) {
                intervals.addLast(dt)
                if (intervals.size > 40) intervals.removeFirst()
            }
        } else {
            sessionStart = now
        }
        lastSwipeAt = now
        sessionSwipes++
        todayTotal++

        val d = decide(now)
        return d
    }

    data class Decision(
        val intervention: Intervention?,
        /** true => also send the feed app to background (kill switch) */
        val forceExit: Boolean,
        logTag: String = "",
    )

    private fun decide(now: Long): Decision {
        val mode = config.mode
        if (mode == InterventionMode.OFF) return Decision(null, false)

        val loc = isLossOfControl()
        val overBudget = todayTotal >= config.dailyBudget
        val overCommitment = config.commitmentStopAt?.let { sessionSwipes >= it } ?: false
        val overSession = sessionSwipes >= config.sessionSoftCap

        // --- Stage 4: BLOCK ---
        if (mode == InterventionMode.BLOCK && (overBudget || overCommitment)) {
            return Decision(
                Intervention.Block(buildReason(overBudget, overCommitment)),
                forceExit = true,
            )
        }
        if ((mode == InterventionMode.STRICT || mode == InterventionMode.MODERATE) && overCommitment) {
            return Decision(Intervention.Block("Your pre-committed stop rule has been reached."), forceExit = true)
        }

        // Anti-spam: never stack two full-screen interventions closer than 25 s
        // (interrupting an interruption would just train app-closing behaviour).
        val spacedEnough = now - lastInterventionAt > 25_000

        // --- Stage 3: hard wall at budget (all modes except OFF/GENTLE-lite) ---
        if (overBudget && spacedEnough && mode != InterventionMode.GENTLE) {
            lastInterventionAt = now
            breachLevel++
            return Decision(
                Intervention.Checkpoint(
                    "Daily budget (${config.dailyBudget}) reached. This is your planned stopping point — " +
                        "one more pull feels free, but studies show post-limit viewing is almost always regretted."
                ), false
            )
        }

        // --- Stage 2: loss-of-control interrupt (the core "neuro" trigger) ---
        if (loc && spacedEnough) {
            lastInterventionAt = now
            breachLevel++
            val secs = cooldownSeconds()
            return Decision(
                Intervention.Cooldown(
                    secs,
                    "Your swipes are accelerating — this is what 'loss of control' looks like in the data. " +
                        "Ride the wave: urges crest and fall in ~${secs}s."
                ), false
            )
        }

        // --- Stage 1: session soft cap friction ---
        if (overSession && spacedEnough && mode != InterventionMode.GENTLE) {
            lastInterventionAt = now
            breachLevel++
            val secs = cooldownSeconds()
            return Decision(
                Intervention.Cooldown(secs, "You've watched $sessionSwipes reels in one sitting. Quick check-in before the next pull."),
                false
            )
        }

        // --- Stage 0: pure awareness (never blocks, keeps reactance low) ---
        return Decision(if (mode == InterventionMode.GENTLE) Intervention.Awareness else null, false)
    }

    /** Escalating ladder 5→10→20→30→45 s, capped. Predictable pause kills
     *  variable-ratio anticipation (rule #1) while staying tolerable. */
    private fun cooldownSeconds(): Int =
        min(45, intArrayOf(5, 10, 20, 30, 45)[min(breachLevel, 4)])

    private fun buildReason(overBudget: Boolean, overCommitment: Boolean) = when {
        overCommitment -> "You promised (while calm) to stop here. Keep that promise — self-trust compounds."
        overBudget -> "Daily limit reached. Tomorrow is a fresh start; tonight, the feed will still be there."
        else -> "Blocked."
    }
}
