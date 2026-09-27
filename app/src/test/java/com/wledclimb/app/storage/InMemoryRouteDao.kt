package com.wledclimb.app.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [RouteDao] backed by a list, so callers can be tested without a database.
 * Room's own behaviour - the cascade, the Flow re-emitting - stays covered by
 * RouteDaoTest against real SQLite.
 */
class InMemoryRouteDao : RouteDao {

    private val rows = MutableStateFlow<List<StoredRoute>>(emptyList())
    private var nextId = 1L

    override suspend fun byId(id: Long): StoredRoute? = rows.value.firstOrNull { it.id == id }

    override fun forWall(wallId: Long): Flow<List<StoredRoute>> =
        rows.map { list -> list.filter { it.wallId == wallId }.sortedByDescending { it.updatedAt } }

    override suspend fun listFor(wallId: Long): List<StoredRoute> =
        rows.value.filter { it.wallId == wallId }.sortedBy { it.name }

    override suspend fun staleFor(wallId: Long, fingerprint: String): List<StoredRoute> =
        rows.value.filter { it.wallId == wallId && it.wallFingerprint != fingerprint }

    override suspend fun insert(route: StoredRoute): Long {
        val id = nextId++
        rows.value = rows.value + route.copy(id = id)
        return id
    }

    override suspend fun update(route: StoredRoute) {
        rows.value = rows.value.map { if (it.id == route.id) route else it }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
