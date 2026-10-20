package com.reelbreaker.telemetry

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Room-backed session telemetry (Phase 4).
 * One row per detected reel swipe with the kinematic brain-state that was
 * active at commit time, plus one row per intervention outcome so we can
 * compute "Trance Loops Broken" and "Prefrontal Overrides".
 */
@Entity(
    tableName = "swipe_events",
    indices = [Index("timestampMs"), Index("dayKey")]
)
data class SwipeEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val dayKey: String,            // yyyy-MM-dd for cheap daily aggregation
    val appPackage: String,
    val isiMs: Long,               // inter-swipe interval at this commit (-1 if first)
    val meanIsiMs: Double,
    val cv: Double,
    /** SmartSwipeDetector.BrainState name at commit time. */
    val brainState: String,
)

@Entity(tableName = "intervention_events")
data class InterventionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val dayKey: String,
    /** COOLDOWN | CHECKPOINT | SPEEDBUMP | BLOCK | DIM_TIER1 */
    val kind: String,
    /** SURFED | OVERRIDE | BREACH — did the prefrontal cortex win? */
    val outcome: String,
    /** Affective label chosen during System-2 task (may be null). */
    val affectLabel: String?,
    val cooldownSeconds: Int,
)

@Dao
interface TelemetryDao {
    @Insert suspend fun insertSwipe(e: SwipeEvent)
    @Insert suspend fun insertIntervention(e: InterventionEvent)

    @Query("SELECT COUNT(*) FROM swipe_events WHERE dayKey = :day")
    suspend fun swipesOn(day: String): Int

    @Query("SELECT COUNT(*) FROM swipe_events WHERE dayKey = :day AND brainState = :state")
    suspend fun stateSwipes(day: String, state: String): Int

    @Query("SELECT COUNT(*) FROM intervention_events WHERE dayKey = :day AND kind = :kind AND outcome = :outcome")
    suspend fun interventions(day: String, kind: String, outcome: String): Int

    @Query("SELECT COUNT(*) FROM intervention_events WHERE dayKey = :day AND outcome = :outcome")
    suspend fun outcomes(day: String, outcome: String): Int

    @Query("SELECT affectLabel AS affectLabel, COUNT(*) AS c FROM intervention_events WHERE affectLabel IS NOT NULL GROUP BY affectLabel ORDER BY c DESC LIMIT 4")
    suspend fun affectHistogram(): List<AffectCount>

    /** V4-5: replay bandit trial history on service start (arm lives in outcome column). */
    @Query("SELECT affectLabel AS arm, outcome = 'SURFED' AS success FROM intervention_events WHERE kind = 'BANDIT' AND affectLabel IS NOT NULL")
    suspend fun banditTrials(): List<BanditTrial>

    @Query("DELETE FROM swipe_events WHERE timestampMs < :before")
    suspend fun pruneSwipes(before: Long)

    @Query("DELETE FROM intervention_events WHERE timestampMs < :before")
    suspend fun pruneInterventions(before: Long)
}

data class AffectCount(val affectLabel: String, val c: Int)
data class BanditTrial(val arm: String, val success: Boolean)

@Database(entities = [SwipeEvent::class, InterventionEvent::class], version = 1, exportSchema = false)
abstract class TelemetryDb : RoomDatabase() {
    abstract fun dao(): TelemetryDao

    companion object {
        @Volatile private var inst: TelemetryDb? = null
        fun get(c: Context): TelemetryDb =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(c.applicationContext, TelemetryDb::class.java, "telemetry.db")
                    .fallbackToDestructiveMigration()
                    .build().also { inst = it }
            }
    }
}

/** Aggregated live view-model pushed to the dashboard via StateFlow. */
data class TelemetrySummary(
    val reelsToday: Int = 0,
    val foragingEpisodes: Int = 0,
    val tranceLoopsBroken: Int = 0,
    val prefrontalOverrides: Int = 0,      // self-control wins
    val urgeSurfsCompleted: Int = 0,
    val blocksIssued: Int = 0,
    val topAffect: String = "",
    /** V4-5: raw bandit trial log (arm name -> success) for discovery card. */
    val banditTrials: List<Pair<String, Boolean>> = emptyList(),
)

/**
 * Single writer/reader facade. The accessibility service (non-coroutine,
 * main-thread event loop) pushes events into a SharedFlow; a background
 * consumer batch-writes them to Room. UI observes derived StateFlows —
 * no DB access ever happens on the main thread.
 */
object TelemetryBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: TelemetryDb
    private lateinit var dao: TelemetryDao

    private val _swipes = MutableSharedFlow<SwipeEvent>(
        extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _interventions = MutableSharedFlow<InterventionEvent>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Monotonic tick bumped whenever any new event lands (drives refresh). */
    private val _tick = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = TelemetryDb.get(context)
        dao = db.dao()
        scope.launch { _swipes.collect { dao.insertSwipe(it); _tick.tryEmit(Unit) } }
        scope.launch { _interventions.collect { dao.insertIntervention(it); _tick.tryEmit(Unit) } }
        // keep DB bounded to ~90 days
        scope.launch {
            dao.pruneSwipes(System.currentTimeMillis() - 90L * 86_400_000L)
            dao.pruneInterventions(System.currentTimeMillis() - 90L * 86_400_000L)
        }
    }

    fun publishSwipe(e: SwipeEvent) { _swipes.tryEmit(e) }
    fun publishIntervention(e: InterventionEvent) { _interventions.tryEmit(e) }

    /** Reactive summary for the UI layer. */
    fun summaryFlow(dayKey: () -> String): StateFlow<TelemetrySummary> =
        _tick
            .map { readSummary(dayKey()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), TelemetrySummary())

    suspend fun readSummary(day: String): TelemetrySummary {
        val hist = runCatching { dao.affectHistogram() }.getOrDefault(emptyList())
        val bandit = runCatching { dao.banditTrials().map { it.arm to it.success } }
            .getOrDefault(emptyList())
        return TelemetrySummary(
            reelsToday = dao.swipesOn(day),
            foragingEpisodes = dao.stateSwipes(day, "FORAGING_STATE"),
            tranceLoopsBroken = dao.interventions(day, "SPEEDBUMP", "OVERRIDE") +
                    dao.outcomes(day, "SURFED"),
            prefrontalOverrides = dao.outcomes(day, "OVERRIDE"),
            urgeSurfsCompleted = dao.outcomes(day, "SURFED"),
            blocksIssued = dao.outcomes(day, "BREACHED_BLOCK"),
            topAffect = hist.firstOrNull()?.affectLabel ?: "",
            banditTrials = bandit,
        )
    }

    /** V4-5 one-shot load of persisted bandit trials for engine-start restore. */
    suspend fun loadBanditTrials(): List<Pair<String, Boolean>> =
        runCatching { dao.banditTrials().map { it.arm to it.success } }.getOrDefault(emptyList())
}
