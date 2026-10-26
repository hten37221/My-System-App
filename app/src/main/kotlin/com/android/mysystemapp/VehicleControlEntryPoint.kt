package com.android.mysystemapp

import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehiclePropertyClient
import com.android.mysystemapp.core.vehicle.VehicleUxPolicy
import com.android.mysystemapp.feature.doorcontrol.data.DoorControlRepository
import com.android.mysystemapp.feature.driverassistance.data.DriverAssistanceRepository
import com.android.mysystemapp.feature.hvac.domain.HvacRepository
import com.android.mysystemapp.feature.seatcontrol.data.SeatControlRepository
import com.android.mysystemapp.feature.vehiclelighting.data.VehicleLightingRepository
import com.android.mysystemapp.feature.vehiclemodes.VehicleModesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Narrow internal integration boundary for device diagnostics. No exported component. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VehicleControlEntryPoint {
    fun connection(): com.android.mysystemapp.core.vehicle.VehiclePropertyConnection

    fun factory(): VehicleFeatureControllerFactory

    fun client(): VehiclePropertyClient

    fun uxPolicy(): VehicleUxPolicy

    fun doorControlRepository(): DoorControlRepository

    fun seatControlRepository(): SeatControlRepository

    fun vehicleLightingRepository(): VehicleLightingRepository

    fun driverAssistanceRepository(): DriverAssistanceRepository

    fun hvacRepository(): HvacRepository

    fun vehicleModesRepository(): VehicleModesRepository
}
