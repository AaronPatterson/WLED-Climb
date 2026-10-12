package com.wledclimb.app.e2e

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.wledclimb.app.MainActivity
import com.wledclimb.app.settings.DataStoreWledSettings
import com.wledclimb.app.storage.ClimbDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app as shipped, driven the way a person drives it.
 *
 * The real [MainActivity], the real Room database, the real DataStore settings
 * and the real HTTP client - talking to a [FakeWledController] on a real
 * socket, whose address is typed into the setup screen. Nothing in the app is
 * replaced to make that possible, so what these tests exercise is the same
 * wiring a phone runs.
 *
 * What they are not is a device. Robolectric draws no pixels and has no
 * touchscreen of its own, so anything that is only visual - a colour, a
 * truncation, a layout that clips - is out of reach here, as is real WLED
 * firmware. Everything with a semantic consequence is in reach.
 *
 * Every test starts from a fresh install. DataStore and the database both keep
 * one instance per process, and Robolectric runs every test in one process, so
 * without the reset below each test would inherit the last one's wall.
 *
 * **The screen is Robolectric's default, deliberately.** That is 320x470dp:
 * a small phone, so one pane at a time and portrait locked, which is the
 * layout most of the app is used in. A more realistic phone was tried first -
 * 411x891, 412x892, and 360x780 at the density of the phone this app is used
 * on - and on every one of them a dialog holding a focused text field never
 * lets Compose go idle: Robolectric's stand-in window manager and Compose keep
 * re-laying out the dialog window, the test waits a minute, and the loop's
 * garbage takes the rest of the suite down with an OutOfMemoryError. Reduced
 * to a bare AlertDialog with a focused OutlinedTextField and none of this
 * app's code, it does the same, and on the default screen it does not - so it
 * is an emulation problem, not the app's, and these tests assert behaviour
 * rather than pixels. The tablet tests set qualifiers of their own and keep
 * clear of text dialogs.
 */
