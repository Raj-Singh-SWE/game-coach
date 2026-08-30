package com.example.myapplication

// =============================================================================
// PostMatchScreen.kt — "Replay Doctor & Gamer DNA" Analytics Screen
//
// Displays the post-match analysis for a completed session:
//   1. DNA Header   — date, duration, event count
//   2. Score Rings  — animated Canvas arcs (Aggression + Consistency)
//   3. Play Style Badge — AGGRESSIVE / BALANCED / DEFENSIVE
//   4. Peak Danger Card — worst health+ammo moment in the match
//   5. Critical Alert Timeline — scrollable chronological log
// =============================================================================

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.DnaReport
import com.example.myapplication.data.GamerDnaAnalyzer
import com.example.myapplication.data.MatchEventDao
import com.example.myapplication.data.TimelineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.platform.LocalContext

// =============================================================================
// Colour palette (dark esports)
// =============================================================================

private val BgBase    = Color(0xFF070B11)
private val BgCard    = Color(0xFF0D1520)
private val BgPanel   = Color(0xFF111A28)
private val Cyan      = Color(0xFF00C8FF)
private val CyanDim   = Color(0x2600C8FF)
private val NeonGreen = Color(0xFF39FF8F)
private val NeonRed   = Color(0xFFFF3B5C)
private val Amber     = Color(0xFFFF9F0A)
private val Yellow    = Color(0xFFFFE03A)
private val TextPrim  = Color(0xFFE8F4FF)
private val TextSec   = Color(0xFF6B8AAA)
private val TextMuted = Color(0xFF3D5570)
private val Border    = Color(0x1F00C8FF)

// =============================================================================
// Entry point
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostMatchScreen(
    sessionId: Long,
    dao: MatchEventDao,
    onBack: () -> Unit
) {
    var report    by remember { mutableStateOf<DnaReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Load data from Room on IO thread, publish result to UI thread
    LaunchedEffect(sessionId) {
        withContext(Dispatchers.IO) {
            val events = dao.getSession(sessionId)
            val stats  = dao.getStats(sessionId)
            val r      = GamerDnaAnalyzer.analyze(stats, events)
            withContext(Dispatchers.Main) {
                report    = r
                isLoading = false
            }
        }
    }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "POST-MATCH ANALYTICS",
                        color = Cyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Cyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0E17))
            )
        }
    ) { padding ->
        when {
            isLoading -> LoadingState(padding)
            report != null -> ReportContent(report = report!!, padding = padding)
            else -> EmptyState(padding)
        }
    }
}

// =============================================================================
// Loading / Empty states
// =============================================================================

@Composable
private fun LoadingState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp)
            Text("Analysing session data...", color = TextSec, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("No session data found.", color = TextMuted, fontSize = 14.sp)
    }
}

// =============================================================================
// Main report layout
// =============================================================================

@Composable
private fun ReportContent(report: DnaReport, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // 1. DNA Header
        item { DnaHeader(report) }

        // 2. Score Rings + Play Style Badge
        item { ScoreRingsSection(report) }

        // 3. Peak Danger card
        report.peakDangerEvent?.let { danger ->
            item { PeakDangerCard(danger, report) }
        }

        // 4. Timeline header
        item {
            Text(
                "CRITICAL ALERT TIMELINE",
                color = Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 5. Timeline rows — inline so they don't nest scroll
        if (report.criticalTimeline.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No critical alerts in this session — clean run!",
                        color = NeonGreen,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            itemsIndexed(report.criticalTimeline) { index, entry ->
                TimelineRow(
                    entry = entry,
                    index = index,
                    isLast = index == report.criticalTimeline.lastIndex
                )
            }
        }

        // Bottom spacer
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// =============================================================================
// Section 1 — DNA Header
// =============================================================================

@Composable
private fun DnaHeader(report: DnaReport) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GAMER DNA PROFILE", color = Cyan, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatChip(label = "EVENTS", value = report.totalEvents.toString())
                StatChip(label = "DURATION", value = formatDuration(report.sessionDurationMs))
                StatChip(label = "AVG HP", value = "%.0f".format(report.avgHealth))
                StatChip(label = "CRITICAL", value = report.criticalTimeline.count { it.eventType == "ALERT" }.toString())
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrim, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

// =============================================================================
// Section 2 — Animated Score Rings
// =============================================================================

@Composable
private fun ScoreRingsSection(report: DnaReport) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BgCard,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Two score rings side-by-side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScoreRing(
                    score      = report.aggressionScore,
                    label      = "AGGRESSION",
                    ringColor  = NeonRed,
                    glowColor  = Color(0x4DFF3B5C)
                )
                // Central divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(120.dp)
                        .background(Border)
                )
                ScoreRing(
                    score      = report.consistencyScore,
                    label      = "CONSISTENCY",
                    ringColor  = Cyan,
                    glowColor  = CyanDim
                )
            }

            // Play Style Badge
            PlayStyleBadge(report.playStyleTag)
        }
    }
}

