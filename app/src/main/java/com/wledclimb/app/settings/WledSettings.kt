package com.wledclimb.app.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persists the WLED controller's address across app restarts.
 *
 * An interface (rather than the DataStore class directly) so ViewModels that
 * depend on it can be unit tested - DataStore needs a real Android `Context`,
 * which isn't available in a local JVM test.
 */
interface WledSettings {

    /** The saved controller address, or null if setup hasn't been completed yet. */
    val wledIp: Flow<String?>

    suspend fun saveWledIp(ip: String)

    /**
     * The stored wall this device last reached. Null before any controller
     * has answered.
     *
     * Kept when setup saves a different address, and replaced only when a
     * controller answers as a different wall. A new address is often the same
     * controller after its lease moved, and the wall that answers - identified
     * by its MAC - is what decides, not the address it was typed in as.
     *
     * This is what lets the app open a wall with no controller in reach. A
     * wall is identified by the controller's MAC, and the MAC can only be
     * asked for - so without a record of which wall this device last reached,
     * there is nothing to look the stored wall up by. The address is not
     * used for that: it is exactly what DHCP hands to a different controller,
     * and identity is the one thing it is not.
     */
    val lastWallId: Flow<Long?>

    suspend fun saveLastWallId(id: Long?)

    /**
     * Whether this device has been told to leave the controller alone - to
     * work offline with the controller in reach, so a route can be built
     * while someone climbs the one on the wall.
     *
     * Persisted rather than held in memory because the app being killed and
     * relaunched must not end it. A relaunch that reconnected would put the
     * half-built route on the wall at the next tap, which is precisely what
     * pausing exists to prevent.
     */
    val paused: Flow<Boolean>

    suspend fun savePaused(paused: Boolean)
}
