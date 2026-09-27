package com.wledclimb.app.storage

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Why a file could not be read as a route backup. */
enum class RouteBackupProblem {
    /** Not JSON at all - the wrong file was picked. */
    Unreadable,

    /** JSON, but not something this app wrote. */
    NotABackup,

    /** Written by a later version of the app than this one. */
    TooNew,

    /** A backup of a different wall. */
    WrongWall,

    /** Structurally fine, with nothing in it. */
    Empty
}

class RouteBackupException(val problem: RouteBackupProblem) : Exception(problem.name)

/** A route as it travels between installs: no ids, because those are local. */
data class BackupRoute(
    val name: String,
    val holds: String,
    val wallFingerprint: String,
    val readOnly: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** A decoded backup file: which wall it came from, and what was on it. */
data class RouteBackupFile(
    val wallName: String,
    val controllerMac: String,
    val width: Int,
    val height: Int,
    val routes: List<BackupRoute>
)

/**
 * Reads and writes the route backup file.
 *
 * Exists because a route lives in an app's private database, which nothing
 * outside that app can reach - not another install of this app, not a file
 * manager, not adb on an unrooted phone. Routes represent real effort on a real
 * wall, and until this existed the only copy of that effort was inside one
 * installed app, lost to a reinstall or a new phone.
 *
 * Holds travel in exactly the text the database stores: `x,y:SLOT`, grid
 * coordinates and palette *positions*. That is what makes a backup portable
 * rather than a snapshot - see [RouteHolds]. Coordinates survive a wall being
 * rebuilt from a different gap file, and slots survive the palette being
 * retuned or replaced, so a file written today still means something after
 * either has changed.
 *
 * Deliberately absent: row ids and `wallId`. They identify rows in one
 * database and mean nothing in another, and carrying them would invite an
 * import to overwrite by id - which is precisely the mistake that turns a
 * restore into data loss.
 *
 * JSON rather than the database file itself. A database file carries Room's
 * schema identity hash, so restoring one into an app built from different
 * source fails in a way that reads as "routes cannot be saved for this wall" -
 * and it would replace everything rather than merge. JSON is also legible,
 * which matters for a format whose whole job is to still be readable later.
 */
object RouteBackup {

    private const val FORMAT = "wled-climb-routes"

    /**
     * The format version this app writes. Read alongside [FORMAT] so a newer
     * file is refused with something to show someone rather than being parsed
     * into whatever fields happen to match.
     */
    const val VERSION = 1

    fun encode(wall: StoredWall, routes: List<StoredRoute>, exportedAt: Long): String {
        val document = JSONObject()
        document.put("format", FORMAT)
        document.put("version", VERSION)
        document.put("exportedAt", exportedAt)

        // The wall is identity, not decoration: an import checks the MAC before
        // putting positions from one wall onto another.
        document.put(
            "wall",
            JSONObject().apply {
                put("name", wall.name)
                put("controllerMac", wall.controllerMac)
                put("width", wall.width)
                put("height", wall.height)
            }
        )

        val array = JSONArray()
        for (route in routes) {
            array.put(
                JSONObject().apply {
                    put("name", route.name)
                    put("holds", route.holds)
                    put("wallFingerprint", route.wallFingerprint)
                    put("readOnly", route.readOnly)
                    put("createdAt", route.createdAt)
                    put("updatedAt", route.updatedAt)
                }
            )
        }
        document.put("routes", array)

        // Indented. The file is small, and being able to read it in a text
        // editor is most of the point of choosing JSON.
        return document.toString(2)
    }

    /** @throws RouteBackupException when [text] is not a backup this app can read. */
    fun decode(text: String): RouteBackupFile {
        val document = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw RouteBackupException(RouteBackupProblem.Unreadable)
        }

        if (document.optString("format") != FORMAT) {
            throw RouteBackupException(RouteBackupProblem.NotABackup)
        }
        if (document.optInt("version", Int.MAX_VALUE) > VERSION) {
            throw RouteBackupException(RouteBackupProblem.TooNew)
        }

        val wall = document.optJSONObject("wall")
            ?: throw RouteBackupException(RouteBackupProblem.NotABackup)
        val mac = wall.optString("controllerMac")
        if (mac.isBlank()) throw RouteBackupException(RouteBackupProblem.NotABackup)

        val array = document.optJSONArray("routes")
            ?: throw RouteBackupException(RouteBackupProblem.NotABackup)

        val routes = mutableListOf<BackupRoute>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val name = entry.optString("name")
            // A route with no name cannot be shown in a list or told apart from
            // its neighbours, so it is skipped rather than imported nameless.
            // One bad entry does not cost the whole file.
            if (name.isBlank()) continue
            routes += BackupRoute(
                name = name,
                holds = entry.optString("holds"),
                wallFingerprint = entry.optString("wallFingerprint"),
                readOnly = entry.optBoolean("readOnly", false),
                createdAt = entry.optLong("createdAt"),
                updatedAt = entry.optLong("updatedAt")
            )
        }
        if (routes.isEmpty()) throw RouteBackupException(RouteBackupProblem.Empty)

        return RouteBackupFile(
            wallName = wall.optString("name"),
            controllerMac = mac,
            width = wall.optInt("width"),
            height = wall.optInt("height"),
            routes = routes
        )
    }
}
