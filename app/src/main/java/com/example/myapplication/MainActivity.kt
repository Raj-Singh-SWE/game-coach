package com.example.myapplication

// =============================================================================
// MainActivity.kt — "Gold Light" Phase (Full Feature Build)
//
// Architecture:
//   Kotlin (UI layer)  ──►  JNI  ──►  C++ TacticalEngine (logic layer)
//                                               │
//                         ┌─────────────────────┤
//                         │                     │
//                         ▼                     ▼
//          TacticalBroadcastService      AppDatabase (Room/SQLite)
//          (Ktor WS server)              MatchEvent rows per session
//                         │                     │
//                         ▼                     ▼
//          dashboard.html             PostMatchScreen (NavHost)
//          (Coach laptop)             GamerDnaAnalyzer
//
// Session lifecycle:
//   - sessionId generated in onCreate (System.currentTimeMillis())
//   - Every C++ engine call logs a MatchEvent row via Room on IO dispatcher
//   - "View Analytics" button navigates to PostMatchScreen(sessionId)
//   - onDestroy purges DB entries older than 7 days
// =============================================================================

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.MatchEvent
import com.example.myapplication.data.MatchEventDao
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ── JNI bridge ─────────────────────────────────────────────────────────────
    companion object {
        init { System.loadLibrary("tactical_engine") }
    }

    external fun getTacticalAlert(jsonState: String): String

    // ── Bound service ──────────────────────────────────────────────────────────
    private var broadcastService: TacticalBroadcastService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            broadcastService = (binder as? TacticalBroadcastService.LocalBinder)?.getService()
            serviceBound = true
            lanIp = broadcastService?.lanIpAddress ?: "0.0.0.0"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            broadcastService = null
            serviceBound = false
        }
    }

    // ── LAN IP (observed by Compose) ───────────────────────────────────────────
    private var lanIp by mutableStateOf("connecting...")

    // ── Room database + DAO ────────────────────────────────────────────────────
    private lateinit var dao: MatchEventDao

    /**
     * Unique identifier for the current play session.
     * All MatchEvent rows logged in this Activity lifecycle share this ID,
     * allowing PostMatchScreen to slice them independently from past sessions.
     */
    private var sessionId: Long = 0L

    // ── Activity lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Stamp this session at launch time
        sessionId = System.currentTimeMillis()

        // Initialise Room (singleton, safe to call here)
        dao = AppDatabase.getInstance(this).matchEventDao()

        // Start + bind the WebSocket foreground service
        val serviceIntent = Intent(this, TacticalBroadcastService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()

                NavHost(
                    navController    = navController,
                    startDestination = "main"
                ) {
                    // ── Main screen ────────────────────────────────────────────
                    composable("main") {
                        TacticalCoPilotScreen(
                            lanIp      = lanIp,
                            onEvaluate = { json, health, enemies, ammo, teammates, zone ->
                                val alert = getTacticalAlert(json)
                                logEvent(health, enemies, ammo, teammates, zone, alert)
                                broadcastPacket(json, alert)
                                alert
                            },
                            onViewAnalytics = {
                                navController.navigate("post_match/$sessionId")
                            }
                        )
                    }

                    // ── Post-match analytics screen ────────────────────────────
                    composable("post_match/{sessionId}") { backEntry ->
                        val sid = backEntry.arguments
                            ?.getString("sessionId")
                            ?.toLongOrNull() ?: sessionId

                        PostMatchScreen(
                            sessionId = sid,
                            dao       = dao,
                            onBack    = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // Prune events older than 7 days so the DB stays lean
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        CoroutineScope(Dispatchers.IO).launch {
            dao.purgeOld(sevenDaysAgo)
        }
    }

    // =========================================================================
    // Event logging (non-blocking, always on IO dispatcher)
    // =========================================================================

    private fun logEvent(
        health: Int, enemies: Int, ammo: Int,
        teammates: Int, zone: Boolean, alert: String
    ) {
        // Derive the top-level event type from the alert prefix
        val eventType = when {
            alert.startsWith("ALERT:")   -> "ALERT"
            alert.startsWith("WARNING:") -> "WARNING"
            alert.startsWith("CAUTION:") -> "CAUTION"
            alert.startsWith("STATUS:")  -> "STATUS"
            else                         -> "ERROR"
        }

        CoroutineScope(Dispatchers.IO).launch {
            dao.insert(
                MatchEvent(
                    sessionId      = sessionId,
                    timestamp      = System.currentTimeMillis(),
                    eventType      = eventType,
                    alertGiven     = alert,
                    health         = health,
                    enemies        = enemies,
                    ammo           = ammo,
                    teammatesAlive = teammates,
                    zoneCollapsing = zone
                )
            )
        }
    }

    // =========================================================================
    // WebSocket broadcast helper
    // =========================================================================

    private fun broadcastPacket(gameStateJson: String, alert: String) {
        val service = broadcastService ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val trimmed = gameStateJson.trimIndent().trimEnd().trimEnd('}')
            val escaped = alert.replace("\\", "\\\\").replace("\"", "\\\"")
            val packet  = """$trimmed,
  "alert": "$escaped",
  "ts": ${System.currentTimeMillis()}
}"""
            service.broadcast(packet)
        }
    }
}

// =============================================================================
// Composables
// =============================================================================

private fun alertColor(alert: String): Color = when {
    alert.startsWith("ALERT:")   -> Color(0xFFFF3B30)
    alert.startsWith("WARNING:") -> Color(0xFFFF9F0A)
    alert.startsWith("CAUTION:") -> Color(0xFFFFD60A)
    alert.startsWith("STATUS:")  -> Color(0xFF30D158)
    else                         -> Color(0xFF8E8E93)
}

/**
 * Main game-state screen.
 *
 * [onEvaluate] now passes all game-state values so the Activity can log them
 * to Room without having to re-parse the JSON string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TacticalCoPilotScreen(
    lanIp: String = "0.0.0.0",
    onEvaluate: (json: String, health: Int, enemies: Int,
                 ammo: Int, teammates: Int, zone: Boolean) -> String,
    onViewAnalytics: () -> Unit = {}
) {
    var health         by remember { mutableIntStateOf(75) }
    var enemies        by remember { mutableIntStateOf(3)  }
    var zoneCollapsing by remember { mutableStateOf(false) }
    var ammo           by remember { mutableIntStateOf(45) }
    var teammatesAlive by remember { mutableIntStateOf(2)  }

    val jsonState = remember(health, enemies, zoneCollapsing, ammo, teammatesAlive) {
        """{"health":$health,"enemies":$enemies,"zone_collapsing":$zoneCollapsing,"ammo":$ammo,"teammates_alive":$teammatesAlive}"""
    }

    // Calls C++ engine, logs to DB, and broadcasts — all via the lambda
    val alertText = remember(jsonState) {
        onEvaluate(jsonState, health, enemies, ammo, teammatesAlive, zoneCollapsing)
    }

    val animatedAlertColor by animateColorAsState(
        targetValue   = alertColor(alertText),
        animationSpec = tween(durationMillis = 400),
        label         = "alertColorAnim"
    )

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Tactical Co-Pilot",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Alert Banner ────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = animatedAlertColor.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            listOf(animatedAlertColor, animatedAlertColor.copy(alpha = 0.3f))
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Text(
                    text       = alertText,
                    color      = animatedAlertColor,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier   = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }

            // ── Engine Badge ────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1C1C1E)) {
                    Text(
                        text = "Engine: C++ / JNI  |  Zero GC Pressure",
                        color = Color(0xFF8E8E93),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ── Coach Dashboard URL Banner ──────────────────────────────────
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF001A2C),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF00C8FF).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("COACH DASHBOARD", color = Color(0xFF00C8FF), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("ws://$lanIp:8080/ws", color = Color.White, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text("Open dashboard.html on your laptop", color = Color(0xFF8E8E93), fontSize = 11.sp)
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2E))

            // ── Sliders Card ────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Game State", color = Color(0xFF8E8E93), fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

                    StateSlider("Health", health.toFloat(), 0f..100f, "$health HP",
                        when { health <= 20 -> Color(0xFFFF3B30); health <= 50 -> Color(0xFFFF9F0A); else -> Color(0xFF30D158) }
                    ) { health = it.toInt() }

                    StateSlider("Nearby Enemies", enemies.toFloat(), 0f..10f, "$enemies",
                        Color(0xFFFF453A)) { enemies = it.toInt() }

                    StateSlider("Ammo", ammo.toFloat(), 0f..120f, "$ammo rds",
                        Color(0xFFFFD60A)) { ammo = it.toInt() }

                    StateSlider("Teammates Alive", teammatesAlive.toFloat(), 0f..4f, "$teammatesAlive",
                        Color(0xFF64D2FF)) { teammatesAlive = it.toInt() }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Zone Collapsing", color = Color.White, fontSize = 14.sp)
                        Switch(
                            checked  = zoneCollapsing,
                            onCheckedChange = { zoneCollapsing = it },
                            colors   = SwitchDefaults.colors(
                                checkedThumbColor  = Color.White,
                                checkedTrackColor  = Color(0xFFFF9F0A)
                            )
                        )
                    }
                }
            }

            // ── JSON Preview Card ───────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("JSON → C++ Engine", color = Color(0xFF8E8E93), fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = jsonState,
                        color = Color(0xFF30D158),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }

            // ── View Analytics Button ───────────────────────────────────────
            Button(
                onClick  = onViewAnalytics,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C8FF),
                    contentColor   = Color(0xFF070B11)
                )
            ) {
                Text(
                    text          = "VIEW POST-MATCH ANALYTICS",
                    fontSize      = 13.sp,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// =============================================================================
// Reusable slider row
// =============================================================================

@Composable
private fun StateSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    activeColor: Color,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(display, color = activeColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value          = value,
            onValueChange  = onValueChange,
            valueRange     = range,
            colors         = SliderDefaults.colors(
                thumbColor        = activeColor,
                activeTrackColor  = activeColor,
                inactiveTrackColor= Color(0xFF3A3A3C)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =============================================================================
// Preview
// =============================================================================

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun TacticalCoPilotPreview() {
    MyApplicationTheme {
        TacticalCoPilotScreen(
            lanIp = "192.168.1.42",
            onEvaluate = { _, _, _, _, _, _ -> "[PREVIEW] C++ Engine Disabled — Run on Device" }
        )
    }
}