@RunWith(RobolectricTestRunner::class)
abstract class EndToEndTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    protected val controller = FakeWledController()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        runBlocking { DataStoreWledSettings(context).clear() }
        ClimbDatabase.closeForTests()
        context.deleteDatabase("climb.db")
    }

    @After
    fun tearDown() {
        scenario?.close()
        ClimbDatabase.closeForTests()
        controller.close()
    }

    // --- the app's lifecycle ----------------------------------------------

    protected fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * The app closed and opened again from nothing, as after being killed in
     * the background. The activity and its view models go, and the database
     * is reopened from disk - only what was persisted comes back.
     */
    protected fun relaunch() {
        scenario?.close()
        ClimbDatabase.closeForTests()
        launch()
    }

    /**
     * Uninstalled and installed again: everything this app stored is gone,
     * and it starts at setup. The controller is left as it was, as a real one
     * would be.
     */
    protected fun reinstall() {
        scenario?.close()
        freshInstall()
        launch()
    }

    protected fun onActivity(block: (MainActivity) -> Unit) {
        scenario!!.onActivity(block)
    }

    /** A configuration change, such as a rotation: state that should survive, survives. */
    protected fun recreate() {
        scenario!!.recreate()
    }

    protected fun pressBack() {
        Espresso.pressBackUnconditionally()
        compose.waitForIdle()
    }

    // --- waiting ----------------------------------------------------------

    /**
     * The network and the database both answer on threads of their own, so
     * anything that follows one of them has to be waited for rather than
     * assumed. Generous, because a slow CI machine is not a failure.
     */
    /**
     * Searches the merged semantics tree, as assertions do by default. A
     * button's description and its enabled state live on different nodes in
     * the unmerged tree, so a matcher combining the two - "Save, and disabled"
     * - can never match there, and waiting on it waits forever.
     */
    protected fun waitFor(matcher: SemanticsMatcher, timeoutMillis: Long = 10_000): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onAllNodes(matcher)[0]
    }

    protected fun waitForText(text: String, timeoutMillis: Long = 10_000) =
        waitFor(hasText(text), timeoutMillis)

    protected fun waitForGone(matcher: SemanticsMatcher, timeoutMillis: Long = 10_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
        }
    }

    protected fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Waits until [condition] holds, for state the screen does not show - what the wall was sent. */
    protected fun eventually(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis, condition)
    }

    // --- setup ------------------------------------------------------------

    protected fun typeAddress(address: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(address)
    }

    protected fun tapTestAndSave() {
        compose.onNode(hasText("Test & save")).performClick()
    }

    /** From the setup screen, to a connected wall. */
    protected fun connect(to: FakeWledController = controller) {
        waitForText("Connect to your WLED controller")
        typeAddress(to.address)
        tapTestAndSave()
        waitForWall(to.name)
    }

    /** Fresh install, launched, set up against [controller], and on the wall. */
    protected fun launchConnected() {
        launch()
        connect()
    }

    protected fun waitForWall(name: String) {
        waitForText(name)
        waitFor(hold(1, 1))
    }

    // --- the wall ---------------------------------------------------------

    /**
     * A hold, by its 1-based column and row - which is how the app describes
     * them to a screen reader, and so how a test can find them. Matched exactly
     * rather than by prefix, so column 1 is not also column 11.
     */
    protected fun hold(column: Int, row: Int): SemanticsMatcher {
        val base = "Hold at column $column, row $row"
        return SemanticsMatcher("hold $column,$row") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it == base || it.startsWith("$base,") }
        }
    }

    protected fun tapHold(column: Int, row: Int) {
        compose.onNode(hold(column, row), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    protected fun isLit(column: Int, row: Int, colour: String): Boolean =
        exists(hasContentDescription("Hold at column $column, row $row, lit $colour"))

    protected fun isUnlit(column: Int, row: Int): Boolean =
        exists(hasContentDescription("Hold at column $column, row $row"))

    /**
     * That a hold is lit, waiting for it. What a route puts on the wall
     * arrives from the database on a thread of its own, after the title has
     * already changed - so checking straight after opening a route races the
     * load, and passes or fails by timing alone.
     *
     * Check something only the new state has before checking for absences:
     * "unlit" can be true of the state being replaced as well.
     */
    protected fun assertLit(column: Int, row: Int, colour: String, why: String = "") {
        try {
            eventually(5_000) { isLit(column, row, colour) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("expected hold $column,$row lit $colour. $why".trim())
        }
    }

    protected fun assertUnlit(column: Int, row: Int, why: String = "") {
        try {
            eventually(5_000) { isUnlit(column, row) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("expected hold $column,$row unlit. $why".trim())
        }
    }

    protected fun litCount(): Int =
        compose.onAllNodes(
            SemanticsMatcher("lit hold") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.startsWith("Hold at column") && it.contains(", lit ") }
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().size

    /** How many holds the grid shows - lit or not. Gaps are not holds and are not counted. */
    protected fun holdCount(): Int =
        compose.onAllNodes(
            SemanticsMatcher("any hold") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.startsWith("Hold at column") }
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().size

    /** Where a hold sits on the LED strip, for what the controller was sent. 3x3 wall only. */
    protected fun segment(column: Int, row: Int): Int = (column - 1) + (row - 1) * 3

    protected fun pickColour(name: String) {
        compose.onNode(
            SemanticsMatcher("swatch $name") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it == name || it == "$name, selected" }
            },
            useUnmergedTree = true
        ).performClick()
        compose.waitForIdle()
    }

    /** What the title above the grid says: the open route's name, or "Unsaved route". */
    protected fun assertTitle(name: String) {
        waitForText(name).assertIsDisplayed()
    }

    // --- top bar ----------------------------------------------------------

    protected fun openRoutes() {
        compose.onNode(hasContentDescription("Saved routes")).performClick()
        waitForText("Routes")
    }

    /** From the routes list back to the wall, on a phone where only one is shown. */
    protected fun backToWall() {
        compose.onNode(hasContentDescription("Saved routes")).performClick()
        waitFor(hold(1, 1))
    }

    /**
     * The power button, whichever state it is in. It is named for what it
     * will do next, so its description is also the clearest statement of what
     * the app believes about the wall.
     */
    protected fun powerDescription(): String? =
        listOf(
            "Turn wall off",
            "Turn wall on",
            "Wall out of reach. Try again",
            "Working offline. Go back online"
        ).firstOrNull { exists(hasContentDescription(it)) }

    protected fun waitForPower(description: String) {
        waitFor(hasContentDescription(description))
    }

    protected fun openMenu() {
        compose.onNode(hasContentDescription("Menu")).performClick()
        compose.waitForIdle()
    }

    protected fun menuItem(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label))

    // --- route actions on the wall -----------------------------------------

    /**
     * Waits for a control to become disabled. Saving writes to the database
     * on a thread of its own, so "save is no longer offered" is the signal
     * that it has landed - checked at once, it races the write.
     */
    protected fun waitForDisabled(description: String) {
        waitFor(hasContentDescription(description) and isNotEnabled())
    }

    protected fun tapSave() {
        compose.onNode(hasContentDescription("Save this route")).performClick()
        compose.waitForIdle()
    }

    protected fun tapSaveAsNew() {
        compose.onNode(hasContentDescription("Save as new")).performClick()
        compose.waitForIdle()
    }

    protected fun tapReset() {
        compose.onNode(hasContentDescription("Reset")).performClick()
        compose.waitForIdle()
    }

    // --- dialogs ----------------------------------------------------------

    protected fun inDialog(matcher: SemanticsMatcher): SemanticsMatcher =
        matcher and hasAnyAncestor(isDialog())

    protected fun dialogButton(label: String): SemanticsNodeInteraction =
        compose.onNode(inDialog(hasText(label)))

    protected fun typeInDialog(text: String) {
        compose.onNode(inDialog(hasSetTextAction())).performTextReplacement(text)
    }

    protected fun dialogFieldText(): String =
        compose.onNode(inDialog(hasSetTextAction())).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    protected fun isDialogShowing(): Boolean = exists(isDialog())

    /** Through the naming dialog that saving an unnamed route opens. */
    protected fun nameAndSave(name: String) {
        waitFor(inDialog(hasSetTextAction()))
        typeInDialog(name)
        dialogButton("Save").performClick()
        compose.waitForIdle()
    }

    // --- the routes list --------------------------------------------------

    protected fun tapNew() {
        compose.onNode(hasContentDescription("New")).performClick()
        compose.waitForIdle()
    }

    /** Opens a route from the list, which also returns to the wall on a phone. */
    protected fun openRoute(name: String) {
        compose.onNode(hasText(name) and !hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
    }

    /** The overflow menu on one row of the routes list. */
    protected fun routeMenu(name: String, action: String) {
        val rows = compose.onAllNodes(hasContentDescription("More"), useUnmergedTree = true)
        val names = savedRouteNames()
        val index = names.indexOf(name)
        check(index >= 0) { "no route called $name in $names" }
        rows[index].performClick()
        compose.waitForIdle()
        compose.onNode(hasText(action)).performClick()
        compose.waitForIdle()
    }

    /**
     * Route names as the list shows them, top to bottom. Read from the rows'
     * text rather than from the database, because the list is what someone
     * sees.
     */
    protected fun savedRouteNames(): List<String> {
        val moreButtons = compose.onAllNodes(hasContentDescription("More"), useUnmergedTree = true)
            .fetchSemanticsNodes()
        return moreButtons.map { more ->
            // The row's name is the first text in the row that holds this button.
            var parent = more.parent
            var found: String? = null
            while (parent != null && found == null) {
                found = parent.children
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .firstOrNull()?.text
                parent = parent.parent
            }
            found ?: "?"
        }
    }
}
