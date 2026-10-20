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

    // escalating cooldown ladder — Tier 3 exponential backoff inside a
    // rolling 60-minute window: T = T_base * 1.8^breach_count (capped)
    private var breachTimes = ArrayDeque<Long>(16)
    private var lastInterventionAt = 0L

    fun updateConfig(c: EngineConfig) { config = c }
    fun setTodayTotal(n: Int) { todayTotal = n }

    /** Number of intervention breaches in the trailing 60 minutes. */
    fun breachCount(now: Long = System.currentTimeMillis()): Int {
        while (breachTimes.isNotEmpty() && now - breachTimes.first() > 60 * 60_000L) {
            breachTimes.removeFirst()
        }
        return breachTimes.size
    }

    private fun registerBreach(now: Long) {
        breachTimes.addLast(now)
        if (breachTimes.size > 16) breachTimes.removeFirst()
    }

    /** Exposes the current state for telemetry / dashboard. */
    fun sessionSwipesNow(): Int = sessionSwipes
    fun meanIsiMs(): Double = if (intervals.isEmpty()) -1.0 else intervals.average()

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

    /** Optional live kinematics supplied by the SmartSwipeDetector. */
    var kinematicsProvider: (() -> com.reelbreaker.core.SmartSwipeDetector.Kinematics?)? = null

    /** V3-4 — set true when CircadianContextProvider flags dark-room + sleep window. */
    var circadianVulnerable: Boolean = false

    /** Effective caps, tightened 50% in the high-risk late-night/dark window. */
    private fun effDailyBudget() = if (circadianVulnerable) max(5, config.dailyBudget / 2) else config.dailyBudget
    private fun effSessionSoftCap() = if (circadianVulnerable) max(3, config.sessionSoftCap / 2) else config.sessionSoftCap

    // ---- V3-6 hazard-rate stochastic gate state ----
    private var swipesSinceLastSpeedbump = 0
    private var speedbumpCount = 0

    /** V4-2 doorway intention: minutes the user pre-committed on entry (0 = none). */
    var doorwayMinutes: Int = 0
    /** V4-1 natural-gap scheduling: set when a Tier-2 trigger is armed. */
    var speedbumpQueuedAt: Long = 0L
        private set

    fun clearSpeedbumpQueue() { speedbumpQueuedAt = 0L }

    /** V4-5 bandit hook: engine asks the app-level bandit which arm to serve. */
    var armSelector: (() -> com.reelbreaker.engine.BanditArm)? = null

    /** V4-6 compassionate escalation: breach #4+ swaps longer timers for an
     *  autonomy escape hatch instead of a punitive 300 s wall. */
    var overrideOffered: Boolean = false
    private var overrideUntil: Long = 0L
    fun overrideActive(now: Long = System.currentTimeMillis()): Boolean = now < overrideUntil
    fun grantOverride(minutes: Int, now: Long = System.currentTimeMillis()) {
        overrideUntil = now + minutes * 60_000L
        overrideOffered = false
    }

    /**
     * V3-3 — Passive lock (dwell watchdog): a full-screen reel looped for
     * >40 s with zero touch events. Registered as a synthetic fatigue event:
     * it counts toward trance scoring and nudges the escalation ladder, but
     * is NOT a swipe (no daily-budget inflation).
     */
    fun onPassiveLock(now: Long = System.currentTimeMillis()) {
        passiveLocks++
        if (sessionStart == 0L) sessionStart = now - TRANCE_SEED_MS
        // inflate the trance clock: treat long dwell as low-variance consumption
        dwellFatigueMs += PASSIVE_LOCK_PENALTY_MS
    }
    private var passiveLocks = 0
    private var dwellFatigueMs = 0L
    fun passiveLocksNow(): Int = passiveLocks
    fun dwellFatigueMs(): Long = dwellFatigueMs

    fun onSessionEnd() {
        sessionSwipes = 0
        breachTimes.clear()
        intervals.clear()
        lastSwipeAt = 0L
        swipesSinceLastSpeedbump = 0
        speedbumpCount = 0
        passiveLocks = 0
        dwellFatigueMs = 0
        doorwayMinutes = 0
        speedbumpQueuedAt = 0L
        overrideOffered = false
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
        /** Tier-2 stochastic speedbump (affective labeling / WM task) */
        val speedbump: Boolean = false,
        /** V4-5: which bandit arm to serve when [speedbump] is true */
        val arm: BanditArm? = null,
        /** V4-6: breach >= 4 -> Trigger Repair screen instead of longer timer */
        val triggerRepair: Boolean = false,
        /** live kinematic classification at decision time */
        val brainState: com.reelbreaker.core.SmartSwipeDetector.BrainState =
            com.reelbreaker.core.SmartSwipeDetector.BrainState.ATTENDED,
    )

    private fun decide(now: Long): Decision {
        val mode = config.mode
        if (mode == InterventionMode.OFF) return Decision(null, false)
        // V4-6: a granted 10-minute autonomy override mutes all friction —
        // the user already paid the reflection cost; shaming them again would
        // only train rage-quitting the whole app.
        if (overrideActive(now)) return Decision(null, false)

        val kin = kinematicsProvider?.invoke()
        val brain = kin?.state
            ?: com.reelbreaker.core.SmartSwipeDetector.BrainState.ATTENDED
        // V3-3: a passive lock (40 s dwell on one looping reel) is itself a
        // loss-of-control signal even though zero swipe events fired.
        val loc = isLossOfControl() || passiveLocks > 0 ||
                brain == com.reelbreaker.core.SmartSwipeDetector.BrainState.FORAGING_STATE ||
                brain == com.reelbreaker.core.SmartSwipeDetector.BrainState.TRANCE_STATE
        val overBudget = todayTotal >= effDailyBudget()
        val overCommitment = config.commitmentStopAt?.let { sessionSwipes >= it } ?: false
        val overSession = sessionSwipes >= effSessionSoftCap()
        swipesSinceLastSpeedbump++

        // --- Stage 4: BLOCK (Tier 3 terminal: kill switch + replacement reward) ---
        if (mode == InterventionMode.BLOCK && (overBudget || overCommitment)) {
            registerBreach(now); lastInterventionAt = now
            return Decision(
                Intervention.Block(buildReason(overBudget, overCommitment)),
                forceExit = true,
                brainState = brain,
            )
        }
        if ((mode == InterventionMode.STRICT || mode == InterventionMode.MODERATE) && overCommitment) {
            registerBreach(now); lastInterventionAt = now
            return Decision(
                Intervention.Block("Your pre-committed stop rule has been reached."),
                forceExit = true, brainState = brain,
            )
        }

        // Anti-spam: never stack two full-screen interventions closer than 25 s
        // (interrupting an interruption would just train app-closing behaviour).
        val spacedEnough = now - lastInterventionAt > 25_000

        // --- Tier 2: HAZARD-RATE STOCHASTIC SPEEDBUMP (V3-6) ------------------
        // The old modulo gate (sessionSwipes % N == 0) was learnable: users
        // anticipated the pause and habituated to it. We now draw from an
        // exponential hazard function after a hard refractory period of 4
        // swipes:
        //     P(trigger | Δ) = 1 - exp(-λ·Δ),  Δ = swipes since last speedbump
        //     λ = 0.18 · (1 + 0.5·speedbumpCount)   — escalation per breach
        // Early in a session pauses are rare and unpredictable; after repeated
        // breaches the hazard rate climbs so friction becomes unavoidable,
        // yet never periodic. Zero back-to-back spam is guaranteed by the
        // refractory floor + the 25 s spacing rule above.
        if (mode != InterventionMode.GENTLE && sessionSwipes >= effSessionSoftCap() / 2 && spacedEnough) {
            val lambda = 0.18 * (1.0 + 0.5 * speedbumpCount)
            val delta = swipesSinceLastSpeedbump
            val p = if (delta > HAZARD_REFRACTORY) 1.0 - Math.exp(-lambda * (delta - HAZARD_REFRACTORY)) else 0.0
            if (delta > HAZARD_REFRACTORY && kotlin.random.Random.nextDouble() < p) {
                lastInterventionAt = now
                registerBreach(now)
                swipesSinceLastSpeedbump = 0
                speedbumpCount++
                // V4-5: Thompson-sampling arm selection (face-down surf / sigh / grid).
                // nextArm() also opens the pending trial for the reward monitor.
                val arm = armSelector?.invoke() ?: BanditArm.ARM_MOTOR_GRID
                speedbumpQueuedAt = System.currentTimeMillis()   // service renders at the next natural gap
                return Decision(
                    intervention = null,
                    forceExit = false,
                    speedbump = true,
                    arm = arm,
                    brainState = brain,
                )
            }
        }

        // --- Stage 3: hard wall at budget (all modes except OFF/GENTLE-lite) ---
        if (overBudget && spacedEnough && mode != InterventionMode.GENTLE) {
            lastInterventionAt = now
            registerBreach(now)
            return Decision(
                Intervention.Checkpoint(
                    "Daily budget (${effDailyBudget()}) reached. This is your planned stopping point — " +
                        "one more pull feels free, but studies show post-limit viewing is almost always regretted."
                ), false, brainState = brain
            )
        }

        // --- Stage 2: loss-of-control interrupt (the core "neuro" trigger) ---
        if (loc && spacedEnough) {
            lastInterventionAt = now
            registerBreach(now)
            // V4-6 compassionate escalation: breach >= 4 in the rolling hour no
            // longer buys a bigger punitive timer (that just trains rage-quitting
            // the blocker). It opens the Trigger Repair screen with a pre-
            // committed autonomy escape hatch instead.
            if (breachCount(now) >= 4) {
                overrideOffered = true
                return Decision(
                    Intervention.Checkpoint("Trigger repair"),
                    forceExit = false,
                    triggerRepair = true,
                    brainState = brain,
                )
            }
            val secs = cooldownSeconds(now)
            val why = when (brain) {
                com.reelbreaker.core.SmartSwipeDetector.BrainState.FORAGING_STATE ->
                    "You are \"foraging\": rejecting clips faster than ${FORAGING_MS / 1000}s apart — that is dopamine-hunting, not enjoying."
                com.reelbreaker.core.SmartSwipeDetector.BrainState.TRANCE_STATE ->
                    "Metronomic swiping detected (low cadence variance): classic trance / dissociative scrolling."
                else ->
                    "Your swipes are accelerating — this is what 'loss of control' looks like in the data."
            }
            return Decision(
                Intervention.Cooldown(
                    secs,
                    "$why Ride the wave: urges crest and fall in ~${secs}s."
                ), false, brainState = brain
            )
        }

        // --- Stage 1: session soft cap friction ---
        if (overSession && spacedEnough && mode != InterventionMode.GENTLE) {
            lastInterventionAt = now
            registerBreach(now)
            val secs = cooldownSeconds(now)
            return Decision(
                Intervention.Cooldown(secs, "You've watched $sessionSwipes reels in one sitting. Quick check-in before the next pull."),
                false, brainState = brain
            )
        }

        // --- Stage 0: pure awareness (never blocks, keeps reactance low) ---
        return Decision(if (mode == InterventionMode.GENTLE) Intervention.Awareness else null, false, brainState = brain)
    }

    companion object {
        const val FORAGING_MS = 2_500L
        const val PASSIVE_LOCK_PENALTY_MS = 90_000L   // each loop adds trance pressure
        const val TRANCE_SEED_MS = 8 * 60_000L
        /** V3-6 hazard gate: minimum swipes between two speedbumps (no spam). */
        const val HAZARD_REFRACTORY = 4
    }

    /**
     * Tier 3 escalating cooldown — V4-6 inverted late curve.
     * Breaches 1–3 escalate exponentially (10 s → 20 s → ~40 s, humane cap);
     * breach >= 4 never reaches a punitive 300 s wall: the engine routes to
     * the Trigger Repair screen with the press-and-hold autonomy hatch
     * instead. Escalation that stays predictable-but-bounded is what lets
     * the vmPFC re-price "one more swipe" without teaching learned helplessness.
     */
    private fun cooldownSeconds(now: Long): Int {
        val breaches = breachCount(now)          // includes the one just registered
        val t = 10.0 * Math.pow(2.0, (breaches - 1).coerceAtLeast(0).toDouble())
        return min(60, max(10, t.toInt()))
    }

    private fun buildReason(overBudget: Boolean, overCommitment: Boolean) = when {
        overCommitment -> "You promised (while calm) to stop here. Keep that promise — self-trust compounds."
        overBudget -> "Daily limit reached. Tomorrow is a fresh start; tonight, the feed will still be there."
        else -> "Blocked."
    }
}
