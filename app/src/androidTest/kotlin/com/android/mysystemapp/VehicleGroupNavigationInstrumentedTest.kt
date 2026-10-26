package com.android.mysystemapp

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Freezes Compose frames to inspect the feedback before the expensive destination is composed. */
class VehicleGroupNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun loadingSelectsTheDestinationAndBlocksNativeInputUntilItsContentIsReady() {
        compose.waitUntil(30000) { compose.onAllNodesWithTag("vehicle-workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val groups = listOf("MODES", "SEATS", "CLIMATE", "LIGHTS", "ASSISTANCE", "DOORS")
        var previous = "DOORS"
        for (destination in groups) {
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("group-$destination").performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onNodeWithTag("group-$destination").assertIsSelected()
                compose.onNodeWithTag("group-$previous").assertIsNotSelected()
                compose.onNodeWithTag("group-$destination-loading", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("group-content-$previous").assertExists()
                assertRailLocked(groups)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            compose.onNodeWithTag("group-content-$destination").assertExists()
            compose.onNodeWithTag("group-$destination").assertIsSelected()
            compose.onNodeWithTag("group-$destination-loading", useUnmergedTree = true).assertDoesNotExist()
            groups.forEach { compose.onNodeWithTag("group-$it").assertIsEnabled() }
            previous = destination
        }
    }

    private fun assertRailLocked(groups: List<String>) {
        groups.forEach { compose.onNodeWithTag("group-$it").assertIsNotEnabled() }
        compose.runOnUiThread {
            val targets = groups.map { "group-$it" }
            var checked = 0

            fun visit(view: View) {
                if (view is FocusItemView && view.target.itemId.value in targets) {
                    checked++
                    assertFalse("Native DPAD/rotary activation must be disabled", view.performClick())
                }
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(compose.activity.window.decorView)
            assertEquals("Every group must have a native focus target", groups.size, checked)
        }
    }
}
