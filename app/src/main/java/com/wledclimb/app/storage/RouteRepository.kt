package com.wledclimb.app.storage

import com.wledclimb.app.grid.Wall
import com.wledclimb.app.grid.fingerprint
import com.wledclimb.app.palette.HoldColor
import kotlinx.coroutines.flow.Flow

/**
 * What an import did. [added] counts rows written, of which [renamed] had to be
 * given a different name to avoid colliding with a route already here;
 * [skipped] were already present, identically, and were left alone.
 */
data class RouteImport(val added: Int, val skipped: Int, val renamed: Int)

/**
 * Saved routes for a wall.
 *
 * Translates between the two ways a route is written down. The app and the
 * wire both work in segment indices; storage works in grid coordinates,
 * because an index only means something beside the width it was computed with
 * (see [com.wledclimb.app.grid.GridPosition]). This class is that boundary,
 * and it is the only place that needs to know both.
 *
 * [now] is injected so tests can assert on timestamps without sleeping.
 */
class RouteRepository(
    private val routes: RouteDao,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun forWall(wallId: Long): Flow<List<StoredRoute>> = routes.forWall(wallId)

    suspend fun byId(id: Long): StoredRoute? = routes.byId(id)

    /**
     * Writes [holds] as a route, creating one when [routeId] is null and
     * overwriting that route when it is not.
     *
     * The wall's fingerprint is recorded as it is now, not as it was when the
     * route was first created: saving is the moment the route is known to
     * match the wall in front of it.
     */
    suspend fun save(
        wallId: Long,
        name: String,
        holds: Map<Int, HoldColor>,
        wall: Wall,
        routeId: Long? = null
    ): Long {
        val stored = RouteHolds.serializeSegments(holds, wall)
        val timestamp = now()

        val existing = routeId?.let { routes.byId(it) }
        if (existing == null) {
            return routes.insert(
                StoredRoute(
                    wallId = wallId,
                    name = name,
                    holds = stored,
                    wallFingerprint = wall.fingerprint,
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
            )
        }

        routes.update(
            existing.copy(
                name = name,
                holds = stored,
                wallFingerprint = wall.fingerprint,
                updatedAt = timestamp
            )
        )
        return existing.id
    }

    /**
     * The route's holds as segment indices, ready to show and push.
     *
     * Holds at positions the wall no longer has are dropped rather than
     * carried through: the wall cannot light a hold that is not there, and
     * pushing an index outside the grid would be a request WLED has no answer
     * for. The route itself is left alone, so the missing holds come back if
     * the wall does - losing a route to a gap-file edit would be far worse
     * than showing one with holes in it.
     */
    suspend fun load(id: Long, wall: Wall): Map<Int, HoldColor>? {
        val route = routes.byId(id) ?: return null
        return RouteHolds.parseSegments(route.holds, wall)
    }

    suspend fun rename(id: Long, name: String) {
        val route = routes.byId(id) ?: return
        routes.update(route.copy(name = name, updatedAt = now()))
    }

    suspend fun delete(id: Long) = routes.delete(id)

    /** This wall's routes as a backup file - see [RouteBackup]. */
    suspend fun backup(wall: StoredWall, exportedAt: Long = now()): String =
        RouteBackup.encode(wall, routes.listFor(wall.id), exportedAt)

    /**
     * Adds the routes in [file] to [wall], and never removes or overwrites one.
     *
     * Merging rather than replacing, because the usual reason to restore is
     * that routes exist in two places and both are wanted - moving off an old
     * install, or pulling in what someone built on another phone. A restore
     * that replaced would make "import the wrong file" cost every route on the
     * wall, and there is no undo here.
     *
     * So nothing is destroyed, and the three cases are:
     *
     * - A route already here under the same name with the same holds is the
     *   same route, imported twice. Skipped, which is what makes importing the
     *   same file again harmless.
     * - The same name with *different* holds is a different route that happens
     *   to share a name - the file's copy comes in under a numbered name rather
     *   than overwriting what is here or hiding behind a duplicate name.
     * - Anything else is simply added.
     *
     * Refuses a file from another wall outright. Holds are positions on a
     * specific wall, so restoring one wall's routes onto another would produce
     * routes that are wrong rather than routes that are stale, and the app has
     * no way to tell the difference afterwards.
     *
     * [currentFingerprint] is only a fallback for a route whose own fingerprint
     * is missing: trusting the file's value is what lets an imported route
     * correctly show as stale when it was built against a wall shape that has
     * since changed.
     */
    suspend fun importInto(
        wall: StoredWall,
        file: RouteBackupFile,
        currentFingerprint: String
    ): RouteImport {
        if (!file.controllerMac.equals(wall.controllerMac, ignoreCase = true)) {
            throw RouteBackupException(RouteBackupProblem.WrongWall)
        }

        val existing = routes.listFor(wall.id)
        val alreadyHere = existing.map { it.name to it.holds }.toSet()
        val namesTaken = existing.mapTo(mutableSetOf()) { it.name }
        val timestamp = now()

        var added = 0
        var skipped = 0
        var renamed = 0

        for (incoming in file.routes) {
            if ((incoming.name to incoming.holds) in alreadyHere) {
                skipped++
                continue
            }

            val name = freeName(incoming.name, namesTaken)
            if (name != incoming.name) renamed++
            namesTaken += name

            routes.insert(
                StoredRoute(
                    wallId = wall.id,
                    name = name,
                    holds = incoming.holds,
                    wallFingerprint = incoming.wallFingerprint.ifBlank { currentFingerprint },
                    readOnly = incoming.readOnly,
                    // A file written before timestamps were recorded, or hand
                    // edited, still imports - it just dates from now.
                    createdAt = incoming.createdAt.takeIf { it > 0 } ?: timestamp,
                    updatedAt = incoming.updatedAt.takeIf { it > 0 } ?: timestamp
                )
            )
            added++
        }

        return RouteImport(added = added, skipped = skipped, renamed = renamed)
    }

    /**
     * [desired], or it with a number appended until no route has that name.
     *
     * Numbered rather than suffixed with "imported": the second import of a
     * changed route would collide with the first one's suffix, and "Warmup (2)"
     * says which of two things it is where "Warmup (imported)" does not.
     */
    private fun freeName(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        var n = 2
        while ("$desired ($n)" in taken) n++
        return "$desired ($n)"
    }
}
