package com.wledclimb.app.wall

/**
 * Whether the wall's controller can be told anything, alongside a wall that is
 * on screen either way.
 *
 * Power and brightness live here rather than beside the route, because they
 * are facts about the controller and there are none to report without one. A
 * last-known "on" shown for a wall nobody can reach would be a guess dressed
 * as an answer.
 */
sealed interface ControllerState {

    /** Being asked. The wall is on screen from storage meanwhile. */
    data object Connecting : ControllerState

    /**
     * Answering. [brightness] is WLED's master brightness and is independent
     * of [on]: switching the wall off leaves it where it was, so both are
     * needed to describe the wall rather than either alone.
     *
     * [busy] is a power change in flight.
     */
    data class Online(
        val on: Boolean,
        val brightness: Int,
        val busy: Boolean = false
    ) : ControllerState

    /**
     * Out of reach, or answering in a way the app cannot use. Routes can
     * still be opened, edited and saved; nothing reaches the wall.
     *
     * The app does not try again by itself. A controller that reappears has
     * whatever it had, and deciding to talk to it again is left to whoever is
     * holding the device.
     */
    data class Offline(val problem: WallProblem) : ControllerState

    /**
     * Offline by choice: the controller may well be in reach, and this device
     * has been told to leave it alone. Everything [Offline] allows, and for
     * the same reason nothing reaches the wall - here, so that a route can be
     * built while someone climbs the one already up.
     *
     * A state of its own rather than an [Offline] with a reason, because it
     * asks something different of whoever is looking: this is waiting for
     * them, where [Offline] is waiting for the controller.
     */
    data object Paused : ControllerState
}
