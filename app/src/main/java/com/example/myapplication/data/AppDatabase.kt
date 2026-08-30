package com.example.myapplication.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// =============================================================================
// AppDatabase — Room SQLite database
//
// Single-instance (double-checked locking) to avoid multiple open connections.
// Uses fallbackToDestructiveMigration so a schema bump during development
// simply wipes and recreates the DB rather than crashing.
// =============================================================================

@Database(
    entities      = [MatchEvent::class],
    version       = 1,
    exportSchema  = false          // Disable schema export for this prototype
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun matchEventDao(): MatchEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton database instance, creating it on first call.
         * Thread-safe via double-checked locking on [INSTANCE].
         */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tactical_copilot.db"
                )
                .fallbackToDestructiveMigration()   // safe for development
                .build()
                .also { INSTANCE = it }
            }
    }
}
