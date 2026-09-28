package com.wledclimb.app.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Opening a database file this build cannot read.
 *
 * Not hypothetical: 0.11.0 shipped with `allowBackup` and no rules, so Android
 * restored a database written against an in-development schema onto fresh
 * installs. Room refused to open it, the wall could not be stored, and route
 * saving was silently dead with no way out from inside the app.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun databaseFile() = context.getDatabasePath(ClimbDatabase.DATABASE_NAME)

    private fun setAsideFiles(): List<File> =
        databaseFile().parentFile?.listFiles().orEmpty()
            .filter { it.name.startsWith("unreadable-") }

    @Before
    @After
    fun clean() {
        databaseFile().parentFile?.listFiles().orEmpty().forEach { it.delete() }
    }

    /**
     * A database at the current version whose schema identity is not this
     * build's - the shape a restore from an older build arrives in.
     */
    private fun writeForeignDatabase(marker: String) {
        val file = databaseFile()
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf("0cc84865ec8054724175e4cd23b69ced")
            )
            // Something recognisable, to prove the file kept is this one.
            db.execSQL("CREATE TABLE keepsake (note TEXT)")
            db.execSQL("INSERT INTO keepsake (note) VALUES (?)", arrayOf(marker))
            db.version = 1
        }
    }

    @Test
    fun `a database this build cannot read does not stop the app saving`() = runBlocking {
        writeForeignDatabase(marker = "from an older build")

        val database = ClimbDatabase.open(context)

        // The thing that was broken: storing the wall, without which wallId is
        // null and routes cannot be saved at all.
        val id = database.walls().insert(
            StoredWall(
                name = "Climbing Wall",
                controllerMac = "b0cbd8e23458",
                controllerAddress = "http://192.168.30.49",
                width = 2,
                height = 2,
                holdGrid = "1111"
            )
        )
        assertTrue("expected a stored wall", id > 0)
        database.close()
    }

    @Test
    fun `the unreadable file is kept, not deleted`() = runBlocking {
        // Routes are hours of someone's effort. A missed version bump must not
        // be able to destroy them, even when it makes them unreachable.
        writeForeignDatabase(marker = "from an older build")

        ClimbDatabase.open(context).close()

        val kept = setAsideFiles()
        assertEquals(1, kept.count { it.name.endsWith(ClimbDatabase.DATABASE_NAME) })
        val salvaged = kept.first { it.name.endsWith(ClimbDatabase.DATABASE_NAME) }
        SQLiteDatabase.openDatabase(salvaged.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            old.rawQuery("SELECT note FROM keepsake", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("from an older build", row.getString(0))
            }
        }
    }

    @Test
    fun `a database this build can read is left alone`() = runBlocking {
        val first = ClimbDatabase.open(context)
        first.walls().insert(
            StoredWall(
                name = "Climbing Wall",
                controllerMac = "b0cbd8e23458",
                controllerAddress = "http://192.168.30.49",
                width = 2,
                height = 2,
                holdGrid = "1111"
            )
        )
        first.close()

        val second = ClimbDatabase.open(context)

        assertEquals("Climbing Wall", second.walls().byMac("b0cbd8e23458")?.name)
        assertTrue("nothing should have been set aside", setAsideFiles().isEmpty())
        second.close()
    }
}
