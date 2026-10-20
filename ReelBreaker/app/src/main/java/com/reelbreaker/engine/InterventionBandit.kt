package com.reelbreaker.engine

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random
import java.util.Random as JRandom

/**
 * V4-5 — On-device Thompson Sampling multi-armed bandit (N-of-1 trial).
 * =====================================================================
 * Tier 2 friction is not one-size-fits-all: some brains respond to physical
 * interruption (face-down urge surf), some to interoceptive exercises
 * (physiological sigh), some to a pure working-memory load (motor grid).
 * Instead of guessing, ReelBreaker runs a personal adaptive experiment:
 *
 *   Arms:  ARM_FACE_DOWN_SURF | ARM_PHYSIOLOGICAL_SIGH | ARM_MOTOR_GRID
 *   Prior: Beta(alpha=2, beta=2) shrinkage prior per arm — every intervention
 *          starts "equally probably helpful" so early data can't lock in a
 *          bad first impression.
 *   Select: sample theta_i ~ Beta(alpha_i, beta_i) at each Tier-2 trigger and
 *          serve the argmax. Thompson sampling is posterior-sampling regret
 *          minimization (Russo & Van Roy 2016): exploration decays
 *          automatically as evidence accumulates — no epsilon schedule needed.
 *   Reward: SUCCESS (alpha+1) if the user voluntarily exits the target app
 *           within 60 s of the intervention AND does not reopen any target
 *           app for 15 minutes. Otherwise FAILURE (beta+1). This operational
 *           definition rewards *disengagement*, not mere dismissal.
 *
 * Pure Kotlin + atomic counters; persistence handled by TelemetryBus rows
 * ("BANDIT" interventions with arm/outcome) which are replayed on service
 * start via [restore] so learning survives reboots and service restarts.
 */
enum class BanditArm { ARM_FACE_DOWN_SURF, ARM_PHYSIOLOGICAL_SIGH, ARM_MOTOR_GRID }

class InterventionBandit(rng: Random = Random.Default) {
    // Java Random for nextGaussian (Marsaglia polar method) used by sampleGamma.
    private val rng = JRandom(rng.nextLong())

    companion object {
        /** Shrinkage prior: keeps tiny samples from dominating selection. */
        const val PRIOR_ALPHA = 2.0
        const val PRIOR_BETA = 2.0
        /** Dashboard insight appears once enough trials exist to mean something. */
        const val MIN_TRIALS_FOR_INSIGHT = 10

        fun armFromName(n: String?): BanditArm? = runCatching { BanditArm.valueOf(n ?: "") }.getOrNull()
    }

    // gamma-corrected counts (successes / failures), guarded by @Synchronized
    private val alpha = HashMap<BanditArm, Double>().also { m ->
        BanditArm.values().forEach { m[it] = PRIOR_ALPHA }
    }
    private val beta = HashMap<BanditArm, Double>().also { m ->
        BanditArm.values().forEach { m[it] = PRIOR_BETA }
    }
    private var lastArm: BanditArm? = null
    private val pending = AtomicLong(0L)     // ts(ms) of the awaiting-outcome trial

    /** Draw from each arm's posterior and pick the winner. */
    @Synchronized
    fun nextArm(): BanditArm {
        val best = BanditArm.values()
            .map { it to sampleBeta(alpha[it]!!, beta[it]!!) }
            .maxByOrNull { it.second }!!
            .first
        lastArm = best
        pending.set(System.currentTimeMillis())
        return best
    }

    /**
     * Called by the reward monitor. [success] follows the strict definition:
     * exited within 60 s AND stayed away >= 15 min.
     */
    @Synchronized
    fun recordOutcome(success: Boolean) {
        val arm = lastArm ?: return
        if (pending.getAndSet(0L) == 0L) return   // already scored
        if (success) alpha[arm] = alpha[arm]!! + 1.0 else beta[arm] = beta[arm]!! + 1.0
    }

    @Synchronized
    fun abandonPending() { pending.set(0L); lastArm = null }

    @Synchronized
    fun pendingSince(): Long = pending.get()

    @Synchronized
    fun currentArm(): BanditArm? = if (pending.get() != 0L) lastArm else null

    @Synchronized
    fun totalTrials(): Int =
        BanditArm.values().sumOf { ((alpha[it]!! - PRIOR_ALPHA) + (beta[it]!! - PRIOR_BETA)).toInt() }

    @Synchronized
    fun stats(): Map<BanditArm, Pair<Double, Double>> =
        BanditArm.values().associateWith { (alpha[it]!! - PRIOR_ALPHA) to (beta[it]!! - PRIOR_BETA) }

    /** Best arm by posterior mean, or null until >= MIN_TRIALS_FOR_INSIGHT. */
    @Synchronized
    fun discovery(): Pair<BanditArm, Double>? {
        if (totalTrials() < MIN_TRIALS_FOR_INSIGHT) return null
        val means = BanditArm.values().map { it to mean(it) }
        val best = means.maxByOrNull { it.second }!!
        val worst = means.minByOrNull { it.second }!!
        if (best.first == worst.first) return best
        val ratio = max(best.second, 0.05) / max(worst.second, 0.05)
        return if (ratio >= 1.3) best else null
    }

    @Synchronized
    fun mean(arm: BanditArm): Double = alpha[arm]!! / (alpha[arm]!! + beta[arm]!!)

    /** Replay persisted outcomes (service start). */
    @Synchronized
    fun restore(rows: List<Pair<String, Boolean>>) {
        rows.forEach { (armName, success) ->
            val arm = armFromName(armName) ?: return@forEach
            if (success) alpha[arm] = alpha[arm]!! + 1.0 else beta[arm] = beta[arm]!! + 1.0
        }
    }

    /**
     * Exact Beta(a,b) sampler without native libs:
     * Gamma draws via Marsaglia–Tsow normal-power method, then X/(X+Y).
     */
    private fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a)
        val y = sampleGamma(b)
        return if (x + y <= 0.0) 0.5 else x / (x + y)
    }

    private fun sampleGamma(shape: Double): Double {
        if (shape <= 0.0) return 0.0
        if (shape < 1.0) {
            // Ahrens–Dieter boost: Gamma(a) = Gamma(a+1) * U^(1/a)
            return sampleGamma(shape + 1.0) * Math.pow(rng.nextDouble(), 1.0 / shape)
        }
        // Marsaglia & Tsow
        val d = shape - 1.0 / 3.0
        val c = 1.0 / Math.sqrt(9.0 * d)
        repeat(64) {
            var x = rng.nextGaussian()
            var v = 1.0 + c * x
            if (v > 0.0) {
                v *= v * v
                val u = rng.nextDouble()
                if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
                if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
            }
        }
        return d  // degenerate fallback: posterior mean of Gamma(shape,1)/shape-ish
    }
}
