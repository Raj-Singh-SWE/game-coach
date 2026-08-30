# =============================================================================
# CMakeLists.txt — "Yellow Light" Phase Native Build
#
# Compiles tactical_engine.cpp into a shared library (.so) that Android
# loads via System.loadLibrary("tactical_engine").
#
# The library is linked against:
#   - log  : Android logcat support (__android_log_print)
# =============================================================================

cmake_minimum_required(VERSION 3.22.1)

project(
    "tactical_engine"
    VERSION 1.0
    LANGUAGES CXX
)

# ── Compiler settings ─────────────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Enable aggressive optimisations in release builds for maximum determinism speed
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-O3 -ffast-math)
endif()

# ── Find system libraries ─────────────────────────────────────────────────────
find_library(
    LOG_LIB   # Variable to store the path
    log        # NDK log library (android/log.h)
)

# ── Build the shared library ──────────────────────────────────────────────────
add_library(
    tactical_engine   # Library name → libtactical_engine.so
    SHARED            # Shared (.so) — required for JNI on Android
    tactical_engine.cpp
)

# ── Include directories ───────────────────────────────────────────────────────
target_include_directories(
    tactical_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ── Link dependencies ─────────────────────────────────────────────────────────
target_link_libraries(
    tactical_engine
    ${LOG_LIB}   # -llog
)
