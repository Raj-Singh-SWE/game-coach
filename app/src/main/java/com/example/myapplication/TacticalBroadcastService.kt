package com.example.myapplication

// =============================================================================
// TacticalBroadcastService.kt
//
// A bound + foreground Android Service that runs a Ktor Netty WebSocket server
// on port 8080.  Every time the C++ engine produces a new tactical evaluation,
// MainActivity calls broadcast() to push a JSON packet to all connected
// Coach Dashboard browser clients on the same Wi-Fi network.
//
// WebSocket endpoint:  ws://<device-LAN-ip>:8080/ws
//
// Packet schema:
//   {
//     "health":          <int>,
//     "enemies":         <int>,
//     "zone_collapsing": <bool>,
//     "ammo":            <int>,
//     "teammates_alive": <int>,
//     "alert":           <string>,
//     "ts":              <long millis>
//   }
// =============================================================================

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG               = "TacticalBroadcast"
private const val WS_PORT           = 8080
private const val CHANNEL_ID        = "tactical_ws_channel"
private const val NOTIFICATION_ID   = 1001

class TacticalBroadcastService : Service() {

    // ── Coroutine scope tied to the service lifetime ─────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Ktor Netty engine (nullable until server starts) ─────────────────────
    private var server: ApplicationEngine? = null

    // ── Thread-safe list of all active WebSocket sessions ────────────────────
    private val clients = CopyOnWriteArrayList<DefaultWebSocketServerSession>()

    // ── Cached LAN IP for display in the Activity UI ─────────────────────────
    var lanIpAddress: String = "0.0.0.0"
        private set

    // ── Binder returned to the binding Activity ───────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): TacticalBroadcastService = this@TacticalBroadcastService
    }

    private val binder = LocalBinder()

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        lanIpAddress = resolveWlanIp()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(lanIpAddress))
        startKtorServer()
        Log.i(TAG, "Service created. WebSocket listening on ws://$lanIpAddress:$WS_PORT/ws")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed, Ktor server stopped.")
    }

    // =========================================================================
    // Ktor WebSocket server
    // =========================================================================

    private fun startKtorServer() {
        server = embeddedServer(
            factory  = Netty,
            port     = WS_PORT,
            host     = "0.0.0.0"
        ) {
            install(WebSockets) {
                // Keep connections alive with a 30-second ping interval
                pingPeriodMillis    = 30_000L
                timeoutMillis       = 60_000L
                maxFrameSize        = 64 * 1024L   // 64 KB max frame
                masking             = false
            }

            routing {
                webSocket("/ws") {
                    // Register this session
                    clients.add(this)
                    val clientCount = clients.size
                    Log.i(TAG, "Coach connected (total clients: $clientCount)")

                    try {
                        // Keep the coroutine alive — consume any frames from the client
                        for (frame in incoming) {
                            // We don't expect client → server messages, but drain the
                            // channel so the connection stays open.
                            if (frame is Frame.Text) {
                                Log.d(TAG, "Received from client: ${frame.readText()}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Client session ended: ${e.message}")
                    } finally {
                        clients.remove(this)
                        Log.i(TAG, "Coach disconnected (remaining clients: ${clients.size})")
                    }
                }
            }
        }

        // Start the server in a background coroutine — non-blocking
        serviceScope.launch {
            try {
                server!!.start(wait = false)
                Log.i(TAG, "Ktor WebSocket server started on port $WS_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Ktor server: ${e.message}", e)
            }
        }
    }

    // =========================================================================
    // Public API called by MainActivity
    // =========================================================================

    /**
     * Broadcasts [jsonPacket] to all currently connected WebSocket clients.
     * Fire-and-forget: runs on [serviceScope]; caller does not block.
     */
    fun broadcast(jsonPacket: String) {
        if (clients.isEmpty()) return          // Nothing to do, fast-path

        serviceScope.launch {
            val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
            for (session in clients) {
                try {
                    session.send(Frame.Text(jsonPacket))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not send to client, marking for removal: ${e.message}")
                    deadSessions.add(session)
                }
            }
            clients.removeAll(deadSessions)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Resolves the device's WLAN (Wi-Fi) IPv4 address.
     * Returns "0.0.0.0" if Wi-Fi is unavailable or IP is not yet assigned.
     */
    @Suppress("DEPRECATION")
    private fun resolveWlanIp(): String {
        return try {
            val wifiManager = applicationContext
                .getSystemService(WIFI_SERVICE) as WifiManager
            val rawIp = wifiManager.connectionInfo.ipAddress
            if (rawIp == 0) return "0.0.0.0"
            // ipAddress is stored as little-endian int
            "%d.%d.%d.%d".format(
                rawIp and 0xff,
                (rawIp shr 8)  and 0xff,
                (rawIp shr 16) and 0xff,
                (rawIp shr 24) and 0xff
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve WLAN IP: ${e.message}")
            "0.0.0.0"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tactical Broadcast Server",
                NotificationManager.IMPORTANCE_LOW        // Silent, no sound
            ).apply {
                description = "Keeps the Coach Dashboard WebSocket server alive"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Coach Dashboard Active")
            .setContentText("ws://$ip:$WS_PORT/ws")
            .setOngoing(true)
            .build()
    }
}
