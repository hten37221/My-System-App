package com.android.mysystemapp

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertyError
import com.android.mysystemapp.core.vehicle.VehiclePropertyResult
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehicleUxPolicyState
import com.android.mysystemapp.feature.vehiclemodes.VehicleModesRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleRuntimeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry get() = EntryPointAccessors.fromApplication(instrumentation.targetContext, VehicleControlEntryPoint::class.java)

    @Test fun realUxRestrictionsRejectValetWritesAndRecoverWhenParked() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = entry.vehicleModesRepository().createController(scope)
            val property = VehicleModesRepository.VALET_PROPERTY_ID
            var original: Boolean? = null
            try {
                withTimeout(20000) { controller.state.first { it.control("VALET")?.area(0)?.confirmedValue != null } }
                val before = (entry.client().getBoolean(property) as VehiclePropertyResult.Success).value.value!!
                original = before
                shell("cmd car_service emulate-driving-state drive")
                withTimeout(15000) { entry.uxPolicy().state.first { it !is VehicleUxPolicyState.Unrestricted } }
                controller.setBoolean("VALET", 0, !before)
                withTimeout(10000) { controller.state.first { it.control("VALET")?.area(0)?.error is VehiclePropertyError.UxRestricted } }
                assertEquals(before, (entry.client().getBoolean(property) as VehiclePropertyResult.Success).value.value)
            } finally {
                shell("cmd car_service emulate-driving-state park")
                withTimeout(15000) { entry.uxPolicy().state.first { it is VehicleUxPolicyState.Unrestricted } }
                original?.let { value ->
                    entry
                        .client()
                        .setBoolean(
                            property,
                            0,
                            value,
                        ).first { it !is com.android.mysystemapp.core.vehicle.VehiclePropertyWriteResult.Pending }
                }
                scope.cancel()
            }
        }

    @Test fun realConnectionRecreatesSessionAndReadsTheSameVehicleValue() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller =
                entry.factory().create(
                    scope,
                    listOf(VehicleFeatureDefinition("lock", VehiclePropertySpec.boolean(0x16200b02))),
                )
            try {
                val initial = withTimeout(20000) { controller.state.first { it.control("lock")?.area(1)?.confirmedValue != null } }
                val before = initial.control("lock")!!.area(1)!!.confirmedValue
                val generation = (initial.connection as VehicleConnectionState.Connected).generation
                entry.connection().reconnect()
                val reconnected =
                    withTimeout(25000) {
                        controller.state.first {
                            val connection = it.connection
                            connection is VehicleConnectionState.Connected &&
                                connection.generation > generation &&
                                it.control("lock")?.area(1)?.confirmedValue != null
                        }
                    }
                assertEquals(before, reconnected.control("lock")!!.area(1)!!.confirmedValue)
            } finally {
                scope.cancel()
            }
        }

    @Test fun nativeRejectedValueRollsBackAndRefreshes(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = entry.factory().create(scope, listOf(VehicleFeatureDefinition("fan", VehiclePropertySpec.int(0x15400501))))
            try {
                val ready = withTimeout(20000) { controller.state.first { it.control("fan")?.area(1)?.confirmedValue != null } }
                val original = ready.control("fan")!!.area(1)!!.confirmedValue
                controller.setInt("fan", 1, 0) // UNKNOWN is advertised but explicitly rejected by CarService.
                withTimeout(10000) { controller.state.first { it.control("fan")?.area(1)?.error != null } }
                assertEquals(original, (entry.client().getInt(0x15400501, 1) as VehiclePropertyResult.Success).value.value)
                assertEquals(
                    original,
                    controller.state.value
                        .control("fan")!!
                        .area(1)!!
                        .confirmedValue,
                )
                controller.refresh()
                withTimeout(20000) {
                    controller.state.first {
                        !it.loading &&
                            it.control("fan")?.area(1)?.let { area -> area.error == null && area.confirmedValue == original } == true
                    }
                }
            } finally {
                scope.cancel()
            }
        }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
