package com.reelbreaker.blocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelbreaker.app.ReelBreakerApp
import com.reelbreaker.core.Events
import com.reelbreaker.core.KnownTargets
import com.reelbreaker.core.SessionTracker
import com.reelbreaker.core.SwipeDetector
import com.reelbreaker.data.Store
import com.reelbreaker.engine.Intervention
import com.reelbreaker.engine.InterventionMode

/**
 * The heart of ReelBreaker.
 *
 * An AccessibilityService is the only *native, OS-level* way to observe and
 * intercept another app's UI without root — the same mechanism that talkback
 * uses. We subscribe to window+scroll events, identify real reel feeds by
 * (package, resource-id) fingerprints, count discrete swipe gestures, run
 * them through the AddictionEngine, and render friction overlays on top.
 *
 * Blocking strategy (in order of intrusiveness):
 *   1. Full-screen friction overlay above the feed (cooldown / checkpoint).
 *   2. Hard-block mode: overlay + performGlobalAction(GLOBAL_ACTION_HOME),
 *      i.e. the phone "escapes" the feed for you — the ultimate response
 *      cost in habit-loops terms (make the routine physically effortful).
 */
class ReelAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ReelAccessibilityService? = null
            private set
    }

    private lateinit var handler: Handler
    private lateinit var detector: SwipeDetector
    /** Public so the dashboard can render the live session without IPC. */
    val sessions = SessionTracker()

    // currently identified feed state
    private var feedPkg: String? = null
    private var feedNodeId: Long = -1L
    private var lastWindowPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        handler = Handler(Looper.getMainLooper())

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        Store.get(applicationContext).blockerActive = true

        detector = SwipeDetector { kind -> onReelSwipe(kind) }
        detector.setHandler(handler)

        sessions.onSession.add { swipes, dur ->
            Store.get(applicationContext).logSession(swipes, dur)
            ReelBreakerApp.engine.onSessionEnd()
            detector.reset()
            feedPkg = null
        }
        handler.postDelayed(idleTicker, 5_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (event.action == AccessibilityEvent.TYPE_VIEW_SCROLLED) onScrolled(event)
        }
    }

    private fun onWindowChanged(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == lastWindowPkg) return
        lastWindowPkg = pkg
        // Leaving a targeted feed => finalize session bookkeeping shortly after
        if (feedPkg != null && pkg != feedPkg) {
            handler.postDelayed({ sessions.tick() }, 3_000)
            feedPkg = null
        }
    }

    /** Confirm we're inside a known reel feed, then feed deltas to the detector. */
    private fun onScrolled(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val target = KnownTargets.forPackage(pkg) ?: return

        Store.get(applicationContext).enabledApps().let { enabled ->
            if (!enabled.contains(pkg)) return
        }

        val root: AccessibilityNodeInfo? = rootInActiveWindow
        val feedNode = findFeedNode(root, target.feedViewIds)
        if (feedNode == null) {
            // scrolled but not inside the feed container (e.g. comments list) — ignore
            root?.recycle()
            return
        }

        feedPkg = pkg
        feedNodeId = feedNode.viewIdResourceName?.hashCode()?.toLong() ?: -1L

        val b = android.graphics.Rect(); feedNode.getBoundsInScreen(b); val h = b.height().coerceAtLeast(1)
        val delta = Events.scrollDeltaY(e)
        val absY = Events.absScrollY(e, h)
        detector.onScroll(delta, absY)
        root?.recycle()
    }

    /** BFS for a node whose viewIdResourceName contains one of the fingerprints. */
    private fun findFeedNode(root: AccessibilityNodeInfo?, ids: List<String>): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 80) {
            val n = queue.removeFirst()
            val vid = n.viewIdResourceName
            if (vid != null && ids.any { vid.contains(it, ignoreCase = true) }) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ---- a committed swipe arrived from the detector ----
    private fun onReelSwipe(kind: SwipeDetector.SwipeKind) {
        val pkg = feedPkg ?: return
        val ctx = applicationContext

        val store = Store.get(ctx)
        store.addReel(pkg)
        // keep live engine aligned with persisted daily total
        ReelBreakerApp.engine.setTodayTotal(store.todayTotal())

        sessions.onSwipe()
        val decision = ReelBreakerApp.engine.onSwipe()
        val iv = decision.intervention

        when (iv) {
            is Intervention.Cooldown -> {
                Store.get(ctx).addCooldown(iv.seconds)
                showOverlay(OverlaySpec.Cooldown(iv.seconds, iv.reason), decision.forceExit)
            }
            is Intervention.Checkpoint ->
                showOverlay(OverlaySpec.Checkpoint(iv.reason), decision.forceExit)
            is Intervention.Block -> {
                Store.get(ctx).addBlockedAttempt()
                showOverlay(OverlaySpec.Block(iv.reason), forceExit = true)
            }
            Intervention.Awareness, null -> {
                if (decision.forceExit) sendHome()
            }
        }
    }

    private fun showOverlay(spec: OverlaySpec, forceExit: Boolean) {
        val i = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockOverlayActivity.EXTRA_KIND, spec.kind)
            putExtra(BlockOverlayActivity.EXTRA_SECONDS, spec.seconds)
            putExtra(BlockOverlayActivity.EXTRA_REASON, spec.reason)
            putExtra(BlockOverlayActivity.EXTRA_FORCE_EXIT, forceExit)
        }
        startActivity(i)
        // Haptic cue: one crisp vibration marks the urge (classical conditioning
        // of a "stop signal", akin to rubber-band aversion therapy cues).
        try {
            val vm = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            vm?.vibrate(longArrayOf(0, 40), -1)
        } catch (_: Exception) {}
    }

    private fun sendHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private val idleTicker = object : Runnable {
        override fun run() {
            sessions.tick()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        Store.get(applicationContext).blockerActive = false
        if (this::handler.isInitialized) handler.removeCallbacks(idleTicker)
        super.onDestroy()
    }

    data class OverlaySpec(val kind: Int, val seconds: Int, val reason: String) {
        companion object {
            const val KIND_COOLDOWN = 0
            const val KIND_CHECKPOINT = 1
            const val KIND_BLOCK = 2
            fun Cooldown(s: Int, r: String) = OverlaySpec(KIND_COOLDOWN, s, r)
            fun Checkpoint(r: String) = OverlaySpec(KIND_CHECKPOINT, 0, r)
            fun Block(r: String) = OverlaySpec(KIND_BLOCK, 0, r)
        }
    }
}
