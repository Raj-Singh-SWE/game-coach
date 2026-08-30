// =============================================================================
// tactical_engine.cpp
// "Yellow Light" Phase — Deterministic C++ Native Tactical Engine
//
// Input:  A JSON string describing the current game state.
// Output: A prioritized tactical alert string.
//
// JSON Input Schema:
// {
//   "health": <int 0-100>,
//   "enemies": <int, number of nearby enemies>,
//   "zone_collapsing": <bool, is the safe zone shrinking?>,
//   "ammo": <int, rounds remaining>,
//   "teammates_alive": <int, number of surviving teammates>
// }
//
// Priority Ladder (highest → lowest):
//   1. CRITICAL  – Health ≤ 20
//   2. DANGER    – Outnumbered (enemies > teammates_alive + 1) AND enemies ≥ 2
//   3. ZONE      – Zone is collapsing
//   4. LOW_AMMO  – Ammo ≤ 10
//   5. CLEAR     – No active threats
// =============================================================================

#include <jni.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "TacticalEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// =============================================================================
// Minimal, zero-dependency JSON parser helpers
// We parse only the flat key-value pairs we need — no external library required.
// =============================================================================

/**
 * Finds the integer value associated with `key` in a flat JSON object string.
 * Returns `default_val` if the key is not found or cannot be parsed.
 *
 * Handles both  "key": 42  and  "key" : 42  (with optional whitespace).
 */
static int json_get_int(const char* json, const char* key, int default_val) {
    // Build the search pattern: "key":
    char pattern[128];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);

    const char* pos = strstr(json, pattern);
    if (!pos) return default_val;

    // Advance past the key and the closing quote
    pos += strlen(pattern);

    // Skip whitespace and the colon
    while (*pos == ' ' || *pos == '\t' || *pos == '\r' || *pos == '\n') pos++;
    if (*pos != ':') return default_val;
    pos++; // skip ':'
    while (*pos == ' ' || *pos == '\t' || *pos == '\r' || *pos == '\n') pos++;

    // Now parse the integer (may be negative)
    if (*pos == '-' || (*pos >= '0' && *pos <= '9')) {
        return (int)strtol(pos, nullptr, 10);
    }
    return default_val;
}

/**
 * Finds the boolean value associated with `key` in a flat JSON object string.
 * Returns `default_val` if the key is not found.
 *
 * Handles JSON `true` and `false` literals.
 */
static bool json_get_bool(const char* json, const char* key, bool default_val) {
    char pattern[128];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);

    const char* pos = strstr(json, pattern);
    if (!pos) return default_val;

    pos += strlen(pattern);

    // Skip whitespace and ':'
    while (*pos == ' ' || *pos == '\t' || *pos == '\r' || *pos == '\n') pos++;
    if (*pos != ':') return default_val;
    pos++;
    while (*pos == ' ' || *pos == '\t' || *pos == '\r' || *pos == '\n') pos++;

    if (strncmp(pos, "true", 4) == 0)  return true;
    if (strncmp(pos, "false", 5) == 0) return false;
    return default_val;
}

// =============================================================================
// Core Deterministic Engine
// =============================================================================

/**
 * Evaluates the tactical situation described by `json_state` and returns a
 * null-terminated priority-alert string.  The returned pointer is a string
 * literal — callers must NOT free it.
 *
 * Priority Ladder:
 *   PRIORITY 1 → "ALERT: CRITICAL HEALTH — RETREAT IMMEDIATELY!"
 *   PRIORITY 2 → "ALERT: OUTNUMBERED — REPOSITION AND CALL FOR BACKUP!"
 *   PRIORITY 3 → "WARNING: ZONE COLLAPSING — MOVE TO SAFE ZONE NOW!"
 *   PRIORITY 4 → "CAUTION: LOW AMMO — FIND SUPPLIES!"
 *   PRIORITY 5 → "STATUS: CLEAR — ADVANCE AND LOOT."
 */
static const char* evaluate_tactical_state(const char* json_state) {
    if (!json_state || json_state[0] == '\0') {
        LOGE("evaluate_tactical_state: received null or empty JSON string");
        return "ERROR: INVALID STATE INPUT";
    }

    // Parse game-state fields
    int  health          = json_get_int (json_state, "health",          100);
    int  enemies         = json_get_int (json_state, "enemies",           0);
    bool zone_collapsing = json_get_bool(json_state, "zone_collapsing", false);
    int  ammo            = json_get_int (json_state, "ammo",            100);
    int  teammates_alive = json_get_int (json_state, "teammates_alive",   1);

    LOGI("State → health=%d, enemies=%d, zone_collapsing=%s, ammo=%d, teammates=%d",
         health, enemies, zone_collapsing ? "true" : "false", ammo, teammates_alive);

    // ── Priority 1: Critical Health ─────────────────────────────────────────
    if (health <= 20) {
        LOGI("Triggering: CRITICAL HEALTH");
        return "ALERT: CRITICAL HEALTH — RETREAT IMMEDIATELY!";
    }

    // ── Priority 2: Outnumbered ──────────────────────────────────────────────
    // Outnumbered = enemies outnumber the whole surviving squad by more than 1
    if (enemies >= 2 && enemies > (teammates_alive + 1)) {
        LOGI("Triggering: OUTNUMBERED");
        return "ALERT: OUTNUMBERED — REPOSITION AND CALL FOR BACKUP!";
    }

    // ── Priority 3: Zone Collapsing ──────────────────────────────────────────
    if (zone_collapsing) {
        LOGI("Triggering: ZONE COLLAPSING");
        return "WARNING: ZONE COLLAPSING — MOVE TO SAFE ZONE NOW!";
    }

    // ── Priority 4: Low Ammo ─────────────────────────────────────────────────
    if (ammo <= 10) {
        LOGI("Triggering: LOW AMMO");
        return "CAUTION: LOW AMMO — FIND SUPPLIES!";
    }

    // ── Priority 5: All Clear ────────────────────────────────────────────────
    LOGI("Triggering: ALL CLEAR");
    return "STATUS: CLEAR — ADVANCE AND LOOT.";
}

// =============================================================================
// JNI Bridge — exposes the engine to Kotlin/Java
//
// JNI method name convention:
//   Java_<package_underscored>_<class>_<method>
//
// Kotlin declaration (in MainActivity.kt):
//   external fun getTacticalAlert(jsonState: String): String
// =============================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_MainActivity_getTacticalAlert(
        JNIEnv* env,
        jobject /* this */,
        jstring json_state_jstr) {

    // Convert the Kotlin/Java String to a C string
    const char* json_state_cstr = env->GetStringUTFChars(json_state_jstr, nullptr);
    if (!json_state_cstr) {
        LOGE("JNI: GetStringUTFChars returned null");
        return env->NewStringUTF("ERROR: JNI STRING CONVERSION FAILED");
    }

    // Run the deterministic engine
    const char* result = evaluate_tactical_state(json_state_cstr);

    // Release the C string — MUST be called before returning
    env->ReleaseStringUTFChars(json_state_jstr, json_state_cstr);

    // Return the result as a Java/Kotlin String
    return env->NewStringUTF(result);
}
