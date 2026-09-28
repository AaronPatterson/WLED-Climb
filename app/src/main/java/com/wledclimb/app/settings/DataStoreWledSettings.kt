package com.wledclimb.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wled_settings")
private val wledIpKey = stringPreferencesKey("wled_ip")
private val lastWallIdKey = longPreferencesKey("last_wall_id")

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
}
