package com.android.mysystemapp.feature.driverassistance.data

import com.android.mysystemapp.core.vehicle.VehicleFeatureController
import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import com.android.mysystemapp.feature.driverassistance.domain.DRIVER_ASSISTANCE_DEFINITIONS
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class DriverAssistanceRepository
    @Inject
    constructor(
        private val factory: VehicleFeatureControllerFactory,
    ) {
        fun createController(scope: CoroutineScope): VehicleFeatureController =
            factory.create(
                scope,
                DRIVER_ASSISTANCE_DEFINITIONS.map { definition ->
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
                        requiresUnrestrictedUx = definition.uxRestricted,
                    )
                },
            )
    }
