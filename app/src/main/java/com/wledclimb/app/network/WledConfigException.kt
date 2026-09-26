package com.wledclimb.app.network

/**
 * The controller answered, but its configuration isn't a usable 2D matrix -
 * e.g. WLED is set up as a plain 1D strip, or the device isn't WLED at all.
 *
 * Distinct from an [java.io.IOException] so callers can tell "couldn't reach
 * the controller" (check the network) apart from "reached it, but it isn't
 * set up as a wall" (check WLED's 2D config) - the two need very different
 * things from the user.
 */
class WledConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)