@Composable
private fun ScoreRing(
    score: Int,
    label: String,
    ringColor: Color,
    glowColor: Color
) {
    // Animate the sweep angle from 0 to the target
    val animatedSweep by animateFloatAsState(
        targetValue    = 260f * (score / 100f),
        animationSpec  = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label          = "ring_sweep_$label"
    )
    // Animate the displayed number
    val animatedScore by animateIntAsState(
        targetValue   = score,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label         = "score_num_$label"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke    = 14.dp.toPx()
                val inset     = stroke / 2f
                val arcSize   = Size(size.width - stroke, size.height - stroke)
                val arcOffset = Offset(inset, inset)

                // Background track
                drawArc(
                    color       = Color(0x1AFFFFFF),
                    startAngle  = -220f,
                    sweepAngle  = 260f,
                    useCenter   = false,
                    topLeft     = arcOffset,
                    size        = arcSize,
                    style       = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // Glow layer (slightly wider, semi-transparent)
                if (animatedSweep > 0f) {
                    drawArc(
                        color      = glowColor,
                        startAngle = -220f,
                        sweepAngle = animatedSweep,
                        useCenter  = false,
                        topLeft    = Offset(inset - 4, inset - 4),
                        size       = Size(size.width - stroke + 8, size.height - stroke + 8),
                        style      = Stroke(width = stroke + 8, cap = StrokeCap.Round)
                    )
                }
                // Filled arc
                drawArc(
                    color       = ringColor,
                    startAngle  = -220f,
                    sweepAngle  = animatedSweep,
                    useCenter   = false,
                    topLeft     = arcOffset,
                    size        = arcSize,
                    style       = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // Number in the centre
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text       = animatedScore.toString(),
                    color      = ringColor,
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 28.sp
                )
                Text("/100", color = TextMuted, fontSize = 9.sp)
            }
        }

        Text(
            text          = label,
            color         = TextSec,
            fontSize       = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun PlayStyleBadge(tag: String) {
    val (color, desc) = when (tag) {
        "AGGRESSIVE" -> Pair(NeonRed,   "High engagement rate, frequent critical alerts")
        "DEFENSIVE"  -> Pair(Cyan,      "Cautious playstyle, good resource management")
        else         -> Pair(NeonGreen, "Well-rounded performance across all metrics")
    }

    Surface(
        shape  = RoundedCornerShape(999.dp),
        color  = color.copy(alpha = 0.1f),
        border = BorderStroke(1.5.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text          = tag,
                color         = color,
                fontSize      = 16.sp,
                fontWeight    = FontWeight.Black,
                letterSpacing = 3.sp,
                fontFamily    = FontFamily.Monospace
            )
            Text(
                text      = desc,
                color     = TextMuted,
                fontSize  = 10.sp,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// =============================================================================
// Section 3 — Peak Danger Card
// =============================================================================

@Composable
private fun PeakDangerCard(danger: com.example.myapplication.data.MatchEvent, report: DnaReport) {
    Surface(
        shape  = RoundedCornerShape(16.dp),
        color  = Color(0xFF1A0A10),
        border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(NeonRed, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "PEAK DANGER MOMENT",
                    color = NeonRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text      = danger.alertGiven,
                color     = NeonRed.copy(alpha = 0.9f),
                fontSize  = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )

            // Stat row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MiniStat("HP",   "${danger.health}",        NeonRed)
                MiniStat("EN",   "${danger.enemies}",       Amber)
                MiniStat("AMO",  "${danger.ammo}",          Yellow)
                MiniStat("ZONE", if (danger.zoneCollapsing) "YES" else "NO", TextSec)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, fontSize = 9.sp, letterSpacing = 0.5.sp)
    }
}

// =============================================================================
// Section 4 — Critical Alert Timeline
// =============================================================================

@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    index: Int,
    isLast: Boolean
) {
    val accentColor = when (entry.eventType) {
        "ALERT"   -> NeonRed
        "WARNING" -> Amber
        else      -> TextMuted
    }

    // Entrance animation — each row fades/slides in with a staggered delay
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        kotlinx.coroutines.delay(index * 60L)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 350, delayMillis = 0),
        label         = "timeline_alpha_$index"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.Top
    ) {
        // ── Left gutter: dot + vertical connector ────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accentColor, CircleShape)
                    .border(2.dp, accentColor.copy(alpha = 0.3f), CircleShape)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(60.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(accentColor.copy(alpha = 0.4f), Color.Transparent)
                            )
                        )
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // ── Event card ────────────────────────────────────────────────────────
        Surface(
            shape  = RoundedCornerShape(10.dp),
            color  = accentColor.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text   = entry.eventType,
                        color  = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text   = GamerDnaAnalyzer.formatRelative(entry.relativeMs),
                        color  = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text       = entry.alertText,
                    color      = accentColor.copy(alpha = 0.85f),
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(6.dp))
                // Snapshot mini-stats
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("HP ${entry.health}",     color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("EN ${entry.enemies}",    color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("AMMO ${entry.ammo}",     color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// =============================================================================
// Utility helpers
// =============================================================================

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}
