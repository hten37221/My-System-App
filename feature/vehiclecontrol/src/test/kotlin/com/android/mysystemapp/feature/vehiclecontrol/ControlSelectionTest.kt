package com.android.mysystemapp.feature.vehiclecontrol

import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSelectionTest {
    @Test fun windshieldsRouteToClimateWithMatchingGlassSelectionWhileOtherGlazingRoutesToDoors() {
        for (area in listOf(1, 2)) {
            val windshield = VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, area)
            assertEquals(ControlGroup.CLIMATE, groupFor(windshield))
            assertEquals(windshield, selectionFor(ControlGroup.CLIMATE, SceneArea.WINDOW, area))
            assertTrue(control(VehiclePropertyAreaType.WINDOW, area).matchesSelection(ControlGroup.CLIMATE, windshield))
            assertFalse(control(VehiclePropertyAreaType.SEAT, area).matchesSelection(ControlGroup.CLIMATE, windshield))
        }
        for (area in listOf(16, 64, 256, 1024, 65536)) {
            assertEquals(ControlGroup.DOORS, groupFor(VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, area)))
        }
    }

    @Test fun compositeZoneIncludesIndividualControlsAndSingleZoneIncludesCompositeControl() {
        val front = VehiclePartSelection(CarPart.HVAC, SceneArea.SEAT, 5)
        assertTrue(control(VehiclePropertyAreaType.SEAT, 1).matchesSelection(ControlGroup.CLIMATE, front))
        assertTrue(control(VehiclePropertyAreaType.SEAT, 4).matchesSelection(ControlGroup.CLIMATE, front))
        assertFalse(control(VehiclePropertyAreaType.SEAT, 16).matchesSelection(ControlGroup.CLIMATE, front))
        assertTrue(control(VehiclePropertyAreaType.SEAT, 5).matchesSelection(ControlGroup.CLIMATE, front.copy(areaId = 1)))
    }

    @Test fun sharedBitsCannotCrossComponentOrAreaDomainBoundaries() {
        val front = VehiclePartSelection(CarPart.HVAC, SceneArea.SEAT, 5)
        assertFalse(control(VehiclePropertyAreaType.WINDOW, 1).matchesSelection(ControlGroup.CLIMATE, front))
        assertFalse(control(VehiclePropertyAreaType.SEAT, 1).matchesSelection(ControlGroup.SEATS, front))
    }

    @Test fun globalControlsRemainVisibleAsContextForASpecificZone() {
        val mirror = VehiclePartSelection(CarPart.MIRRORS, SceneArea.MIRROR, 1)
        assertTrue(control(VehiclePropertyAreaType.GLOBAL, 0).matchesSelection(ControlGroup.DOORS, mirror))
        assertTrue(control(VehiclePropertyAreaType.SEAT, 16).matchesSelection(ControlGroup.SEATS, null))
    }

    private fun control(
        type: VehiclePropertyAreaType,
        area: Int,
    ) = VehicleControlUiModel(
        key = "test",
        propertyId = 1,
        areaId = area,
        areaType = type,
        section = "",
        title = "",
        summary = "",
        info = "",
        limitations = "",
        dependencies = "",
        editor = VehicleEditorUiKind.SWITCH,
        readable = true,
        writable = true,
        available = true,
        pending = false,
    )
}
