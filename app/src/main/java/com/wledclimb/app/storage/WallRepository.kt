package com.wledclimb.app.storage

import com.wledclimb.app.grid.Wall

/**
 * The stored wall behind a controller, created the first time one is reached.
 *
 * Routes hang off a wall row, so something has to exist before a route can be
 * saved. That happens on connect rather than on first save: a wall discovered
 * by talking to the controller is a fact, and waiting until someone saves a
 * route would mean doing this work in the middle of the one action that must
 * not fail.
 *
 * **Walls are identified by the controller's MAC**, which WLED reports as
 * `mac` on `/json/info` and takes from the chip's eFuse. It survives reboots,
 * firmware updates, renaming, and a DHCP lease putting the controller on a new
 * address - which the address itself plainly does not. The address is still
 * stored, and updated whenever the controller turns up somewhere new, so a
 * wall can be reached without rediscovery.
 *
 * There is no second way to find a wall. A controller that reports no MAC is
 * refused on connect, so the MAC is always there to look up by - and matching
 * on an address instead would hand one wall's routes to whichever controller
 * DHCP put at that address next.
 *
 * The database enforces one row per MAC. Two rows for one controller would
 * split a wall's routes across both, and whichever the app found first would
 * look like it had lost half of them - so that is a constraint rather than
 * something this class is merely careful about.
 *
 * Takes the controller's MAC and name as plain values rather than the type the
 * network layer parses them into. Storage has no business knowing the wire
 * format exists, and the two facts it actually needs are a string and a
 * nullable string.
 *
 * Shape and name are refreshed from the controller on every connect, because
 * the controller is the authority on both. [StoredWall.lastSelectedRouteId] is
 * deliberately preserved: it is the app's own state, not the controller's.
 */
class WallRepository(private val walls: WallDao) {

    /**
     * The stored wall behind an id the UI already holds. Needed by anything
     * that has to name the wall rather than merely write to it - a backup
     * carries the controller MAC so it can only be restored onto the same wall.
     */
    suspend fun byId(id: Long): StoredWall? = walls.byId(id)

    /** Records which route this wall was last showing, so it can be reselected. */
    suspend fun selectRoute(wallId: Long, routeId: Long?) =
        walls.setLastSelectedRoute(wallId, routeId)

    /**
     * Records unsaved work, or clears it with null once the wall matches a
     * saved route again.
     */
    suspend fun saveDraft(wallId: Long, draftHolds: String?) =
        walls.setDraft(wallId, draftHolds)

    suspend fun findOrCreate(
        controllerMac: String,
        name: String,
        controllerAddress: String,
        wall: Wall
    ): StoredWall {
        val existing = walls.byMac(controllerMac)

        if (existing == null) {
            val fresh = StoredWall(
                name = name,
                controllerMac = controllerMac,
                controllerAddress = controllerAddress,
                width = wall.width,
                height = wall.height,
                holdGrid = HoldGrid.serialize(wall)
            )
            return fresh.copy(id = walls.insert(fresh))
        }

        val refreshed = existing.copy(
            name = name,
            // Moves the address when the controller turns up somewhere new.
            // The MAC cannot have changed - it is what found this row.
            controllerAddress = controllerAddress,
            width = wall.width,
            height = wall.height,
            holdGrid = HoldGrid.serialize(wall)
        )
        // Only when something actually moved. A write per connect would churn
        // the database for nothing and, once routes are listed from a Flow,
        // re-emit the whole list every time the app reconnects.
        if (refreshed != existing) walls.update(refreshed)
        return refreshed
    }
}
