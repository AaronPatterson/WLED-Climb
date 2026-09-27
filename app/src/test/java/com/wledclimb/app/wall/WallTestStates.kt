package com.wledclimb.app.wall

/** The controller's state, for tests that expect it to be answering. */
internal val WallUiState.Ready.online: ControllerState.Online
    get() = controller as? ControllerState.Online ?: error("Expected Online but was $controller")
