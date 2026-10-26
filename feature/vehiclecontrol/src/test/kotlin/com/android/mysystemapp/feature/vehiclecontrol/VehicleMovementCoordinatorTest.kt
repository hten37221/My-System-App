package com.android.mysystemapp.feature.vehiclecontrol

import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.vehicle.VehicleFeatureControllerFactory
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertyAccess
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaCapability
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyCapability
import com.android.mysystemapp.core.vehicle.VehiclePropertyChangeMode
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehicle.VehiclePropertyValue
import com.android.mysystemapp.core.vehicle.VehicleUxPolicyState
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyClient
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyConnection
import com.android.mysystemapp.core.vehicle.fake.FakeVehicleUxPolicy
import com.android.mysystemapp.core.vehicle.fake.FakeVehicleWriteBehavior
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleMovementCoordinatorTest {
    @Test fun manualStopCoversAnExternalMovementWithoutAnAppStart() =
        runTest {
            val fixture = fixture()
            runCurrent()
            fixture.client.emitExternal(VehiclePropertySpec.int(PROPERTY_ID), VehiclePropertyArea(1), 1)
            runCurrent()
            fixture.coordinator.stop()
            runCurrent()
            assertTrue(fixture.writes().isEmpty()) // Lifecycle cleanup does not own this movement.
            fixture.coordinator.requestStop(control)
            runCurrent()
            assertEquals(listOf(0), fixture.writes())
            assertTrue(fixture.errors.isEmpty())
        }

    @Test fun manualStopValidatesLiveCapabilityAreaAndPolicy() =
        runTest {
            val fixture = fixture()
            runCurrent()
            fixture.coordinator.requestStop(control.copy(propertyId = 0x15400b85))
            fixture.coordinator.requestStop(control.copy(areaId = 4))
            fixture.policy.setState(VehicleUxPolicyState.Restricted(1))
            fixture.coordinator.requestStop(control)
            runCurrent()
            assertTrue(fixture.writes().isEmpty())
            assertEquals(3, fixture.errors.size)
            val readOnly = this.fixture(access = VehiclePropertyAccess.READ)
            runCurrent()
            readOnly.coordinator.requestStop(control)
            runCurrent()
            assertTrue(readOnly.writes().isEmpty())
            assertEquals(1, readOnly.errors.size)
        }

    @Test fun failedManualStopDoesNotAcquireAnUnrestrictedPolicyBypass() =
        runTest {
            val fixture = fixture()
            runCurrent()
            fixture.client.setWriteBehavior(PROPERTY_ID, 1, FakeVehicleWriteBehavior.ERROR)
            fixture.coordinator.requestStop(control)
            runCurrent()
            assertEquals(listOf(0), fixture.writes())
            fixture.policy.setState(VehicleUxPolicyState.Restricted(1))
            fixture.coordinator.requestStop(control)
            runCurrent()
            assertEquals(listOf(0), fixture.writes())
            fixture.policy.setState(VehicleUxPolicyState.Unrestricted)
            fixture.client.setWriteBehavior(PROPERTY_ID, 1, FakeVehicleWriteBehavior.ACKNOWLEDGE_BY_CALLBACK)
            fixture.coordinator.requestStop(control)
            runCurrent()
            assertEquals(listOf(0, 0), fixture.writes())
        }

    @Test fun explicitStopCanRetryAfterFailureAndRetiresOnlyAfterConfirmedZero() =
        runTest {
            val fixture = fixture()
            runCurrent()
            fixture.coordinator.move(control, 1)
            runCurrent()
            fixture.client.setWriteBehavior(PROPERTY_ID, 1, FakeVehicleWriteBehavior.ERROR)
            fixture.coordinator.stop(control)
            runCurrent()
            assertEquals(listOf(1, 0), fixture.writes())
            assertEquals(1, fixture.errors.size)

            fixture.client.setWriteBehavior(PROPERTY_ID, 1, FakeVehicleWriteBehavior.ACKNOWLEDGE_BY_CALLBACK)
            fixture.coordinator.stop(control)
            runCurrent()
            assertEquals(listOf(1, 0, 0), fixture.writes())
            fixture.coordinator.stop(control)
            runCurrent()
            assertEquals(listOf(1, 0, 0), fixture.writes())
        }

    @Test fun failedLifecycleStopRemainsRetryableUnderRestrictedUxAndPreventsNewStarts() =
        runTest {
            val viewModelScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
            val fixture = fixture(startScope = viewModelScope)
            runCurrent()
            fixture.coordinator.move(control, 1)
            runCurrent()
            fixture.client.setWriteBehavior(PROPERTY_ID, 1, FakeVehicleWriteBehavior.TIMEOUT)
            fixture.coordinator.stop()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()
            assertEquals(1, fixture.errors.size)

            viewModelScope.cancel()
            fixture.policy.setState(VehicleUxPolicyState.Restricted(1))
            fixture.coordinator.move(control, -1)
            fixture.client.setWriteBehavior(PROPERTY_ID, 1, FakeVehicleWriteBehavior.ACKNOWLEDGE_BY_CALLBACK)
            fixture.coordinator.stop()
            runCurrent()
            assertEquals(listOf(1, 0, 0), fixture.writes())
        }

    @Test fun olderConfirmedStopCannotEraseNewerQueuedMovementGeneration() =
        runTest {
            val firstStop = CompletableDeferred<Unit>()
            var attempts = 0
            val fixture = fixture { if (++attempts == 1) firstStop.await() }
            runCurrent()
            fixture.coordinator.move(control, 1)
            runCurrent()
            fixture.coordinator.stop(control)
            runCurrent()
            fixture.coordinator.move(control, -1)
            runCurrent()
            assertEquals(listOf(1), fixture.writes())

            firstStop.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 0, -1), fixture.writes())
            fixture.coordinator.stop()
            runCurrent()
            assertEquals(listOf(1, 0, -1, 0), fixture.writes())
            assertTrue(fixture.errors.isEmpty())
        }

    @Test fun repeatedStopCallsCoalesceWhileAcknowledgementIsPending() =
        runTest {
            val finishStop = CompletableDeferred<Unit>()
            var attempts = 0
            val fixture =
                fixture {
                    attempts++
                    finishStop.await()
                }
            runCurrent()
            fixture.coordinator.move(control, 1)
            runCurrent()
            fixture.coordinator.stop(control)
            runCurrent()
            fixture.coordinator.stop(control)
            fixture.coordinator.stop()
            runCurrent()
            assertEquals(1, attempts)
            finishStop.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 0), fixture.writes())
        }

    private data class Fixture(
        val client: FakeVehiclePropertyClient,
        val policy: FakeVehicleUxPolicy,
        val coordinator: VehicleMovementCoordinator,
        val errors: MutableList<String>,
    ) {
        fun writes() = client.writes.map { it.value }
    }

    private fun TestScope.fixture(
        startScope: CoroutineScope = backgroundScope,
        access: VehiclePropertyAccess = VehiclePropertyAccess.READ_WRITE,
        beforeStop: suspend () -> Unit = {},
    ): Fixture {
        val spec = VehiclePropertySpec.int(PROPERTY_ID)
        val area = VehiclePropertyArea(1)
        val client =
            FakeVehiclePropertyClient(
                capabilities =
                    listOf(
                        VehiclePropertyCapability(
                            spec,
                            access,
                            VehiclePropertyChangeMode.ON_CHANGE,
                            VehiclePropertyAreaType.DOOR,
                            listOf(VehiclePropertyAreaCapability(area, access, -1, 1)),
                            minSampleRateHz = 0f,
                            maxSampleRateHz = 0f,
                        ),
                    ),
                values = listOf(VehiclePropertyValue(spec, area, 0, VehiclePropertyStatus.AVAILABLE, 0)),
            )
        val policy = FakeVehicleUxPolicy()
        val doors =
            VehicleFeatureControllerFactory(client, FakeVehiclePropertyConnection(), policy)
                .create(startScope, listOf(VehicleFeatureDefinition(control.key, spec, true)))
        val errors = mutableListOf<String>()
        val coordinator =
            VehicleMovementCoordinator(
                startScope,
                backgroundScope,
                policy,
                doors,
                sendStop = {
                    flow {
                        beforeStop()
                        emitAll(client.setInt(PROPERTY_ID, 1, 0))
                    }
                },
                onError = errors::add,
            )
        return Fixture(client, policy, coordinator, errors)
    }

    private companion object {
        const val PROPERTY_ID = 0x16400b01
        val control =
            VehicleControlUiModel(
                key = "DOOR_MOVEMENT",
                propertyId = PROPERTY_ID,
                areaId = 1,
                areaType = VehiclePropertyAreaType.DOOR,
                section = "Doors",
                title = "Move",
                summary = "",
                info = "",
                limitations = "",
                dependencies = "",
                editor = VehicleEditorUiKind.ENUM,
                readable = true,
                writable = true,
                available = true,
                pending = false,
            )
    }
}
