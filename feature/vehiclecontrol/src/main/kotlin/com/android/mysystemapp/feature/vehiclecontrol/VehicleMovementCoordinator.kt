package com.android.mysystemapp.feature.vehiclecontrol

import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleFeatureController
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehicle.VehiclePropertyWriteResult
import com.android.mysystemapp.core.vehicle.VehicleUxPolicy
import com.android.mysystemapp.core.vehicle.VehicleUxPolicyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Main-confined actuator sequencing. The independent STOP scope outlives the ViewModel. */
internal class VehicleMovementCoordinator(
    private val startScope: CoroutineScope,
    private val stopScope: CoroutineScope,
    private val uxPolicy: VehicleUxPolicy,
    private val doors: VehicleFeatureController,
    private val sendStop: (VehicleControlUiModel) -> Flow<VehiclePropertyWriteResult<Int>>,
    private val onError: (String) -> Unit,
) {
    private data class ActiveMovement(
        val control: VehicleControlUiModel,
        val generation: Long,
        val requiresUnrestrictedUx: Boolean = false,
    )

    private val requested = mutableMapOf<Pair<String, Int>, ActiveMovement>()

    // A failed or timed-out STOP must remain eligible for explicit/lifecycle/UXR retries.
    private val mustStop = mutableMapOf<Pair<String, Int>, ActiveMovement>()
    private val stopping = mutableMapOf<Pair<String, Int>, Job>()
    private var nextMovement = 0L

    fun move(
        control: VehicleControlUiModel,
        direction: Int,
    ) {
        if (direction == 0) {
            stop(control)
            return
        }
        if (uxPolicy.state.value !is VehicleUxPolicyState.Unrestricted) return
        val key = control.key to control.areaId
        val active = ActiveMovement(control, ++nextMovement)
        requested[key] = active
        mustStop[key] = active
        startScope.launch {
            stopping[key]?.join()
            if (requested[key] == active && uxPolicy.state.value is VehicleUxPolicyState.Unrestricted) {
                doors.setInt(control.key, control.areaId, direction)
            }
        }
    }

    /** A deliberate user STOP also covers movement initiated outside this ViewModel. */
    fun requestStop(control: VehicleControlUiModel) {
        val key = control.key to control.areaId
        if (mustStop[key] == null) {
            val snapshot = doors.state.value
            val property = snapshot.control(control.key)
            val area = property?.area(control.areaId)
            val valid =
                control.key in setOf("DOOR_MOVEMENT", "WINDOW_MOVEMENT") &&
                    property?.definition?.spec?.propertyId == control.propertyId &&
                    property.areaType == control.areaType &&
                    property.supported &&
                    area?.access?.canWrite == true &&
                    area.status == VehiclePropertyStatus.AVAILABLE
            val rejection =
                when {
                    snapshot.connection !is VehicleConnectionState.Connected -> "Vehicle service is not connected"
                    !valid -> "Movement control is not available for this property and area"
                    uxPolicy.state.value !is VehicleUxPolicyState.Unrestricted -> "Park the vehicle to stop an external movement"
                    else -> null
                }
            if (rejection != null) {
                onError(rejection)
                return
            }
            mustStop[key] = ActiveMovement(control, ++nextMovement, requiresUnrestrictedUx = true)
        }
        stop(control)
    }

    /** Previously requested actuators can always be stopped, including while UXR is restricted. */
    fun stop(control: VehicleControlUiModel? = null) {
        val targets = if (control == null) mustStop.values.toList() else listOfNotNull(mustStop[control.key to control.areaId])
        targets.forEach { active ->
            val item = active.control
            val key = item.key to item.areaId
            requested.remove(key)
            if (stopping[key]?.isActive == true) return@forEach
            val job =
                stopScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result =
                            withTimeout(4000) {
                                doors.cancelPendingWrite(item.key, item.areaId)
                                check(!active.requiresUnrestrictedUx || uxPolicy.state.value is VehicleUxPolicyState.Unrestricted) {
                                    "Park the vehicle to stop an external movement"
                                }
                                sendStop(item).first { it !is VehiclePropertyWriteResult.Pending }
                            }
                        when (result) {
                            is VehiclePropertyWriteResult.Confirmed -> {
                                if (result.value.value == 0 && result.value.status == VehiclePropertyStatus.AVAILABLE) {
                                    if (mustStop[key] == active) mustStop.remove(key)
                                } else {
                                    onError("Movement stop was not confirmed")
                                }
                            }
                            is VehiclePropertyWriteResult.Error -> onError(result.error.description)
                            is VehiclePropertyWriteResult.Pending -> Unit
                        }
                    } catch (error: Exception) {
                        onError(error.message ?: "Movement stop failed")
                    }
                }
            stopping[key] = job
            job.invokeOnCompletion {
                stopScope.launch { if (stopping[key] === job) stopping.remove(key) }
            }
            job.start()
        }
    }
}
