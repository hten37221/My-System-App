package com.android.mysystemapp.feature.vehiclecontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.mysystemapp.core.common.ActionResult
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.vehicle.VehiclePropertyClient
import com.android.mysystemapp.core.vehicle.VehicleUxPolicy
import com.android.mysystemapp.core.vehicle.VehicleUxPolicyState
import com.android.mysystemapp.feature.doorcontrol.data.DoorControlRepository
import com.android.mysystemapp.feature.driverassistance.data.DriverAssistanceRepository
import com.android.mysystemapp.feature.hvac.domain.HvacUseCases
import com.android.mysystemapp.feature.seatcontrol.data.SeatControlRepository
import com.android.mysystemapp.feature.vehiclelighting.data.VehicleLightingRepository
import com.android.mysystemapp.feature.vehiclemodes.VehicleModesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ControlGroup { MODES, DOORS, SEATS, CLIMATE, LIGHTS, ASSISTANCE }

@HiltViewModel
class VehicleControlViewModel
    @Inject
    constructor(
        doorsRepository: DoorControlRepository,
        seatsRepository: SeatControlRepository,
        lightsRepository: VehicleLightingRepository,
        assistanceRepository: DriverAssistanceRepository,
        modesRepository: VehicleModesRepository,
        private val climate: HvacUseCases,
        val uxPolicy: VehicleUxPolicy,
        private val client: VehiclePropertyClient,
    ) : ViewModel() {
        val doors = doorsRepository.createController(viewModelScope)
        val seats = seatsRepository.createController(viewModelScope)
        val lights = lightsRepository.createController(viewModelScope)
        val assistance = assistanceRepository.createController(viewModelScope)
        val modes = modesRepository.createController(viewModelScope)
        val hvac = climate.observeState()
        val message = MutableStateFlow<String?>(null)
        private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val movement =
            VehicleMovementCoordinator(
                viewModelScope,
                stopScope,
                uxPolicy,
                doors,
                sendStop = { client.setInt(it.propertyId, it.areaId, 0) },
                onError = { message.value = it },
            )

        init {
            viewModelScope.launch { uxPolicy.state.collect { if (it !is VehicleUxPolicyState.Unrestricted) stopMovement() } }
        }

        fun refresh() {
            listOf(doors, seats, lights, assistance, modes).forEach { it.refresh() }
            viewModelScope.launch { climate.refresh() }
        }

        private fun controller(group: ControlGroup) =
            when (group) {
                ControlGroup.DOORS -> doors
                ControlGroup.SEATS -> seats
                ControlGroup.LIGHTS -> lights
                ControlGroup.ASSISTANCE -> assistance
                ControlGroup.MODES -> modes
                ControlGroup.CLIMATE -> null
            }

        fun boolean(
            group: ControlGroup,
            control: VehicleControlUiModel,
            value: Boolean,
        ) {
            if (group == ControlGroup.CLIMATE) {
                climateCommand { climate.setBoolean(control.key, value) }
            } else {
                controller(group)?.setBoolean(control.key, control.areaId, value)
            }
        }

        fun number(
            group: ControlGroup,
            control: VehicleControlUiModel,
            value: Float,
        ) {
            if (group == ControlGroup.CLIMATE) {
                climateCommand {
                    if (control.usesFloatSlider) climate.setFloat(control.key, value) else climate.setInt(control.key, value.toInt())
                }
            } else {
                controller(group)?.setInt(control.key, control.areaId, value.toInt())
            }
        }

        private fun climateCommand(action: suspend () -> ActionResult) {
            viewModelScope.launch {
                val result = action()
                if (result is ActionResult.Failure) message.value = result.message
            }
        }

        fun move(
            control: VehicleControlUiModel,
            direction: Int,
        ) = movement.move(control, direction)

        /** A stop for a previously started movement is allowed even when UXR changes. */
        fun stopMovement(control: VehicleControlUiModel? = null) = movement.stop(control)

        fun requestStop(control: VehicleControlUiModel) = movement.requestStop(control)

        override fun onCleared() {
            stopMovement()
            super.onCleared()
        }

        fun clearMessage() {
            message.value = null
        }
    }
