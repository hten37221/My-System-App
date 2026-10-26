package com.android.mysystemapp.feature.vehiclecontrol

import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleFeatureAreaState
import com.android.mysystemapp.core.vehicle.VehicleFeatureControlState
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertyAccess
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehiclemodel.HeadlightMode
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.SceneProperties
import com.android.mysystemapp.core.vehiclemodel.geometry.CarGeometryBuilder
import com.android.mysystemapp.feature.hvac.domain.ClimateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBindingTest {
    @Test fun confirmedOnSettingReachesEmissiveGeometryWhilePendingRequestsDoNot() {
        val template = state().controls.single()
        val property =
            template.copy(
                definition = VehicleFeatureDefinition("HEADLIGHTS", VehiclePropertySpec.int(SceneProperties.HEADLIGHTS_SWITCH)),
                areaType = VehiclePropertyAreaType.GLOBAL,
                areas = listOf(template.areas.single().copy(area = VehiclePropertyArea.GLOBAL, value = 0, confirmedValue = 1)),
            )
        val scene = bindVehicleScene(listOf(state().copy(controls = listOf(property))), ClimateState())
        assertEquals(HeadlightMode.LOW_BEAM, scene.headlightMode)
        assertTrue(CarGeometryBuilder.buildCarModel(scene).any { it.isEmissive })
    }

    private fun state(status: VehiclePropertyStatus = VehiclePropertyStatus.AVAILABLE) =
        VehicleFeatureState(
            connection = VehicleConnectionState.Connected(1),
            controls =
                listOf(
                    VehicleFeatureControlState(
                        definition = VehicleFeatureDefinition("DOOR_POSITION", VehiclePropertySpec.int(SceneProperties.DOOR_POS)),
                        supported = true,
                        areaType = VehiclePropertyAreaType.DOOR,
                        areas =
                            listOf(
                                VehicleFeatureAreaState(
                                    area = VehiclePropertyArea(1),
                                    access = VehiclePropertyAccess.READ_WRITE,
                                    minValue = 0,
                                    maxValue = 1,
                                    value = 1,
                                    confirmedValue = 0,
                                    pending = true,
                                    status = status,
                                ),
                            ),
                    ),
                ),
        )

    @Test fun pendingRequestNeverBecomesAnObservedPosition() {
        val scene = bindVehicleScene(listOf(state()), ClimateState())
        assertEquals(0f, scene.normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, 1))
    }

    @Test fun unavailableAndDisconnectedSnapshotsDoNotKeepStalePositions() {
        val unavailable = bindVehicleScene(listOf(state(VehiclePropertyStatus.UNAVAILABLE)), ClimateState())
        assertNull(unavailable.normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, 1))
        val disconnected = bindVehicleScene(listOf(state().copy(connection = VehicleConnectionState.Disconnected())), ClimateState())
        assertNull(disconnected.normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, 1))
    }
}
