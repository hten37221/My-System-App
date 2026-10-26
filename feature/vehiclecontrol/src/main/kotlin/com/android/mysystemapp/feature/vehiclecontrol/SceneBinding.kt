package com.android.mysystemapp.feature.vehiclecontrol

import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.ObservedValue
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.SceneKey
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import com.android.mysystemapp.core.vehiclemodel.VehicleSceneState
import com.android.mysystemapp.feature.hvac.domain.ClimateState
import com.android.mysystemapp.feature.hvac.domain.ClimateValueStatus

fun VehiclePropertyAreaType.sceneArea(): SceneArea? =
    when (this) {
        VehiclePropertyAreaType.GLOBAL -> SceneArea.GLOBAL
        VehiclePropertyAreaType.DOOR -> SceneArea.DOOR
        VehiclePropertyAreaType.WINDOW -> SceneArea.WINDOW
        VehiclePropertyAreaType.SEAT -> SceneArea.SEAT
        VehiclePropertyAreaType.MIRROR -> SceneArea.MIRROR
        else -> null
    }

fun bindVehicleScene(
    states: List<VehicleFeatureState>,
    climate: ClimateState,
): VehicleSceneState {
    val values =
        buildMap {
            states.filter { it.connection is com.android.mysystemapp.core.vehicle.VehicleConnectionState.Connected }.forEach { state ->
                state.controls.filter { it.supported }.forEach { control ->
                    val type = control.areaType.sceneArea() ?: return@forEach
                    control.areas.filter { it.status == VehiclePropertyStatus.AVAILABLE && it.access.canRead }.forEach { area ->
                        val observed = area.confirmedValue ?: return@forEach
                        put(
                            SceneKey(control.definition.spec.propertyId, type, area.area.areaId),
                            ObservedValue(observed, (area.minValue as? Number)?.toFloat(), (area.maxValue as? Number)?.toFloat()),
                        )
                    }
                }
            }
            climate.controls
                .filter { climate.connected }
                .filter {
                    it.status == ClimateValueStatus.AVAILABLE ||
                        it.status == ClimateValueStatus.PENDING
                }.forEach { control ->
                    val type = control.capability.areaType.sceneArea() ?: return@forEach
                    val observed = control.observedBooleanValue ?: control.observedFloatValue ?: control.observedIntValue ?: return@forEach
                    put(
                        SceneKey(control.capability.propertyId, type, control.capability.zone.areaId),
                        ObservedValue(observed, control.capability.min, control.capability.max),
                    )
                }
        }
    return VehicleSceneState(values)
}

fun selectionFor(
    group: ControlGroup,
    type: SceneArea,
    area: Int,
) = VehiclePartSelection(
    when (group) {
        ControlGroup.DOORS ->
            when (type) {
                SceneArea.WINDOW -> CarPart.GLASS_WINDOWS
                SceneArea.MIRROR -> CarPart.MIRRORS
                else ->
                    if (area ==
                        0x20000000
                    ) {
                        CarPart.TRUNK
                    } else {
                        CarPart.DOORS
                    }
            }
        ControlGroup.SEATS -> CarPart.SEATS
        ControlGroup.CLIMATE ->
            if (type == SceneArea.WINDOW && area in listOf(1, 2)) CarPart.GLASS_WINDOWS else CarPart.HVAC
        ControlGroup.LIGHTS -> CarPart.HEADLIGHTS
        ControlGroup.ASSISTANCE -> CarPart.ADAS
        ControlGroup.MODES -> CarPart.MODES
    },
    type,
    area,
)

fun groupFor(part: CarPart) =
    when (part) {
        CarPart.DOORS, CarPart.TRUNK, CarPart.GLASS_WINDOWS, CarPart.MIRRORS -> ControlGroup.DOORS
        CarPart.SEATS -> ControlGroup.SEATS
        CarPart.HVAC -> ControlGroup.CLIMATE
        CarPart.HEADLIGHTS -> ControlGroup.LIGHTS
        CarPart.ADAS -> ControlGroup.ASSISTANCE
        else -> ControlGroup.MODES
    }

fun groupFor(selection: VehiclePartSelection): ControlGroup =
    if (selection.part == CarPart.GLASS_WINDOWS && selection.areaType == SceneArea.WINDOW && selection.areaId in listOf(1, 2)) {
        ControlGroup.CLIMATE
    } else {
        groupFor(selection.part)
    }
