package com.android.mysystemapp.feature.hvac.data

import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleFeatureAreaState
import com.android.mysystemapp.core.vehicle.VehicleFeatureControlState
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertyAccess
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyAreaType
import com.android.mysystemapp.core.vehicle.VehiclePropertyError
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.feature.hvac.domain.ClimateControl
import com.android.mysystemapp.feature.hvac.domain.ClimateState
import com.android.mysystemapp.feature.hvac.domain.ClimateValueStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidHvacPlatformMappingTest {
    @Test
    fun disabledBackendErrorStaysUnavailableInsteadOfBecomingADeviceFault() {
        val disabled = VehiclePropertyError.Unavailable(FAN, VehiclePropertyArea(1), retryable = false)
        val result =
            climate(
                property(
                    "FAN_SPEED",
                    FAN,
                    listOf(
                        area(
                            1,
                            value = 5,
                            confirmed = 2,
                            status = VehiclePropertyStatus.UNAVAILABLE,
                            pending = true,
                            error = disabled,
                        ),
                    ),
                ),
            ).control("FAN_SPEED:1")
        assertThat(result.status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.unavailableReason).isEqualTo(disabled.description)
        assertThat(result.observedIntValue).isEqualTo(2)
    }

    @Test
    fun groupedPowerZonesGateOnlyDeclaredDependentSeats() {
        val result =
            climate(
                power(listOf(area(5, false), area(16, true))),
                property("FAN_SPEED", FAN, listOf(area(1), area(4), area(16))),
                property("AC", AC, listOf(area(1, true))),
            )

        assertThat(result.control("FAN_SPEED:1").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.control("FAN_SPEED:4").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.control("FAN_SPEED:16").status).isEqualTo(ClimateValueStatus.AVAILABLE)
        assertThat(result.control("AC:1").status).isEqualTo(ClimateValueStatus.AVAILABLE)
        assertThat(result.control("POWER:5").status).isEqualTo(ClimateValueStatus.AVAILABLE)
        assertThat(result.control("FAN_SPEED:1").unavailableReason).contains("confirmed on")
    }

    @Test
    fun compositeDependentZoneRequiresEveryMatchingPowerZoneOn() {
        val fan = property("FAN_SPEED", FAN, listOf(area(5)))
        val powerOff = power(listOf(area(1, true), area(4, false)))
        val powerOn = power(listOf(area(1, true), area(4, true)))

        assertThat(climate(powerOff, fan).control("FAN_SPEED:5").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(climate(powerOn, fan).control("FAN_SPEED:5").status).isEqualTo(ClimateValueStatus.AVAILABLE)
    }

    @Test
    fun pendingPowerOnDoesNotEnableDependentBeforeConfirmation() {
        val fan = property("FAN_SPEED", FAN, listOf(area(1)))
        val pendingPower =
            power(
                listOf(area(5, value = true, confirmed = false, pending = true)),
                dependencies = listOf(FAN, POWER),
            )
        val pending = climate(pendingPower, fan)

        assertThat(pending.control("POWER:5").status).isEqualTo(ClimateValueStatus.PENDING)
        assertThat(pending.control("POWER:5").booleanValue).isTrue()
        assertThat(pending.control("POWER:5").observedBooleanValue).isFalse()
        assertThat(pending.control("FAN_SPEED:1").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        val confirmedPower = power(listOf(area(5, true)))
        assertThat(climate(confirmedPower, fan).control("FAN_SPEED:1").status).isEqualTo(ClimateValueStatus.AVAILABLE)
    }

    @Test
    fun backendUnavailableAndErrorPrecedePending() {
        val fan =
            property(
                "FAN_SPEED",
                FAN,
                listOf(
                    area(1, value = 5, confirmed = 2, status = VehiclePropertyStatus.UNAVAILABLE, pending = true),
                    area(4, value = 5, confirmed = 2, status = VehiclePropertyStatus.ERROR, pending = true),
                    area(
                        16,
                        value = 5,
                        confirmed = 2,
                        pending = true,
                        error = VehiclePropertyError.InvalidValue(FAN, VehiclePropertyArea(16)),
                    ),
                ),
            )
        val result = climate(fan)

        assertThat(result.control("FAN_SPEED:1").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.control("FAN_SPEED:4").status).isEqualTo(ClimateValueStatus.ERROR)
        assertThat(result.control("FAN_SPEED:16").status).isEqualTo(ClimateValueStatus.ERROR)
        assertThat(result.control("FAN_SPEED:1").observedIntValue).isEqualTo(2)
        assertThat(result.control("FAN_SPEED:1").observedTimestampNanos).isEqualTo(42)
    }

    @Test
    fun unavailablePowerPrecedesDependentPendingEvenWithAnOldTrueObservation() {
        val fan = property("FAN_SPEED", FAN, listOf(area(1, value = 5, confirmed = 2, pending = true)))
        val unavailablePower = power(listOf(area(5, true, status = VehiclePropertyStatus.UNAVAILABLE)))

        val result = climate(unavailablePower, fan).control("FAN_SPEED:1")

        assertThat(result.status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.intValue).isEqualTo(5)
        assertThat(result.observedIntValue).isEqualTo(2)
    }

    @Test
    fun equalNumericSeatAndWindowAreasNeverEstablishAPowerRelationship() {
        val power = power(listOf(area(1, false)), dependencies = listOf(FAN, DEFROSTER))
        val result =
            climate(
                power,
                property("FAN_SPEED", FAN, listOf(area(1))),
                property("DEFROSTER", DEFROSTER, listOf(area(1, true)), VehiclePropertyAreaType.WINDOW),
            )

        assertThat(result.control("FAN_SPEED:1").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.control("FRONT_DEFROSTER:1").status).isEqualTo(ClimateValueStatus.AVAILABLE)
    }

    @Test
    fun actualGlobalPowerGatesDeclaredDependenciesAcrossAreaDomains() {
        val power =
            power(
                listOf(area(0, false)),
                dependencies = listOf(FAN, DEFROSTER),
                type = VehiclePropertyAreaType.GLOBAL,
            )
        val result =
            climate(
                power,
                property("FAN_SPEED", FAN, listOf(area(4))),
                property("DEFROSTER", DEFROSTER, listOf(area(1, true)), VehiclePropertyAreaType.WINDOW),
            )

        assertThat(result.control("FAN_SPEED:4").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.control("FRONT_DEFROSTER:1").status).isEqualTo(ClimateValueStatus.UNAVAILABLE)
        assertThat(result.control("POWER:0").status).isEqualTo(ClimateValueStatus.AVAILABLE)
    }

    @Test
    fun unmatchedZonesAndEmptyDependencyListDeferToTheirOwnBackendStatus() {
        val fan = property("FAN_SPEED", FAN, listOf(area(4)))
        assertThat(climate(power(listOf(area(1, false))), fan).control("FAN_SPEED:4").status)
            .isEqualTo(ClimateValueStatus.AVAILABLE)
        assertThat(climate(power(listOf(area(4, false)), dependencies = emptyList()), fan).control("FAN_SPEED:4").status)
            .isEqualTo(ClimateValueStatus.AVAILABLE)
    }

    @Test
    fun pendingEditorRetainsGenuineConfirmedObservation() {
        val result =
            climate(property("FAN_SPEED", FAN, listOf(area(1, value = 5, confirmed = 2, pending = true))))
                .control("FAN_SPEED:1")

        assertThat(result.status).isEqualTo(ClimateValueStatus.PENDING)
        assertThat(result.intValue).isEqualTo(5)
        assertThat(result.observedIntValue).isEqualTo(2)
        assertThat(result.observedTimestampNanos).isEqualTo(42)
    }

    @Test
    fun dispatchDependencyCheckUsesTheSuppliedCurrentSnapshot() {
        val on = snapshot(power(listOf(area(5, true))))
        val off = snapshot(power(listOf(area(5, false))))
        val unknown = snapshot(power(listOf(area(5, true, confirmed = null))))

        assertThat(climatePowerUnavailableReason(on, FAN, VehiclePropertyAreaType.SEAT, 1)).isNull()
        assertThat(climatePowerUnavailableReason(off, FAN, VehiclePropertyAreaType.SEAT, 1)).isNotNull()
        assertThat(climatePowerUnavailableReason(unknown, FAN, VehiclePropertyAreaType.SEAT, 1)).isNotNull()
        assertThat(climatePowerUnavailableReason(off, POWER, VehiclePropertyAreaType.SEAT, 5)).isNull()
    }

    private fun ClimateState.control(key: String): ClimateControl = controls.first { it.key == key }

    private fun climate(vararg properties: VehicleFeatureControlState): ClimateState = toClimateState(snapshot(*properties))

    private fun snapshot(vararg properties: VehicleFeatureControlState): VehicleFeatureState =
        VehicleFeatureState(loading = false, connection = VehicleConnectionState.Connected(1), controls = properties.toList())

    private fun power(
        areas: List<VehicleFeatureAreaState>,
        dependencies: List<Int> = listOf(FAN),
        type: VehiclePropertyAreaType = VehiclePropertyAreaType.SEAT,
    ): VehicleFeatureControlState = property("POWER", POWER, areas, type).copy(configArray = dependencies)

    private fun property(
        key: String,
        id: Int,
        areas: List<VehicleFeatureAreaState>,
        type: VehiclePropertyAreaType = VehiclePropertyAreaType.SEAT,
    ): VehicleFeatureControlState =
        VehicleFeatureControlState(
            definition =
                VehicleFeatureDefinition(
                    key,
                    if (id == POWER || id == AC || id == DEFROSTER) VehiclePropertySpec.boolean(id) else VehiclePropertySpec.int(id),
                ),
            supported = true,
            access = VehiclePropertyAccess.READ_WRITE,
            areaType = type,
            areas = areas,
        )

    private fun area(
        id: Int,
        value: Any? = 2,
        confirmed: Any? = value,
        status: VehiclePropertyStatus = VehiclePropertyStatus.AVAILABLE,
        pending: Boolean = false,
        error: VehiclePropertyError? = null,
    ): VehicleFeatureAreaState =
        VehicleFeatureAreaState(
            area = VehiclePropertyArea(id),
            access = VehiclePropertyAccess.READ_WRITE,
            value = value,
            confirmedValue = confirmed,
            confirmedTimestampNanos = 42,
            status = status,
            pending = pending,
            error = error,
        )

    private companion object {
        const val POWER = 0x15200510
        const val FAN = 0x15400500
        const val AC = 0x15200505
        const val DEFROSTER = 0x13200504
    }
}
