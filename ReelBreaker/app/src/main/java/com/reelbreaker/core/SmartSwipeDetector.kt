package com.reelbreaker.core

import android.graphics.Rect
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * SmartSwipeDetector — precision gesture pipeline (Phase 2).
 * ==========================================================
 * Problems it solves:
 *
 *  A) FALSE POSITIVES. Naive TYPE_VIEW_SCROLLED counting fires for comment
 *     sheets, profile grids and settings lists. We require:
 *     1. The scrolled node's bounds cover >= 80% of screen HEIGHT and >= 90%
 *        of WIDTH => it is the full-bleed reel viewport, not a sub-list.
 *     2. No active bottom-sheet node in the window hierarchy (comments sheet,
 *        share sheet). Bottom sheets are detected as scrollable nodes whose
 *        top edge sits below 25% of the screen while covering < 80% height.
 *
 *  B) MOMENTUM COALESCING. One physical thumb flick emits dozens of scroll
 *     events during fling decay. State machine per gesture:
 *     IDLE -> TRACKING (events closer than BURST_GAP_MS join one burst)
 *          -> SETTLING (quiet period SETTLE_MS) -> COMMITTED (1 reel).
 *     A hard cooldown of PAGE_SNAP_COOLDOWN_MS after each commit guarantees
 *     1 flick = 1 count even for pathological event storms.
 *
 *  C) KINEMATIC STATE CLASSIFICATION. We keep a rolling window of the last
 *     15 Inter-Swipe Intervals (ISI, ms) and compute:
 *         mu  = mean(ISI)                — swipe cadence
 *         sd  = stddev(ISI)              — cadence volatility
 *         CV  = sd / mu                  — coefficient of variation
 *     Real-time behavioural states (validated against problematic-media-use
 *     literature, LaRose et al.; dopamine-hunting "foraging" vs. dissociative
 *     "trance" consumption are distinct failure modes needing different
 *     interventions):
 *         FORAGING_STATE: >= 6 consecutive swipes with mu < 2500 ms
 *                         (rapid rejection / slot-machine lever pulling).
 *         TRANCE_STATE:   session > 5 min AND CV < 0.35
 *                         (metronomic autopilot — the "zombie scroll").
 */
class SmartSwipeDetector(private val listener: (SwipeKind) -> Unit) {

    enum class SwipeKind { NEXT, PREVIOUS }

    /** Kinematic classification of the user's current scrolling mode. */
    enum class BrainState { ATTENDED, FORAGING_STATE, TRANCE_STATE }

    data class Kinematics(
        val sampleCount: Int,
        val meanIsiMs: Double,
        val sdIsiMs: Double,
        val cv: Double,
        val fastStreak: Int,
        val state: BrainState,
    )

    companion object {
        const val BURST_GAP_MS = 250L            // max gap between events of one gesture
        const val SETTLE_MS = 420L               // quiet period before a gesture commits
        const val PAGE_SNAP_COOLDOWN_MS = 400L   // debounce after a committed page-snap
        const val MIN_SWIPE_PX = 80              // ignore jitter
        const val MAX_REEL_INTERVAL_MS = 90_000L // idle beyond this => new session

        const val VIEWPORT_MIN_HEIGHT_RATIO = 0.80f
        const val VIEWPORT_MIN_WIDTH_RATIO = 0.90f

        const val ISI_WINDOW = 15
        const val FORAGING_MEAN_MS = 2_500.0
        const val FORAGING_STREAK = 6
        const val TRANCE_MIN_SESSION_MS = 8 * 60_000L
        const val TRANCE_MAX_CV = 0.35
    }

    // ---- gesture state machine ----
    private var lastEventAt = 0L
    private var gestureStartY = -1
    private var gestureEndY = -1
    private var gestureDirection = 0
    private var pendingCommit: Runnable? = null
    private var lastCommitAt = 0L
    private var handler: Handler? = null

    // ---- kinematics ----
    private val isi = ArrayDeque<Long>(ISI_WINDOW)
    private var lastCommittedAt = 0L
    private var fastStreak = 0
    var sessionStart = 0L
        private set

    fun setHandler(h: Handler) { handler = h }

    /**
     * Viewport verification. Returns true only when [node] is a full-screen
     * vertical pager-like container AND no bottom-sheet overlay is active.
     */
    fun viewportVerified(node: AccessibilityNodeInfo?, screenH: Int, screenW: Int): Boolean {
        node ?: return false
        val b = Rect()
        node.getBoundsInScreen(b)
        val hRatio = b.height().toFloat() / screenH.coerceAtLeast(1)
        val wRatio = b.width().toFloat() / screenW.coerceAtLeast(1)
        if (hRatio < VIEWPORT_MIN_HEIGHT_RATIO || wRatio < VIEWPORT_MIN_WIDTH_RATIO) return false
        return !commentSheetActive(screenH)
    }

