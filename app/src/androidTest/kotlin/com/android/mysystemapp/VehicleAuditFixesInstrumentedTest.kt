package com.android.mysystemapp

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.mysystemapp.core.vehicle.VehiclePropertyResult
import com.android.mysystemapp.core.vehicle.VehiclePropertyWriteResult
import com.android.mysystemapp.core.vehicle.VehicleUxPolicyState
import com.android.mysystemapp.core.vehiclemodel.HeadlightMode
import com.android.mysystemapp.core.vehiclemodel.geometry.CarGeometryBuilder
import com.android.mysystemapp.feature.hvac.domain.ClimateState
import com.android.mysystemapp.feature.vehiclecontrol.VehicleControlViewModel
import com.android.mysystemapp.feature.vehiclecontrol.bindVehicleScene
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises actual native focus and CarService writes, with original values restored in every case. */
class VehicleAuditFixesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val entry get() =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            VehicleControlEntryPoint::class.java,
        )

    private fun ready(): VehicleControlViewModel {
        compose.waitUntil(30000) { compose.onAllNodesWithTag("vehicle-workspace").fetchSemanticsNodes().isNotEmpty() }
        lateinit var vm: VehicleControlViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[VehicleControlViewModel::class.java] }
        runBlocking {
            withTimeout(20000) {
                entry.uxPolicy().state.first { it is VehicleUxPolicyState.Unrestricted }
                vm.doors.state.first { it.control("DOOR_POSITION")?.area(1)?.confirmedValue != null }
            }
        }
        compose.onNodeWithTag("group-DOORS").performClick()
        if (compose.onAllNodesWithTag("zone-DOOR-1").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("zone-dropdown-trigger").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("zone-DOOR-1").performClick()
        compose.waitForIdle()
        return vm
    }

    private fun focusNative(identity: String) {
        // Leave touch mode before selecting a target, so the first Center is an edit, not a focus hand-off.
        device.pressDPadDown()
        compose.waitForIdle()
        compose.runOnUiThread {
            fun find(view: View): FocusItemView? {
                if (view is FocusItemView && view.target.itemId.value == identity) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val view = checkNotNull(find(compose.activity.window.decorView)) { "No native focus target: $identity" }
            compose.activity.rotary.requestFocus(view.target)
        }
        compose.waitUntil(10000) { focusedIdentity() == identity }
        compose.waitForIdle()
    }

    private fun focusedIdentity(): String? {
        var identity: String? = null
        compose.runOnUiThread { identity = (compose.activity.currentFocus as? FocusItemView)?.target?.itemId?.value }
        return identity
    }

    private suspend fun read(
        property: Int,
        area: Int = 1,
    ): Int = checkNotNull((entry.client().getInt(property, area) as VehiclePropertyResult.Success).value.value)

    private suspend fun set(
        property: Int,
        area: Int,
        value: Int,
    ) {
        val result = entry.client().setInt(property, area, value).first { it !is VehiclePropertyWriteResult.Pending }
        assertTrue("Native write failed: $result", result is VehiclePropertyWriteResult.Confirmed)
    }

    private suspend fun awaitValue(
        property: Int,
        area: Int,
        value: Int,
    ) {
        withTimeout(10000) { while (read(property, area) != value) delay(30) }
    }

    @Test fun dpadStagesAndCommitsDoorPositionOnlyOnLeavingDirectManipulation(): Unit =
        runBlocking {
            ready()
            val original = read(DOOR_POS)
            try {
                focusNative(POSITION_ID)
                device.pressDPadCenter()
                compose.runOnUiThread {
                    assertTrue(
                        "Center must enter slider adjustment",
                        (compose.activity.currentFocus as FocusItemView).isInDirectManipulationMode,
                    )
                }
                if (original == 0) device.pressDPadRight() else device.pressDPadLeft()
                compose.waitForIdle()
                assertEquals("DPAD edits must stay local until adjustment finishes", original, read(DOOR_POS))
                device.pressBack()
                awaitValue(DOOR_POS, 1, if (original == 0) 1 else 0)
            } finally {
                set(DOOR_POS, 1, original)
            }
        }

    @Test fun nativeDetailsFocusOpensDialogWithoutChangingTheControl(): Unit =
        runBlocking {
            ready()
            val original = read(DOOR_POS)
            focusNative(POSITION_ID)
            device.pressDPadRight()
            compose.waitForIdle()
            assertEquals("DPAD must reach the separate Details target", "$POSITION_ID-info", focusedIdentity())
            device.pressDPadCenter()
            compose.waitForIdle()
            compose
                .onNodeWithText(
                    compose.activity.getString(com.android.mysystemapp.feature.vehiclecontrol.R.string.close),
                    useUnmergedTree = true,
                ).assertExists()
            assertEquals(original, read(DOOR_POS))
            device.pressBack()
        }

    @Test fun explicitStopButtonStopsMovementInitiatedOutsideTheViewModel(): Unit =
        runBlocking {
            val vm = ready()
            withTimeout(20000) { vm.doors.state.first { it.control("DOOR_MOVEMENT")?.area(1)?.confirmedValue != null } }
            val original = read(DOOR_MOVE)
            try {
                set(DOOR_MOVE, 1, 1)
                withTimeout(10000) { vm.doors.state.first { it.control("DOOR_MOVEMENT")?.area(1)?.confirmedValue == 1 } }
                focusNative("control-DOORS-DOOR_MOVEMENT-DOOR-1-stop")
                device.pressDPadCenter()
                awaitValue(DOOR_MOVE, 1, 0)
            } finally {
                set(DOOR_MOVE, 1, original)
            }
        }

    @Test fun onSelectionIsConfirmedAndLightsTheSharedVehicleGeometry(): Unit =
        runBlocking {
            val vm = ready()
            val original = read(HEADLIGHTS, 0)
            try {
                set(HEADLIGHTS, 0, 0)
                withTimeout(20000) { vm.lights.state.first { it.control("HEADLIGHTS")?.area(0)?.confirmedValue == 0 } }
                compose.onNodeWithTag("group-LIGHTS").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("control-LIGHTS-HEADLIGHTS-GLOBAL-0-option-1", useUnmergedTree = true).performClick()
                val confirmed =
                    withTimeout(10000) {
                        vm.lights.state.first {
                            it.control("HEADLIGHTS")?.area(0)?.let { a ->
                                !a.pending &&
                                    a.confirmedValue == 1
                            } ==
                                true
                        }
                    }
                assertEquals(1, read(HEADLIGHTS, 0))
                val scene = bindVehicleScene(listOf(confirmed), ClimateState())
                assertEquals(HeadlightMode.LOW_BEAM, scene.headlightMode)
                assertTrue(CarGeometryBuilder.buildCarModel(scene).any { it.isEmissive })
            } finally {
                set(HEADLIGHTS, 0, original)
            }
        }

    private companion object {
        const val DOOR_POS = 0x16400b00
        const val DOOR_MOVE = 0x16400b01
        const val HEADLIGHTS = 0x11400e10
        const val POSITION_ID = "control-DOORS-DOOR_POSITION-DOOR-1"
    }
}
