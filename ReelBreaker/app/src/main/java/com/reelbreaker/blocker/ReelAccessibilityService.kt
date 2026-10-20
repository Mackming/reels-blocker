package com.reelbreaker.blocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelbreaker.R
import com.reelbreaker.app.ReelBreakerApp
import com.reelbreaker.core.CircadianContextProvider
import com.reelbreaker.core.Events
import com.reelbreaker.core.KnownTargets
import com.reelbreaker.core.ReelProbe
import com.reelbreaker.core.SessionTracker
import com.reelbreaker.core.SmartSwipeDetector
import com.reelbreaker.data.Store
import com.reelbreaker.engine.BanditArm
import com.reelbreaker.engine.Intervention
import com.reelbreaker.engine.InterventionMode
import com.reelbreaker.overlay.InterventionOverlayController
import com.reelbreaker.overlay.FaceDownUrgeSurfController
import com.reelbreaker.overlay.SighPacerController
import com.reelbreaker.overlay.SensoryDampener
import com.reelbreaker.telemetry.InterventionEvent
import com.reelbreaker.telemetry.SwipeEvent
import com.reelbreaker.telemetry.TelemetryBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The heart of ReelBreaker — V3 neuro-adaptive intervention engine.
 *
 * Pipeline per scroll event:
 *   TYPE_VIEW_SCROLLED
 *     -> package fingerprint (KnownTargets + user-enabled apps)
 *     -> SmartSwipeDetector.viewportVerified()  (>=80% screen height, no
 *        comment bottom-sheet active in the window hierarchy)
 *     -> momentum coalescing state machine      (1 flick = 1 reel)
 *     -> rolling ISI kinematics                 (mu, sigma, CV; FORAGING /
 *        TRANCE classification)
 *     -> AddictionEngine.decide()               (hazard-rate Tier 2,
 *        escalating Tier 3, circadian-tightened caps)
 *     -> TYPE_ACCESSIBILITY_OVERLAY friction    (V3-1: zero BAL latency)
 *     -> audio-focus severing                   (V3-2)
 *     -> Room telemetry publish
 */
