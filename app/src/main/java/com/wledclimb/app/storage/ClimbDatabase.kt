package com.wledclimb.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.wledclimb.app.BuildConfig

/**
 * Saved walls and the routes drawn on them.
 *
 * Version 1 is still the first schema, because none has ever been released -
 * 0.9.0 shipped without anything that opens this database. Columns added
 * before that point are folded into version 1 rather than migrated to, which
 * keeps the first published schema whole instead of arriving with a migration
 * from a version nobody ever had.
 *
 * That stops being true with the first release that writes here. From then on
 * a changed entity needs a version bump and a Migration, or Room's identity
 * hash disagrees with the file on disk, the open throws, and every saved route
 * becomes unreachable.
 *
 * Foreign keys are enabled explicitly. Room declares the constraint but SQLite
 * ignores foreign keys unless the pragma is set per connection, so without
 * this the cascade that removes a wall's routes would quietly not happen.
 */
@Database(
    entities = [StoredWall::class, StoredRoute::class],
    version = 1,
    exportSchema = true
)
abstract class ClimbDatabase : RoomDatabase() {

    abstract fun walls(): WallDao
    abstract fun routes(): RouteDao

    companion object {
        @Volatile
        private var instance: ClimbDatabase? = null

        /**
         * The one database for the process.
         *
         * Room tolerates several instances over the same file but each opens
         * its own connection and keeps its own invalidation tracker, so a write
         * through one would not wake a Flow collected from another - queries
         * that simply never re-emit, which is a miserable thing to debug.
         */
        fun instance(context: Context): ClimbDatabase =
            instance ?: synchronized(this) {
                instance ?: open(context).also { instance = it }
            }

        fun open(context: Context): ClimbDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ClimbDatabase::class.java,
                "climb.db"
            )
                .apply {
                    // Debug builds throw the database away when the schema
                    // changes instead of refusing to open. On a development
                    // phone the schema moves whenever a branch is rebuilt, and
                    // Room's answer to a mismatch is to fail the open - which
                    // this app reports, correctly but unhelpfully, as routes
                    // not being saveable for this wall. That has now cost two
                    // rounds of confusion, and it is never the interesting
                    // failure on a device whose data is a handful of test
                    // routes.
                    //
                    // Release builds keep the refusal. Silently deleting
                    // someone's routes because a version number was missed is
                    // a far worse outcome than an app that will not start
                    // saving until the migration is written.
                    if (BuildConfig.DEBUG) fallbackToDestructiveMigration(dropAllTables = true)
                }
                .build()
    }
}
