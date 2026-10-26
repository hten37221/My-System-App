package com.android.mysystemapp.feature.vehiclelighting.data

import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehiclePropertyAccess
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaCapability
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyCapability
import com.android.mysystemapp.core.vehicle.VehiclePropertyChangeMode
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehicle.VehiclePropertyValue
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyClient
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyConnection
import com.android.mysystemapp.core.vehicle.fake.FakeVehicleUxPolicy
import com.android.mysystemapp.feature.vehiclelighting.domain.VEHICLE_LIGHTING_DEFINITIONS
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleLightingRepositoryTest {
    @Test fun `fake light mode reaches lighting repository`() =
        runTest {
            val definition = VEHICLE_LIGHTING_DEFINITIONS.first()
            val spec = VehiclePropertySpec.int(definition.propertyId)
            val capability =
                VehiclePropertyCapability(
                    spec,
                    VehiclePropertyAccess.READ_WRITE,
                    VehiclePropertyChangeMode.ON_CHANGE,
                    VehiclePropertyAreaType.GLOBAL,
                    listOf(
                        VehiclePropertyAreaCapability(
                            VehiclePropertyArea.GLOBAL,
                            VehiclePropertyAccess.READ_WRITE,
                            null,
                            null,
                            listOf(0, 1, 2, 3),
                        ),
                    ),
                    0f,
                    0f,
                )
            val value = VehiclePropertyValue(spec, VehiclePropertyArea.GLOBAL, 1, VehiclePropertyStatus.AVAILABLE, 1)
            val client = FakeVehiclePropertyClient(listOf(capability), listOf(value))
            val controller = VehicleLightingRepository(factory(client)).createController(backgroundScope)
            runCurrent()
            assertThat(controller.state.value.controls).hasSize(8)
            assertThat(
                controller.state.value
                    .control(definition.id.name)
                    ?.area(0)
                    ?.value,
            ).isEqualTo(1)
        }

    @Test fun `lighting specs use the INT32 VehicleLightSwitch value type`() {
        val specs = VehicleLightingRepository.vehicleLightingDefinitions().map { it.spec }

        assertThat(specs.map { it.valueType }).containsExactlyElementsIn(
            List(VEHICLE_LIGHTING_DEFINITIONS.size) { VehiclePropertyValueType.INT },
        )
    }

    private fun factory(client: FakeVehiclePropertyClient) =
        VehicleFeatureControllerFactory(
            client,
            FakeVehiclePropertyConnection(),
            FakeVehicleUxPolicy(),
        )
}
