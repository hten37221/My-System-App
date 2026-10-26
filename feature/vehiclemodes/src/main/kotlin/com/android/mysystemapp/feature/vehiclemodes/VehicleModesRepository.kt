package com.android.mysystemapp.feature.vehiclemodes

import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** Native capability only. Drive/Pet have no provider contract on this image. */
class VehicleModesRepository
    @Inject
    constructor(
        private val factory: VehicleFeatureControllerFactory,
    ) {
        fun createController(scope: CoroutineScope) = factory.create(scope, DEFINITIONS)

        companion object {
            const val VALET_PROPERTY_ID = 0x11200a05
            val DEFINITIONS =
                listOf(VehicleFeatureDefinition("VALET", VehiclePropertySpec.boolean(VALET_PROPERTY_ID), requiresUnrestrictedUx = true))
        }
    }
