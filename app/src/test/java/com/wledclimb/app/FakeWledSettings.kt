package com.wledclimb.app

import com.wledclimb.app.settings.WledSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [WledSettings], so tests don't need DataStore or a Context. */
class FakeWledSettings(initialIp: String? = null, initialWallId: Long? = null) : WledSettings {

    private val saved = MutableStateFlow(initialIp)
    private val savedWall = MutableStateFlow(initialWallId)

    override val wledIp: Flow<String?> = saved

    /** What's currently persisted - null when setup has never succeeded. */
    val savedIp: String? get() = saved.value

    override suspend fun saveWledIp(ip: String) {
        saved.value = ip
    }

    override val lastWallId: Flow<Long?> = savedWall

    /** The wall last reached - null when none has been, or it was forgotten. */
    val savedWallId: Long? get() = savedWall.value

    override suspend fun saveLastWallId(id: Long?) {
        savedWall.value = id
    }
}
