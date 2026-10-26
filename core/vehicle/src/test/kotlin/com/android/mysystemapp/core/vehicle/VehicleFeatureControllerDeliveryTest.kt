package com.android.mysystemapp.core.vehicle

import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyClient
import com.android.mysystemapp.core.vehicle.fake.FakeVehiclePropertyConnection
import com.android.mysystemapp.core.vehicle.fake.FakeVehicleUxPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleFeatureControllerDeliveryTest {
    @Test
    fun `late acknowledgement cannot replace a newer confirmed external observation`() =
        runTest {
            val spec = VehiclePropertySpec.int(0x2140600d)
            val area = VehiclePropertyArea.GLOBAL
            val base = FakeVehiclePropertyClient(listOf(capability(spec, listOf(area))), listOf(value(spec, area, 0)))
            val acknowledge = CompletableDeferred<Unit>()
            val client = controlledClient(base) { acknowledge.await() }
            val controller = createController(client, listOf(definition("position", spec)))
            runCurrent()

            controller.setInt("position", 0, 1)
            runCurrent()
            base.emitExternal(spec, area, 2, timestampNanos = 20)
            runCurrent()
            assertThat(
                controller.state.value
                    .control("position")!!
                    .area(0)!!
                    .confirmedValue,
            ).isEqualTo(2)

            acknowledge.complete(Unit)
            runCurrent()
            val confirmed =
                controller.state.value
                    .control("position")!!
                    .area(0)!!
            assertThat(confirmed.value).isEqualTo(2)
            assertThat(confirmed.confirmedValue).isEqualTo(2)
            assertThat(confirmed.confirmedTimestampNanos).isEqualTo(20)
            assertThat(confirmed.pending).isFalse()
            assertThat(confirmed.requestedValue).isNull()

            base.emitExternal(spec, area, 3, timestampNanos = 15)
            runCurrent()
            assertThat(
                controller.state.value
                    .control("position")!!
                    .area(0)!!
                    .value,
            ).isEqualTo(2)
        }

    @Test
    fun `stop waits for cancelled predecessor delivery even when its successor was cancelled`() =
        runTest {
            val spec = VehiclePropertySpec.int(0x16400b01)
            val area = VehiclePropertyArea.GLOBAL
            val base = FakeVehiclePropertyClient(listOf(capability(spec, listOf(area))), listOf(value(spec, area, 0)))
            val finishFirstDelivery = CompletableDeferred<Unit>()
            val deliveries = mutableListOf<Any>()
            val client =
                controlledClient(base) { requested ->
                    if (requested == 1) {
                        // A binder call already in progress cannot be interrupted by Job.cancel().
                        withContext(NonCancellable) {
                            finishFirstDelivery.await()
                            deliveries += requested
                        }
                    } else {
                        deliveries += requested
                    }
                }
            val controller = createController(client, listOf(definition("move", spec)))
            runCurrent()
            controller.setInt("move", 0, 1)
            runCurrent()
            controller.setInt("move", 0, -1)
            runCurrent()
            val stop =
                launch {
                    controller.cancelPendingWrite("move", 0)
                    client.setInt(spec.propertyId, 0, 0).collect()
                }
            runCurrent()
            assertThat(stop.isCompleted).isFalse()
            assertThat(deliveries).isEmpty()

            finishFirstDelivery.complete(Unit)
            runCurrent()
            assertThat(stop.isCompleted).isTrue()
            assertThat(deliveries).containsExactly(1, 0).inOrder()
            assertThat(
                controller.state.value
                    .control("move")!!
                    .area(0)!!
                    .pending,
            ).isFalse()
        }

    @Test
    fun `queued successor checks live UXR after predecessor finishes delivery`() =
        runTest {
            val spec = VehiclePropertySpec.int(0x16400b01)
            val area = VehiclePropertyArea.GLOBAL
            val base = FakeVehiclePropertyClient(listOf(capability(spec, listOf(area))), listOf(value(spec, area, 0)))
            val finishFirstDelivery = CompletableDeferred<Unit>()
            val deliveries = mutableListOf<Any>()
            val client =
                controlledClient(base) { requested ->
                    withContext(NonCancellable) {
                        if (requested == 1) finishFirstDelivery.await()
                        deliveries += requested
                    }
                }
            val policy = FakeVehicleUxPolicy()
            val controller = createController(client, listOf(definition("move", spec, true)), uxPolicy = policy)
            runCurrent()
            controller.setInt("move", 0, 1)
            runCurrent()
            controller.setInt("move", 0, -1)
            runCurrent()
            policy.setState(VehicleUxPolicyState.Restricted(1))
            finishFirstDelivery.complete(Unit)
            runCurrent()

            assertThat(deliveries).containsExactly(1)
            val state =
                controller.state.value
                    .control("move")!!
                    .area(0)!!
            assertThat(state.pending).isFalse()
            assertThat(state.value).isEqualTo(0)
            assertThat(state.error).isInstanceOf(VehiclePropertyError.UxRestricted::class.java)
        }

    private fun controlledClient(
        base: VehiclePropertyClient,
        delivery: suspend (Any) -> Unit,
    ): VehiclePropertyClient =
        object : VehiclePropertyClient {
            override suspend fun <T : Any> capability(
                spec: VehiclePropertySpec<T>,
                timeoutMillis: Long,
            ) = base.capability(spec, timeoutMillis)

            override suspend fun discoverCapabilities(
                specs: Collection<VehiclePropertySpec<*>>,
                timeoutMillis: Long,
            ) = base.discoverCapabilities(specs, timeoutMillis)

            override suspend fun <T : Any> get(
                spec: VehiclePropertySpec<T>,
                area: VehiclePropertyArea,
                timeoutMillis: Long,
            ) = base.get(spec, area, timeoutMillis)

            override fun <T : Any> observe(
                spec: VehiclePropertySpec<T>,
                areas: Set<VehiclePropertyArea>,
                updateRateHz: Float,
            ) = base.observe(spec, areas, updateRateHz)

            override fun <T : Any> set(
                spec: VehiclePropertySpec<T>,
                area: VehiclePropertyArea,
                value: T,
                acknowledgementTimeoutMillis: Long,
            ): Flow<VehiclePropertyWriteResult<T>> =
                flow {
                    emit(VehiclePropertyWriteResult.Pending(spec, area, value))
                    delivery(value)
                    emit(
                        VehiclePropertyWriteResult.Confirmed(
                            VehiclePropertyValue(spec, area, value, VehiclePropertyStatus.AVAILABLE, timestampNanos = 10),
                            VehicleWriteConfirmation.CALLBACK,
                        ),
                    )
                }
        }

    private fun <T : Any> capability(
        spec: VehiclePropertySpec<T>,
        areas: List<VehiclePropertyArea>,
    ) = VehiclePropertyCapability(
        spec = spec,
        access = VehiclePropertyAccess.READ_WRITE,
        changeMode = VehiclePropertyChangeMode.ON_CHANGE,
        areaType =
            if (areas == listOf(VehiclePropertyArea.GLOBAL)) {
                VehiclePropertyAreaType.GLOBAL
            } else {
                VehiclePropertyAreaType.SEAT
            },
        areas =
            areas.map {
                VehiclePropertyAreaCapability(
                    area = it,
                    access = VehiclePropertyAccess.READ_WRITE,
                    minValue = null,
                    maxValue = null,
                )
            },
        minSampleRateHz = 0f,
        maxSampleRateHz = 0f,
    )

    private fun <T : Any> value(
        spec: VehiclePropertySpec<T>,
        area: VehiclePropertyArea,
        value: T,
    ) = VehiclePropertyValue(
        spec = spec,
        area = area,
        value = value,
        status = VehiclePropertyStatus.AVAILABLE,
        timestampNanos = 1,
    )

    private fun definition(
        key: String,
        spec: VehiclePropertySpec<*>,
        requiresUnrestrictedUx: Boolean = false,
    ) = VehicleFeatureDefinition(key, spec, requiresUnrestrictedUx)

    private fun kotlinx.coroutines.test.TestScope.createController(
        client: VehiclePropertyClient,
        definitions: List<VehicleFeatureDefinition>,
        uxPolicy: FakeVehicleUxPolicy = FakeVehicleUxPolicy(),
        connection: FakeVehiclePropertyConnection = FakeVehiclePropertyConnection(),
    ): VehicleFeatureController =
        VehicleFeatureControllerFactory(
            client,
            connection,
            uxPolicy,
        ).create(backgroundScope, definitions)
}
