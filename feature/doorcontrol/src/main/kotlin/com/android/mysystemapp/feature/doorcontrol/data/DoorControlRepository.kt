package com.android.mysystemapp.feature.doorcontrol.data

import com.android.mysystemapp.core.vehicle.VehicleFeatureController
import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import com.android.mysystemapp.feature.doorcontrol.domain.DOOR_CONTROL_DEFINITIONS
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class DoorControlRepository
    @Inject
    constructor(
        private val factory: VehicleFeatureControllerFactory,
    ) {
        fun createController(scope: CoroutineScope): VehicleFeatureController =
            factory.create(
                scope,
                DOOR_CONTROL_DEFINITIONS.map {
                    VehicleFeatureDefinition(
                        it.id.name,
                        when (it.valueType) {
                            VehiclePropertyValueType.BOOLEAN -> VehiclePropertySpec.boolean(it.propertyId)
                            VehiclePropertyValueType.INT -> VehiclePropertySpec.int(it.propertyId)
                            VehiclePropertyValueType.FLOAT -> VehiclePropertySpec.float(it.propertyId)
                        },
                        true,
                    )
                },
            )
    }
