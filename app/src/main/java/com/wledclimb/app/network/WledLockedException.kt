package com.wledclimb.app.network

/**
 * The controller refused to change a file because its settings are locked
 * with a PIN.
 *
 * WLED answers 401 to a file upload until the settings PIN has been entered,
 * and once it has, it stays unlocked for every client for a while - so the way
 * out is to unlock it in WLED's own settings page and try again. Its own type
 * rather than an [java.io.IOException] for the same reason as
 * [WledConfigException]: "check the network" would be the wrong advice for a
 * controller that answered perfectly clearly.
 */
class WledLockedException(message: String) : Exception(message)
