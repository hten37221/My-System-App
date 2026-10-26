package com.android.mysystemapp.core.vehicle.di

import com.android.mysystemapp.core.vehicle.DefaultVehiclePropertyClient
import com.android.mysystemapp.core.vehicle.DefaultVehiclePropertyConnection
import com.android.mysystemapp.core.vehicle.DefaultVehicleUxPolicy
import com.android.mysystemapp.core.vehicle.VehiclePropertyClient
import com.android.mysystemapp.core.vehicle.VehiclePropertyConnection
import com.android.mysystemapp.core.vehicle.VehicleReconnectPolicy
import com.android.mysystemapp.core.vehicle.VehicleUxPolicy
import com.android.mysystemapp.core.vehicle.internal.AndroidCarConnector
import com.android.mysystemapp.core.vehicle.internal.PlatformCarConnector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VehicleCoreBindingsModule {
    @Binds
    @Singleton
    abstract fun bindCarConnector(implementation: AndroidCarConnector): PlatformCarConnector

    @Binds
    @Singleton
    abstract fun bindVehicleConnection(implementation: DefaultVehiclePropertyConnection): VehiclePropertyConnection

    @Binds
    @Singleton
    abstract fun bindVehiclePropertyClient(implementation: DefaultVehiclePropertyClient): VehiclePropertyClient

    @Binds
    @Singleton
    abstract fun bindVehicleUxPolicy(implementation: DefaultVehicleUxPolicy): VehicleUxPolicy
}

@Module
@InstallIn(SingletonComponent::class)
internal object VehicleCoreConfigurationModule {
    @Provides
    @Singleton
    fun provideReconnectPolicy(): VehicleReconnectPolicy = VehicleReconnectPolicy()
}
