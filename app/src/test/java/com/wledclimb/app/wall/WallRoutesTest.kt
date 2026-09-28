// Every test here drives coroutines through a test dispatcher, and the whole
// of kotlinx-coroutines-test is marked experimental - runTest, runCurrent,
// UnconfinedTestDispatcher, setMain. There is no non-experimental way to write
// these tests, so the opt-in is an acknowledgement rather than a choice, and
// it belongs at the top of the file rather than repeated at every call site.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Saving, loading, renaming and deleting routes through the ViewModel.
 *
 * The fake controller serves a 2x2 wall, so segment indices run 0..3 and
 * index 3 is position (1,1).
 */
class WallRoutesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Fixture(val client: FakeWledClient = FakeWledClient(on = true)) {
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()
        val settings = FakeWledSettings()
        val viewModel = WallViewModel(
            client = client,
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = settings,
            controllerAddress = "http://wall.test"
        )
    }

    private fun connected(viewModel: WallViewModel): WallUiState.Connected =
        viewModel.uiState.value as? WallUiState.Connected
            ?: error("Expected Connected but was " + viewModel.uiState.value)

    @Test
    fun `the connected wall is stored so routes have something to hang off`() = runTest {
        val fixture = Fixture()
        runCurrent()

        assertNotNull(connected(fixture.viewModel).wallId)
    }

    @Test
    fun `the connected wall is remembered so it can be opened offline`() = runTest {
        // With no controller to ask for its MAC, this is the only way back to
        // the stored wall.
        val fixture = Fixture()
        runCurrent()

        assertEquals(connected(fixture.viewModel).wallId, fixture.settings.savedWallId)
    }

    @Test
    fun `a controller that cannot be reached changes nothing remembered`() = runTest {
        val fixture = Fixture(FakeWledClient(failWith = IOException("connect timed out")))
        runCurrent()

        assertNull(fixture.settings.savedWallId)
    }

    @Test
    fun `saving stores what is on the wall and selects the new route`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.selectColor(HoldColor.Blue)
        fixture.viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        fixture.viewModel.saveRoute("Traverse")
        runCurrent()

        val saved = fixture.viewModel.savedRoutes.value
        assertEquals(1, saved.size)
        assertEquals("Traverse", saved.single().name)
        // Position (1,1), and Blue by palette slot rather than by name.
        assertEquals("1,1:4", saved.single().holds)
        assertEquals(saved.single().id, connected(fixture.viewModel).selectedRouteId)
    }

    @Test
    fun `the selected route is remembered on the wall for next launch`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()

        fixture.viewModel.saveRoute("Traverse")
        runCurrent()

        val wallId = connected(fixture.viewModel).wallId!!
        val routeId = fixture.viewModel.savedRoutes.value.single().id
        assertEquals(routeId, fixture.wallDao.byId(wallId)?.lastSelectedRouteId)
    }

    @Test
    fun `loading a route shows it and pushes it to the wall`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.selectColor(HoldColor.Green)
        fixture.viewModel.toggleHold(segmentIndex = 2)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.clearWall()
        runCurrent()
        assertTrue(connected(fixture.viewModel).litHolds.isEmpty())

        fixture.viewModel.loadRoute(routeId)
        runCurrent()

        assertEquals(mapOf(2 to HoldColor.Green), connected(fixture.viewModel).litHolds)
        assertEquals(mapOf(2 to HoldColor.Green.hex), fixture.client.pushedHolds.last())
        assertEquals(routeId, connected(fixture.viewModel).selectedRouteId)
    }

    @Test
    fun `saving over a route replaces it rather than adding another`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.toggleHold(segmentIndex = 1)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse", routeId = routeId)
        runCurrent()

        val saved = fixture.viewModel.savedRoutes.value
        assertEquals(1, saved.size)
        assertEquals(routeId, saved.single().id)
        assertEquals("0,0:0;1,0:0", saved.single().holds)
    }

    @Test
    fun `renaming changes the name without touching the holds`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val before = fixture.viewModel.savedRoutes.value.single()

        fixture.viewModel.renameRoute(before.id, "Warm up")
        runCurrent()

        val after = fixture.viewModel.savedRoutes.value.single()
        assertEquals("Warm up", after.name)
        assertEquals(before.holds, after.holds)
    }

    @Test
    fun `deleting the selected route clears the selection`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.deleteRoute(routeId)
        runCurrent()

        assertTrue(fixture.viewModel.savedRoutes.value.isEmpty())
        assertNull(connected(fixture.viewModel).selectedRouteId)
    }

    @Test
    fun `deleting the open route takes its holds off the wall`() = runTest {
        // Leaving them up would strand holds belonging to a route that no
        // longer exists - unsaved work by definition, which would then offer
        // to be saved as a route that was just deliberately thrown away.
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.deleteRoute(routeId)
        runCurrent()

        assertTrue(connected(fixture.viewModel).litHolds.isEmpty())
        assertEquals(emptyMap<Int, String>(), fixture.client.pushedHolds.last())
        assertFalse(connected(fixture.viewModel).modified)
    }

    @Test
    fun `deleting the open route with unsaved edits still clears it`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.toggleHold(segmentIndex = 3)
        runCurrent()
        assertTrue(connected(fixture.viewModel).modified)

        fixture.viewModel.deleteRoute(routeId)
        runCurrent()

        assertTrue(connected(fixture.viewModel).litHolds.isEmpty())
        assertFalse(connected(fixture.viewModel).modified)
        assertNull(fixture.wallDao.byId(connected(fixture.viewModel).wallId!!)?.draftHolds)
    }

    @Test
    fun `deleting a route that is not open leaves the wall alone`() = runTest {
        // Removing something from a list is not a reason to change the wall.
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Keep")
        runCurrent()
        val keep = fixture.viewModel.savedRoutes.value.single().id

        fixture.viewModel.toggleHold(segmentIndex = 3)
        runCurrent()
        fixture.viewModel.saveRoute("Other", routeId = null)
        runCurrent()
        val other = fixture.viewModel.savedRoutes.value.first { it.id != keep }.id
        fixture.viewModel.loadRoute(keep)
        runCurrent()

        fixture.viewModel.deleteRoute(other)
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red), connected(fixture.viewModel).litHolds)
        assertEquals(keep, connected(fixture.viewModel).selectedRouteId)
    }

    @Test
    fun `a wall that could not be stored still lights holds, but saves nothing`() = runTest {
        // Losing the database costs saving routes. It must not cost the grid.
        val fixture = Fixture()
        fixture.wallDao.failWith = IllegalStateException("database unavailable")
        fixture.viewModel.refresh()
        runCurrent()

        assertNull(connected(fixture.viewModel).wallId)

        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        assertEquals(mapOf(0 to HoldColor.Red), connected(fixture.viewModel).litHolds)

        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        assertTrue(fixture.viewModel.savedRoutes.value.isEmpty())
    }

    @Test
    fun `reconnecting comes back to the route the wall was left on`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.selectColor(HoldColor.Green)
        fixture.viewModel.toggleHold(segmentIndex = 2)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id

        // Stands in for the app being reopened: same stores, fresh ViewModel.
        val reopened = WallViewModel(
            client = fixture.client,
            walls = WallRepository(fixture.wallDao),
            routes = RouteRepository(fixture.routeDao) { 1000L },
            settings = fixture.settings,
            controllerAddress = "http://wall.test"
        )
        runCurrent()

        assertEquals(routeId, connected(reopened).selectedRouteId)
        assertEquals(mapOf(2 to HoldColor.Green), connected(reopened).litHolds)
    }

    @Test
    fun `the restored route is pushed, not just displayed`() = runTest {
        // The app cannot read the wall back - WLED answers /json/live with 501
        // - so what it shows has to be what it sent, or the two can disagree
        // with nothing to notice it.
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 1)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()

        val pushesBefore = fixture.client.pushedHolds.size
        WallViewModel(
            client = fixture.client,
            walls = WallRepository(fixture.wallDao),
            routes = RouteRepository(fixture.routeDao) { 1000L },
            settings = fixture.settings,
            controllerAddress = "http://wall.test"
        )
        runCurrent()

        assertTrue(fixture.client.pushedHolds.size > pushesBefore)
        assertEquals(mapOf(1 to HoldColor.Red.hex), fixture.client.pushedHolds.last())
    }

    @Test
    fun `a wall with no route selected starts blank`() = runTest {
        val fixture = Fixture()
        runCurrent()

        assertNull(connected(fixture.viewModel).selectedRouteId)
        assertTrue(connected(fixture.viewModel).litHolds.isEmpty())
    }

    @Test
    fun `a selected route that has since been deleted is ignored`() = runTest {
        // Deleting clears the selection, but a route can also vanish from
        // another device. Coming back to a missing route must not blank the
        // connect.
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        fixture.viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = fixture.viewModel.savedRoutes.value.single().id
        fixture.routeDao.delete(routeId)

        val reopened = WallViewModel(
            client = fixture.client,
            walls = WallRepository(fixture.wallDao),
            routes = RouteRepository(fixture.routeDao) { 1000L },
            settings = fixture.settings,
            controllerAddress = "http://wall.test"
        )
        runCurrent()

        assertTrue(connected(reopened).litHolds.isEmpty())
    }
}
