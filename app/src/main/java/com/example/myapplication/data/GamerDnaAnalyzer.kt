package com.example.myapplication.data

// =============================================================================
// GamerDnaAnalyzer — Pure Kotlin scoring engine
//
// Takes the SQL aggregate stats + full event list for a session and derives:
//
//   AggressionScore  (0–100)
//     = clamp( (highEnemy / total) * 100  +  criticals * 3,  0, 100 )
//     Captures how often the player engaged high-threat situations and how
//     frequently those engagements reached critical severity.
//
//   ConsistencyScore  (0–100)
//     = clamp( (solid / total) * 100  -  criticals * 5,      0, 100 )
//     Rewards sustained "solid" game-state windows (health > 50, ammo > 30)
//     and penalises each critical event that broke that stability.
//
//   PlayStyle = Aggressive | Balanced | Defensive
//     Derived from the delta between the two scores.
// =============================================================================

// ── Output data structures ────────────────────────────────────────────────────

data class TimelineEntry(
    /** Milliseconds elapsed from the very first event in the session. */
    val relativeMs: Long,
    val eventType: String,      // "ALERT" or "WARNING"
    val alertText: String,
    val health: Int,
    val enemies: Int,
    val ammo: Int
)

data class DnaReport(
    // ── Scores ────────────────────────────────────────────────────────────────
    val aggressionScore: Int,       // 0–100
    val consistencyScore: Int,      // 0–100

    // ── Profile ───────────────────────────────────────────────────────────────
    val playStyleTag: String,       // "AGGRESSIVE" | "BALANCED" | "DEFENSIVE"

    // ── Session metadata ─────────────────────────────────────────────────────
    val totalEvents: Int,
    val sessionDurationMs: Long,    // first → last event span
    val avgHealth: Float,

    // ── Highlights ───────────────────────────────────────────────────────────
    /** Event where health + ammo was at its lowest (most dangerous moment). */
    val peakDangerEvent: MatchEvent?,

    /** Chronological list of ALERT and WARNING events for the timeline UI. */
    val criticalTimeline: List<TimelineEntry>
)

// ── Analyzer singleton ────────────────────────────────────────────────────────

object GamerDnaAnalyzer {

    /**
     * Derives the full [DnaReport] from the pre-computed [stats] aggregate
     * and the ordered [events] list returned by [MatchEventDao].
     *
     * Designed to run on [kotlinx.coroutines.Dispatchers.IO] — all work is
     * pure CPU, no suspending calls.
     */
    fun analyze(stats: SessionStats, events: List<MatchEvent>): DnaReport {

        // Guard: empty session returns a zero report
        val total = stats.total.coerceAtLeast(1)

        // ── Aggression Score ──────────────────────────────────────────────────
        // High enemy density × 100 plus a bonus for every critical alert fired.
        val aggressionRaw = (stats.highEnemy * 100.0 / total) + (stats.criticals * 3.0)
        val aggressionScore = aggressionRaw.toInt().coerceIn(0, 100)

        // ── Consistency Score ─────────────────────────────────────────────────
        // Proportion of time in "solid" state minus a penalty per critical.
        val consistencyRaw = (stats.solid * 100.0 / total) - (stats.criticals * 5.0)
        val consistencyScore = consistencyRaw.toInt().coerceIn(0, 100)

        // ── Play Style ────────────────────────────────────────────────────────
        val delta = aggressionScore - consistencyScore
        val playStyleTag = when {
            delta >  20 -> "AGGRESSIVE"
            delta < -20 -> "DEFENSIVE"
            else        -> "BALANCED"
        }

        // ── Session duration ──────────────────────────────────────────────────
        val durationMs = if (events.size >= 2) {
            events.last().timestamp - events.first().timestamp
        } else 0L

        // ── Peak danger = minimum (health + ammo) across the session ──────────
        val peakDanger = events.minByOrNull { it.health + it.ammo }

        // ── Critical timeline — ALERT & WARNING events only ───────────────────
        val sessionStart = events.firstOrNull()?.timestamp ?: 0L
        val timeline = events
            .filter { it.eventType == "ALERT" || it.eventType == "WARNING" }
            .map { e ->
                TimelineEntry(
                    relativeMs = e.timestamp - sessionStart,
                    eventType  = e.eventType,
                    alertText  = e.alertGiven,
                    health     = e.health,
                    enemies    = e.enemies,
                    ammo       = e.ammo
                )
            }

        return DnaReport(
            aggressionScore   = aggressionScore,
            consistencyScore  = consistencyScore,
            playStyleTag      = playStyleTag,
            totalEvents       = stats.total,
            sessionDurationMs = durationMs,
            avgHealth         = stats.avgHealth,
            peakDangerEvent   = peakDanger,
            criticalTimeline  = timeline
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Formats a relative millisecond offset as "+M:SS" (e.g. "+1:42").
     * Used by the timeline UI to show when each alert occurred in the session.
     */
    fun formatRelative(ms: Long): String {
        val totalSecs  = (ms / 1000).coerceAtLeast(0)
        val mins       = totalSecs / 60
        val secs       = totalSecs % 60
        return "+%d:%02d".format(mins, secs)
    }
}
