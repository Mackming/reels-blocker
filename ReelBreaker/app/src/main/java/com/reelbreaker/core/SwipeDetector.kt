package com.reelbreaker.core

import android.view.accessibility.AccessibilityEvent

/**
 * Swipe detection: a "reel" is counted when the user performs one discrete
 * vertical fling/scroll gesture inside a detected feed window.
 *
 * Why not just count TYPE_VIEW_SCROLLED events? Because a single fling emits
 * dozens of them (momentum decay). Naive counting would report 30-60 reels
 * per minute of real scrolling. We instead use a dwell/coalescing model:
 *
 *  - A scroll burst (events closer together than BURST_GAP_MS) is ONE gesture.
 *  - The gesture is committed as a counted reel once scrolling settles
 *    (no event for SETTLE_MS) AND the total displacement exceeded MIN_SWIPE_PX
 *    OR the velocity was fling-like.
 *  - Direction filter: vertical feeds only count downward swipes (next reel);
 *    upward swipes (previous reel) also count since both consume attention.
 */
class SwipeDetector(private val listener: (SwipeKind) -> Unit) {

    enum class SwipeKind { NEXT, PREVIOUS }

    companion object {
        const val BURST_GAP_MS = 250L      // max gap between events in one gesture
        const val SETTLE_MS = 420L         // quiet period after which a gesture commits
        const val MIN_SWIPE_PX = 80        // ignore tiny jitters
        const val MAX_REEL_INTERVAL_MS = 90_000L // beyond this idle => new session
    }

    private var lastEventAt = 0L
    private var gestureStartY = -1
    private var gestureEndY = -1
    private var gestureDirection = 0
    private var pendingCommit: Runnable? = null
    private var handler: android.os.Handler? = null

    fun setHandler(h: android.os.Handler) { handler = h }

    /** Called for every TYPE_VIEW_SCROLLED on a confirmed feed node. */
    fun onScroll(deltaY: Int, absoluteY: Int, ts: Long = System.currentTimeMillis()) {
        val h = handler ?: return
        if (deltaY == 0) return

        val gap = ts - lastEventAt
        if (gap > BURST_GAP_MS || gestureStartY < 0) {
            // start of a new gesture
            gestureStartY = absoluteY - deltaY
            gestureDirection = 0
        }
        gestureEndY = absoluteY
        gestureDirection = if (deltaY > 0) 1 else if (deltaY < 0) -1 else gestureDirection
        lastEventAt = ts

        // (Re)schedule commit when motion settles
        pendingCommit?.let { h.removeCallbacks(it) }
        val r = Runnable { commit() }
        pendingCommit = r
        h.postDelayed(r, SETTLE_MS)
    }

    private fun commit() {
        val distance = kotlin.math.abs(gestureEndY - gestureStartY)
        val dir = gestureDirection
        gestureStartY = -1
        gestureDirection = 0
        if (distance < MIN_SWIPE_PX || dir == 0) return
        listener(if (dir > 0) SwipeKind.NEXT else SwipeKind.PREVIOUS)
    }

    fun reset() {
        pendingCommit?.let { handler?.removeCallbacks(it) }
        pendingCommit = null
        gestureStartY = -1
        gestureDirection = 0
    }
}

/**
 * Session tracker: groups swipes into viewing sessions. A session starts at
 * the first swipe and ends after [idleGapMs] without activity (default 90 s).
 * Sessions + inter-swipe latency are the raw material for the behavioural
 * engine (dopamine-ramp detection, loss-of-control estimation).
 */
class SessionTracker(private val idleGapMs: Long = SwipeDetector.MAX_REEL_INTERVAL_MS) {

    data class Snapshot(
        val sessionActive: Boolean,
        val sessionSwipes: Int,
        val sessionElapsedSec: Long,
        val swipesPerMinute: Float,
        val totalToday: Int,
    )

    private var sessionStart = 0L
    private var lastSwipe = 0L
    private var sessionCount = 0
    var totalToday: Int = 0; private set

    val onSession: MutableList<(endedAfterSwipes: Int, durationSec: Long) -> Unit> = mutableListOf()

    fun onSwipe(ts: Long = System.currentTimeMillis()) {
        if (sessionCount == 0 || ts - lastSwipe > idleGapMs) {
            endSession(ts)          // close previous if any
            sessionStart = ts
        }
        sessionCount++
        lastSwipe = ts
        totalToday++
    }

    /** Call periodically (e.g. every 5 s) to expire idle sessions. */
    fun tick(ts: Long = System.currentTimeMillis()) {
        if (sessionCount > 0 && ts - lastSwipe > idleGapMs) endSession(ts)
    }

    private fun endSession(ts: Long) {
        if (sessionCount > 0) {
            val dur = (ts - sessionStart) / 1000
            onSession.forEach { it(sessionCount, dur) }
        }
        sessionCount = 0
    }

    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot {
        val active = sessionCount > 0 && now - lastSwipe <= idleGapMs
        val elapsed = if (active) (now - sessionStart) / 1000 else 0
        val spm = if (elapsed > 5) sessionCount * 60f / elapsed else 0f
        return Snapshot(active, sessionCount, elapsed, spm, totalToday)
    }
}

/** Utility to read the dominant scroll delta out of an accessibility event. */
object Events {
    fun scrollDeltaY(e: AccessibilityEvent): Int {
        // AccessibilityEvent has no itemIndex API; use the pixel scroll delta,
        // falling back to a conservative full-page estimate for page-snap feeds.
        val sc = e.scrollY     // pixel delta when provided by the scrolling view
        return when {
            sc != 0 -> sc
            else -> 0
        }
    }

    fun absScrollY(e: AccessibilityEvent, nodeHeightPx: Int): Int {
        // When the view reports no pixel delta, treat the event as one page snap.
        return if (e.scrollY != 0) e.scrollY else nodeHeightPx
    }
}
