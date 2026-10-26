package com.android.mysystemapp

import android.content.pm.PackageManager
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import com.android.mysystemapp.feature.vehiclecontrol.partLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleWorkspaceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        compose.waitUntil(30000) { compose.onAllNodesWithTag("vehicle-workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun allGroupsOpenAndModesHaveNativeValetAndExplicitUnsupportedProviders() {
        ready()
        for (group in listOf("DOORS", "SEATS", "CLIMATE", "LIGHTS", "ASSISTANCE", "MODES")) {
            compose.onNodeWithTag("group-$group").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("controls-list").assertExists()
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, compose.activity.checkSelfPermission("android.car.permission.CONTROL_VALET_MODE"))
        compose.onNodeWithTag("control-MODES-VALET-GLOBAL-0").assertExists()
        compose.onNodeWithText(compose.activity.getString(com.android.mysystemapp.feature.vehiclecontrol.R.string.drive)).assertExists()
        compose.onNodeWithText(compose.activity.getString(com.android.mysystemapp.feature.vehiclecontrol.R.string.pet)).assertExists()
    }

    @Test fun windowAreaAndDoorAreaHaveDistinctSelections() {
        ready()
        selectZone(VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 16))
        compose.onNodeWithTag("control-DOORS-DOOR_LOCK-DOOR-16").assertExists()
        compose.onAllNodesWithTag("control-DOORS-WINDOW_POSITION-WINDOW-16").assertCountEquals(0)
        selectZone(VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, 16))
        compose.onNodeWithTag("control-DOORS-WINDOW_POSITION-WINDOW-16").assertExists()
        compose.onAllNodesWithTag("control-DOORS-DOOR_LOCK-DOOR-16").assertCountEquals(0)
    }

    @Test fun cameraGestureAndCancellationLeaveControlsUsable() {
        ready()
        compose
            .onNodeWithTag(
                "vehicle-model",
            ).performTouchInput {
                swipe(
                    center,
                    center +
                        androidx.compose.ui.geometry
                            .Offset(140f, 30f),
                    400,
                )
            }
        compose.onNodeWithTag("vehicle-model").performTouchInput {
            down(center)
            moveBy(
                androidx.compose.ui.geometry
                    .Offset(-80f, 10f),
            )
            cancel()
        }
        // Change pointer count mid-gesture, then cancel: the next tap must remain usable.
        compose.onNodeWithTag("vehicle-model").performTouchInput {
            down(
                0,
                center -
                    androidx.compose.ui.geometry
                        .Offset(40f, 0f),
            )
            down(
                1,
                center +
                    androidx.compose.ui.geometry
                        .Offset(40f, 0f),
            )
            updatePointerTo(
                0,
                center -
                    androidx.compose.ui.geometry
                        .Offset(80f, 0f),
            )
            updatePointerTo(
                1,
                center +
                    androidx.compose.ui.geometry
                        .Offset(80f, 0f),
            )
            move()
            up(1)
            moveBy(
                0,
                androidx.compose.ui.geometry
                    .Offset(20f, 10f),
            )
            cancel()
        }
        compose.onNodeWithTag("camera-${com.android.mysystemapp.feature.vehiclecontrol.R.string.cabin}").performClick()
        compose.onNodeWithTag("group-SEATS").performClick()
        compose.onNodeWithTag("controls-list").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("group-SEATS").assertExists()
        compose.onNodeWithTag("controls-list").assertExists()
    }

    @Test fun selectedAreaSurvivesRecreationAndLiveThemeChanges() {
        ready()
        val selected = VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 1)
        selectZone(selected)
        val label =
            partLabel(
                selected,
                compose.activity.resources.configuration.locales[0]
                    .language == "vi",
            )
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("selected-area").assertTextEquals(label)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val original = Regex("Night mode: (no|yes|auto)").find(device.executeShellCommand("cmd uimode night"))?.groupValues?.get(1)
        checkNotNull(original) { "Cannot capture original night-mode setting" }
        val next = if (original == "yes") "no" else "yes"
        try {
            device.executeShellCommand("cmd uimode night $next")
            compose.waitUntil(10000) {
                val night = compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                night ==
                    if (next ==
                        "yes"
                    ) {
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    } else {
                        android.content.res.Configuration.UI_MODE_NIGHT_NO
                    }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("selected-area").assertTextEquals(label)
            compose.onNodeWithTag("control-DOORS-DOOR_POSITION-DOOR-1").assertExists()
        } finally {
            device.executeShellCommand("cmd uimode night $original")
        }
    }

    private fun selectZone(selection: VehiclePartSelection) {
        val label =
            partLabel(
                selection,
                compose.activity.resources.configuration.locales[0]
                    .language == "vi",
            )
        val tag = "zone-${selection.areaType.name}-${selection.areaId}"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("zone-dropdown-trigger").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.waitUntil(10000) {
            compose
                .onNodeWithTag("selected-area")
                .fetchSemanticsNode()
                .config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                ?.joinToString("") { it.text } == label
        }
        compose.waitForIdle()
    }

    @Test fun nativeFocusRespondsToDpadAndRotary() {
        ready()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithTag("group-DOORS").performClick()
        device.pressDPadDown()
        compose.waitForIdle()
        var label: String? = null
        compose.runOnUiThread {
            label =
                compose.activity.currentFocus
                    ?.contentDescription
                    ?.toString()
        }
        assertTrue("DPAD must focus an actionable native control", !label.isNullOrBlank())
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(
                "cmd car_service inject-rotary -d 0 -i 10 -c true",
            ).close()
        device.waitForIdle()
        compose.runOnUiThread {
            assertTrue(
                "Rotary focus remains actionable",
                !compose.activity.currentFocus
                    ?.contentDescription
                    .isNullOrBlank(),
            )
        }
    }

    @Test fun externalDoorPositionUpdatesTheEditorAndRenderedScene() {
        ready()
        selectZone(VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 1))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entry =
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                compose.activity.application,
                VehicleControlEntryPoint::class.java,
            )
        val original =
            kotlinx.coroutines.runBlocking {
                (entry.client().getInt(0x16400b00, 1) as com.android.mysystemapp.core.vehicle.VehiclePropertyResult.Success).value.value!!
            }
        val target = if (original == 0)1 else 0
        try {
            instrumentation.uiAutomation.executeShellCommand("cmd car_service set-property-value DOOR_POS 1 $target").close()
            compose.waitUntil(12000) {
                compose
                    .onNodeWithTag(
                        "control-DOORS-DOOR_POSITION-DOOR-1",
                    ).fetchSemanticsNode()
                    .config
                    .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) ==
                    target.toString()
            }
            compose.waitForIdle()
            val folder = java.io.File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.FileOutputStream(java.io.File(folder, "external-door-update.png")).use { output ->
                checkNotNull(screenshot).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("cmd car_service set-property-value DOOR_POS 1 $original").close()
        }
    }
}
