package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// =============================================================================
// MatchEvent — Room Entity
//
// Every call to the C++ tactical engine produces one row in this table.
// The sessionId groups all events from a single play session so the analytics
// screen can slice any past match independently.
// =============================================================================

@Entity(tableName = "match_events")
data class MatchEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Unix millis of session start — groups events into one match. */
    val sessionId: Long,

    /** Unix millis when this specific event was captured. */
    val timestamp: Long,

    /**
     * Top-level severity of the alert:
     *   "ALERT"   — Critical health / outnumbered
     *   "WARNING" — Zone collapsing
     *   "CAUTION" — Low ammo
     *   "STATUS"  — All clear
     *   "ERROR"   — Engine error
     */
    val eventType: String,

    /** Full alert string returned by the C++ engine. */
    val alertGiven: String,

    // ── Game-state snapshot at the moment of evaluation ──────────────────────
    val health: Int,
    val enemies: Int,
    val ammo: Int,
    val teammatesAlive: Int,
    val zoneCollapsing: Boolean
)
