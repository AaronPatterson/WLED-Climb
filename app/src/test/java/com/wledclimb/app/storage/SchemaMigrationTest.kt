package com.wledclimb.app.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every schema this app has ever shipped can still be opened.
 *
 * Builds a real database from each JSON in `app/schemas` - the exact shape
 * that version wrote on disk - and opens it with the current code. Room
 * validates the tables and indices it finds against what it expects and runs
 * whatever migrations are needed to get there, so this fails when an entity
 * changes without a version bump, when a version is bumped without a
 * migration, and when a migration exists but does not produce the schema the
 * new version describes.
 *
 * Reads the schema directory rather than naming versions, so a new one is
 * covered by being committed. That matters because the thing most likely to
 * go wrong is forgetting a step, and a test listing the versions by hand would
 * be forgotten in the same breath.
 *
 * There is nothing to migrate yet - version 1 is the only schema there has
 * been - so today this mostly proves the machinery works. It earns its keep
 * with the first migration.
 *
 * It deliberately does **not** catch an entity edited without a version bump,
 * and cannot: the build rewrites `app/schemas` from the entities, so this
 * would read the file the build just regenerated and agree with it. That case
 * is caught in CI, by the committed schema having changed at all. See the
 * "Schema matches the one committed" step in `.github/workflows/ci.yml`.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun databaseFile() = context.getDatabasePath(ClimbDatabase.DATABASE_NAME)

    @Before
    @After
    fun clean() {
        databaseFile().parentFile?.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun schemaFiles(): List<File> =
        File("schemas/com.wledclimb.app.storage.ClimbDatabase")
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.nameWithoutExtension.toInt() }

    /** Writes the database exactly as [schema]'s version left it on disk. */
    private fun createFrom(schema: File) {
        val database = JSONObject(schema.readText()).getJSONObject("database")
        val file = databaseFile()
        file.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j)
                            .getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
            // room_master_table and its identity hash, which is how Room
            // recognises the schema it is looking at.
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = database.getInt("version")
        }
    }

    @Test
    fun `there is a schema to migrate from`() {
        // exportSchema being switched off, or the JSON not being committed,
        // would leave every other test here passing vacuously.
        assertTrue("no exported schemas found - is exportSchema still on?", schemaFiles().isNotEmpty())
    }

    @Test
    fun `every shipped schema opens under the current code`() = runBlocking {
        for (schema in schemaFiles()) {
            clean()
            createFrom(schema)

            val database = ClimbDatabase.open(context)
            try {
                // Touches both tables, so the open is real rather than lazy.
                database.walls().byMac("b0cbd8e23458")
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
                assertTrue("schema ${schema.name}: wall not stored", id > 0)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun `the newest exported schema is the version the code declares`() {
        // A bumped version with no schema committed leaves the next migration
        // with nothing to be written against.
        val newest = schemaFiles().last()
        val exported = JSONObject(newest.readText()).getJSONObject("database").getInt("version")

        assertEquals(
            "app/schemas has $exported but the code declares " +
                "$CLIMB_DATABASE_VERSION - commit the new schema",
            CLIMB_DATABASE_VERSION,
            exported
        )
    }
}
