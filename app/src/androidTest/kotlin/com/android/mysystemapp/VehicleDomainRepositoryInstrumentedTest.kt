package com.android.mysystemapp

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleFeatureAreaState
import com.android.mysystemapp.core.vehicle.VehicleFeatureControlState
import com.android.mysystemapp.core.vehicle.VehicleFeatureController
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertyChangeMode
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises repository event flow against the running VHAL for each vehicle domain. */
@RunWith(AndroidJUnit4::class)
class VehicleDomainRepositoryInstrumentedTest {
    private lateinit var entryPoint: VehicleControlEntryPoint

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        entryPoint = EntryPointAccessors.fromApplication(context, VehicleControlEntryPoint::class.java)
    }

    @Test
    fun driverAssistance_externalMutationIsObservedAndRestored() =
        runBlocking {
            exerciseDomain { entryPoint.driverAssistanceRepository().createController(it) }
        }

    @Test
    fun seatControl_externalMutationIsObservedAndRestored() =
        runBlocking {
            exerciseDomain { entryPoint.seatControlRepository().createController(it) }
        }

    @Test
    fun vehicleLighting_externalMutationIsObservedAndRestored() =
        runBlocking {
            exerciseDomain { entryPoint.vehicleLightingRepository().createController(it) }
        }

    @Test
    fun doorControl_externalMutationIsObservedAndRestored() =
        runBlocking {
            exerciseDomain { entryPoint.doorControlRepository().createController(it) }
        }

    private suspend fun exerciseDomain(create: (CoroutineScope) -> VehicleFeatureController) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = create(scope)
        try {
            controller.refresh()
            val state =
                withTimeout(15_000) {
                    controller.state.first {
                        !it.loading && it.connection is VehicleConnectionState.Connected
                    }
                }
            val candidateState =
                withTimeoutOrNull(15_000) {
                    controller.state.first { state ->
                        state.connection is VehicleConnectionState.Connected &&
                            readableWritableCandidates(state).isNotEmpty()
                    }
                }
            val inspectedState = candidateState ?: state
            val candidates = readableWritableCandidates(inspectedState)
            val diagnostic =
                inspectedState.controls.joinToString(" | ") { control ->
                    val areas =
                        control.areas.joinToString(",") { area ->
                            "${area.area.areaId}:${area.access}:${area.status}:${area.value}"
                        }
                    "${control.definition.key}:supported=${control.supported},access=${control.access},areas=[$areas],error=${control.error?.description}"
                }
            assertTrue(
                "The published image exposes no readable/writable control for this domain; " +
                    "connection=${inspectedState.connection}, controls=${inspectedState.controls.size}; $diagnostic",
                candidates.isNotEmpty(),
            )

            val eventCandidates =
                candidates.filter { (control, _) ->
                    control.changeMode != VehiclePropertyChangeMode.STATIC
                }
            assertTrue(
                "The published image exposes no ON_CHANGE control for this domain",
                eventCandidates.isNotEmpty(),
            )

            val originalValues = mutableListOf<Mutation>()
            try {
                eventCandidates.forEach { (control, area) ->
                    val target = alternateValue(control.definition.spec.valueType, area)
                    if (target == null || target == area.value) return@forEach
                    originalValues += Mutation(control, area, area.value!!)
                    set(controller, control, area, target)
                    assertTrue(
                        "The emulator VHAL did not confirm ${control.definition.key} after an in-app write.",
                        awaitValue(controller, control.definition.key, area.area.areaId, target),
                    )

                    // Change the value outside the app back to the original.  This
                    // proves that the repository observes an external VHAL event,
                    // rather than merely observing its own SET confirmation.
                    externalSet(
                        control.definition.spec.propertyId,
                        area.area.areaId,
                        area.value!!,
                    )
                    assertTrue(
                        "The emulator VHAL did not emit an external event for ${control.definition.key}.",
                        awaitValue(
                            controller,
                            control.definition.key,
                            area.area.areaId,
                            area.value!!,
                        ),
                    )
                }
                assertTrue("At least one external mutation must execute", originalValues.isNotEmpty())
            } finally {
                originalValues.asReversed().forEach { mutation ->
                    set(controller, mutation.control, mutation.area, mutation.original)
                    assertTrue(
                        "Original value was not restored for ${mutation.control.definition.key}/${mutation.area.area.areaId}",
                        awaitValue(
                            controller,
                            mutation.control.definition.key,
                            mutation.area.area.areaId,
                            mutation.original,
                        ),
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun readableWritableCandidates(state: VehicleFeatureState): List<Pair<VehicleFeatureControlState, VehicleFeatureAreaState>> =
        state.controls
            .filter { it.supported }
            .flatMap { control ->
                control.areas
                    .filter {
                        it.access.canRead &&
                            it.access.canWrite &&
                            it.value != null &&
                            it.status == VehiclePropertyStatus.AVAILABLE &&
                            it.area.areaId >= 0
                    }.map { control to it }
            }

    private suspend fun awaitValue(
        controller: VehicleFeatureController,
        key: String,
        areaId: Int,
        expected: Any,
    ): Boolean =
        withTimeoutOrNull(10_000) {
            controller.state.first { state ->
                state.control(key)?.area(areaId)?.let { area ->
                    !area.pending && area.error == null && area.confirmedValue.sameValue(expected)
                } == true
            }
            true
        } ?: false

    private fun set(
        controller: VehicleFeatureController,
        control: com.android.mysystemapp.core.vehicle.VehicleFeatureControlState,
        area: VehicleFeatureAreaState,
        value: Any,
    ) {
        when (control.definition.spec.valueType) {
            VehiclePropertyValueType.BOOLEAN -> controller.setBoolean(control.definition.key, area.area.areaId, value as Boolean)
            VehiclePropertyValueType.INT -> controller.setInt(control.definition.key, area.area.areaId, (value as Number).toInt())
            VehiclePropertyValueType.FLOAT -> controller.setFloat(control.definition.key, area.area.areaId, (value as Number).toFloat())
        }
    }

    private fun alternateValue(
        type: VehiclePropertyValueType,
        area: VehicleFeatureAreaState,
    ): Any? =
        when (type) {
            VehiclePropertyValueType.BOOLEAN -> !(area.value as? Boolean ?: return null)
            VehiclePropertyValueType.INT,
            VehiclePropertyValueType.FLOAT,
            -> {
                if (type == VehiclePropertyValueType.INT) {
                    area.supportedEnumValues.firstOrNull { !it.sameValue(checkNotNull(area.value)) }?.let { return (it as Number).toInt() }
                }
                val current = (area.value as? Number)?.toDouble() ?: return null
                val minimum = (area.minValue as? Number)?.toDouble() ?: return null
                val maximum = (area.maxValue as? Number)?.toDouble() ?: return null
                val target = if (current == minimum) maximum else minimum
                if (type == VehiclePropertyValueType.INT) target.toInt() else target.toFloat()
            }
        }

    private fun externalSet(
        propertyId: Int,
        areaId: Int,
        value: Any,
    ) {
        val token =
            when (value) {
                // `cmd car_service set-property-value` parses BOOLEAN as the
                // literal tokens `true`/`false`; numeric 1/0 can return a
                // successful shell invocation without producing the expected
                // VHAL event on the emulator.
                is Boolean -> if (value) "true" else "false"
                is Float -> value.toString()
                is Double -> value.toString()
                is Number -> value.toString()
                else -> error("Unsupported external value $value")
            }
        val output =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand(
                    "cmd car_service set-property-value 0x${propertyId.toHexString()} 0x${areaId.toHexString()} $token",
                ).let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        assertTrue("External property command failed: $output", !output.contains("Exception") && !output.contains("Error"))
    }

    private data class Mutation(
        val control: com.android.mysystemapp.core.vehicle.VehicleFeatureControlState,
        val area: VehicleFeatureAreaState,
        val original: Any,
    )
}

private fun Any?.sameValue(expected: Any): Boolean =
    when {
        this is Number && expected is Number -> this.toDouble() == expected.toDouble()
        else -> this == expected
    }