    /**
     * Heuristic: a scrollable node anchored in the lower part of the screen
     * that does NOT fill the viewport is a comments / share bottom sheet.
     * While one is present we suppress all counting.
     */
    private fun commentSheetActive(screenH: Int): Boolean {
        val root = ReelProbe.root?.invoke() ?: return false
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 120) {
            val (n, _) = queue.removeFirst()
            val b = Rect(); n.getBoundsInScreen(b)
            val hRatio = b.height().toFloat() / screenH.coerceAtLeast(1)
            val topRatio = b.top.toFloat() / screenH.coerceAtLeast(1)
            val isSheet = n.isScrollable &&
                    hRatio in 0.25f..0.79f &&   // partial-height panel
                    topRatio > 0.20f &&          // anchored lower half-ish
                    b.width() > screenH * 0.5f   // wide panel, not a side drawer
            if (isSheet) return true
            if (guard % 4 == 0 && n.childCount == 0) { /* leaf */ }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to 1) }
        }
        return false
    }

    /** Called for every TYPE_VIEW_SCROLLED on a viewport-verified feed node. */
    fun onScroll(deltaY: Int, absoluteY: Int, ts: Long = System.currentTimeMillis()) {
        val h = handler ?: return
        if (deltaY == 0) return
        if (ts - lastCommitAt < PAGE_SNAP_COOLDOWN_MS) return   // post-commit debounce

        val gap = ts - lastEventAt
        if (gap > BURST_GAP_MS || gestureStartY < 0) {
            gestureStartY = absoluteY - deltaY
            gestureDirection = 0
        }
        gestureEndY = absoluteY
        gestureDirection = if (deltaY > 0) 1 else if (deltaY < 0) -1 else gestureDirection
        lastEventAt = ts

        pendingCommit?.let { h.removeCallbacks(it) }
        val r = Runnable { commit(ts) }
        pendingCommit = r
        h.postDelayed(r, SETTLE_MS)
    }

    private fun commit(now: Long) {
        val distance = abs(gestureEndY - gestureStartY)
        val dir = gestureDirection
        gestureStartY = -1
        gestureDirection = 0
        if (distance < MIN_SWIPE_PX || dir == 0) return

        lastCommitAt = now
        if (lastCommittedAt == 0L) {
            sessionStart = now
        } else {
            val dt = now - lastCommittedAt
            if (dt in 1..MAX_REEL_INTERVAL_MS) pushIsi(dt)
            if (now - sessionStart > MAX_REEL_INTERVAL_MS) {
                sessionStart = now                       // fresh session after long dwell
                isi.clear(); fastStreak = 0
            }
        }
        lastCommittedAt = now
        listener(if (dir > 0) SwipeKind.NEXT else SwipeKind.PREVIOUS)
    }

    private fun pushIsi(dt: Long) {
        isi.addLast(dt)
        if (isi.size > ISI_WINDOW) isi.removeFirst()
        fastStreak = if (dt < FORAGING_MEAN_MS.toInt() * 2) {
            // streak counts swipes whose *running* mean stays under threshold
            if (kinematics().meanIsiMs < FORAGING_MEAN_MS) fastStreak + 1 else 1
        } else 0
    }

    /**
     * Cheap real-time check used by the pre-swipe haptic cue: true when the
     * running mean ISI has dropped under the foraging threshold with enough
     * samples to be meaningful.
     */
    fun isForagingZone(): Boolean {
        if (isi.size < 3) return false
        return isi.average() < FORAGING_MEAN_MS
    }

    /** Rolling ISI statistics + live brain-state classification. */
    fun kinematics(): Kinematics {
        if (isi.isEmpty()) return Kinematics(0, 0.0, 0.0, 0.0, 0, BrainState.ATTENDED)
        val arr = isi.toList()
        val mu = arr.average()
        val variance = if (arr.size > 1) arr.sumOf { (it - mu) * (it - mu) } / (arr.size - 1) else 0.0
        val sd = kotlin.math.sqrt(variance)
        val cv = if (mu > 0) sd / mu else 0.0
        val now = System.currentTimeMillis()
        val sessionMs = if (lastCommittedAt > 0) now - sessionStart else 0L

        val state = when {
            fastStreak >= FORAGING_STREAK && mu < FORAGING_MEAN_MS -> BrainState.FORAGING_STATE
            sessionMs > TRANCE_MIN_SESSION_MS && arr.size >= 5 && cv < TRANCE_MAX_CV -> BrainState.TRANCE_STATE
            else -> BrainState.ATTENDED
        }
        return Kinematics(arr.size, mu, sd, cv, fastStreak, state)
    }

    fun reset() {
        pendingCommit?.let { handler?.removeCallbacks(it) }
        pendingCommit = null
        gestureStartY = -1
        gestureDirection = 0
    }

    fun resetAll() {
        reset()
        isi.clear()
        fastStreak = 0
        lastCommittedAt = 0L
        sessionStart = 0L
    }
}

/** Tiny indirection so the detector can peek at the live window without
 *  depending on the service class (keeps it unit-testable). */
object ReelProbe {
    var root: (() -> AccessibilityNodeInfo?)? = null
}
