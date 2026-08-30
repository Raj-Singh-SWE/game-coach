<div align="center">

# 🎮 Game Coach — Tactical Co-Pilot

**An Android AI co-pilot that analyses your battle royale game state in real-time using a C++ decision engine, streams live telemetry to a coach dashboard over WebSocket, and generates a post-match Gamer DNA profile.**

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![C++17](https://img.shields.io/badge/Engine-C++17%20%2F%20JNI-00599C?logo=cplusplus&logoColor=white)](https://isocpp.org)
[![Ktor](https://img.shields.io/badge/Server-Ktor%202.3-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![Room](https://img.shields.io/badge/DB-Room%20SQLite-orange)](https://developer.android.com/training/data-storage/room)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## ✨ Features

| Feature | Detail |
|---|---|
| 🧠 **C++ Tactical Engine** | Deterministic priority-ladder alert system running over JNI — zero GC pressure |
| 📡 **Live Coach Dashboard** | Ktor Netty WebSocket server broadcasts telemetry to `dashboard.html` on any laptop |
| 🗄️ **Session Logging** | Every engine evaluation is persisted to a Room/SQLite `match_events` table |
| 🧬 **Gamer DNA Scoring** | Aggression Score + Consistency Score derived from a single SQL aggregate query |
| 📊 **Post-Match Analytics** | Compose screen with animated Canvas score rings, play-style badge & alert timeline |
| 🔔 **Foreground Service** | Android foreground service keeps the WS server alive with a persistent notification |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Android App                         │
│                                                         │
│  Compose UI (sliders)                                   │
│       │                                                 │
│       ▼  jsonState                                      │
│  getTacticalAlert()  ──► JNI ──► C++ TacticalEngine    │
│       │                          (priority ladder)      │
│       │  alert string                                   │
│       ├──────────────────────────────────────────────┐  │
│       │                                              │  │
│       ▼                                              ▼  │
│  Room DB (MatchEvent)              TacticalBroadcastService  │
│  MatchEventDao.insert()            Ktor Netty :8080/ws  │
│       │                                              │  │
│       ▼                                              ▼  │
│  GamerDnaAnalyzer                         Wi-Fi LAN     │
│  AggressionScore                              │         │
│  ConsistencyScore                             ▼         │
│  PlayStyleTag                         dashboard.html    │
│       │                               (Coach laptop)   │
│       ▼                                                 │
│  PostMatchScreen (Compose)                              │
└─────────────────────────────────────────────────────────┘
```

---

## 📱 Screens

### 1 · Tactical Co-Pilot (Main)
- Interactive sliders for **Health**, **Enemies**, **Ammo**, **Teammates Alive**, **Zone Collapsing**
- Live JSON preview sent to the C++ engine
- Colour-coded alert banner (red → amber → yellow → green by severity)
- **Coach Dashboard URL** displayed in a neon banner (`ws://<phone-ip>:8080/ws`)
- **"VIEW POST-MATCH ANALYTICS"** button

### 2 · Post-Match Analytics
- **DNA Header** — session duration, total events, avg HP, critical alert count
- **Animated Score Rings** — two `Canvas` arcs that sweep from 0 → score over 1.2 s
- **Play Style Badge** — `AGGRESSIVE` / `BALANCED` / `DEFENSIVE`
- **Peak Danger Card** — the worst health + ammo moment in the session
- **Critical Alert Timeline** — staggered fade-in rows with relative `+M:SS` timestamps

### 3 · Coach Dashboard (`dashboard.html`)
- Zero dependencies — open directly in any browser, no build step
- Auto-reconnecting WebSocket client; persists the phone IP in `localStorage`
- Health bar (animated, threshold-aware), Enemies, Ammo, Teammates stat cards
- Scrolling alert log colour-coded by severity (ALERT / WARNING / CAUTION / STATUS)
- Pulsing connection status dot

---

## 🧠 C++ Priority Ladder

```
Input JSON: { "health": 75, "enemies": 3, "zone_collapsing": false,
              "ammo": 45, "teammates_alive": 2 }

PRIORITY 1 → health ≤ 20              → "ALERT: CRITICAL HEALTH — RETREAT IMMEDIATELY!"
PRIORITY 2 → enemies > teammates + 1  → "ALERT: OUTNUMBERED — REPOSITION AND CALL FOR BACKUP!"
PRIORITY 3 → zone_collapsing          → "WARNING: ZONE COLLAPSING — MOVE TO SAFE ZONE NOW!"
PRIORITY 4 → ammo ≤ 10               → "CAUTION: LOW AMMO — FIND SUPPLIES!"
PRIORITY 5 → (all clear)              → "STATUS: CLEAR — ADVANCE AND LOOT."
```

---

## 🧬 Gamer DNA Scoring

```
AggressionScore  = clamp( (highEnemy / total) × 100  +  criticals × 3,  0, 100 )
ConsistencyScore = clamp( (solid / total) × 100  −  criticals × 5,      0, 100 )

delta = Aggression − Consistency
PlayStyle = "AGGRESSIVE"  if delta >  20
          = "DEFENSIVE"   if delta < −20
          = "BALANCED"    otherwise
```

Where:
- **highEnemy** = events where `enemies ≥ 2`
- **criticals** = events where the engine issued an `ALERT`
- **solid** = events where `health > 50 AND ammo > 30`

All stats are computed in a **single SQL aggregate pass** via Room DAO.

---

## 📡 WebSocket Packet Schema

Every slider change broadcasts this JSON to all connected coaches:

```json
{
  "health": 75,
  "enemies": 3,
  "zone_collapsing": false,
  "ammo": 45,
  "teammates_alive": 2,
  "alert": "ALERT: OUTNUMBERED — REPOSITION AND CALL FOR BACKUP!",
  "ts": 1725000000000
}
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 + C++17 |
| UI | Jetpack Compose (Material 3) |
| Navigation | Navigation Compose 2.7.7 |
| Native bridge | JNI (`tactical_engine.cpp`) |
| WebSocket server | Ktor 2.3.12 + Netty |
| Local database | Room 2.6.1 / SQLite |
| Code generation | KSP 2.0.21-1.0.28 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |
| ABIs | `arm64-v8a`, `armeabi-v7a` |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Meerkat or later
- Android NDK (for C++ compilation)
- A device or emulator running Android 7.0+ (API 24)

### Build & Run

```bash
git clone https://github.com/Raj-Singh-SWE/game-coach.git
cd game-coach
```

1. Open the project in **Android Studio**
2. Let Gradle sync (KSP will auto-generate Room binding code)
3. Connect your Android device
4. **Run** → the app installs and starts the WebSocket foreground service

### Using the Coach Dashboard

1. Launch the app on your phone
2. Note the **Coach Dashboard URL** shown in the blue banner:
   ```
   ws://192.168.x.x:8080/ws
   ```
3. Open `dashboard.html` from the repo root in a browser **on the same Wi-Fi network**
4. Enter the phone IP → click **CONNECT**
5. Move any slider on the phone → the dashboard updates in real-time

### Viewing Post-Match Analytics

1. Interact with the sliders a few times to log events
2. Tap **"VIEW POST-MATCH ANALYTICS"** at the bottom of the main screen
3. Watch the Aggression and Consistency score rings animate to your result

---

## 📁 Project Structure

```
MyApplication2/
├── app/
│   ├── build.gradle.kts                     # Ktor + Room + Navigation deps
│   └── src/main/
│       ├── AndroidManifest.xml              # Permissions + service declaration
│       ├── cpp/
│       │   ├── CMakeLists.txt               # NDK build config
│       │   └── tactical_engine.cpp          # C++ priority-ladder engine
│       └── java/com/example/myapplication/
│           ├── MainActivity.kt              # NavHost + session lifecycle
│           ├── TacticalBroadcastService.kt  # Ktor WebSocket foreground service
│           ├── PostMatchScreen.kt           # Gamer DNA analytics UI
│           └── data/
│               ├── MatchEvent.kt            # Room @Entity
│               ├── MatchEventDao.kt         # DAO + SessionStats SQL
│               ├── AppDatabase.kt           # Room singleton
│               └── GamerDnaAnalyzer.kt      # Scoring + DnaReport
├── dashboard.html                           # Coach Dashboard (no build step)
└── gradle/
    └── libs.versions.toml                   # Version catalog
```

---

## 📜 License

```
MIT License — Copyright (c) 2026 Raj Singh
```

Free to use, modify, and distribute with attribution.

---

<div align="center">
  Built with ⚡ by <a href="https://github.com/Raj-Singh-SWE">Raj Singh</a>
</div>
