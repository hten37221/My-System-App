package com.android.mysystemapp.feature.seatcontrol.data

import com.android.mysystemapp.core.vehicle.VehicleFeatureController
import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import com.android.mysystemapp.feature.seatcontrol.domain.SEAT_CONTROL_DEFINITIONS
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class SeatControlRepository
    @Inject
    constructor(
        private val factory: VehicleFeatureControllerFactory,
    ) {
        fun createController(scope: CoroutineScope): VehicleFeatureController =
            factory.create(
                scope,
                SEAT_CONTROL_DEFINITIONS.map { definition ->
                    VehicleFeatureDefinition(
                        definition.id.name,
                        when (definition.valueType) {
                            VehiclePropertyValueType.BOOLEAN -> VehiclePropertySpec.boolean(definition.propertyId)
                            VehiclePropertyValueType.INT -> VehiclePropertySpec.int(definition.propertyId)
                            VehiclePropertyValueType.FLOAT -> VehiclePropertySpec.float(definition.propertyId)
                        },
                        definition.requiresUnrestrictedUx,
                    )
                },
            )
    }
