package app.trackerlens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trackerlens.ui.MainActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI tests on a real device or emulator: the real app, the real installed apps,
 * real scans. Assertions are about structure and flow, since installed apps differ per device.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun launch(onboarded: Boolean, includeSystem: Boolean = false) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", onboarded).putBoolean("include_system", includeSystem)
            .remove("reviewed_apps").remove("journey_course").commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() { scenario?.close() }

    /** Waits for the dashboard to show real results (the first scan can take a while). */
    private fun awaitDashboard() {
        // Ready when the score card shows a real score (its screen-reader description starts "Privacy score"),
        // which only happens once results are loaded; tied to meaning, not to tile wording.
        compose.waitUntil(SCAN_TIMEOUT) { compose.onAllNodes(hasContentDescription("Privacy score", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The dashboard is a lazy list: scroll to an item (as a user would) before using it. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToKeyAction()).performScrollToNode(matcher)
    }

    /** Switches to a bottom-navigation tab. */
    private fun openTab(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    }

    private fun searchBar() = hasText("Search apps").also { openTabIfNeeded(); scrollTo(it) }

    private fun openTabIfNeeded() {
        if (compose.onAllNodes(hasText("Search apps")).fetchSemanticsNodes().isEmpty()) openTab("Apps")
    }

    @Test fun journeyFromWelcomeToDashboard() {
        launch(onboarded = false)
        compose.onNodeWithText("See who's inside your apps").assertExists()
        compose.onNodeWithContentDescription("Step 1 of 5").assertExists()
        compose.onNodeWithText("Get started").performClick()

        compose.onNodeWithText("How it works").assertExists()
        compose.onNodeWithText("Scan my apps").performClick()

        compose.onNodeWithContentDescription("Step 3 of 5").assertExists()
        compose.waitUntil(SCAN_TIMEOUT) { compose.onAllNodesWithText("Show my results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show my results").performClick()

        compose.onNodeWithText("Your results").assertExists()
        compose.onNodeWithText("What can I do?").performClick()

        compose.onNodeWithText("Your privacy plan").assertExists()
        compose.onNodeWithText("Go to my dashboard").performClick()

        awaitDashboard()
        // The dashboard is organised into tabs; Apps holds the full list with search.
        openTab("Apps")
        compose.onNode(searchBar()).assertExists()
    }

    @Test fun scoreIsExplained() {
        launch(onboarded = true)
        awaitDashboard()
        compose.onNodeWithContentDescription("What does the score mean?").performClick()
        compose.onNodeWithText("About the privacy score").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("About the privacy score").assertDoesNotExist()
    }

    @Test fun searchWithNoMatchSaysSo() {
        launch(onboarded = true)
        awaitDashboard()
        compose.onNode(searchBar()).performTextInput("zzzz-no-such-app")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No apps match", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun openAppDetailsAndComeBack() {
        launch(onboarded = true)
        awaitDashboard()
        val appRow = SemanticsMatcher("opens app details") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open details" }
        openTab("Apps")
        scrollTo(appRow)
        compose.onAllNodes(appRow).onFirst().performClick()
        compose.onNodeWithText("Open app settings").assertExists()
        compose.onNodeWithText("Trackers (", substring = true).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(searchBar()).assertExists()
    }

    @Test fun alertsScreenOpensAndCloses() {
        launch(onboarded = true)
        awaitDashboard()
        openTab("Alerts")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("No alerts yet") or hasText("See app details") or hasText("Tracker added", substring = true) or hasText("Permission added", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Back from a tab returns to Home.
        Espresso.pressBack()
        awaitDashboard()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Your next step") or hasText("You're all set")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun trackersTabListsTrackersAndBackReturnsHome() {
        launch(onboarded = true)
        awaitDashboard()
        openTab("Trackers")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("found across", substring = true) or hasText("No known trackers were found", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        Espresso.pressBack()
        awaitDashboard()
    }

    @Test fun backFromATrackersAppListReturnsToTheTrackersList() {
        // System apps included so there are real trackers to pick (preinstalled Google apps carry some).
        launch(onboarded = true, includeSystem = true)
        awaitDashboard()
        openTab("Trackers")
        val trackerRow = SemanticsMatcher("opens a tracker's apps") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Show apps with") == true
        }
        compose.waitUntil(SCAN_TIMEOUT) { compose.onAllNodes(trackerRow).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(trackerRow).onFirst().performClick()
        compose.onNodeWithText("Apps with", substring = true).assertExists()

        Espresso.pressBack()
        // Back on the Trackers list (title), with the filter gone, ready to pick another tracker.
        compose.onAllNodes(trackerRow).onFirst().assertExists()
        compose.onNodeWithText("Apps with", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val SCAN_TIMEOUT = 180_000L
    }
}
