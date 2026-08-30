package com.example.myapplication.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// =============================================================================
// SessionStats — POJO returned by the aggregate SQL query.
//
// Column aliases in the @Query below MUST match these field names exactly —
// Room maps them by name, not by position.
// =============================================================================

data class SessionStats(
    /** Total number of engine evaluations in this session. */
    val total: Int,

    /** Evaluations where enemies >= 2 (high-threat engagements). */
    val highEnemy: Int,

    /** Evaluations where the engine issued an ALERT (critical severity). */
    val criticals: Int,

    /** Evaluations where the engine issued STATUS: CLEAR. */
    val clears: Int,

    /**
     * Evaluations where the player was in a "solid" state:
     *   health > 50 AND ammo > 30 — sustained performance baseline.
     */
    val solid: Int,

    /** Mean health across the whole session (nullable → 0f if no rows). */
    val avgHealth: Float
)

// =============================================================================
// MatchEventDao
// =============================================================================

@Dao
interface MatchEventDao {

    // ── Write ─────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MatchEvent)

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * All events for [sid] in chronological order.
     * Used to build the timeline and locate the peak-danger moment.
     */
    @Query("SELECT * FROM match_events WHERE sessionId = :sid ORDER BY timestamp ASC")
    suspend fun getSession(sid: Long): List<MatchEvent>

    /**
     * Single-pass aggregate query that returns all stats needed for scoring.
     *
     * Aliases map directly to [SessionStats] field names.
     * COALESCE guards AVG() against returning NULL on an empty table.
     */
    @Query("""
        SELECT
            COUNT(*)                                                    AS total,
            SUM(CASE WHEN enemies >= 2       THEN 1 ELSE 0 END)        AS highEnemy,
            SUM(CASE WHEN eventType = 'ALERT'   THEN 1 ELSE 0 END)     AS criticals,
            SUM(CASE WHEN eventType = 'STATUS'  THEN 1 ELSE 0 END)     AS clears,
            SUM(CASE WHEN health > 50 AND ammo > 30 THEN 1 ELSE 0 END) AS solid,
            COALESCE(AVG(CAST(health AS REAL)), 0.0)                    AS avgHealth
        FROM match_events
        WHERE sessionId = :sid
    """)
    suspend fun getStats(sid: Long): SessionStats

    // ── Maintenance ───────────────────────────────────────────────────────────

    /**
     * Deletes events older than [cutoff] (Unix millis) to keep the DB lean.
     * Called from MainActivity.onDestroy — prune anything older than 7 days.
     */
    @Query("DELETE FROM match_events WHERE timestamp < :cutoff")
    suspend fun purgeOld(cutoff: Long)
}
