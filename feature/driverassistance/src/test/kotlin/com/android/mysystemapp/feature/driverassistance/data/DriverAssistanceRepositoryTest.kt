package com.android.mysystemapp.feature.driverassistance.data

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
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyClient
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyConnection
import com.android.mysystemapp.core.vehicle.fake.FakeVehicleUxPolicy
import com.android.mysystemapp.feature.driverassistance.domain.DRIVER_ASSISTANCE_DEFINITIONS
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverAssistanceRepositoryTest {
    @Test fun `fake capability reaches feature repository without fallback values`() =
        runTest {
            val definition = DRIVER_ASSISTANCE_DEFINITIONS.first()
            val spec = VehiclePropertySpec.boolean(definition.propertyId)
            val client = FakeVehiclePropertyClient(listOf(capability(spec)), listOf(value(spec, true)))
            val controller = DriverAssistanceRepository(factory(client)).createController(backgroundScope)
            runCurrent()
            assertThat(controller.state.value.controls).hasSize(30)
            assertThat(
                controller.state.value
                    .control(definition.id.name)
                    ?.areas
                    ?.single()
                    ?.value,
            ).isEqualTo(true)
        }

    private fun <T : Any> capability(spec: VehiclePropertySpec<T>) =
        VehiclePropertyCapability(
            spec,
            VehiclePropertyAccess.READ_WRITE,
            VehiclePropertyChangeMode.ON_CHANGE,
            VehiclePropertyAreaType.GLOBAL,
            listOf(VehiclePropertyAreaCapability(VehiclePropertyArea.GLOBAL, VehiclePropertyAccess.READ_WRITE)),
            0f,
            0f,
        )

    private fun <T : Any> value(
        spec: VehiclePropertySpec<T>,
        value: T,
    ) = VehiclePropertyValue(
        spec,
        VehiclePropertyArea.GLOBAL,
        value,
        VehiclePropertyStatus.AVAILABLE,
        1,
    )

    private fun factory(client: FakeVehiclePropertyClient) =
        VehicleFeatureControllerFactory(
            client,
            FakeVehiclePropertyConnection(),
            FakeVehicleUxPolicy(),
        )
}
