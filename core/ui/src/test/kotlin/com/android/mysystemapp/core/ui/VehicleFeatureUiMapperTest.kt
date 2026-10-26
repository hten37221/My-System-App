package com.android.mysystemapp.core.ui

import com.android.mysystemapp.core.vehicle.VehicleFeatureAreaState
import com.android.mysystemapp.core.vehicle.VehicleFeatureControlState
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertyAccess
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyChangeMode
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleFeatureUiMapperTest {
    @Test
    fun writeOnlySlotActionBuildsOptionsWithoutFakeCurrentValue() {
        val spec = VehiclePropertySpec.int(0x15400B80)
        val state =
            VehicleFeatureState(
                loading = false,
                controls =
                    listOf(
                        VehicleFeatureControlState(
                            definition = VehicleFeatureDefinition("memoryRecall", spec),
                            supported = true,
                            access = VehiclePropertyAccess.WRITE,
                            changeMode = VehiclePropertyChangeMode.ON_CHANGE,
                            areaType = VehiclePropertyAreaType.SEAT,
                            areas =
                                listOf(
                                    VehicleFeatureAreaState(
                                        area = VehiclePropertyArea(1),
                                        access = VehiclePropertyAccess.WRITE,
                                        minValue = 0,
                                        maxValue = 3,
                                        status = VehiclePropertyStatus.AVAILABLE,
                                    ),
                                ),
                        ),
                    ),
            )

        val controls =
            state.toUiControls(
                metadata =
                    listOf(
                        VehicleControlUiMetadata(
                            key = "memoryRecall",
                            section = "Memory",
                            title = "Recall",
                            summary = "Choose slot",
                            info = "Write-only action",
                            limitations = "",
                            dependencies = "",
                            editor = VehicleEditorUiKind.ENUM,
                            enumLabels = (0..3).associateWith { "Slot $it" },
                            emptyValueLabel = "Choose slot",
                        ),
                    ),
                errorMessage = { it.description },
            )

        assertEquals(1, controls.size)
        assertTrue(controls.single().available)
        assertTrue(controls.single().writable)
        assertEquals(null, controls.single().selectedEnumKey)
        assertEquals("Choose slot", controls.single().valueLabel)
        assertEquals(listOf("0", "1", "2", "3"), controls.single().enumOptions.map { it.key })
        assertTrue(isVehicleControlRotaryActionable(controls.single(), connected = true, restricted = false))
    }
}
