package com.wledclimb.app.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wled_settings")
private val wledIpKey = stringPreferencesKey("wled_ip")
private val lastWallIdKey = longPreferencesKey("last_wall_id")
private val pausedKey = booleanPreferencesKey("paused")

/** [WledSettings] backed by Jetpack DataStore. */
class DataStoreWledSettings(private val context: Context) : WledSettings {

    override val wledIp: Flow<String?> = context.dataStore.data.map { it[wledIpKey] }

    override suspend fun saveWledIp(ip: String) {
        context.dataStore.edit { it[wledIpKey] = ip }
    }

    override val lastWallId: Flow<Long?> = context.dataStore.data.map { it[lastWallIdKey] }

    override suspend fun saveLastWallId(id: Long?) {
        context.dataStore.edit {
            it.remove(lastWallIdKey)
            if (id != null) it[lastWallIdKey] = id
        }
    }

    override val paused: Flow<Boolean> = context.dataStore.data.map { it[pausedKey] ?: false }

    override suspend fun savePaused(paused: Boolean) {
        context.dataStore.edit { it[pausedKey] = paused }
    }

    /**
     * Forgets everything, as a fresh install would.
     *
     * For tests only. DataStore keeps one instance per file per process, and
     * Robolectric runs every test in one process, so without this the address
     * one test saved is already there when the next test starts - which is the
     * difference between testing a first run and testing a returning one.
     */
    @VisibleForTesting
    internal suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
