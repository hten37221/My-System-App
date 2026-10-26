package com.android.mysystemapp.feature.vehiclecontrol

import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection

internal fun VehicleControlUiModel.matchesSelection(
    group: ControlGroup,
    selection: VehiclePartSelection?,
): Boolean {
    // Global controls remain relevant context (for example, folding all mirrors from one mirror).
    if (selection == null || !supported || areaType == VehiclePropertyAreaType.GLOBAL) return true
    return areaType.sceneArea()?.let { selection.overlaps(selectionFor(group, it, areaId)) } == true
}
