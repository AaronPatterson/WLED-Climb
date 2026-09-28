// Same reason as the other ViewModel tests here: driving coroutines through a
// test dispatcher means the whole of kotlinx-coroutines-test, all experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.storage.DemoWall
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The practice wall: a wall with no controller behind it. */
class WallDemoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Fixture(demo: Boolean = true) {
        val client = FakeWledClient(on = true)
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()
        val settings = FakeWledSettings()
        val viewModel = WallViewModel(
            client = client,
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = settings,
            controllerAddress = "http://practice.invalid",
            demo = demo
        )
    }

    private fun ready(viewModel: WallViewModel): WallUiState.Ready =
        viewModel.uiState.value as? WallUiState.Ready
            ?: error("Expected Ready but was " + viewModel.uiState.value)

    @Test
    fun `the practice wall opens without a controller`() = runTest {
        val fixture = Fixture()
        runCurrent()

        val state = ready(fixture.viewModel)
        assertEquals(ControllerState.Demo, state.controller)
        assertEquals(DemoWall.NAME, state.name)
        assertEquals(DemoWall.WIDTH, state.wall.width)
        assertEquals(DemoWall.HEIGHT, state.wall.height)
    }

    @Test
    fun `nothing is asked of the controller`() = runTest {
        // The whole point: a practice wall must work with nothing to talk to,
        // so a request here would be one that fails on a device with no wall.
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.selectColor(HoldColor.Blue)
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()

        assertEquals(0, fixture.client.getWallCount)
        assertTrue("nothing should have been pushed", fixture.client.pushedHolds.isEmpty())
        assertTrue("power should not have been touched", fixture.client.setOnCalls.isEmpty())
    }

    @Test
    fun `it has the shape of a real wall, gaps included`() = runTest {
        // A grid where every cell is a hold would not show that walls have
        // gaps, which is most of what makes the grid worth looking at.
        val fixture = Fixture()
        runCurrent()

        val cells = ready(fixture.viewModel).wall.cells.flatten()
        assertEquals(DemoWall.WIDTH * DemoWall.HEIGHT, cells.size)
        assertTrue("expected some holds", cells.any { it })
        assertTrue("expected some gaps", cells.any { !it })
    }

    @Test
    fun `routes save on it like any other wall`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.viewModel.selectColor(HoldColor.Red)
        // 0,0 is a hold on this wall - the grid starts with '1'.
        fixture.viewModel.toggleHold(segmentIndex = 0)
        runCurrent()

        fixture.viewModel.saveRoute("Practice route")
        runCurrent()

        assertEquals(
            listOf("Practice route"),
            fixture.viewModel.savedRoutes.value.map { it.name }
        )
    }

    @Test
    fun `it is not recorded as the wall this device last reached`() = runTest {
        // That setting exists so a real wall can be reopened with its
        // controller out of reach. Writing the practice wall there would open
        // it next launch in place of the garage.
        val fixture = Fixture()
        runCurrent()

        assertNull(fixture.settings.savedWallId)
    }

    @Test
    fun `pausing does nothing - there is no controller to leave alone`() = runTest {
        val fixture = Fixture()
        runCurrent()

        fixture.viewModel.pause()
        runCurrent()

        assertEquals(ControllerState.Demo, ready(fixture.viewModel).controller)
    }

    @Test
    fun `reconnecting does nothing - there is nothing to come back to`() = runTest {
        val fixture = Fixture()
        runCurrent()

        fixture.viewModel.reconnect()
        runCurrent()

        assertEquals(ControllerState.Demo, ready(fixture.viewModel).controller)
        assertEquals(0, fixture.client.getWallCount)
    }

    @Test
    fun `reopening it finds the same wall rather than making a second`() = runTest {
        val fixture = Fixture()
        runCurrent()
        val first = ready(fixture.viewModel).wallId

        val again = WallViewModel(
            client = fixture.client,
            walls = WallRepository(fixture.wallDao),
            routes = RouteRepository(fixture.routeDao) { 1000L },
            settings = fixture.settings,
            controllerAddress = "http://practice.invalid",
            demo = true
        )
        runCurrent()

        assertNotNull(first)
        assertEquals(first, (again.uiState.value as WallUiState.Ready).wallId)
    }
}