class ReelAccessibilityService : AccessibilityService(), InterventionOverlayController.Host,
    FaceDownUrgeSurfController.Host {

    companion object {
        var instance: ReelAccessibilityService? = null
            private set
        const val SETTINGS_PKG = "com.android.settings"
        const val PRECOMMIT_SECONDS = 20L
        /** V4-2: only ask the doorway question after this much time away. */
        const val DOORWAY_AWAY_MS = 15 * 60_000L
        /** V4-5 bandit reward window: exit within 60 s … */
        const val BANDIT_EXIT_WINDOW_MS = 60_000L
        /** … and no target app for 15 minutes. */
        const val BANDIT_AWAY_REQUIRED_MS = 15 * 60_000L
        /** V4-1 natural-gap: quiet period & max queue wait. */
        const val GAP_QUIET_MS = 1_500L
        const val GAP_MAX_WAIT_MS = 8_000L
    }

    private lateinit var handler: Handler
    private lateinit var detector: SmartSwipeDetector
    /** Public so the dashboard can render the live session without IPC. */
    val sessions = SessionTracker()
    private var dampener: SensoryDampener? = null
    private lateinit var audio: AudioInterventionManager
    private lateinit var overlayCtl: InterventionOverlayController
    private lateinit var circadian: CircadianContextProvider
    private lateinit var surfCtl: FaceDownUrgeSurfController
    private lateinit var sighCtl: SighPacerController

    // currently identified feed state
    private var feedPkg: String? = null
    private var lastWindowPkg: String? = null

    // ---- V3-3 dwell watchdog ----
    private var lastReelActivityAt = 0L
    private var watchdogRunning = false

    // ---- V3-6 anti-tamper guard ----
    private var precommitShown = false
    private var precommitView: android.view.View? = null

    // ---- V4-2 doorway checkpoint ----
    private var lastTargetExitAt = System.currentTimeMillis()
    private var doorwayPending = false
    private var doorwayView: android.view.View? = null
    private var interventionThisSession = false

    // ---- V4-1 natural-gap scheduler state ----
    private var lastScrollDeltaAt = 0L
    private var queuedArm: BanditArm? = null
    private var queuedReason = ""
    private var queuedStrict = false

    // ---- V4-5 bandit reward monitor ----
    private var banditServedAt = 0L
    private var banditAwaySince = 0L

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun dayKey() = dayFmt.format(Date())

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
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        Store.get(applicationContext).blockerActive = true
        TelemetryBus.init(applicationContext)

        detector = SmartSwipeDetector { kind -> onReelSwipe(kind) }
        detector.setHandler(handler)
        ReelProbe.root = { runCatching { rootInActiveWindow }.getOrNull() }

        // wire live kinematics into the behavioural engine
        ReelBreakerApp.engine.kinematicsProvider = { detector.kinematics() }

        dampener = SensoryDampener(applicationContext)
        audio = AudioInterventionManager(applicationContext)
        overlayCtl = InterventionOverlayController(
            applicationContext,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            host = this,
        )
        surfCtl = FaceDownUrgeSurfController(
            applicationContext,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            host = this,
        )
        sighCtl = SighPacerController(
            applicationContext,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            // vibrate + goHome only; the pacer never calls onSurfFinished.
            host = this,
        )
        circadian = CircadianContextProvider(applicationContext)
        applySleepWindowFromStore()

        // V4-5: replay persisted bandit trials so learning survives restarts.
        kotlinx.coroutines.MainScope().launch {
            runCatching { ReelBreakerApp.bandit.restore(TelemetryBus.loadBanditTrials()) }
        }

        sessions.onSession.add { swipes, dur ->
            Store.get(applicationContext).logSession(swipes, dur)
            ReelBreakerApp.engine.onSessionEnd()
            detector.resetAll()
            feedPkg = null
            dampener?.fadeTo(0f)
            audio.release()                       // restore feed audio at session end
            ReelBreakerApp.engine.circadianVulnerable = false
            dampener?.circadianBaseline = false
            dampener?.setWarmRedShift(false)
            cancelQueuedSpeedbump()
        }
        handler.postDelayed(idleTicker, 5_000)

        // V4-5: sensor-driven overlay controllers report outcomes through here.
        com.reelbreaker.overlay.ReelBreakerAppHooks.sink = { outcome, armName ->
            audio.release()
            ReelBreakerApp.bandit.recordOutcome(success = outcome == "SURFED")
            Store.get(applicationContext).recordOverride(outcome)
            TelemetryBus.publishIntervention(
                InterventionEvent(
                    timestampMs = System.currentTimeMillis(),
                    dayKey = dayKey(),
                    kind = "SPEEDBUMP",
                    outcome = outcome,
                    affectLabel = armName,
                    cooldownSeconds = 0,
                )
            )
        }
    }

    /** V4-1 natural-gap poller: renders a queued Tier-2 at a decision boundary. */
    private val gapChecker = object : Runnable {
        override fun run() {
            val arm = queuedArm ?: return
            val now = System.currentTimeMillis()
            val waited = now - ReelBreakerApp.engine.speedbumpQueuedAt
            val quiet = now - lastScrollDeltaAt
            if (feedPkg == null || overlayCtl.isShowing || surfCtl.isShowing || sighCtl.isShowing) {
                cancelQueuedSpeedbump(); return
            }
            if ((quiet >= GAP_QUIET_MS && lastScrollDeltaAt > 0) || waited >= GAP_MAX_WAIT_MS) {
                serveQueuedSpeedbump()
            } else {
                handler.postDelayed(this, 300)
            }
        }
    }

    private fun cancelQueuedSpeedbump() {
        queuedArm = null
        ReelBreakerApp.engine.clearSpeedbumpQueue()
        handler.removeCallbacks(gapChecker)
    }

    /** V4-1: if the feed went quiet mid-swipe, snap the clock forward so the
     *  gap rule (quiet >= GAP_QUIET_MS after a transition) can be satisfied. */
    private fun onReelTransition() {
        lastScrollDeltaAt = System.currentTimeMillis() - GAP_QUIET_MS + 250L
    }

    private fun serveQueuedSpeedbump() {
        val arm = queuedArm ?: return
        cancelQueuedSpeedbump()
        audio.hardSever()                              // V3-2/V4-3: silence the feed
        banditServedAt = System.currentTimeMillis()
        when (arm) {
            BanditArm.ARM_FACE_DOWN_SURF -> surfCtl.show(arm.name)
            BanditArm.ARM_PHYSIOLOGICAL_SIGH -> sighCtl.show {
                // pacer completed or user tapped done: log an OVERRIDE (they stayed engaged)
                onSpeedbumpFinished("OVERRIDE", arm.name)
            }
            BanditArm.ARM_MOTOR_GRID -> overlayCtl.showSpeedbump(queuedReason, strictTask = true)
        }
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "SPEEDBUMP",
                outcome = "SERVED",
                affectLabel = arm.name,
                cooldownSeconds = 0,
            )
        )
    }

    private fun applySleepWindowFromStore() {
        runCatching {
            val s = Store.get(applicationContext)
            circadian.sleepStart = java.time.LocalTime.parse(s.sleepWindowStart)
            circadian.sleepEnd = java.time.LocalTime.parse(s.sleepWindowEnd)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            else -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    onScrolled(event)
                }
            }
        }
        // ---- V3-6 anti-tamper guard: settings self-defense ----
        maybeGuardSettings(event)
    }

    private fun onWindowChanged(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg == lastWindowPkg) return
        val wasTarget = KnownTargets.forPackage(lastWindowPkg) != null
        lastWindowPkg = pkg

        // ---- V4-1: leaving the target app auto-dismisses every overlay ----
        if (KnownTargets.forPackage(pkg) == null && pkg != packageName) {
            if (overlayCtl.isShowing || surfCtl.isShowing || sighCtl.isShowing || doorwayView != null) {
                cancelQueuedSpeedbump()
                overlayCtl.detach(); surfCtl.detach(); sighCtl.detach(); removeDoorway()
                audio.release()
            }
        }
        // ---- V4-2: remember when we left a target app (doorway re-arm + agency) ----
        if (wasTarget && KnownTargets.forPackage(pkg) == null) {
            lastTargetExitAt = System.currentTimeMillis()
            maybeRecordUnassistedStop()
            doorwayPending = true
        }
        if (feedPkg != null && pkg != feedPkg) {
            handler.postDelayed({ sessions.tick() }, 3_000)
            feedPkg = null
            dampener?.fadeTo(0f)
            stopWatchdog()
            audio.release()
        }
        // entering our own speedbump/block UI: ignore
        if (pkg == packageName) return
        // ---- V4-5 bandit reward monitor: track continuous time away ----
        if (KnownTargets.forPackage(pkg) != null) {
            banditAwaySince = 0L
            ReelBreakerApp.bandit.recordOutcome(success = false)   // reopened -> failure
        } else {
            if (banditServedAt > 0 && banditAwaySince == 0L &&
                System.currentTimeMillis() - banditServedAt <= BANDIT_EXIT_WINDOW_MS
            ) {
                banditAwaySince = System.currentTimeMillis()       // exited in-window
            }
        }
        // ---- V3-4: session start => one-shot ambient light sample ----
        if (KnownTargets.forPackage(pkg) != null &&
            Store.get(applicationContext).enabledApps().contains(pkg)
        ) {
            circadian.sampleOnce()
            runCatching {
                val st = circadian.evaluate()
                ReelBreakerApp.engine.circadianVulnerable = st.vulnerable
                dampener?.circadianBaseline = st.vulnerable
                dampener?.setWarmRedShift(st.vulnerable)
            }
            startWatchdog()
            // ---- V4-2 doorway checkpoint: fresh entry after >= 15 min away ----
            if (doorwayPending &&
                System.currentTimeMillis() - lastTargetExitAt >= DOORWAY_AWAY_MS
            ) {
                doorwayPending = false
                showDoorway()
            }
        }
    }

    /** V4-2/V4-7: user quit before any Tier-2/3 fired => self-control win. */
    private fun maybeRecordUnassistedStop() {
        if (interventionThisSession) return
        val s = sessions.snapshot()
        if (!s.sessionActive || s.sessionSwipes < 3) return   // drive-by, not a scroll session
        val store = Store.get(applicationContext)
        val mins = s.sessionElapsedSec / 60
        val withinIntention = when (ReelBreakerApp.engine.doorwayMinutes) {
            0 -> true                                   // declined prompt: no quota to breach
            else -> mins <= ReelBreakerApp.engine.doorwayMinutes + 1
        }
        if (!withinIntention && !store.mode.let { it == InterventionMode.GENTLE }) {
            // overran the chosen window but still quit unaided — count it,
            // abstinence-violation research says partial wins matter
        }
        store.recordUnassistedStop(mins)
        store.doorwayIntention = "used"
        vibrateDoubleTap()
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "AGENCY",
                outcome = "UNASSISTED_STOP",
                affectLabel = "${mins}m",
                cooldownSeconds = 0,
            )
        )
    }

    private fun vibrateDoubleTap() {
        try {
            val vm = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            vm?.vibrate(longArrayOf(0, 30, 90, 30), -1)   // crisp double-tap confirmation
        } catch (_: Exception) {}
    }

    /** Fixed-length haptic pulse used by the sensor-driven overlay controllers. */
    private fun vibrateLen(ms: Long) {
        try {
            val vm = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            vm?.vibrate(ms)
        } catch (_: Exception) {}
    }

    // ================= V4-2 — Doorway checkpoint bottom sheet =================

    @SuppressLint("ClickableViewAccessibility")
    private fun showDoorway() {
        if (doorwayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val col = android.widget.LinearLayout(applicationContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
            setBackgroundColor(0xF6151520.toInt())
        }
        val q = android.widget.TextView(applicationContext).apply {
            text = getString(R.string.doorway_prompt)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        col.addView(q)
        val row = android.widget.LinearLayout(applicationContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        listOf("2" to R.string.doorway_2, "5" to R.string.doorway_5, "10" to R.string.doorway_10)
            .forEach { (mins, res) ->
                val b = android.widget.Button(applicationContext).apply {
                    text = getString(res); isAllCaps = false
                    setOnClickListener { chooseDoorway(mins.toInt()) }
                }
                row.addView(b, android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        col.addView(row)
        val pass = android.widget.Button(applicationContext).apply {
            text = getString(R.string.doorway_just_scrolling); isAllCaps = false
            setOnClickListener { chooseDoorway(0) }
        }
        col.addView(pass)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try { wm.addView(col, p); doorwayView = col } catch (_: Exception) { doorwayView = null }
    }

    private fun chooseDoorway(minutes: Int) {
        removeDoorway()
        ReelBreakerApp.engine.doorwayMinutes = minutes
        Store.get(applicationContext).doorwayIntention = if (minutes == 0) "none" else "${minutes}m"
        interventionThisSession = false
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "DOORWAY",
                outcome = if (minutes == 0) "DECLINED" else "COMMITTED",
                affectLabel = if (minutes == 0) null else "${minutes}min",
                cooldownSeconds = 0,
            )
        )
    }

    private fun removeDoorway() {
        doorwayView?.let { v ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        doorwayView = null
    }

    /** Confirm we're inside a real reel viewport, then feed deltas downstream. */
    private fun onScrolled(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val target = KnownTargets.forPackage(pkg) ?: return
        if (!Store.get(applicationContext).enabledApps().contains(pkg)) return

        val root = rootInActiveWindow ?: return
        val feedNode = findFeedNode(root, target.feedViewIds)
        if (feedNode == null) { root.recycle(); return }

        // ---- viewport verification (kills comment/feed false positives)
        val metrics = resources.displayMetrics
        if (!detector.viewportVerified(feedNode, metrics.heightPixels, metrics.widthPixels)) {
            root.recycle()
            return
        }

        feedPkg = pkg
        lastReelActivityAt = System.currentTimeMillis()
        val b = Rect(); feedNode.getBoundsInScreen(b); val h = b.height().coerceAtLeast(1)
        val delta = Events.scrollDeltaY(e)
        val absY = Events.absScrollY(e, h)
        detector.onScroll(delta, absY)
        if (delta != 0) lastScrollDeltaAt = System.currentTimeMillis()

        // ---- Pre-swipe haptic dissonance (Tier 1 companion cue) ----
        if (delta != 0 && detector.isForagingZone()) maybeHapticDissonance()

        // ---- V3-2/V4-3: foraging cadence or doorway overrun ducks audio ~70% ----
        val overDoorway = ReelBreakerApp.engine.doorwayMinutes > 0 &&
            sessions.snapshot().sessionElapsedSec >= ReelBreakerApp.engine.doorwayMinutes * 60L
        if (detector.isForagingZone() || overDoorway) {
            audio.duck()
            dampener?.setWarmRedShift(true)   // V4-3 late-stage warm dimming
        }

        root.recycle()
    }

    private var lastDissonanceAt = 0L
    private fun maybeHapticDissonance() {
        val now = System.currentTimeMillis()
        if (now - lastDissonanceAt < 2_500) return           // sparse, never annoying
        lastDissonanceAt = now
        try {
            val vm = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            // off-rhythm: randomized tick length so it can't be habituated to
            val ms = (18..45).random().toLong()
            vm?.vibrate(longArrayOf(0, ms), -1)
        } catch (_: Exception) {}
    }

    // ================= V3-3 — 1 Hz dwell-time watchdog =================

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        lastReelActivityAt = System.currentTimeMillis()
        handler.postDelayed(watchdogTick, 1_000)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        handler.removeCallbacks(watchdogTick)
    }

    private val watchdogTick = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return
            val pkg = feedPkg
            if (pkg == null || overlayCtl.isShowing || surfCtl.isShowing ||
                sighCtl.isShowing || precommitView != null
            ) {
                handler.postDelayed(this, 1_000); return
            }
            // ---- V4-3 dwell watchdog: passive zombie-looping => Tier 1 dampening.
            // The legacy full-screen "passive lock" intervention is replaced by
            // sub-threshold sensory devaluation (duck + warm dim + blur ramp).
            val dwell = System.currentTimeMillis() - lastReelActivityAt
            if (dwell > OverlaySpec.PASSIVE_LOCK_MS) {
                val root = rootInActiveWindow
                val target = KnownTargets.forPackage(pkg)
                val node = root?.let { r -> target?.let { t -> findFeedNode(r, t.feedViewIds) } }
                val m = resources.displayMetrics
                val stillFullBleed = node != null &&
                        detector.viewportVerified(node, m.heightPixels, m.widthPixels)
                if (stillFullBleed) onPassiveLock()
                lastReelActivityAt = System.currentTimeMillis() // rearm for next loop
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private var tier1BlurRamped = false

    private fun onPassiveLock() {
        ReelBreakerApp.engine.onPassiveLock()
        // V4-3 Tier 1 sensory dampening — not a block: duck the audio hard,
        // push the warm-red dim, and progressively blur the reel behind any
        // future overlay. Catches zombie looping / auto-scroll cheaply.
        audio.duck()
        dampener?.updateProgress(
            swipesInSession = sessions.snapshot().sessionSwipes + 4,
            threshold = effectiveSoftCap(),
            enabled = true,
        )
        dampener?.setWarmRedShift(true)
        if (!tier1BlurRamped) {
            tier1BlurRamped = true
            rampTier1HardwareBlur()
        }
        vibrateCue()
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "PASSIVE_LOCK",
                outcome = "DETECTED",
                affectLabel = null,
                cooldownSeconds = 0,
            )
        )
    }

    /** V4-3 hardware blur: FLAG_BLUR_BEHIND radius 2px -> 10px over ~6 s (API 31+). */
    private fun rampTier1HardwareBlur() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = doorwayView ?: return
        var px = 2
        val step = object : Runnable {
            override fun run() {
                if (doorwayView == null) return
                runCatching {
                    val lp = v.layoutParams as WindowManager.LayoutParams
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    lp.setBlurBehindRadius(px.coerceAtMost(10))
                    wm.updateViewLayout(v, lp)
                }
                px += 2
                if (px <= 10) handler.postDelayed(this, 1_200)
            }
        }
        handler.post(step)
    }

    private fun effectiveSoftCap(): Int {
        val store = Store.get(applicationContext)
        return if (ReelBreakerApp.engine.circadianVulnerable)
            maxOf(3, store.sessionSoftCap / 2) else store.sessionSoftCap
    }

    // ================= V3-6 — Settings self-defense =================

    private fun maybeGuardSettings(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg != SETTINGS_PKG) {
            if (precommitView != null) removePrecommit()   // left settings: release
            return
        }
        val store = Store.get(applicationContext)
        val engaged = store.mode == com.reelbreaker.engine.InterventionMode.STRICT ||
                store.mode == com.reelbreaker.engine.InterventionMode.BLOCK ||
                ReelBreakerApp.engine.breachCount() > 0
        if (!engaged || precommitShown) return

        // Is the user actually looking at OUR app's detail page?
        val root = rootInActiveWindow ?: return
        if (!treeContainsText(root, getString(R.string.app_name))) return
        showPrecommit()
    }

    private fun treeContainsText(node: AccessibilityNodeInfo?, needle: String): Boolean {
        node ?: return false
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(node to 0)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 200) {
            val (n, _) = queue.removeFirst()
            val t = "${n.text} ${n.contentDescription}"
            if (t.contains(needle, ignoreCase = true)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to 1) }
        }
        return false
    }

    private fun showPrecommit() {
        if (precommitView != null) return
        precommitShown = true
        audio.hardSever()                                  // silence during reflection
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val col = android.widget.LinearLayout(applicationContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 96, 64, 64)
            setBackgroundColor(0xFA0B0B12.toInt())
        }
        val title = android.widget.TextView(applicationContext).apply {
            text = getString(R.string.precommit_title)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        val body = android.widget.TextView(applicationContext).apply {
            text = getString(R.string.precommit_body)
            textSize = 15f
            setTextColor(0xFFC9C6D9.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        val countdown = android.widget.TextView(applicationContext).apply {
            textSize = 30f
            setTextColor(0xFF8FD3FF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        col.addView(title); col.addView(body); col.addView(countdown)
        col.setOnTouchListener { _, _ -> true }             // modal: blocks the toggle

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (android.os.Build.VERSION.SDK_INT >= 31)
                        WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= 31) setBlurBehindRadius(12)
        }
        try {
            wm.addView(col, p)
            precommitView = col
        } catch (_: Exception) { return }

        val endAt = System.currentTimeMillis() + PRECOMMIT_SECONDS * 1000L
        val tick = object : Runnable {
            override fun run() {
                val left = endAt - System.currentTimeMillis()
                if (left > 0 && precommitView != null) {
                    countdown.text = getString(R.string.precommit_remaining, (left / 1000 + 1).toInt())
                    handler.postDelayed(this, 250)
                } else {
                    removePrecommit()
                }
            }
        }
        handler.post(tick)
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "PRECOMMIT_GUARD",
                outcome = "REFLECTION_SHOWN",
                affectLabel = null,
                cooldownSeconds = PRECOMMIT_SECONDS.toInt(),
            )
        )
    }

    private fun removePrecommit() {
        precommitView?.let { v ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        precommitView = null
        audio.release()
        // cooldown expires naturally; re-arming requires leaving & returning to settings
    }

    // ---- BFS for a node whose viewIdResourceName contains one of the fingerprints. ----
    private fun findFeedNode(root: AccessibilityNodeInfo?, ids: List<String>): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 80) {
            val n = queue.removeFirst()
            val vid = n.viewIdResourceName
            if (vid != null && ids.any { vid.contains(it, ignoreCase = true) }) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // ---- a committed swipe arrived from the detector (1 flick = 1 count) ----
    private fun onReelSwipe(kind: SmartSwipeDetector.SwipeKind) {
        val pkg = feedPkg ?: return
        val ctx = applicationContext
        val store = Store.get(ctx)
        val kin = detector.kinematics()

        store.addReel(pkg)
        ReelBreakerApp.engine.setTodayTotal(store.todayTotal())
        sessions.onSwipe()
        lastReelActivityAt = System.currentTimeMillis()   // watchdog reset by real touch

        TelemetryBus.publishSwipe(
            SwipeEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                appPackage = pkg,
                isiMs = -1,
                meanIsiMs = kin.meanIsiMs,
                cv = kin.cv,
                brainState = kin.state.name,
            )
        )

        // ---- Tier 1: sub-threshold sensory dampening (non-blocking) ----
        dampener?.updateProgress(
            swipesInSession = sessions.snapshot().sessionSwipes,
            threshold = effectiveSoftCap(),
            enabled = store.mode != com.reelbreaker.engine.InterventionMode.OFF &&
                    store.mode != com.reelbreaker.engine.InterventionMode.GENTLE,
        )

        val decision = ReelBreakerApp.engine.onSwipe()
        val iv = decision.intervention
        if (decision.speedbump || iv != null) interventionThisSession = true

        when {
            // V4-1: Tier-2 no longer lands mid-swipe — it queues and the
            // natural-gap scheduler serves it after 1.5 s of zero scroll delta
            // (max 8 s wait), i.e. at a decision boundary between reels.
            decision.speedbump -> queueSpeedbump(kin, decision.arm)
            decision.triggerRepair -> showTriggerRepair()
            iv is Intervention.Cooldown -> {
                store.addCooldown(iv.seconds)
                showFriction(InterventionOverlayController.Kind.COOLDOWN, iv.seconds, iv.reason)
            }
            iv is Intervention.Checkpoint ->
                showFriction(InterventionOverlayController.Kind.CHECKPOINT, 0, iv.reason)
            iv is Intervention.Block -> {
                store.addBlockedAttempt()
                store.recordAssistedEnd()
                // Tier 3 terminal: kill switch FIRST, replacement reward after.
                showFriction(InterventionOverlayController.Kind.BLOCK, 0, iv.reason)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            else -> {
                if (decision.forceExit) {
                    store.recordAssistedEnd()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        }
    }

    // ================= V4-1 natural-gap queueing =================

    private fun queueSpeedbump(kin: SmartSwipeDetector.Kinematics, arm: BanditArm?) {
        queuedReason = when (kin.state) {
            SmartSwipeDetector.BrainState.FORAGING_STATE ->
                "Rapid-fire rejection detected (mean swipe interval ${"%.1f".format(kin.meanIsiMs / 1000)}s)."
            SmartSwipeDetector.BrainState.TRANCE_STATE ->
                "Autopilot detected: metronomic swiping (CV=${"%.2f".format(kin.cv)}) for >8 min."
            else -> "Random check-in — the machine pauses without warning now."
        }
        queuedArm = arm ?: BanditArm.ARM_MOTOR_GRID
        handler.removeCallbacks(gapChecker)
        handler.postDelayed(gapChecker, 300)
    }

    // ================= V4-6 Trigger Repair (compassionate escalation cap) ====

    /**
     * Breach >= 4 within the rolling hour: instead of a punitive 300 s wall we
     * honour the pre-commitment made in a cold state — a continuous 4-second
     * press-and-hold grants a 10-minute autonomy override and logs the event
     * for the evening review. Settings & emergency dialer are never touched.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showTriggerRepair() {
        audio.hardSever()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val col = android.widget.LinearLayout(applicationContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 72, 56, 56)
            setBackgroundColor(0xF60B0B12.toInt())
        }
        col.setOnTouchListener { _, _ -> true }
        val title = android.widget.TextView(applicationContext).apply {
            text = getString(R.string.repair_title); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
        }
        val body = android.widget.TextView(applicationContext).apply {
            text = getString(R.string.repair_body); textSize = 15f
            setTextColor(0xFFC9C6D9.toInt()); gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val hold = android.widget.Button(applicationContext).apply {
            text = getString(R.string.repair_hold); isAllCaps = false
        }
        val progress = android.widget.TextView(applicationContext).apply {
            textSize = 15f; setTextColor(0xFF8FD3FF.toInt()); gravity = Gravity.CENTER
        }
        col.addView(title); col.addView(body); col.addView(hold); col.addView(progress)

        var holdStart = 0L
        val holdTick = object : Runnable {
            override fun run() {
                if (holdStart == 0L) return
                val held = System.currentTimeMillis() - holdStart
                progress.text = "${(4_000 - held).coerceAtLeast(0) / 1000.0}s…"
                if (held >= 4_000L) {
                    grantOverrideFromRepair()
                } else {
                    hold.postDelayed(this, 100)
                }
            }
        }
        hold.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    holdStart = System.currentTimeMillis(); v.postDelayed(holdTick, 100)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (System.currentTimeMillis() - holdStart < 4_000L) {
                        holdStart = 0L; v.removeCallbacks(holdTick)
                        progress.text = getString(R.string.repair_keep_holding)
                    }
                }
            }
            true
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (android.os.Build.VERSION.SDK_INT >= 31)
                        WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 31) setBlurBehindRadius(10)
        }
        try { wm.addView(col, p) } catch (_: Exception) {}
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "TRIGGER_REPAIR",
                outcome = "OFFERED",
                affectLabel = null,
                cooldownSeconds = 0,
            )
        )
    }

    private fun grantOverrideFromRepair() {
        ReelBreakerApp.engine.grantOverride(minutes = 10)
        Store.get(applicationContext).addOverrideLog10m()
        overlayCtl.detach(); surfCtl.detach(); sighCtl.detach()
        audio.release()
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "TRIGGER_REPAIR",
                outcome = "OVERRIDE_GRANTED_10M",
                affectLabel = "evening-review",
                cooldownSeconds = 0,
            )
        )
    }

    private fun showFriction(kind: InterventionOverlayController.Kind, seconds: Int, reason: String) {
        if (kind != InterventionOverlayController.Kind.COOLDOWN || seconds <= 0) audio.hardSever()
        else audio.hardSever()                              // all Tier 3: full sever
        overlayCtl.showFriction(kind, seconds, reason)
    }

    /** Haptic cue: one crisp vibration marks the urge (stop-signal conditioning). */
    override fun vibrateCue() {
        try {
            val vm = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            vm?.vibrate(longArrayOf(0, 40), -1)
        } catch (_: Exception) {}
    }

    // ---------------- InterventionOverlayController.Host ----------------

    override fun onSpeedbumpFinished(outcome: String, affect: String?) {
        audio.release()                                    // consciously unlocked -> restore
        if (outcome == "DONE")                             // motor-grid win: exited within window
            ReelBreakerApp.bandit.recordOutcome(success = true)
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "SPEEDBUMP",
                outcome = outcome,
                affectLabel = affect,
                cooldownSeconds = 0,
            )
        )
        Store.get(applicationContext).recordOverride(outcome)
    }

    override fun onCooldownSurfed(seconds: Int) {
        audio.release()
        recordCooldownOutcome("SURFED", seconds)
    }

    override fun onBlockDismissed() { audio.release() }

    override fun goHome() {
        audio.release()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** V3-5 intent teleport: launch the pre-committed constructive app. */
    override fun launchReplacement(pkg: String) {
        audio.release()
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(i) }
        } else performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun isCircadianVulnerable(): Boolean =
        ReelBreakerApp.engine.circadianVulnerable

    override fun replacementPackage(): String? =
        Store.get(applicationContext).replacementPackage

    /** Called by legacy activities/tests when a cooldown completes/aborts. */
    fun recordCooldownOutcome(outcome: String, seconds: Int) {
        TelemetryBus.publishIntervention(
            InterventionEvent(
                timestampMs = System.currentTimeMillis(),
                dayKey = dayKey(),
                kind = "COOLDOWN",
                outcome = outcome,
                affectLabel = null,
                cooldownSeconds = seconds,
            )
        )
        Store.get(applicationContext).recordOverride(outcome)
    }

    /** Expose rolling kinematics (mu / CV / brain-state) to the dashboard. */
    fun liveKinematics(): SmartSwipeDetector.Kinematics = detector.kinematics()

    private val idleTicker = object : Runnable {
        override fun run() {
            sessions.tick()
            // ---- V4-5 bandit reward scorer: exit <=60 s + no target app for 15 min = success ----
            if (ReelBreakerApp.bandit.pendingSince() > 0 && banditAwaySince > 0 &&
                System.currentTimeMillis() - banditAwaySince >= BANDIT_AWAY_REQUIRED_MS
            ) {
                ReelBreakerApp.bandit.recordOutcome(success = true)
                banditAwaySince = 0L
                banditServedAt = 0L
            }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        ReelProbe.root = null
        Store.get(applicationContext).blockerActive = false
        dampener?.release()
        stopWatchdog()
        if (::overlayCtl.isInitialized) overlayCtl.releaseAll()
        if (::audio.isInitialized) audio.release()
        if (this::handler.isInitialized) {
            handler.removeCallbacks(idleTicker)
            handler.removeCallbacksAndMessages(null)
        }
        super.onDestroy()
    }

    data class OverlaySpec(val kind: Int, val seconds: Int, val reason: String) {
        companion object {
            const val KIND_COOLDOWN = 0
            const val KIND_CHECKPOINT = 1
            const val KIND_BLOCK = 2
            const val PASSIVE_LOCK_MS = 40_000L
            /** V4-1 natural-gap: quiet period & max queue wait. */
            const val GAP_QUIET_MS = 1_500L
            const val GAP_MAX_WAIT_MS = 8_000L
            fun Cooldown(s: Int, r: String) = OverlaySpec(KIND_COOLDOWN, s, r)
            fun Checkpoint(r: String) = OverlaySpec(KIND_CHECKPOINT, 0, r)
            fun Block(r: String) = OverlaySpec(KIND_BLOCK, 0, r)
        }
    }
}

