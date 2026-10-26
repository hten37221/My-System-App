package com.android.mysystemapp

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyResult
import com.android.mysystemapp.core.vehicle.VehiclePropertyWriteResult
import com.android.mysystemapp.feature.vehiclecontrol.VehicleControlViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VehicleMovementLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun activeNativeDoorMovementStopsWhenTheActivityStops() =
        runBlocking {
            val entry = EntryPointAccessors.fromApplication(compose.activity.application, VehicleControlEntryPoint::class.java)
            lateinit var vm: VehicleControlViewModel
            compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[VehicleControlViewModel::class.java] }
            withTimeout(20000) { vm.doors.state.first { it.control("DOOR_MOVEMENT")?.area(1)?.confirmedValue != null } }
            val original = (entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value.value!!
            val control =
                VehicleControlUiModel(
                    key = "DOOR_MOVEMENT",
                    propertyId = 0x16400b01,
                    areaId = 1,
                    areaType = VehiclePropertyAreaType.DOOR,
                    section = "Doors",
                    title = "Move",
                    summary = "",
                    info = "",
                    limitations = "",
                    dependencies = "",
                    editor = VehicleEditorUiKind.ENUM,
                    readable = true,
                    writable = true,
                    available = true,
                    pending = false,
                )
            try {
                compose.runOnUiThread { vm.move(control, 1) }
                withTimeout(6000) {
                    while ((entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value.value != 1) delay(30)
                }
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                withTimeout(6000) {
                    while ((entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value.value != 0) delay(30)
                }
                assertEquals(0, (entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value.value)
            } finally {
                entry.client().setInt(0x16400b01, 1, original).first { it !is VehiclePropertyWriteResult.Pending }
            }
        }

    @Test fun immediateReleaseCannotBeOvertakenByALateNativeStart(): Unit =
        runBlocking {
            val entry = EntryPointAccessors.fromApplication(compose.activity.application, VehicleControlEntryPoint::class.java)
            lateinit var vm: VehicleControlViewModel
            compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[VehicleControlViewModel::class.java] }
            withTimeout(20000) { vm.doors.state.first { it.control("DOOR_MOVEMENT")?.area(1)?.confirmedValue != null } }
            val original = (entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value
            val control =
                VehicleControlUiModel(
                    key = "DOOR_MOVEMENT",
                    propertyId = 0x16400b01,
                    areaId = 1,
                    areaType = VehiclePropertyAreaType.DOOR,
                    section = "Doors",
                    title = "Move",
                    summary = "",
                    info = "",
                    limitations = "",
                    dependencies = "",
                    editor = VehicleEditorUiKind.ENUM,
                    readable = true,
                    writable = true,
                    available = true,
                    pending = false,
                )
            try {
                compose.runOnUiThread {
                    repeat(12) {
                        vm.move(control, 1)
                        vm.stopMovement(control)
                    }
                }
                withTimeout(8000) {
                    while (true) {
                        val current = (entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value
                        if (current.value == 0 && current.timestampNanos > original.timestampNanos)break
                        delay(30)
                    }
                }
                delay(400)
                assertEquals(0, (entry.client().getInt(0x16400b01, 1) as VehiclePropertyResult.Success).value.value)
            } finally {
                entry.client().setInt(0x16400b01, 1, original.value!!).first { it !is VehiclePropertyWriteResult.Pending }
            }
        }
}
