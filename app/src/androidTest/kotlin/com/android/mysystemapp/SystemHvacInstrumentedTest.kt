package com.android.mysystemapp

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.mysystemapp.core.common.ActionResult
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyClient
import com.android.mysystemapp.core.vehicle.VehiclePropertyError
import com.android.mysystemapp.core.vehicle.VehiclePropertyResult
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyWriteResult
import com.android.mysystemapp.feature.hvac.domain.ClimateControl
import com.android.mysystemapp.feature.hvac.domain.ClimateControlId
import com.android.mysystemapp.feature.hvac.domain.ClimateControlKind
import com.android.mysystemapp.feature.hvac.domain.ClimateState
import com.android.mysystemapp.feature.hvac.domain.ClimateValueStatus
import com.android.mysystemapp.feature.hvac.domain.HvacRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemHvacInstrumentedTest {
    private lateinit var repository: HvacRepository
    private lateinit var client: VehiclePropertyClient

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val entry = EntryPointAccessors.fromApplication(context, VehicleControlEntryPoint::class.java)
        repository = entry.hvacRepository()
        client = entry.client()
    }

    @Test
    fun climatePropertiesAreDiscoveredAndObserveVehicleUpdates(): Unit =
        runBlocking {
            withPoweredFan { fan, _ ->
                assertTrue(
                    repository.state
                        .first()
                        .controls
                        .all {
                            it.capability.zone.title
                                .isNotBlank()
                        },
                )
                val min = checkNotNull(fan.capability.min).toInt()
                val max = checkNotNull(fan.capability.max).toInt()
                val target = if (fan.observedIntValue == min) max else min
                assertSuccess(repository.setInt(fan.key, target))
                withTimeout(10_000) {
                    repository.state.first { state ->
                        state.control(fan.key)?.let { it.status == ClimateValueStatus.AVAILABLE && it.observedIntValue == target } == true
                    }
                }
                assertEquals(target, read(VehiclePropertySpec.int(fan.capability.propertyId), fan.capability.zone.areaId))

                val external = if (target == min) max else min
                val output =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .uiAutomation
                        .executeShellCommand(
                            "cmd car_service set-property-value HVAC_FAN_SPEED ${fan.capability.zone.areaId} $external",
                        ).let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                assertFalse(output, output.contains("Exception") || output.contains("Error"))
                withTimeout(10_000) {
                    repository.state.first { state -> state.control(fan.key)?.observedIntValue == external }
                }
            }
        }

    @Test
    fun nativePowerDependencyBlocksFanWhileOffAndRecoversAfterConfirmedOn(): Unit =
        runBlocking {
            withPoweredFan { fan, power ->
                assertTrue("Known AVD must declare the fan power dependency", power.isNotEmpty())
                val original = read(VehiclePropertySpec.int(fan.capability.propertyId), fan.capability.zone.areaId)
                set(POWER, power.first().areaId, false)
                withTimeout(10_000) {
                    repository.state.first { state ->
                        state.confirmedPower(power.first().areaId, false) &&
                            state.control(fan.key)?.status == ClimateValueStatus.UNAVAILABLE
                    }
                }
                val target =
                    if (original ==
                        fan.capability.min?.toInt()
                    ) {
                        checkNotNull(fan.capability.max).toInt()
                    } else {
                        checkNotNull(fan.capability.min).toInt()
                    }
                assertTrue("Power-off fan dispatch must be rejected", repository.setInt(fan.key, target) is ActionResult.Failure)
                val disabledRead =
                    client.get(
                        VehiclePropertySpec.int(fan.capability.propertyId),
                        VehiclePropertyArea(fan.capability.zone.areaId),
                    )
                assertTrue(
                    "Native fan read must report unavailable while power is disabled: $disabledRead",
                    disabledRead is VehiclePropertyResult.Failure && disabledRead.error is VehiclePropertyError.Unavailable,
                )

                set(POWER, power.first().areaId, true)
                awaitPoweredFan(fan, power)
                // The disabled backend cannot return a value. Check that the rejected edit had
                // no effect once power is confirmed on, before issuing a new valid edit.
                assertEquals(original, read(VehiclePropertySpec.int(fan.capability.propertyId), fan.capability.zone.areaId))
                assertSuccess(repository.setInt(fan.key, target))
                withTimeout(10_000) {
                    repository.state.first { state -> state.control(fan.key)?.observedIntValue == target }
                }
                assertEquals(target, read(VehiclePropertySpec.int(fan.capability.propertyId), fan.capability.zone.areaId))
            }
        }

    @Test
    fun climateCapabilitiesExposeEachSupportedControlWithCorrectShape(): Unit =
        runBlocking {
            assertSuccess(repository.refresh())
            val state = withTimeout(20_000) { repository.state.first { it.connected && it.controls.isNotEmpty() } }
            state.controls.forEach { control ->
                when (control.capability.kind) {
                    ClimateControlKind.FLOAT_RANGE,
                    ClimateControlKind.INT_RANGE,
                    -> {
                        assertNotNull("${control.title} must provide a lower bound", control.capability.min)
                        assertNotNull("${control.title} must provide an upper bound", control.capability.max)
                    }
                    ClimateControlKind.INT_OPTIONS ->
                        assertFalse("${control.title} must provide supported options", control.capability.options.isEmpty())
                    else -> Unit
                }
            }
        }

    /** Snapshot power before enabling it; capture/restore the readable fan while powered. */
    private suspend fun withPoweredFan(block: suspend (ClimateControl, List<PowerValue>) -> Unit) {
        val state =
            withTimeout(20_000) {
                repository.state.first { climate ->
                    climate.connected && climate.controls.any { it.capability.id == ClimateControlId.FAN_SPEED && it.capability.writable }
                }
            }
        val fan = state.controls.first { it.capability.id == ClimateControlId.FAN_SPEED && it.capability.writable }
        assertNotNull("Fan minimum", fan.capability.min)
        assertNotNull("Fan maximum", fan.capability.max)
        val capabilityResult = client.capability(POWER)
        assertTrue("HVAC power capability must be available: $capabilityResult", capabilityResult is VehiclePropertyResult.Success)
        val capability = (capabilityResult as VehiclePropertyResult.Success).value
        val power =
            if (fan.capability.propertyId in capability.configArray) {
                capability.areas
                    .filter {
                        if (capability.areaType == VehiclePropertyAreaType.GLOBAL) {
                            it.area.areaId == 0
                        } else {
                            capability.areaType == fan.capability.areaType && it.area.areaId and fan.capability.zone.areaId != 0
                        }
                    }.map { PowerValue(it.area.areaId, read(POWER, it.area.areaId)) }
            } else {
                emptyList()
            }
        val fanSpec = VehiclePropertySpec.int(fan.capability.propertyId)
        var originalFan: Int? = null
        try {
            power.forEach { set(POWER, it.areaId, true) }
            val ready = awaitPoweredFan(fan, power)
            originalFan = read(fanSpec, fan.capability.zone.areaId)
            block(ready, power)
        } finally {
            val failures = mutableListOf<Throwable>()
            originalFan?.let { value ->
                power.forEach { runCatching { set(POWER, it.areaId, true) }.onFailure(failures::add) }
                runCatching {
                    set(fanSpec, fan.capability.zone.areaId, value)
                    assertEquals(value, read(fanSpec, fan.capability.zone.areaId))
                }.onFailure(failures::add)
            }
            power.asReversed().forEach { runCatching { set(POWER, it.areaId, it.original) }.onFailure(failures::add) }
            assertTrue("HVAC state restoration failed: $failures", failures.isEmpty())
            power.forEach { assertEquals(it.original, read(POWER, it.areaId)) }
        }
    }

    /** Auto-discovery owns startup; an asynchronous refresh must not reset a ready write gate. */
    private suspend fun awaitPoweredFan(
        fan: ClimateControl,
        power: List<PowerValue>,
    ): ClimateControl =
        withTimeout(15_000) {
            repository.state.first { climate ->
                climate.connected &&
                    power.all { climate.confirmedPower(it.areaId, true) } &&
                    climate.control(fan.key)?.let {
                        it.status == ClimateValueStatus.AVAILABLE && it.observedIntValue != null && it.observedTimestampNanos != null
                    } == true
            }
        }.control(fan.key)!!

    private fun ClimateState.confirmedPower(
        areaId: Int,
        enabled: Boolean,
    ): Boolean =
        control("POWER:$areaId")?.let {
            it.status == ClimateValueStatus.AVAILABLE && it.observedBooleanValue == enabled && it.observedTimestampNanos != null
        } == true

    private suspend fun <T : Any> read(
        spec: VehiclePropertySpec<T>,
        areaId: Int,
    ): T {
        val result = client.get(spec, VehiclePropertyArea(areaId))
        assertTrue("Read failed: $result", result is VehiclePropertyResult.Success)
        return checkNotNull((result as VehiclePropertyResult.Success).value.value)
    }

    private suspend fun <T : Any> set(
        spec: VehiclePropertySpec<T>,
        areaId: Int,
        value: T,
    ) {
        val terminal =
            withTimeout(10_000) { client.set(spec, VehiclePropertyArea(areaId), value).first { it !is VehiclePropertyWriteResult.Pending } }
        assertTrue("Write failed: $terminal", terminal is VehiclePropertyWriteResult.Confirmed)
    }

    private fun ClimateState.control(key: String): ClimateControl? = controls.firstOrNull { it.key == key }

    private fun assertSuccess(result: ActionResult) {
        assertEquals((result as? ActionResult.Failure)?.message ?: "Expected successful HVAC action", ActionResult.Success, result)
    }

    private data class PowerValue(
        val areaId: Int,
        val original: Boolean,
    )

    private companion object {
        val POWER = VehiclePropertySpec.boolean(0x15200510)
    }
}
