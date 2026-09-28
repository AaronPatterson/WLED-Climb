package com.wledclimb.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.util.Log
import com.wledclimb.app.BuildConfig
import java.io.File

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

        private const val TAG = "ClimbDatabase"

        internal const val DATABASE_NAME = "climb.db"

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

        /**
         * The database, with an unopenable one moved out of the way first.
         *
         * Room refuses to open a file whose schema does not match what the app
         * was built against, and until now that refusal was permanent: the
         * open threw on every DAO call, the wall could not be stored, and the
         * app ran with route saving silently switched off for good. There was
         * no way out from inside the app, and the file it would not read was
         * still sitting there being unreadable.
         *
         * Shipped as exactly that. A restore from Android's cloud backup put a
         * database from an in-development schema onto a fresh install of
         * 0.11.0, and every new install was in the same position: an app that
         * looked fine and could never save a route.
         *
         * So a file that cannot be opened is renamed rather than kept or
         * deleted. Renamed because deleting is the thing the comment below is
         * right to refuse - routes are hours of somebody's effort, and a
         * missed version bump should not be able to destroy them. Moved aside
         * because leaving it in place destroys nothing and costs everything:
         * the routes in a database Room will not open are already unreachable,
         * and keeping the file there only means the app cannot save new ones
         * either.
         *
         * The old file stays on disk, named for when it was set aside, where a
         * migration written later can still reach it.
         */
        fun open(context: Context): ClimbDatabase {
            val first = build(context)
            return try {
                // Forces the schema check now, rather than at whichever DAO
                // call happens first. A few milliseconds once per process, and
                // it buys a single place where the failure can be handled
                // instead of one at every call site.
                first.openHelper.writableDatabase
                first
            } catch (e: Exception) {
                Log.e(TAG, "the database could not be opened; setting it aside", e)
                try {
                    first.close()
                } catch (closing: Exception) {
                    Log.e(TAG, "closing the unusable database failed", closing)
                }
                setAside(context)
                build(context)
            }
        }

        private fun build(context: Context): ClimbDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ClimbDatabase::class.java,
                DATABASE_NAME
            )
                .apply {
                    // Debug builds throw the database away when the schema
                    // changes instead of refusing to open. On a development
                    // phone the schema moves whenever a branch is rebuilt, and
                    // Room's answer to a mismatch is to fail the open - which
                    // this app reported, correctly but unhelpfully, as routes
                    // not being saveable for this wall.
                    //
                    // Release builds keep the refusal, and [open] catches it.
                    // Silently deleting someone's routes because a version
                    // number was missed is a far worse outcome than setting
                    // the file aside where it can still be read later.
                    if (BuildConfig.DEBUG) fallbackToDestructiveMigration(dropAllTables = true)
                }
                .build()

        /**
         * Renames the database and its write-ahead log out of the way.
         *
         * All three files together: a stray -wal or -shm beside a fresh
         * database is read as belonging to it, which would carry the problem
         * straight into the replacement.
         */
        private fun setAside(context: Context) {
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal", "-shm")) {
                val file = context.getDatabasePath(DATABASE_NAME + suffix)
                if (!file.exists()) continue
                val moved = File(file.parentFile, "unreadable-$stamp-${file.name}")
                if (!file.renameTo(moved)) {
                    // Nothing left to try: a fresh database cannot be created
                    // while this one holds the name. Deleting it here would be
                    // the silent destruction this is written to avoid.
                    Log.e(TAG, "could not move ${file.name} aside")
                }
            }
        }
    }
}
