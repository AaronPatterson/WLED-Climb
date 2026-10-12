// Same reason as the other ViewModel tests here: driving coroutines through a
// test dispatcher means the whole of kotlinx-coroutines-test, all experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.StoredRoute
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Two route operations asked for in one gesture finish in the order they were
 * asked for.
 *
 * The "save first?" prompt is the one place a single tap starts two of them:
 * it saves, and then performs whatever it interrupted. Both are coroutines and
 * both suspend part way through on the database, so when they interleaved the
 * second finished first and the first then undid it.
 *
 * **The database here is made to suspend on purpose.** [InMemoryRouteDao]
 * returns without ever suspending, so the first operation runs to completion
 * before the second is even started and the interleaving cannot happen. That
 * is not how Room behaves, and a test using the plain fake passes whether the
 * ordering is fixed or not - which is exactly the test that would have let
 * this ship.
 *
 * The fake controller serves a 2x2 wall, so segment indices run 0..3.
 */
class WallRouteOrderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * [InMemoryRouteDao] with a write that suspends, as a real one does.
     *
     * Only the writes: a read that suspends would be realistic too, but the
     * point here is the window a write leaves open, and widening it any
     * further would stop the test describing anything in particular.
     */
    private class SlowRouteDao(private val real: RouteDao = InMemoryRouteDao()) : RouteDao {

        override suspend fun byId(id: Long): StoredRoute? = real.byId(id)

        override fun forWall(wallId: Long): Flow<List<StoredRoute>> = real.forWall(wallId)

        override suspend fun listFor(wallId: Long): List<StoredRoute> = real.listFor(wallId)

        override suspend fun staleFor(wallId: Long, fingerprint: String): List<StoredRoute> =
            real.staleFor(wallId, fingerprint)

        override suspend fun insert(route: StoredRoute): Long {
            delay(10)
            return real.insert(route)
        }

        override suspend fun update(route: StoredRoute) {
            delay(10)
            real.update(route)
        }

        override suspend fun delete(id: Long) {
            delay(10)
            real.delete(id)
        }
    }

    private class Fixture {
        val wallDao = InMemoryWallDao()
        val routeDao = SlowRouteDao()
        val viewModel = WallViewModel(
            client = FakeWledClient(on = true),
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = FakeWledSettings(),
            controllerAddress = "http://wall.test"
        )
    }

    private fun ready(viewModel: WallViewModel): WallUiState.Ready =
        viewModel.uiState.value as? WallUiState.Ready
            ?: error("Expected Ready but was " + viewModel.uiState.value)

    @Test
    fun `saving and then starting a new route leaves the new one open`() = runTest {
        // Reported from a fresh install: build a route, tap New, choose to
        // save, give it a name - and the blank wall that followed was titled
        // with the name just given. The save finished after the new route had
        // cleared the selection, and selected the route it had written.
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.viewModel.selectColor(HoldColor.Blue)
        fixture.viewModel.toggleHold(segmentIndex = 3)
        advanceUntilIdle()

        // What the unsaved-changes prompt does when "save" is chosen with no
        // route open: save under the new name, then carry out what it
        // interrupted.
        fixture.viewModel.saveRoute("Warmup")
        fixture.viewModel.newRoute()
        advanceUntilIdle()

        val state = ready(fixture.viewModel)
        assertNull("the new route should be the open one", state.selectedRouteId)
        assertTrue("the wall should be blank", state.litHolds.isEmpty())
    }

    @Test
    fun `the route is still saved`() = runTest {
        // Ordering must not cost the save itself.
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.viewModel.selectColor(HoldColor.Blue)
        fixture.viewModel.toggleHold(segmentIndex = 3)
        advanceUntilIdle()

        fixture.viewModel.saveRoute("Warmup")
        fixture.viewModel.newRoute()
        advanceUntilIdle()

        assertEquals(listOf("Warmup"), fixture.viewModel.savedRoutes.value.map { it.name })
    }

    @Test
    fun `a blank route started after a save does not count as modified`() = runTest {
        // Whichever order they finish in, the baseline that "modified" is
        // judged against has to describe the route actually on the wall.
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.viewModel.selectColor(HoldColor.Blue)
        fixture.viewModel.toggleHold(segmentIndex = 3)
        advanceUntilIdle()

        fixture.viewModel.saveRoute("Warmup")
        fixture.viewModel.newRoute()
        advanceUntilIdle()

        assertEquals(false, ready(fixture.viewModel).modified)
    }

    @Test
    fun `saving and then opening another route leaves that one open`() = runTest {
        // The same prompt answered the other way: save, then open the route
        // that was being switched to.
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.viewModel.selectColor(HoldColor.Red)
        fixture.viewModel.toggleHold(segmentIndex = 0)
        fixture.viewModel.saveRoute("Traverse")
        advanceUntilIdle()
        val traverse = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.newRoute()
        advanceUntilIdle()
        fixture.viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        fixture.viewModel.saveRoute("Warmup")
        fixture.viewModel.loadRoute(traverse)
        advanceUntilIdle()

        assertEquals(traverse, ready(fixture.viewModel).selectedRouteId)
    }
}
