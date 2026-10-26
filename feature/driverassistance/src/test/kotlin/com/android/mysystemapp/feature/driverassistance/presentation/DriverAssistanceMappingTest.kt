package com.android.mysystemapp.feature.driverassistance.presentation

import com.android.mysystemapp.core.vehicle.VehicleFeatureControlState
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import com.android.mysystemapp.feature.driverassistance.domain.DRIVER_ASSISTANCE_DEFINITIONS
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriverAssistanceMappingTest {
    @Test
    fun `mapping keeps every capability discoverable when vehicle exposes none`() {
        val state =
            VehicleFeatureState(
                loading = false,
                controls =
                    DRIVER_ASSISTANCE_DEFINITIONS.map { definition ->
                        VehicleFeatureControlState(
                            definition =
                                VehicleFeatureDefinition(
                                    key = definition.id.name,
                                    spec =
                                        when (definition.valueType) {
                                            VehiclePropertyValueType.BOOLEAN ->
                                                VehiclePropertySpec.boolean(definition.propertyId)
                                            VehiclePropertyValueType.INT ->
                                                VehiclePropertySpec.int(definition.propertyId)
                                            VehiclePropertyValueType.FLOAT ->
                                                VehiclePropertySpec.float(definition.propertyId)
                                        },
                                ),
                            supported = false,
                        )
                    },
            )

        val controls =
            mapDriverAssistanceControls(
                state = state,
                titles = Array(30) { "Title $it" },
                descriptions = Array(30) { "Description $it" },
                sections = Array(7) { "Section $it" },
                levelLabels = Array(8) { "Level $it" },
                laneModeLabels = Array(8) { "Lane mode $it" },
                profileLabels = Array(8) { "Profile $it" },
                limitations = "Unavailable",
                dependencies = "Vehicle service",
                unavailable = "Not exposed by this vehicle",
            )

        assertThat(controls).hasSize(DRIVER_ASSISTANCE_DEFINITIONS.size)
        assertThat(controls).containsNoneIn(
            controls.filter { it.supported },
        )
        assertThat(controls.mapNotNull { it.illustrationRes }).isEmpty()
        assertThat(controls.map { it.key }.distinct()).hasSize(controls.size)
    }
}
